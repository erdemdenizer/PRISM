package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;

/**
 * The recovery of an earlier session's native limits at start-up (plan §6.4, §8.4), on every system
 * against a fake limiter: each record is looked at; what may still cap a process is carried into
 * this session's ledger before the earlier file goes; the earlier file stays when the carrying
 * cannot be made durable; a live session's ledger and this session's own are never read.
 */
class NativeRecoveryTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.ofEpochMilli(10_000));

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();
    private final ActivityLog activity = new ActivityLog(100, () -> Instant.EPOCH);
    private NativeLimitLedger own;
    private NativeRecovery recovery;

    @BeforeEach
    void setUp() {
        own = ledger("2-2");
        // No Prism process of the test's instance names runs; the host's PID 1 plays no part.
        recovery = new NativeRecovery(limiter, own, Optional.of(folder.path()), files, activity, instance -> false);
    }

    private NativeLimitLedger ledger(String instance) {
        return new NativeLimitLedger(Optional.of(folder.path()), instance, files, Runnable::run);
    }

    private static LimitRecord applied(String instance) {
        EffectiveLimits values = EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty());
        return LimitRecord.intended(DEMO, "demo", values, instance).applied(values);
    }

    private void earlierSession(String instance, LimitRecord record) {
        assertThat(ledger(instance).putDurably(record)).isTrue();
    }

    private boolean exists(String instance) {
        return Files.exists(NativeLimitLedger.file(folder.path(), instance));
    }

    @Test
    void aLimitThatMayStillBeInForceIsCarriedOverAndTheEarlierFileGoes() {
        earlierSession("1-1", applied("1-1"));

        List<RecoveredLimit> found = recovery.lookAtOrphans();

        assertThat(found)
                .singleElement()
                .satisfies(each -> assertThat(each.verdict()).isEqualTo(RecoveredLimit.Verdict.UNREACHABLE));
        assertThat(own.find(DEMO)).as("said again by the next start").isPresent();
        assertThat(exists("1-1")).isFalse();
        assertThat(recovery.found()).hasSize(1);
        assertThat(activity.entries())
                .anySatisfy(entry ->
                        assertThat(entry.message()).contains("may still be limited by an earlier Prism session"));
    }

    @Test
    void aLimitProvenAbsentIsNotCarriedOver() {
        earlierSession("1-1", applied("1-1"));
        limiter.recover = record -> new RecoveredLimit(record, RecoveredLimit.Verdict.NOT_IN_JOB, "in no job");

        assertThat(recovery.lookAtOrphans())
                .singleElement()
                .satisfies(each -> assertThat(each.verdict()).isEqualTo(RecoveredLimit.Verdict.NOT_IN_JOB));

        assertThat(own.find(DEMO)).isEmpty();
        assertThat(exists("1-1")).isFalse();
    }

    /** A failure to look is no proof of anything: the record is carried over as possibly in force. */
    @Test
    void aLimiterThatThrowsGivesFailedAndTheRecordIsCarriedOver() {
        earlierSession("1-1", applied("1-1"));
        limiter.recover = record -> {
            throw new IllegalStateException("the native call failed");
        };

        assertThat(recovery.lookAtOrphans()).singleElement().satisfies(each -> {
            assertThat(each.verdict()).isEqualTo(RecoveredLimit.Verdict.FAILED);
            assertThat(each.blocksAnotherLimiter()).isTrue();
        });
        assertThat(own.find(DEMO)).isPresent();
    }

    /** Evidence is never discarded: if this session cannot keep the record, the earlier file stays. */
    @Test
    void whenTheCarryingCannotBeMadeDurableTheEarlierFileStays() {
        earlierSession("1-1", applied("1-1"));
        files.diskFull = true;

        recovery.lookAtOrphans();

        files.diskFull = false;
        assertThat(exists("1-1")).as("kept for the next start").isTrue();
        assertThat(NativeLimitLedger.readDead(folder.path(), "1-1", files)).hasSize(1);
    }

    @Test
    void aSessionWhoseLockIsHeldOrWhoseJournalIsThereAndThisSessionsOwnAreNotReadAsOrphans() throws Exception {
        earlierSession("1-1", applied("1-1"));
        Files.writeString(folder.path().resolve("suspensions-1-1.lock"), "");
        files.locksHeldElsewhere.add("suspensions-1-1.lock");
        earlierSession("3-3", applied("3-3"));
        Files.writeString(folder.path().resolve("suspensions-3-3.journal"), "");
        own.putDurably(applied("2-2"));

        assertThat(recovery.lookAtOrphans()).isEmpty();
        assertThat(limiter.calls).isEmpty();
        assertThat(exists("1-1")).as("its lock is held: a running session").isTrue();
        assertThat(exists("3-3"))
                .as("its journal is there: read by the journal's recovery")
                .isTrue();
        assertThat(recovery.lookAt("2-2")).as("its own").isEmpty();
    }

    /**
     * A lock file alone is no sign of life (audit, C): a running session holds its lock. One that
     * can be taken, of a Prism process that does not run, is proof the session ended; the ledger is
     * read, settled, and its leftover lock file goes with it.
     */
    @Test
    void aLockThatCanBeTakenOfAProcessThatDoesNotRunIsProofTheSessionEnded() throws Exception {
        earlierSession("1-1", applied("1-1"));
        Files.writeString(folder.path().resolve("suspensions-1-1.lock"), "");

        assertThat(recovery.lookAtOrphans())
                .singleElement()
                .satisfies(each -> assertThat(each.verdict()).isEqualTo(RecoveredLimit.Verdict.UNREACHABLE));

        assertThat(limiter.calls).containsExactly("recover 4242");
        assertThat(exists("1-1")).isFalse();
        assertThat(Files.exists(folder.path().resolve("suspensions-1-1.lock"))).isFalse();
    }

    /** Neither is a free lock proof while the Prism process the name records still runs (audit, C). */
    @Test
    void aSessionWhosePrismProcessStillRunsIsLeftAloneEvenWithItsLockFree() throws Exception {
        NativeRecovery running = new NativeRecovery(
                limiter, own, Optional.of(folder.path()), files, activity, instance -> instance.equals("1-1"));
        earlierSession("1-1", applied("1-1"));
        byte[] before = onDisk("1-1");

        assertThat(running.lookAtOrphans()).isEmpty();
        assertThat(running.lookAt("1-1"))
                .as("nor through its journal's recovery")
                .isEmpty();

        assertThat(limiter.calls).isEmpty();
        assertThat(onDisk("1-1")).isEqualTo(before);
        assertThat(running.mayStillCap(DEMO)).isPresent();
    }

    /** D: a folder of ledgers that cannot be listed is not an empty one: everything stays blocked. */
    @Test
    void aFolderThatCannotBeListedIsNotAnEmptyOneAndBlocksEveryProcessThatRanUntilNow() {
        earlierSession("1-1", applied("1-1"));
        files.denied = true;

        assertThat(recovery.lookAtOrphans()).isEmpty();

        files.denied = false;
        assertThat(limiter.calls).isEmpty();
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
        assertThat(recovery.pending()).hasValueSatisfying(why -> assertThat(why).contains("cannot be read"));
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message()).contains("cannot be listed"));
    }

    /** A ledger the system says is gone after the listing, and only then, lets the barrier go. */
    @Test
    void onlyAConfirmedAbsenceLetsTheBarrierGo() {
        earlierSession("1-1", applied("1-1"));
        files.metadataUnknown.add("native-limits-1-1.ledger");
        recovery.refresh();
        assertThat(recovery.mayStillCap(DEMO)).as("cannot tell: still held").isPresent();

        files.metadataUnknown.clear();
        NativeLimitLedger.deleteDead(folder.path(), "1-1", files);
        recovery.refresh();

        assertThat(recovery.mayStillCap(DEMO)).isEmpty();
        assertThat(recovery.pending()).isEmpty();
    }

    /**
     * E, the order that matters: a record is found before its file goes, and the file goes before the
     * barrier is refreshed. Asked at every step, the barrier never lets the process through.
     */
    @Test
    void theBarrierHoldsAtEveryStepOfSettlingALedger() {
        earlierSession("1-1", applied("1-1"));
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        files.aroundDelete = (file, when) -> {
            if (String.valueOf(file.getFileName()).equals("native-limits-1-1.ledger")) {
                if (when.equals("after")) {
                    // The file is gone: a refresh now settles the folder, so only the found record can block.
                    recovery.refresh();
                }
                seen.add(when + "=" + recovery.mayStillCap(DEMO).isPresent());
            }
        };
        recovery.refresh();
        assertThat(recovery.pending())
                .as("the ledger is listed: its time bounds the barrier")
                .isPresent();

        recovery.lookAtOrphans();

        assertThat(seen).containsExactly("before=true", "after=true");
        assertThat(recovery.mayStillCap(DEMO)).as("now by its record").isPresent();
    }

    /** E, at once from several threads: one look settles the ledger, once; none leaves a stale "settled". */
    @Test
    void looksAndRefreshesAtOnceSettleALedgerExactlyOnce() throws Exception {
        earlierSession("1-1", applied("1-1"));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<Boolean> blocked = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            List<java.util.concurrent.Future<?>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int kind = i % 3;
                runs.add(pool.submit(() -> {
                    start.await();
                    switch (kind) {
                        case 0 -> recovery.lookAtOrphans();
                        case 1 -> recovery.refresh();
                        default -> blocked.add(recovery.mayStillCap(DEMO).isPresent());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> each : runs) {
                each.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(limiter.calls).as("recovered exactly once").containsExactly("recover 4242");
        assertThat(own.records()).hasSize(1);
        assertThat(exists("1-1")).isFalse();
        assertThat(blocked).as("never let through at any moment").containsOnly(true);
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
    }

    @Test
    void anEmptyEarlierLedgerIsDeletedWithoutAskingTheSystem() {
        NativeLimitLedger earlier = ledger("1-1");
        assertThat(earlier.putDurably(applied("1-1"))).isTrue();
        earlier.remove(DEMO);
        assertThat(earlier.awaitLatest(NativeLimitLedger.WAIT)).isTrue();
        assertThat(exists("1-1")).isTrue();

        assertThat(recovery.lookAtOrphans()).isEmpty();

        assertThat(limiter.calls).isEmpty();
        assertThat(exists("1-1")).isFalse();
    }

    // ----- Audit item 1: evidence that cannot be read whole is never deleted -----

    private static final ProcessIdentity LATER =
            new ProcessIdentity(5_555, Instant.now().plusSeconds(3_600));

    private byte[] onDisk(String instance) throws Exception {
        return Files.readAllBytes(NativeLimitLedger.file(folder.path(), instance));
    }

    @Test
    void aLedgerThatCannotBeReadIsKeptSaidAndBlocksTheEmulatedLimitUntilTheNextStartReadsIt() throws Exception {
        earlierSession("1-1", applied("1-1"));
        byte[] before = onDisk("1-1");
        files.readsFail = 1;

        assertThat(recovery.lookAtOrphans()).isEmpty();

        assertThat(limiter.calls)
                .as("nothing is decided from what was not read")
                .isEmpty();
        assertThat(onDisk("1-1")).as("the evidence stays as it was").isEqualTo(before);
        assertThat(recovery.mayStillCap(DEMO))
                .as("a process that ran while it was written")
                .isPresent();
        assertThat(recovery.mayStillCap(LATER))
                .as("a process that started after it")
                .isEmpty();
        assertThat(recovery.pending()).isPresent();
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message())
                        .contains("could not be read")
                        .contains("kept and read again at the next start"));

        // The next start: the file can be read now.
        NativeLimitLedger next = ledger("3-3");
        NativeRecovery again =
                new NativeRecovery(limiter, next, Optional.of(folder.path()), files, activity, instance -> false);
        assertThat(again.lookAtOrphans())
                .singleElement()
                .satisfies(each -> assertThat(each.verdict()).isEqualTo(RecoveredLimit.Verdict.UNREACHABLE));
        assertThat(exists("1-1")).as("settled, carried over, then deleted").isFalse();
        assertThat(next.find(DEMO)).isPresent();
        assertThat(again.mayStillCap(DEMO)).as("now by its record").isPresent();
        assertThat(again.pending())
                .as("the carried record is this session's own ledger")
                .isEmpty();
    }

    @Test
    void aDamagedLedgerWithNoRecordThatCanBeReadIsKeptAndBlocks() throws Exception {
        Files.writeString(NativeLimitLedger.file(folder.path(), "1-1"), "prism native limits 1\nrecord garbled\n");
        byte[] before = onDisk("1-1");

        assertThat(recovery.lookAtOrphans()).isEmpty();

        assertThat(limiter.calls).isEmpty();
        assertThat(onDisk("1-1")).isEqualTo(before);
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
        assertThat(activity.entries())
                .anySatisfy(entry ->
                        assertThat(entry.message()).contains("is damaged").contains("The file is kept as it is"));
    }

    @Test
    void aDamagedLedgerIsSettledAsFarAsItCanBeReadAndKeptWhole() throws Exception {
        earlierSession("1-1", applied("1-1"));
        String text = new String(onDisk("1-1"), java.nio.charset.StandardCharsets.UTF_8);
        int end = text.indexOf("\nend ");
        // A second record that cannot be parsed, so the check fails too.
        Files.writeString(
                NativeLimitLedger.file(folder.path(), "1-1"),
                text.substring(0, end + 1) + "record pid=5555 start=broken\n" + text.substring(end + 1));
        byte[] before = onDisk("1-1");

        List<RecoveredLimit> found = recovery.lookAtOrphans();

        assertThat(found).singleElement().satisfies(each -> {
            assertThat(each.record().identity()).isEqualTo(DEMO);
            assertThat(each.verdict()).isEqualTo(RecoveredLimit.Verdict.UNREACHABLE);
        });
        assertThat(own.find(DEMO))
                .as("the record that could be read is carried over")
                .isPresent();
        assertThat(onDisk("1-1")).as("the unparsed rest is not destroyed").isEqualTo(before);
        assertThat(recovery.mayStillCap(new ProcessIdentity(5_555, Instant.ofEpochMilli(20_000))))
                .as("a process that ran while the damaged file was written")
                .isPresent();
    }

    @Test
    void untilTheFirstLookEveryProcessMayBeNamedAndASettledFolderBlocksNothing() {
        assertThat(recovery.mayStillCap(LATER))
                .as("not read yet: nothing is known")
                .isPresent();

        recovery.lookAtOrphans();

        assertThat(recovery.mayStillCap(DEMO)).isEmpty();
        assertThat(recovery.pending()).isEmpty();
    }

    /** A damaged ledger is read again by every start, kept, and carried over without duplicates. */
    @Test
    void aDamagedLedgerIsReadAgainAtTheNextStartKeptAndNotCarriedTwice() throws Exception {
        earlierSession("1-1", applied("1-1"));
        String text = new String(onDisk("1-1"), java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(NativeLimitLedger.file(folder.path(), "1-1"), text.replace(" cpu=2000", " cpu=2001"));
        byte[] before = onDisk("1-1");
        recovery.lookAtOrphans();

        NativeLimitLedger next = ledger("3-3");
        NativeRecovery again =
                new NativeRecovery(limiter, next, Optional.of(folder.path()), files, activity, instance -> false);
        again.lookAtOrphans();

        assertThat(onDisk("1-1")).isEqualTo(before);
        assertThat(next.records()).as("one record per process").hasSize(1);
        assertThat(again.mayStillCap(DEMO)).isPresent();
        assertThat(again.pending()).isPresent();
    }

    /**
     * Through the dead-session hook (the session's lock is held while it is looked at): an unreadable
     * ledger survives the coordinator's clean-up of the journal and is read as an orphan next time.
     */
    @Test
    void anUnreadableLedgerLookedAtThroughItsSessionSurvivesAndIsReadAsAnOrphanNextTime() throws Exception {
        earlierSession("1-1", applied("1-1"));
        files.readsFail = 1;

        assertThat(recovery.lookAt("1-1")).isEmpty();
        assertThat(exists("1-1")).isTrue();

        // The coordinator removes the ended session's journal and lock; the ledger stays.
        NativeLimitLedger next = ledger("3-3");
        NativeRecovery again =
                new NativeRecovery(limiter, next, Optional.of(folder.path()), files, activity, instance -> false);
        assertThat(again.lookAtOrphans())
                .singleElement()
                .satisfies(each -> assertThat(each.record().identity()).isEqualTo(DEMO));
        assertThat(exists("1-1")).isFalse();
    }

    /** A ledger that is gone between the listing and the look holds nothing back. */
    @Test
    void aLedgerGoneSinceTheListingHoldsNothingBack() {
        earlierSession("1-1", applied("1-1"));
        files.readsFail = 1;
        recovery.lookAtOrphans();
        assertThat(recovery.mayStillCap(DEMO)).isPresent();

        NativeLimitLedger.deleteDead(folder.path(), "1-1", files);
        recovery.refresh();

        assertThat(recovery.mayStillCap(DEMO)).isEmpty();
        assertThat(recovery.pending()).isEmpty();
    }

    // ----- Audit of the recovery trust boundary (A to E) -----

    /** A: a listed ledger whose state cannot be told is evidence; it is kept, and it holds the barrier. */
    @Test
    void aListedLedgerWhoseStateCannotBeToldIsKeptAndHoldsTheBarrier() throws Exception {
        earlierSession("1-1", applied("1-1"));
        byte[] before = onDisk("1-1");
        files.metadataUnknown.add("native-limits-1-1.ledger");

        recovery.lookAtOrphans();

        assertThat(limiter.calls).isEmpty();
        assertThat(onDisk("1-1")).isEqualTo(before);
        assertThat(recovery.mayStillCap(DEMO)).as("B: the barrier stays").isPresent();
        assertThat(recovery.mayStillCap(LATER))
                .as("its time cannot be read: every process that started until now may be named")
                .isEmpty();
    }

    /**
     * C: a session that still holds its lock, whose lock and journal cannot be looked at, is never
     * taken for an ended one: its ledger is not read for recovery, not deleted, and holds the barrier.
     */
    @Test
    void aSessionThatHoldsItsLockIsNeverTakenForAnOrphanWhateverItsFilesSay() throws Exception {
        earlierSession("7-7", applied("7-7"));
        byte[] before = onDisk("7-7");
        Files.writeString(folder.path().resolve("suspensions-7-7.journal"), "");
        Files.writeString(folder.path().resolve("suspensions-7-7.lock"), "");
        files.locksHeldElsewhere.add("suspensions-7-7.lock");
        files.metadataUnknown.add("suspensions-7-7.lock");
        files.metadataUnknown.add("suspensions-7-7.journal");

        recovery.lookAtOrphans();

        assertThat(limiter.calls)
                .as("nothing is asked of the system for a live session")
                .isEmpty();
        assertThat(onDisk("7-7")).as("its ledger is not touched").isEqualTo(before);
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
    }

    /** C in isolation: a journal whose state cannot be told is not "gone", with the lock free and no Prism process running. */
    @Test
    void aJournalWhoseStateCannotBeToldKeepsTheSessionFromBeingTakenForEnded() throws Exception {
        earlierSession("1-1", applied("1-1"));
        byte[] before = onDisk("1-1");
        Files.writeString(folder.path().resolve("suspensions-1-1.journal"), "");
        files.metadataUnknown.add("suspensions-1-1.journal");

        assertThat(recovery.lookAtOrphans()).isEmpty();

        assertThat(limiter.calls).isEmpty();
        assertThat(onDisk("1-1")).isEqualTo(before);
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
    }

    /** B: a ledger listed, then reported gone by the system, lets the barrier go; one that cannot be told keeps it. */
    @Test
    void aLedgerListedThenReportedGoneLetsTheBarrierGoAndOneThatCannotBeToldDoesNot() {
        earlierSession("1-1", applied("1-1"));
        files.metadataUnknown.add("native-limits-1-1.ledger");
        recovery.refresh();
        assertThat(recovery.mayStillCap(DEMO)).isPresent();

        files.metadataUnknown.clear();
        files.reportedAbsent.add("native-limits-1-1.ledger");
        recovery.refresh();

        assertThat(exists("1-1")).as("still listed").isTrue();
        assertThat(recovery.mayStillCap(DEMO)).as("the system said it is gone").isEmpty();
    }

    /** Invariant 8: an orphan that could not be proven ended is looked at again in the same session. */
    @Test
    void anOrphanWhoseLockWasHeldIsSettledByALaterLookInTheSameSession() throws Exception {
        earlierSession("1-1", applied("1-1"));
        Files.writeString(folder.path().resolve("suspensions-1-1.lock"), "");
        files.locksHeldElsewhere.add("suspensions-1-1.lock");
        recovery.lookAtOrphans();
        assertThat(limiter.calls).isEmpty();

        files.locksHeldElsewhere.clear();
        recovery.lookAtOrphans();

        assertThat(limiter.calls).containsExactly("recover 4242");
        assertThat(exists("1-1")).isFalse();
    }

    /** A damaged ledger is said once per session, not at every later look. */
    @Test
    void aDamagedLedgerIsNotSettledAgainByLaterLooksInTheSameSession() throws Exception {
        earlierSession("1-1", applied("1-1"));
        String text = new String(onDisk("1-1"), java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(NativeLimitLedger.file(folder.path(), "1-1"), text.replace(" cpu=2000", " cpu=2001"));
        recovery.lookAtOrphans();
        recovery.lookAtOrphans();

        assertThat(limiter.calls).containsExactly("recover 4242");
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
    }

    // ----- Final audit: the look at start-up, whatever goes wrong -----

    /** A failed look with no ledger on disk leaves nothing blocked for the session. */
    @Test
    void aStartUpLookThatFailsWithNoLedgerThereLeavesNothingBlocked() {
        files.listsThrowingUnexpectedly = 1;

        recovery.lookAtStartUp();

        assertThat(recovery.pending()).isEmpty();
        assertThat(recovery.mayStillCap(DEMO)).isEmpty();
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message()).contains("could not be read"));
    }

    /** A failed look with a ledger on disk keeps the barrier: what is on disk decides. */
    @Test
    void aStartUpLookThatFailsWithALedgerThereKeepsTheBarrier() {
        earlierSession("1-1", applied("1-1"));
        files.listsThrowingUnexpectedly = 1;

        recovery.lookAtStartUp();

        assertThat(limiter.calls).isEmpty();
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
        assertThat(exists("1-1")).isTrue();
    }

    /** When the look and the refresh after it both fail, every process that ran until now stays blocked. */
    @Test
    void aStartUpLookWhoseRefreshFailsTooBlocksEveryProcessThatRanUntilNow() {
        files.listsThrowingUnexpectedly = 2;

        recovery.lookAtStartUp();

        assertThat(recovery.mayStillCap(DEMO)).isPresent();
        assertThat(recovery.pending()).isPresent();
        assertThat(recovery.mayStillCap(LATER))
                .as("a process that starts later is not named")
                .isEmpty();
    }

    /** Where no Prism session can have set a native limit (no folder given), nothing is ever unsettled. */
    @Test
    void whereNoNativeLimitCanHaveBeenSetNothingIsUnsettledEvenWithAStrayLedger() {
        earlierSession("1-1", applied("1-1"));
        NativeRecovery none = new NativeRecovery(limiter, own, Optional.empty(), files, activity, instance -> false);

        none.lookAtStartUp();

        assertThat(none.pending()).isEmpty();
        assertThat(none.mayStillCap(DEMO)).isEmpty();
        assertThat(limiter.calls).isEmpty();
    }
}
