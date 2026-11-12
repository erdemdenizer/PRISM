package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.SuspendResult;

/**
 * Taking over, after a crash, a suspension an earlier session recorded (ADR-0011), against a fake
 * system. A suspend count is anonymous: Prism takes over only threads it recorded, each found at
 * exactly one, all of them or none, and never lowers a count it cannot attribute to itself.
 */
class WindowsAdoptTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30Z");
    private static final int PID = 5_000;
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, START);

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsSuspensions suspensions = new WindowsSuspensions(win32, new WindowsProcessAccess(win32));

    WindowsAdoptTest() {
        win32.process(PID, START);
        win32.thread(11, PID).creation = 133_700_000_000_000_011L;
        win32.thread(12, PID).creation = 133_700_000_000_000_012L;
    }

    /** What an earlier session left: its threads suspended once each, its handles gone, and its memento. */
    private String crashedSession() {
        SuspendResult result = suspensions.suspend(TARGET);
        WindowsSuspension suspension = (WindowsSuspension) result.suspension().orElseThrow();
        String memento = suspension.memento().orElseThrow();
        suspension.close();
        win32.calls.clear();
        return memento;
    }

    private int count(int tid) {
        return win32.threads.get(tid).suspendCount;
    }

    @Test
    void withoutAMementoNothingIsAskedOfTheSystemAndNothingIsOffered() {
        win32.threads.get(11).suspendCount = 1;
        win32.threads.get(12).suspendCount = 1;

        AdoptResult result = suspensions.adopt(TARGET, Optional.empty());

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(win32.calls).as("not even the process is opened").isEmpty();
        assertThat(List.of(count(11), count(12))).containsOnly(1);
    }

    @Test
    void anUnreadableMementoIsRefusedWithoutAnyCall() {
        AdoptResult result = suspensions.adopt(TARGET, Optional.of("x9:garbage"));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(win32.calls).isEmpty();
    }

    @Test
    void theMementoNamesEachHeldThreadByIdAndCreationTime() {
        SuspendResult result = suspensions.suspend(TARGET);

        assertThat(result.suspension().orElseThrow().memento())
                .hasValue("w1:11@133700000000000011,12@133700000000000012");
        result.suspension().get().close();
    }

    @Test
    void everyRecordedThreadAtOneIsTakenOverAndResumedOnceWithTheProbesUndone() {
        String memento = crashedSession();

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.ADOPTED);
        assertThat(List.of(count(11), count(12))).as("the probes are undone").containsOnly(1);
        WindowsSuspension adopted = (WindowsSuspension) result.suspension().orElseThrow();
        assertThat(adopted.outstanding()).isEqualTo(2);

        assertThat(suspensions.resume(adopted).succeeded()).isTrue();
        assertThat(List.of(count(11), count(12))).containsOnly(0);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aThreadWithAnotherSuspensionAsWellRefusesTheWholeProcessAndChangesNoCount() {
        String memento = crashedSession();
        win32.threads.get(12).suspendCount = 2;

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(result.outcome().detail().orElseThrow()).contains("another suspension");
        assertThat(count(11)).isEqualTo(1);
        assertThat(count(12)).isEqualTo(2);
        assertThat(result.suspension()).isEmpty();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aThreadThatCannotBeOpenedButIsStillListedRefusesTheWholeProcess() {
        String memento = crashedSession();
        win32.threads.get(12).openError = WindowsSuspensions.ERROR_INVALID_PARAMETER;

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(result.outcome().detail().orElseThrow()).contains("is listed but cannot be opened");
        assertThat(List.of(count(11), count(12)))
                .as("no count lowered, the probe undone")
                .containsOnly(1);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void anExceptionWhileProbingUndoesTheProbesAndIsNotSwallowed() {
        String memento = crashedSession();
        java.util.concurrent.atomic.AtomicInteger probed = new java.util.concurrent.atomic.AtomicInteger();
        win32.afterSuspend = () -> {
            if (probed.incrementAndGet() == 2) {
                throw new IllegalStateException("broken");
            }
        };

        assertThatThrownBy(() -> suspensions.adopt(TARGET, Optional.of(memento)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(11)).as("the probe made before the failure is undone").isEqualTo(1);
    }

    @Test
    void threadsAllAtZeroMeanTheProcessIsNotSuspendedAnyMore() {
        String memento = crashedSession();
        win32.threads.get(11).suspendCount = 0;
        win32.threads.get(12).suspendCount = 0;

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.NOT_SUSPENDED);
        assertThat(List.of(count(11), count(12))).containsOnly(0);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void threadsPartlyResumedByAnotherProgramAreNotTouched() {
        String memento = crashedSession();
        win32.threads.get(12).suspendCount = 0;

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(count(11)).as("the one left at one is not taken").isEqualTo(1);
        assertThat(count(12)).isZero();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void anEndedThreadAndAThreadWhoseIdWasReusedArePassedOver() {
        win32.thread(13, PID).creation = 133_700_000_000_000_013L;
        String memento = crashedSession();
        // Thread 12 ended: it cannot be opened and the system no longer lists it.
        win32.threads.get(12).openError = WindowsSuspensions.ERROR_INVALID_PARAMETER;
        win32.processes.get(PID).threads.remove(Integer.valueOf(12));
        // Thread 13 ended and its ID went to a newer thread of the same process.
        win32.threads.get(13).creation = 133_700_000_000_999_999L;
        win32.threads.get(13).suspendCount = 0;

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.ADOPTED);
        assertThat(result.suspension().orElseThrow().outstanding()).isEqualTo(1);
        assertThat(win32.threads.get(13).suspends)
                .as("the newer thread is never probed")
                .isEqualTo(1);
        result.suspension().get().close();
    }

    @Test
    void aProcessUnderADebuggerIsNeverTouched() {
        String memento = crashedSession();
        win32.processes.get(PID).debugger = Optional.of(true);

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("OpenThread"));
        assertThat(List.of(count(11), count(12))).containsOnly(1);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aDebuggerStateThatCannotBeReadIsTakenAsCannotTell() {
        String memento = crashedSession();
        win32.processes.get(PID).debugger = Optional.empty();

        assertThat(suspensions.adopt(TARGET, Optional.of(memento)).verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("SuspendThread"));
    }

    @Test
    void aThreadThatChangesWhileItIsCheckedRefusesAndUndoesOnlyTheProbes() {
        String memento = crashedSession();
        // Between the probe and its undoing another program resumes thread 12 once.
        win32.afterSuspend = () -> {
            if (count(12) == 2 && win32.threads.get(12).suspends == 2) {
                win32.threads.get(12).suspendCount = 1;
            }
        };

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(count(11)).as("Prism's recorded increment is left as it was").isEqualTo(1);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aProbeThatCannotBeUndoneStaysHeldSoThatItIsNotForgotten() {
        String memento = crashedSession();
        win32.threads.get(12).suspendCount = 2;
        win32.threads.get(11).resumeError = 6;

        AdoptResult result = suspensions.adopt(TARGET, Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.REFUSED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.suspension()).isPresent();
        assertThat(result.suspension().get().outstanding()).isEqualTo(1);
        win32.threads.get(11).resumeError = 0;
        suspensions.resume((WindowsSuspension) result.suspension().get());
        assertThat(count(11)).isEqualTo(1);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aProcessThatHasGoneIsReportedGone() {
        String memento = crashedSession();
        win32.processes.get(PID).exitCode = 0;

        assertThat(suspensions.adopt(TARGET, Optional.of(memento)).verdict()).isEqualTo(AdoptResult.Verdict.GONE);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aPidThatBelongsToAnotherProcessNowIsGone() {
        String memento = crashedSession();

        AdoptResult result = suspensions.adopt(new ProcessIdentity(PID, START.plusSeconds(1)), Optional.of(memento));

        assertThat(result.verdict()).isEqualTo(AdoptResult.Verdict.GONE);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("OpenThread"));
    }

    @Test
    void theMementoShrinksWithWhatIsStillHeld() {
        SuspendResult result = suspensions.suspend(TARGET);
        WindowsSuspension suspension = (WindowsSuspension) result.suspension().orElseThrow();
        win32.threads.get(12).resumeError = 6;

        suspensions.resume(suspension);

        assertThat(suspension.memento()).hasValue("w1:12@133700000000000012");
        win32.threads.get(12).resumeError = 0;
        suspensions.resume(suspension);
    }

    @Test
    void aThreadWhoseCreationTimeCannotBeReadGivesNoMementoAtAll() {
        SuspendResult result = suspensions.suspend(TARGET);
        win32.threads.get(12).creation = -1;

        assertThat(result.suspension().orElseThrow().memento()).isEmpty();
        result.suspension().get().close();
    }
}
