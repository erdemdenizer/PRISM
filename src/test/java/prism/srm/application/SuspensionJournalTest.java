package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * The suspension journal in a test's own folder (ADR-0011): written whole and checked, a failure
 * that leaves the previous file and fails the journal, the lock that tells a dead session's journal
 * from a live one's, and what is read from a damaged file.
 */
class SuspensionJournalTest {

    private static final Instant T = Instant.parse("2026-10-08T10:00:00Z");

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();

    private SuspensionJournal open(String instance) {
        return SuspensionJournal.open(journalFolder.path(), instance, files, millis -> {});
    }

    private static JournalEntry entry(long pid, Set<SuspensionReason> reasons, Optional<String> memento) {
        return new JournalEntry(
                new ProcessIdentity(pid, T.plusNanos(123_456_789)),
                "demo workload | \"quoted\" \n name",
                reasons,
                memento,
                Optional.of("CPU limit 30 %"),
                "1234-5678",
                T,
                T.plusSeconds(1),
                false,
                false);
    }

    @Test
    void anOpenedJournalIsHealthyAndEmptyAndTheEntriesReadBackExactly() throws Exception {
        try (SuspensionJournal journal = open("1-1")) {
            assertThat(journal.health().healthy()).isTrue();
            JournalEntry user = entry(4242, EnumSet.of(SuspensionReason.USER), Optional.of("w1:12@3"));
            JournalEntry limiter = entry(4343, EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty());

            assertThat(journal.write(List.of(user, limiter))).isTrue();

            SuspensionJournal.Decoded decoded = SuspensionJournal.decode(Files.readAllBytes(journal.file()));
            assertThat(decoded.corrupt()).isFalse();
            assertThat(decoded.entries()).containsExactly(user, limiter);
            assertThat(journalFolder.path().resolve("suspensions-1-1.tmp")).doesNotExist();
        }
    }

    @Test
    void aFullDiskLeavesThePreviousFileAndFailsTheJournal() throws Exception {
        try (SuspensionJournal journal = open("1-1")) {
            journal.write(List.of(entry(1, EnumSet.of(SuspensionReason.USER), Optional.empty())));
            byte[] before = Files.readAllBytes(journal.file());
            files.diskFull = true;

            assertThat(journal.write(List.of())).isFalse();

            assertThat(journal.health().healthy()).isFalse();
            assertThat(journal.health().problem().orElseThrow()).contains("not enough space");
            assertThat(Files.readAllBytes(journal.file())).isEqualTo(before);
            assertThat(journalFolder.path().resolve("suspensions-1-1.tmp")).doesNotExist();
            files.diskFull = false;
            assertThat(journal.write(List.of())).as("failed until a retry").isFalse();
        }
    }

    @Test
    void aMoveTheSystemRefusesIsTriedThreeTimes() throws Exception {
        try (SuspensionJournal journal = open("1-1")) {
            files.failingMoves = 2;
            int moves = files.moves;

            assertThat(journal.write(List.of(entry(1, EnumSet.of(SuspensionReason.USER), Optional.empty()))))
                    .isTrue();
            assertThat(files.moves - moves).isEqualTo(3);

            files.failingMoves = 3;
            assertThat(journal.write(List.of())).isFalse();
            assertThat(journal.health().problem().orElseThrow()).contains("could not be replaced");
            assertThat(SuspensionJournal.decode(Files.readAllBytes(journal.file()))
                            .entries())
                    .as("the file before the failed write")
                    .hasSize(1);
        }
    }

    @Test
    void aFileThatReadsBackOtherwiseFailsTheJournalAndIsKeptAside() throws Exception {
        try (SuspensionJournal journal = open("1-1")) {
            files.readsBackOtherwise = true;

            assertThat(journal.write(List.of(entry(1, EnumSet.of(SuspensionReason.USER), Optional.empty()))))
                    .isFalse();

            assertThat(journal.health().problem().orElseThrow()).contains("did not read back");
            try (var listed = Files.list(journalFolder.path())) {
                assertThat(listed.map(path -> path.getFileName().toString()))
                        .anyMatch(name -> name.contains(".corrupt-"));
            }
        }
    }

    @Test
    void aLostLockFailsTheJournalAtTheNextWriteAndCheck() {
        try (SuspensionJournal journal = open("1-1")) {
            files.locksLost = true;

            assertThat(journal.verify()).isFalse();
            assertThat(journal.health().problem().orElseThrow()).contains("lock");
        }
    }

    @Test
    void aFileChangedOnDiskFailsTheCheck() throws Exception {
        try (SuspensionJournal journal = open("1-1")) {
            journal.write(List.of(entry(1, EnumSet.of(SuspensionReason.USER), Optional.empty())));
            assertThat(journal.verify()).isTrue();
            Files.writeString(journal.file(), "something else");

            assertThat(journal.verify()).isFalse();
            assertThat(journal.health().problem().orElseThrow()).contains("changed on disk");
        }
    }

    @Test
    void aFolderThatMayNotBeWrittenGivesAFailedJournalNotAnException() {
        files.denied = true;

        SuspensionJournal journal = open("1-1");

        assertThat(journal.health().healthy()).isFalse();
        journal.close();
    }

    @Test
    void aRetryAfterTheFailureIsGoneMakesTheJournalHealthyWithTheLatestEntries() throws Exception {
        try (SuspensionJournal journal = open("1-1")) {
            files.diskFull = true;
            JournalEntry entry = entry(9, EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty());
            journal.write(List.of(entry));
            files.diskFull = false;

            assertThat(journal.retry()).isTrue();

            assertThat(journal.health().healthy()).isTrue();
            assertThat(SuspensionJournal.decode(Files.readAllBytes(journal.file()))
                            .entries())
                    .containsExactly(entry);
        }
    }

    @Test
    void theJournalOfAnEndedSessionIsFoundAndOneThatIsStillLockedIsLeftAlone() throws Exception {
        crashedSession("1-1", entry(7, EnumSet.of(SuspensionReason.USER), Optional.empty()));
        try (SuspensionJournal running = open("2-2");
                SuspensionJournal current = open("3-3")) {
            running.write(List.of(entry(8, EnumSet.of(SuspensionReason.USER), Optional.empty())));

            List<SuspensionJournal.Dead> dead = current.deadJournals();

            assertThat(dead).hasSize(1);
            assertThat(dead.get(0).entries())
                    .extracting(e -> e.identity().pid())
                    .containsExactly(7L);
            assertThat(current.deadJournals())
                    .as("held by the first finder until it is done")
                    .isEmpty();
            current.finish(dead.get(0));
            assertThat(journalFolder.path().resolve("suspensions-1-1.journal")).doesNotExist();
            assertThat(journalFolder.path().resolve("suspensions-1-1.lock")).doesNotExist();
        }
    }

    @Test
    void anAbandonedDeadJournalStaysForALaterSession() {
        crashedSession("1-1", entry(7, EnumSet.of(SuspensionReason.USER), Optional.empty()));
        try (SuspensionJournal current = open("3-3")) {
            SuspensionJournal.Dead dead = current.deadJournals().get(0);

            current.abandon(dead);

            assertThat(dead.journal()).exists();
            assertThat(current.deadJournals()).hasSize(1);
        }
    }

    @Test
    void aDamagedFileIsShownAsFarAsItCanBeReadAndKeptAside() throws Exception {
        byte[] good = SuspensionJournal.encode(
                "1-1",
                List.of(
                        entry(7, EnumSet.of(SuspensionReason.USER), Optional.empty()),
                        entry(8, EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty())));
        String text = new String(good, StandardCharsets.UTF_8);
        // One entry line made unreadable, the end line no longer matching.
        String damaged = text.replaceFirst("pid=8 ", "pid=eight ");
        Files.writeString(journalFolder.path().resolve("suspensions-1-1.journal"), damaged);
        try (SuspensionJournal current = open("3-3")) {
            SuspensionJournal.Dead dead = current.deadJournals().get(0);

            assertThat(dead.corrupt()).isTrue();
            assertThat(dead.entries()).extracting(e -> e.identity().pid()).containsExactly(7L);
            try (var listed = Files.list(journalFolder.path())) {
                assertThat(listed.map(path -> path.getFileName().toString()))
                        .anyMatch(name -> name.startsWith("suspensions-1-1.journal.corrupt-"));
            }
            current.finish(dead);
        }
    }

    @Test
    void aControlledEndDeletesAnEmptyJournalAndKeepsOneThatHoldsEntries() {
        SuspensionJournal empty = open("1-1");
        empty.close();
        assertThat(journalFolder.path().resolve("suspensions-1-1.journal")).doesNotExist();
        assertThat(journalFolder.path().resolve("suspensions-1-1.lock")).doesNotExist();

        SuspensionJournal holding = open("2-2");
        holding.write(List.of(entry(7, EnumSet.of(SuspensionReason.USER), Optional.empty())));
        holding.close();
        assertThat(journalFolder.path().resolve("suspensions-2-2.journal")).exists();
    }

    /** Runs where POSIX permissions exist: Linux and macOS, in CI. */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
        org.junit.jupiter.api.condition.OS.LINUX,
        org.junit.jupiter.api.condition.OS.MAC
    })
    void onPosixSystemsTheFolderAndTheFilesAreTheOwnersOnly() throws Exception {
        Path state = journalFolder.path().resolve("state");
        try (SuspensionJournal journal = SuspensionJournal.open(state, "1-1", files, millis -> {})) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(state)))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(journal.file())))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void anInstanceIsNamedByItsPidAndStartTimeOrByARandomNameWithoutOne() {
        assertThat(SuspensionJournal.instanceName(42, Optional.of(T))).isEqualTo("42-" + T.toEpochMilli());
        assertThat(SuspensionJournal.instanceName(42, Optional.empty()))
                .startsWith("42-")
                .hasSizeGreaterThan(20);
    }

    @Test
    void aLineWithAPidNoSystemGivesIsNotReadAndTheRestIs() throws Exception {
        byte[] good = SuspensionJournal.encode(
                "1-1",
                List.of(
                        entry(7, EnumSet.of(SuspensionReason.USER), Optional.empty()),
                        entry(8, EnumSet.of(SuspensionReason.USER), Optional.empty())));
        String text = new String(good, StandardCharsets.UTF_8).replaceFirst("pid=8 ", "pid=9999999999 ");

        SuspensionJournal.Decoded decoded = SuspensionJournal.decode(text.getBytes(StandardCharsets.UTF_8));

        assertThat(decoded.entries()).extracting(e -> e.identity().pid()).containsExactly(7L);
        assertThat(decoded.corrupt()).isTrue();
    }

    /** What a session that ended without a controlled shutdown leaves: its journal, and no lock held on it. */
    private void crashedSession(String instance, JournalEntry... entries) {
        try {
            Files.write(
                    journalFolder.path().resolve("suspensions-" + instance + ".journal"),
                    SuspensionJournal.encode(instance, List.of(entries)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // --- Journals whose lock is not free yet (milestone 9, recovery visibility) ---

    /**
     * A journal whose lock is held is read by nobody; it is reported as waiting only when its writer
     * has ended (the system has not let its lock go yet), never when that session still runs.
     */
    @Test
    void aLockedJournalOfAnEndedSessionWaitsAndOneOfARunningSessionIsLeftAlone() {
        crashedSession("1-1", entry(7, EnumSet.of(SuspensionReason.USER), Optional.empty()));
        crashedSession("5-5", entry(9, EnumSet.of(SuspensionReason.USER), Optional.empty()));
        crashedSession("6-6", entry(11, EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty()));
        files.locksHeldElsewhere.add("suspensions-1-1.lock");
        files.locksHeldElsewhere.add("suspensions-5-5.lock");
        try (SuspensionJournal current =
                SuspensionJournal.open(journalFolder.path(), "3-3", files, millis -> {}, "5-5"::equals)) {
            SuspensionJournal.Scan first = current.scan();

            assertThat(first.dead())
                    .extracting(dead -> dead.journal().getFileName().toString())
                    .as("the one journal whose lock is free")
                    .containsExactly("suspensions-6-6.journal");
            assertThat(first.waiting())
                    .extracting(path -> path.getFileName().toString())
                    .as("its writer has ended; the running session's is not waited for")
                    .containsExactly("suspensions-1-1.journal");
            current.finish(first.dead().get(0));

            files.locksHeldElsewhere.remove("suspensions-1-1.lock");
            SuspensionJournal.Scan second = current.scan();

            assertThat(second.dead())
                    .singleElement()
                    .satisfies(dead -> assertThat(dead.entries())
                            .extracting(e -> e.identity().pid())
                            .containsExactly(7L));
            assertThat(second.waiting()).isEmpty();
            current.finish(second.dead().get(0));
            assertThat(journalFolder.path().resolve("suspensions-5-5.journal"))
                    .as("the running session's journal is untouched")
                    .exists();
        }
    }

    /** Whether the session of an instance name still runs: its PID alive and started when the name says. */
    @Test
    void theWriterOfAnInstanceNameRunsOnlyWhileItsProcessWithThatStartLives() {
        ProcessHandle self = ProcessHandle.current();
        String own = SuspensionJournal.instanceName(self.pid(), self.info().startInstant());
        long started = self.info().startInstant().orElseThrow().toEpochMilli();

        assertThat(SuspensionJournal.writerRunning(own)).isTrue();
        assertThat(SuspensionJournal.writerRunning(self.pid() + "-" + (started - 60_000)))
                .as("the same PID, another start: the session has ended")
                .isFalse();
        assertThat(SuspensionJournal.writerRunning(self.pid() + "-" + java.util.UUID.randomUUID()))
                .as("no start recorded and the PID lives: taken as running")
                .isTrue();
        assertThat(SuspensionJournal.writerRunning(Long.MAX_VALUE + "-1"))
                .as("no such process")
                .isFalse();
    }
}
