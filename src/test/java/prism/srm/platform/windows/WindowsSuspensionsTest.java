package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.SuspendResult;

/**
 * Per-thread suspension against a fake system: Prism adds exactly one to each thread's suspend
 * count, takes back exactly that one, and never forgets one it may still hold.
 */
class WindowsSuspensionsTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30Z");
    private static final int PID = 5_000;
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, START);

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsSuspensions suspensions = new WindowsSuspensions(win32, new WindowsProcessAccess(win32));

    WindowsSuspensionsTest() {
        win32.process(PID, START);
    }

    private void threads(int... ids) {
        for (int id : ids) {
            win32.thread(id, PID);
        }
    }

    private int count(int tid) {
        return win32.threads.get(tid).suspendCount;
    }

    @Test
    void everyThreadIsSuspendedOnceAndHeldByItsHandle() {
        threads(11, 12, 13);

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        WindowsSuspension suspension = (WindowsSuspension) result.suspension().get();
        assertThat(suspension.threads()).isEqualTo(3);
        assertThat(suspension.outstanding()).isEqualTo(3);
        assertThat(List.of(count(11), count(12), count(13))).containsOnly(1);
        assertThat(win32.openHandles())
                .as("the process and the three threads stay open while the suspension is held")
                .containsExactlyInAnyOrder("process 5000", "thread 11", "thread 12", "thread 13");
    }

    @Test
    void aThreadStartedMeanwhileIsCaughtByTheNextListingAndTheListingsStop() {
        threads(11, 12);
        win32.thread(14, PID);
        win32.listings.add(List.of(11, 12));
        win32.listings.add(List.of(11, 12, 14));
        win32.listings.add(List.of(11, 12, 14));

        suspensions.suspend(TARGET);

        assertThat(count(14)).isEqualTo(1);
        assertThat(win32.calls.stream().filter("Toolhelp threads"::equals)).hasSize(3);
        assertThat(win32.calls.stream().filter(call -> call.startsWith("SuspendThread")))
                .hasSize(3);
    }

    @Test
    void listingStopsAfterThreePasses() {
        threads(11, 12, 13);
        win32.listings.add(List.of(11));
        win32.listings.add(List.of(11, 12));
        win32.listings.add(List.of(11, 12, 13));

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(win32.calls.stream().filter("Toolhelp threads"::equals)).hasSize(WindowsSuspensions.LISTINGS);
        assertThat(result.suspension().get().threads()).isEqualTo(3);
    }

    @Test
    void aThreadThatEndedSinceTheListingIsPassedOver() {
        threads(11, 12);
        win32.threads.get(12).openError = WindowsSuspensions.ERROR_INVALID_PARAMETER;
        // Ended: the next listing no longer names it.
        win32.listings.add(List.of(11, 12));
        win32.listings.add(List.of(11));

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().succeeded()).isTrue();
        assertThat(result.suspension().get().threads()).isEqualTo(1);
    }

    /** A thread ID can be reused between the listing and the open: the thread of another process is never suspended. */
    @Test
    void aThreadIdNowUsedByAnotherProcessIsNotSuspended() {
        threads(11);
        win32.process(9_999, START);
        win32.thread(12, 9_999);
        win32.listings.add(List.of(11, 12));

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(count(12)).isZero();
        assertThat(result.suspension().get().threads()).isEqualTo(1);
        assertThat(win32.openHandles()).doesNotContain("thread 12");
    }

    @Test
    void aThreadWhoseOwnerCannotBeReadIsNeverSuspendedOnAGuess() {
        threads(11, 12);
        win32.threads.get(12).ownerUnreadable = true;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.suspension()).isEmpty();
        assertThat(count(11)).as("rolled back").isZero();
        assertThat(count(12)).isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    /** A refusal part way is rolled back: each thread Prism suspended gets exactly one resume. */
    @Test
    void aRefusalPartWayRollsBackExactlyWhatPrismDid() {
        threads(11, 12, 13);
        win32.threads.get(11).suspendCount = 2; // another program holds this one already
        win32.threads.get(13).openError = 5;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.outcome().nativeCode()).isEqualTo(OptionalInt.of(5));
        assertThat(result.suspension()).isEmpty();
        assertThat(count(11)).as("the other program's suspensions stay").isEqualTo(2);
        assertThat(count(12)).isZero();
        assertThat(win32.threads.get(11).resumes).isEqualTo(1);
        assertThat(win32.threads.get(12).resumes).isEqualTo(1);
        assertThat(win32.openHandles()).isEmpty();
        assertThat(win32.doubleCloses()).isEmpty();
    }

    @Test
    void aFailingSuspendThreadIsRolledBackToo() {
        threads(11, 12);
        win32.threads.get(12).suspendError = 31;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.outcome().nativeCode()).isEqualTo(OptionalInt.of(31));
        assertThat(count(11)).isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    /**
     * A rollback that fails on a live thread keeps that thread's increment: the result is a failure
     * that still holds a suspension of exactly that thread, with its handle open.
     */
    @Test
    void aRollbackThatCannotReleaseALiveThreadKeepsItsOwnership() {
        threads(11, 12, 13);
        win32.threads.get(13).suspendError = 31;
        win32.threads.get(12).resumeError = 6;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.outcome().detail().get()).contains("rollback left 1 live thread");
        WindowsSuspension remainder = (WindowsSuspension) result.suspension().get();
        assertThat(remainder.outstanding()).isEqualTo(1);
        assertThat(count(11)).isZero();
        assertThat(count(12)).isEqualTo(1);
        assertThat(win32.openHandles()).containsExactlyInAnyOrder("process 5000", "thread 12");

        // A later resume tries that thread, and only that thread.
        win32.threads.get(12).resumeError = 0;
        ControlOutcome resumed = suspensions.resume(remainder);

        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(count(12)).isZero();
        assertThat(win32.threads.get(11).resumes)
                .as("released once, never again")
                .isEqualTo(1);
        assertThat(win32.threads.get(12).resumes).isEqualTo(2);
        assertThat(win32.openHandles()).isEmpty();
        assertThat(win32.doubleCloses()).isEmpty();
    }

    /** What ResumeThread does on an ended thread is undocumented; the thread's exit code decides. */
    @Test
    void aRollbackFailureOnAThreadThatHasEndedReleasesIt() {
        threads(11, 12);
        win32.threads.get(12).suspendError = 31;
        win32.threads.get(11).resumeError = 5;
        win32.threads.get(11).ended = true;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.suspension()).as("nothing is left to hold").isEmpty();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void resumeTakesBackPrismsIncrementOnlyAndClosesEverything() {
        threads(11, 12);
        win32.threads.get(11).suspendCount = 1; // suspended by another program before
        SuspendResult result = suspensions.suspend(TARGET);

        ControlOutcome resumed =
                suspensions.resume((WindowsSuspension) result.suspension().get());

        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(count(11)).as("the other program's suspension stays").isEqualTo(1);
        assertThat(count(12)).isZero();
        assertThat(result.suspension().get().outstanding()).isZero();
        assertThat(win32.openHandles()).isEmpty();
        assertThat(win32.doubleCloses()).isEmpty();
    }

    @Test
    void aResumeThatFailsOnALiveThreadKeepsItAndSaysSo() {
        threads(11, 12);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();
        win32.threads.get(12).resumeError = 6;

        ControlOutcome resumed = suspensions.resume(suspension);

        assertThat(resumed.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(resumed.nativeCode()).isEqualTo(OptionalInt.of(6));
        assertThat(suspension.outstanding()).isEqualTo(1);
        assertThat(win32.openHandles()).containsExactlyInAnyOrder("process 5000", "thread 12");
    }

    @Test
    void resumingAfterTheProcessExitedCallsNothingReleasesEverythingAndSaysItHasExited() {
        threads(11, 12);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();
        win32.processes.get(PID).exitCode = 1;

        ControlOutcome resumed = suspensions.resume(suspension);

        assertThat(resumed.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(suspension.outstanding()).isZero();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("ResumeThread"));
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void closingASuspensionLeavesItsThreadsSuspendedAndClosesTheHandlesOnce() {
        threads(11);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();

        suspension.close();
        suspension.close();

        assertThat(count(11)).isEqualTo(1);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("ResumeThread"));
        assertThat(win32.openHandles()).isEmpty();
        assertThat(win32.doubleCloses()).isEmpty();
    }

    @Test
    void aRunningProcessWithoutAListedThreadIsANativeError() {
        win32.listings.add(List.of());

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.suspension()).isEmpty();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aProcessThatExitsBeforeItsThreadsAreListedIsNotFound() {
        threads(11);
        // It passes the identity check, then exits: its threads are gone from the listing.
        win32.onListThreads = () -> win32.processes.get(PID).exitCode = 0;
        win32.listings.add(List.of());

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aThreadListThatCannotBeTakenIsANativeError() {
        threads(11);
        win32.threadListingError = 8;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.outcome().nativeCode()).isEqualTo(OptionalInt.of(8));
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aStaleIdentitySuspendsNothing() {
        threads(11);

        SuspendResult result = suspensions.suspend(new ProcessIdentity(PID, START.plusMillis(2)));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(count(11)).isZero();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("Toolhelp threads"));
    }

    /** A pass that finds no new thread is the last: a stable process is listed twice, not three times. */
    @Test
    void aStableThreadListIsTakenTwice() {
        threads(11, 12);

        suspensions.suspend(TARGET);

        assertThat(win32.calls.stream().filter("Toolhelp threads"::equals)).hasSize(2);
    }

    /** Nothing known about a thread whose release failed: Prism keeps owning its increment. */
    @Test
    void aFailedReleaseOfAThreadWhoseExitCodeCannotBeReadKeepsItOwned() {
        threads(11, 12);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();
        win32.threads.get(12).resumeError = 6;
        win32.threads.get(12).exitCodeFails = true;

        ControlOutcome resumed = suspensions.resume(suspension);

        assertThat(resumed.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(suspension.outstanding()).isEqualTo(1);
        assertThat(win32.openHandles()).containsExactlyInAnyOrder("process 5000", "thread 12");
    }

    /** No evidence of an exit is no exit: every owned thread still gets its one resume. */
    @Test
    void aProcessWhoseExitCodeCannotBeReadIsResumedThreadByThread() {
        threads(11, 12);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();
        win32.processes.get(PID).exitCodeFails = true;

        ControlOutcome resumed = suspensions.resume(suspension);

        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(win32.threads.get(11).resumes).isEqualTo(1);
        assertThat(win32.threads.get(12).resumes).isEqualTo(1);
        assertThat(List.of(count(11), count(12))).containsOnly(0);
        assertThat(win32.openHandles()).isEmpty();
    }

    /** The process exits part way and the next thread is refused: nothing is left to resume, and nothing is. */
    @Test
    void aProcessThatExitsPartWayThroughAFailedSuspendIsNotResumed() {
        threads(11, 12);
        win32.afterSuspend = () -> {
            win32.processes.get(PID).exitCode = 0;
            win32.threads.get(12).openError = 5;
        };

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.suspension()).isEmpty();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("ResumeThread"));
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aProcessThatExitsWhileItIsBeingSuspendedIsNotFoundAndNothingIsHeld() {
        threads(11, 12);
        win32.afterSuspend = () -> win32.processes.get(PID).exitCode = 0;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(result.suspension()).isEmpty();
        assertThat(win32.calls).noneMatch(call -> call.startsWith("ResumeThread"));
        assertThat(win32.openHandles()).isEmpty();
    }

    /** A thread passed over as ended whose ID is a new thread of the process at the next listing is suspended. */
    @Test
    void anIdPassedOverAsEndedAndThenUsedByANewThreadOfTheProcessIsSuspended() {
        threads(11, 12);
        win32.threads.get(12).openError = 87;
        int[] listings = {0};
        win32.onListThreads = () -> {
            if (++listings[0] == 2) {
                win32.threads.get(12).openError = 0;
            }
        };

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(List.of(count(11), count(12))).containsOnly(1);
    }

    /** OpenThread does not document 87; a thread the next listing still names and that still cannot be opened is a failure. */
    @Test
    void aThreadListedAgainThatStillCannotBeOpenedIsAFailureAndRolledBack() {
        threads(11, 12);
        win32.threads.get(12).openError = 87;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.outcome().nativeCode()).isEqualTo(OptionalInt.of(87));
        assertThat(result.suspension()).isEmpty();
        assertThat(count(11)).isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void threadsStillBeingCreatedAfterTheLastListingAreReportedWithTheSuccess() {
        threads(11, 12, 13);
        win32.listings.add(List.of(11));
        win32.listings.add(List.of(11, 12));
        win32.listings.add(List.of(11, 12, 13));

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(result.outcome().detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("still being created"));
    }

    @Test
    void anOwnerThatCannotBeReadKeepsTheCodeOfTheFailure() {
        threads(11);
        win32.threads.get(11).ownerUnreadable = true;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.outcome().nativeCode()).isEqualTo(OptionalInt.of(6));
    }

    /** Nothing expected throws here; if something does, no thread is left suspended without a hold. */
    @Test
    void anUnexpectedExceptionPartWayIsRolledBackBeforeItGoesOn() {
        threads(11, 12);
        int[] listings = {0};
        IllegalStateException unexpected = new IllegalStateException("simulated");
        win32.onListThreads = () -> {
            if (++listings[0] == 2) {
                throw unexpected;
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> suspensions.suspend(TARGET))
                .isSameAs(unexpected);

        assertThat(List.of(count(11), count(12))).containsOnly(0);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void resumingAClosedSuspensionIsAnErrorAndNotAnException() {
        threads(11);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();
        suspension.close();

        ControlOutcome resumed = suspensions.resume(suspension);

        assertThat(resumed.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("ResumeThread"));
    }

    /** The exit code cannot be read and every thread has ended: nothing is called "resumed" in the detail. */
    @Test
    void threadsThatAllEndedWhileTheExitCodeCannotBeReadAreReleasedAndCountedAsEnded() {
        threads(11, 12);
        WindowsSuspension suspension =
                (WindowsSuspension) suspensions.suspend(TARGET).suspension().get();
        win32.processes.get(PID).exitCodeFails = true;
        for (int tid : new int[] {11, 12}) {
            win32.threads.get(tid).ended = true;
            win32.threads.get(tid).resumeError = 6;
        }

        ControlOutcome resumed = suspensions.resume(suspension);

        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(resumed.detail()).contains("0 thread(s) released, 2 had ended");
        assertThat(suspension.outstanding()).isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    /**
     * Milestone 9 review: which failed suspends the limiter may make again, and which end it. Only a
     * thread shown to have ended under Prism's hand, of a process shown to run, with everything undone.
     */
    @Test
    void aThreadThatEndedAsItWasSuspendedIsTransientOnceEverythingIsUndone() {
        threads(11, 12, 13);
        win32.threads.get(12).suspendError = 5;
        win32.threads.get(12).ended = true;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.transientFailure()).isTrue();
        assertThat(result.suspension()).isEmpty();
        assertThat(List.of(count(11), count(12), count(13))).as("rolled back").containsOnly(0);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aThreadThatStillRunsAndRefusesItsSuspensionIsNoTransientFailure() {
        threads(11, 12, 13);
        win32.threads.get(12).suspendError = 5;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.transientFailure()).isFalse();
        assertThat(List.of(count(11), count(12), count(13))).as("rolled back").containsOnly(0);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aThreadWhoseEndCannotBeReadIsNoTransientFailure() {
        threads(11, 12);
        win32.threads.get(12).suspendError = 5;
        win32.threads.get(12).exitCodeFails = true;

        assertThat(suspensions.suspend(TARGET).transientFailure()).isFalse();
    }

    @Test
    void aThreadThatMayNotBeOpenedIsNoTransientFailure() {
        // An access refusal of OpenThread is the thread's security descriptor: it answers the same
        // way next time. The limit ends at once, also when other threads had been held.
        threads(11, 12);
        win32.threads.get(12).openError = 5;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.transientFailure()).isFalse();
        assertThat(count(11)).as("rolled back").isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void anEndedThreadOfAProcessThatHasExitedIsNoTransientFailure() {
        threads(11, 12);
        win32.threads.get(12).suspendError = 5;
        win32.threads.get(12).ended = true;
        win32.afterSuspend = () -> win32.processes.get(PID).exitCode = 0;

        assertThat(suspensions.suspend(TARGET).transientFailure()).isFalse();
    }

    @Test
    void anEndedThreadOfAProcessWhoseStateCannotBeReadIsNoTransientFailure() {
        threads(11, 12);
        win32.threads.get(12).suspendError = 5;
        win32.threads.get(12).ended = true;
        win32.afterSuspend = () -> win32.processes.get(PID).exitCodeFails = true;

        assertThat(suspensions.suspend(TARGET).transientFailure()).isFalse();
    }

    @Test
    void aThreadWhoseOwnerCannotBeReadAndThatEndedIsTransient() {
        threads(11, 12);
        win32.threads.get(12).ownerUnreadable = true;
        win32.threads.get(12).ended = true;

        assertThat(suspensions.suspend(TARGET).transientFailure()).isTrue();
        assertThat(count(11)).as("rolled back").isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aThreadWhoseOwnerCannotBeReadAndThatRunsIsNoTransientFailure() {
        threads(11, 12);
        win32.threads.get(12).ownerUnreadable = true;

        assertThat(suspensions.suspend(TARGET).transientFailure()).isFalse();
    }

    @Test
    void aProcessThatMayNotBeOpenedIsNoTransientFailure() {
        threads(11);
        win32.processes.get(PID).openError = 5;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.transientFailure()).isFalse();
    }

    @Test
    void anIdentityThatNoLongerMatchesIsNoTransientFailure() {
        threads(11);

        SuspendResult result = suspensions.suspend(new ProcessIdentity(PID, START.plusSeconds(1)));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(result.transientFailure()).isFalse();
    }

    @Test
    void aListingThatFailsIsNoTransientFailure() {
        threads(11);
        win32.threadListingError = 8;

        assertThat(suspensions.suspend(TARGET).transientFailure()).isFalse();
    }

    @Test
    void aThreadListedAgainThatStillCannotBeOpenedIsNoTransientFailure() {
        threads(11, 12);
        win32.threads.get(12).openError = WindowsSuspensions.ERROR_INVALID_PARAMETER;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.outcome().succeeded()).isFalse();
        assertThat(result.transientFailure()).isFalse();
    }

    @Test
    void aFailureWhoseRollbackLeftAThreadHeldIsNoTransientFailure() {
        threads(11, 12);
        win32.threads.get(12).suspendError = 31;
        win32.threads.get(12).ended = true;
        win32.threads.get(11).resumeError = 5;

        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.suspension()).isPresent();
        assertThat(result.transientFailure()).isFalse();
        result.suspension().get().close();
    }
}
