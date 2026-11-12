package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.FakeProcessController.FakeSuspension;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryState;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;

/**
 * What the coordinator does with the journal of a session that ended without a controlled
 * shutdown (ADR-0011): it resumes nothing by itself, closes what it can show to be over, lists
 * the rest for the user, and resumes a listed process only on request and after the controller's
 * check.
 */
class SuspensionCoordinatorRecoveryTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-08T10:00:00Z"));
    private static final Instant WRITTEN = Instant.parse("2026-10-08T09:00:00Z");

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final List<List<RecoveredSuspension>> listed = new ArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "2-2", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
        coordinator.setRecoveredListener(listed::add);
    }

    @AfterEach
    void closeJournal() {
        journal.close();
    }

    private static JournalEntry entry(Set<SuspensionReason> reasons, Optional<String> memento) {
        return new JournalEntry(
                DEMO, "demo", reasons, memento, Optional.of("test"), "1-1", WRITTEN, WRITTEN, false, false);
    }

    private static JournalEntry user() {
        return entry(EnumSet.of(SuspensionReason.USER), Optional.empty());
    }

    private void unixStopped() {
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
    }

    private List<JournalEntry> journaled() throws Exception {
        return SuspensionJournal.decode(Files.readAllBytes(journal.file())).entries();
    }

    private RecoveredSuspension only() {
        assertThat(coordinator.recovered()).hasSize(1);
        return coordinator.recovered().get(0);
    }

    @Test
    void anEntryWhoseProcessHasExitedIsClosedAndNotListed() throws Exception {
        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");

        assertThat(coordinator.recover(List.of(user()))).isTrue();

        assertThat(coordinator.recovered()).isEmpty();
        assertThat(controller.calls).containsExactly("liveness 4242");
        assertThat(journaled()).isEmpty();
    }

    @Test
    void anEntryWhoseProcessRunsIsClosedAndNothingIsDone() {
        controller.capabilities = FakeProcessController.unixLike();

        coordinator.recover(List.of(user()));

        assertThat(coordinator.recovered()).isEmpty();
        assertThat(controller.calls).containsExactly("liveness 4242", "stopState 4242");
        assertThat(activity.entries()).anySatisfy(e -> assertThat(e.message()).contains("it runs. Nothing was done."));
    }

    @Test
    void aStoppedProcessIsListedAndNotResumed() throws Exception {
        unixStopped();

        coordinator.recover(List.of(entry(EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty())));

        RecoveredSuspension listedEntry = only();
        assertThat(listedEntry.state()).isEqualTo(RecoveryState.POSSIBLY_SUSPENDED);
        assertThat(listedEntry.resumable()).isTrue();
        assertThat(listedEntry.explanation()).contains("does not prove the stop is Prism's");
        assertThat(controller.calls).doesNotContain("resume 4242", "adopt 4242");
        assertThat(listed).as("published at once").isNotEmpty();
        assertThat(coordinator.held()).as("never shown as held in this session").isEmpty();
        assertThat(journaled())
                .singleElement()
                .satisfies(carried -> assertThat(carried.carried()).isTrue());
    }

    @Test
    void aProcessStoppedUnderADebuggerIsForTheUserToHandle() {
        unixStopped();
        controller.stopState = target -> StopState.TRACED;

        coordinator.recover(List.of(user()));

        assertThat(only().state()).isEqualTo(RecoveryState.MANUAL_INTERVENTION);
        assertThat(only().resumable()).isFalse();
    }

    @Test
    void aProcessWhoseLivenessCannotBeCheckedIsForTheUserToHandle() {
        controller.liveness = target -> ControlOutcome.of(OperationResult.ACCESS_DENIED, "denied");

        coordinator.recover(List.of(user()));

        assertThat(only().state()).isEqualTo(RecoveryState.MANUAL_INTERVENTION);
        assertThat(only().explanation()).contains("denied");
    }

    @Test
    void aWindowsEntryWithoutTheThreadsIsListedForManualInterventionAndNeverChecked() {
        controller.stopState = target -> StopState.UNKNOWN;

        coordinator.recover(List.of(entry(EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty())));

        RecoveredSuspension listedEntry = only();
        assertThat(listedEntry.state()).isEqualTo(RecoveryState.MANUAL_INTERVENTION);
        assertThat(listedEntry.resumable()).isFalse();
        assertThat(listedEntry.explanation()).contains("Resource Monitor");

        assertThat(coordinator.resumeRecovered(DEMO).outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.calls).doesNotContain("adopt 4242", "resume 4242", "suspend 4242");
    }

    @Test
    void aWindowsEntryWithTheThreadsIsCheckedOnlyWhenTheUserAsks() {
        controller.stopState = target -> StopState.UNKNOWN;

        coordinator.recover(List.of(entry(EnumSet.of(SuspensionReason.USER), Optional.of("w1:12@3"))));

        assertThat(only().state()).isEqualTo(RecoveryState.POSSIBLY_SUSPENDED);
        assertThat(only().explanation()).contains("checked when you ask");
        assertThat(controller.calls).doesNotContain("adopt 4242");
    }

    @Test
    void dismissingLeavesTheProcessAsItIsAndClosesTheEntry() throws Exception {
        controller.stopState = target -> StopState.UNKNOWN;
        coordinator.recover(List.of(entry(EnumSet.of(SuspensionReason.CPU_LIMITER), Optional.empty())));
        int before = controller.calls.size();

        assertThat(coordinator.dismissRecovered(DEMO)).isTrue();

        assertThat(controller.calls).hasSize(before);
        assertThat(coordinator.recovered()).isEmpty();
        assertThat(journaled()).isEmpty();
        assertThat(coordinator.dismissRecovered(DEMO)).isFalse();
    }

    @Test
    void aResumeTheCheckAllowsReleasesOnceAndClosesTheEntry() throws Exception {
        unixStopped();
        List<FakeSuspension> taken = new ArrayList<>();
        controller.adopt = (target, memento) -> {
            FakeSuspension suspension = controller.adopted(target);
            taken.add(suspension);
            return AdoptResult.adopted(ControlOutcome.success("stopped"), suspension);
        };
        coordinator.recover(List.of(user()));

        SuspensionCoordinator.RecoveryOutcome outcome = coordinator.resumeRecovered(DEMO);

        assertThat(outcome.outcome().succeeded()).isTrue();
        assertThat(outcome.closed()).isTrue();
        assertThat(controller.calls).containsSubsequence("adopt 4242", "resume 4242");
        assertThat(taken).singleElement().satisfies(s -> assertThat(s.resumes).isEqualTo(1));
        assertThat(coordinator.recovered()).isEmpty();
        assertThat(coordinator.held()).isEmpty();
        assertThat(journaled()).isEmpty();
    }

    @Test
    void aProcessNoLongerSuspendedIsClosedWithoutAResume() {
        unixStopped();
        controller.adopt = (target, memento) -> AdoptResult.of(
                AdoptResult.Verdict.NOT_SUSPENDED,
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "no longer suspended"));
        coordinator.recover(List.of(user()));

        SuspensionCoordinator.RecoveryOutcome outcome = coordinator.resumeRecovered(DEMO);

        assertThat(outcome.closed()).isTrue();
        assertThat(controller.calls).doesNotContain("resume 4242");
        assertThat(coordinator.recovered()).isEmpty();
    }

    @Test
    void aRefusedCheckChangesNothingAndTheEntrySaysWhy() {
        controller.stopState = target -> StopState.UNKNOWN;
        controller.adopt = (target, memento) -> AdoptResult.of(
                AdoptResult.Verdict.REFUSED,
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "another suspension also holds it"));
        coordinator.recover(List.of(entry(EnumSet.of(SuspensionReason.USER), Optional.of("w1:12@3"))));

        SuspensionCoordinator.RecoveryOutcome outcome = coordinator.resumeRecovered(DEMO);

        assertThat(outcome.closed()).isFalse();
        assertThat(controller.calls).doesNotContain("resume 4242");
        assertThat(only().explanation())
                .contains("another suspension also holds it")
                .contains("changed nothing");
        assertThat(only().resumable()).isTrue();
        assertThat(coordinator.held()).isEmpty();
    }

    @Test
    void aCheckThatCouldNotBeUndoneIsKeptAsHeldSoItIsNotForgotten() throws Exception {
        controller.stopState = target -> StopState.UNKNOWN;
        controller.adopt = (target, memento) -> new AdoptResult(
                AdoptResult.Verdict.REFUSED,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "a probe could not be undone"),
                Optional.of(controller.adopted(target)));
        coordinator.recover(List.of(entry(EnumSet.of(SuspensionReason.USER), Optional.of("w1:12@3"))));

        coordinator.resumeRecovered(DEMO);

        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(journaled())
                .singleElement()
                .satisfies(e -> assertThat(e.incomplete()).isTrue());
    }

    @Test
    void aProcessPrismHoldsInThisSessionIsNotCheckedAgain() {
        unixStopped();
        coordinator.recover(List.of(user()));
        controller.suspend = target -> SuspendResult.suspended(ControlOutcome.success(), controller.adopted(target));
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        SuspensionCoordinator.RecoveryOutcome outcome = coordinator.resumeRecovered(DEMO);

        assertThat(outcome.outcome().detail().orElseThrow()).contains("Prism holds it in this session");
        assertThat(controller.calls).doesNotContain("adopt 4242");
    }

    @Test
    void anEntryNotResumedStaysInTheJournalAfterAControlledShutdown() throws Exception {
        unixStopped();
        coordinator.recover(List.of(user()));

        coordinator.refuseFurtherSuspensions();
        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));
        coordinator.finalizeForShutdown(Duration.ofSeconds(1));

        assertThat(controller.calls).doesNotContain("resume 4242", "adopt 4242");
        assertThat(journaled())
                .singleElement()
                .satisfies(e -> assertThat(e.carried()).isTrue());
    }

    @Test
    void aJournalThatCannotTakeTheListedEntriesSaysSoAndTheyAreStillListed() {
        unixStopped();
        files.diskFull = true;

        assertThat(coordinator.recover(List.of(user()))).isFalse();

        assertThat(coordinator.recovered()).hasSize(1);
        assertThat(coordinator.journalHealthy()).isFalse();
    }

    @Test
    void anEntryThatCannotBeCheckedIsListedForTheUserAndTheOthersAreStillListed() {
        unixStopped();
        ProcessIdentity other = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));
        controller.liveness = target -> {
            if (target.equals(DEMO)) {
                throw new ArithmeticException("integer overflow");
            }
            return ControlOutcome.success();
        };
        JournalEntry second = new JournalEntry(
                other,
                "other",
                EnumSet.of(SuspensionReason.USER),
                Optional.empty(),
                Optional.empty(),
                "1-1",
                WRITTEN,
                WRITTEN,
                false,
                false);

        assertThat(coordinator.recover(List.of(user(), second))).isTrue();

        assertThat(coordinator.recovered())
                .extracting(RecoveredSuspension::identity, RecoveredSuspension::state)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(DEMO, RecoveryState.MANUAL_INTERVENTION),
                        org.assertj.core.api.Assertions.tuple(other, RecoveryState.POSSIBLY_SUSPENDED));
        assertThat(coordinator.recovered().get(0).explanation()).contains("could not be checked");
    }

    @Test
    void theJournalsOfEarlierSessionsStayWhenThisSessionCannotTakeTheirEntriesAndGoWhenItCan() throws Exception {
        unixStopped();
        Path dead = journalFolder.path().resolve("suspensions-1-1.journal");
        Files.write(dead, SuspensionJournal.encode("1-1", List.of(user())));
        files.diskFull = true;

        coordinator.recoverEarlierSessions();

        assertThat(dead).as("kept for a later start").exists();
        assertThat(coordinator.recovered()).as("still shown in this session").hasSize(1);
        files.diskFull = false;
        assertThat(journal.retry()).isTrue();
        try (SuspensionJournal later = SuspensionJournal.open(journalFolder.path(), "3-3", files, millis -> {})) {
            SuspensionCoordinator next = new SuspensionCoordinator(
                    controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(later));

            next.recoverEarlierSessions();

            assertThat(next.recovered()).hasSize(1);
            assertThat(dead)
                    .as("its lock was let go, and the entries are carried now")
                    .doesNotExist();
            next.refuseFurtherSuspensions();
            next.finalizeForShutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void anEntryWithANameThatWouldBreakALineIsShownOnOne() {
        unixStopped();
        JournalEntry forged = new JournalEntry(
                DEMO,
                "demo\nERROR fake line",
                EnumSet.of(SuspensionReason.USER),
                Optional.empty(),
                Optional.empty(),
                "1-1",
                WRITTEN,
                WRITTEN,
                false,
                false);

        coordinator.recover(List.of(forged));

        assertThat(only().name()).doesNotContain("\n").startsWith("demo ");
    }

    // --- Journals read after start-up (milestone 9, recovery visibility) ---

    private Path deadJournal(String instance, JournalEntry... entries) throws Exception {
        Path dead = journalFolder.path().resolve("suspensions-" + instance + ".journal");
        Files.write(dead, SuspensionJournal.encode(instance, List.of(entries)));
        return dead;
    }

    private long listedWarnings() {
        return activity.entries().stream()
                .filter(entry -> entry.message().contains("may have been left suspended by an earlier session"))
                .count();
    }

    /**
     * A journal whose lock the system still holds is not read at start-up; read later, it is listed
     * then, once, and carried over before its file goes. Nothing is resumed or suspended.
     */
    @Test
    void aJournalWhoseLockIsFreedLaterIsListedThenOnceAndCarriedOver() throws Exception {
        unixStopped();
        Path dead = deadJournal("1-1", user());
        files.locksHeldElsewhere.add("suspensions-1-1.lock");

        assertThat(coordinator.recoverEarlierSessions())
                .as("waiting for its lock")
                .isEqualTo(1);
        assertThat(coordinator.recovered()).isEmpty();
        assertThat(dead).exists();

        files.locksHeldElsewhere.remove("suspensions-1-1.lock");
        assertThat(coordinator.recoverEarlierSessions()).isZero();

        assertThat(coordinator.recovered())
                .singleElement()
                .satisfies(entry -> assertThat(entry.identity()).isEqualTo(DEMO));
        assertThat(listed.get(listed.size() - 1)).as("the listener is told").hasSize(1);
        assertThat(journaled())
                .as("carried over")
                .extracting(JournalEntry::identity)
                .containsExactly(DEMO);
        assertThat(dead).as("deleted once carried").doesNotExist();
        assertThat(coordinator.recoverEarlierSessions()).isZero();
        assertThat(coordinator.recovered()).as("no duplicate").hasSize(1);
        assertThat(listedWarnings()).as("told once").isEqualTo(1);
        assertThat(controller.calls)
                .noneMatch(call -> call.startsWith("resume") || call.startsWith("suspend") || call.startsWith("adopt"));
    }

    /** Of several earlier journals, those that can be read are listed now and the waiting one later. */
    @Test
    void ofSeveralEarlierJournalsTheWaitingOneIsListedLaterWithoutDuplicates() throws Exception {
        unixStopped();
        ProcessIdentity other = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));
        deadJournal("1-1", user());
        Path waiting = deadJournal(
                "7-7",
                new JournalEntry(
                        other,
                        "other",
                        EnumSet.of(SuspensionReason.USER),
                        Optional.empty(),
                        Optional.empty(),
                        "7-7",
                        WRITTEN,
                        WRITTEN,
                        false,
                        false));
        files.locksHeldElsewhere.add("suspensions-7-7.lock");

        assertThat(coordinator.recoverEarlierSessions()).isEqualTo(1);
        assertThat(coordinator.recovered())
                .extracting(RecoveredSuspension::identity)
                .containsExactly(DEMO);

        files.locksHeldElsewhere.remove("suspensions-7-7.lock");
        assertThat(coordinator.recoverEarlierSessions()).isZero();

        assertThat(coordinator.recovered())
                .extracting(RecoveredSuspension::identity)
                .containsExactly(DEMO, other);
        assertThat(journaled()).extracting(JournalEntry::identity).containsExactlyInAnyOrder(DEMO, other);
        assertThat(waiting).doesNotExist();
    }

    /** An entry the user dismissed is not listed again by a journal read later in the same session. */
    @Test
    void anEntryTheUserDismissedIsNotListedAgainByAJournalReadLater() throws Exception {
        unixStopped();
        deadJournal("1-1", user());
        assertThat(coordinator.recoverEarlierSessions()).isZero();
        assertThat(coordinator.dismissRecovered(DEMO)).isTrue();

        Path later = deadJournal("8-8", user());
        assertThat(coordinator.recoverEarlierSessions()).isZero();

        assertThat(coordinator.recovered()).isEmpty();
        assertThat(later).as("closed, nothing to carry").doesNotExist();
    }

    /** A transfer the journal cannot take keeps the earlier journal for the next start, and lists what it read. */
    @Test
    void aTransferThatCannotBeWrittenKeepsTheJournalAndStillListsIt() throws Exception {
        unixStopped();
        Path dead = deadJournal("1-1", user());
        files.locksHeldElsewhere.add("suspensions-1-1.lock");
        assertThat(coordinator.recoverEarlierSessions()).isEqualTo(1);
        files.locksHeldElsewhere.remove("suspensions-1-1.lock");
        files.diskFull = true;

        assertThat(coordinator.recoverEarlierSessions()).isZero();

        assertThat(dead).as("kept for the next start").exists();
        assertThat(coordinator.recovered()).as("shown in this session").hasSize(1);
        assertThat(activity.entries()).anyMatch(entry -> entry.message().contains("kept for the next start"));
    }

    /** Once the shutdown has begun, no journal of an earlier session is read or touched. */
    @Test
    void noEarlierJournalIsReadOnceTheShutdownHasBegun() throws Exception {
        unixStopped();
        Path dead = deadJournal("1-1", user());
        byte[] before = Files.readAllBytes(dead);
        coordinator.refuseFurtherSuspensions();

        assertThat(coordinator.recoverEarlierSessions()).isZero();

        assertThat(coordinator.recovered()).isEmpty();
        assertThat(Files.readAllBytes(dead)).isEqualTo(before);
    }
}
