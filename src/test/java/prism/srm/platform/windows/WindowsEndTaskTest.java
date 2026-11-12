package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/** End Task posts a close request to the application's own windows and claims nothing more. */
class WindowsEndTaskTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30Z");
    private static final int PID = 2_222;
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, START);

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsEndTask endTask = new WindowsEndTask(win32, new WindowsProcessAccess(win32));

    WindowsEndTaskTest() {
        win32.process(PID, START);
    }

    private void window(long hwnd, int pid, boolean visible, boolean owned, boolean tool) {
        win32.windows.add(new Win32.Window(hwnd, pid, visible, owned, tool));
    }

    @Test
    void aProcessWithoutWindowsCannotBeAskedToClose() {
        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(win32.posted).isEmpty();
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void hiddenOwnedAndForeignWindowsAreNotAsked() {
        window(1, PID, false, false, false);
        window(2, PID, true, true, false);
        window(3, 999, true, false, false);
        window(4, 0, true, false, false);

        assertThat(endTask.requestClose(TARGET).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(win32.posted).isEmpty();
    }

    @Test
    void theVisibleUnownedWindowIsAskedAndThatIsSuccessNotAnExit() {
        window(10, PID, true, false, false);
        window(11, PID, true, true, false);

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(outcome.detail().get()).startsWith("close requested");
        assertThat(win32.posted).containsExactly(10L);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void everyMainWindowIsAskedAndAToolWindowOnlyWhenThereIsNoOther() {
        window(20, PID, true, false, false);
        window(21, PID, true, false, true);
        window(22, PID, true, false, false);

        endTask.requestClose(TARGET);

        assertThat(win32.posted).containsExactly(20L, 22L);
    }

    @Test
    void aProcessWithOnlyAToolWindowIsAskedThroughIt() {
        window(30, PID, true, false, true);

        assertThat(endTask.requestClose(TARGET).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(win32.posted).containsExactly(30L);
    }

    @Test
    void oneWindowAskedIsEnoughWhenAnotherWentAwayMeanwhile() {
        window(40, PID, true, false, false);
        window(41, PID, true, false, false);
        win32.postErrors.put(40L, WindowsEndTask.ERROR_INVALID_WINDOW_HANDLE);

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(outcome.detail().get()).contains("1 of 2", "1 gone meanwhile");
    }

    /** UIPI refuses a post to a process at a higher integrity level with ERROR_ACCESS_DENIED. */
    @Test
    void postsTheSystemRefusesAreAccessDenied() {
        window(50, PID, true, false, false);
        win32.postErrors.put(50L, 5);

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void anotherPostFailureIsANativeErrorWithItsCode() {
        window(60, PID, true, false, false);
        window(61, PID, true, false, false);
        win32.postErrors.put(60L, 1816);
        win32.postErrors.put(61L, 5);

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(1816));
    }

    @Test
    void windowsThatAllWentAwayWithTheProcessMeanThatItIsGone() {
        window(70, PID, true, false, false);
        win32.postErrors.put(70L, WindowsEndTask.ERROR_INVALID_WINDOW_HANDLE);
        // The process passed the identity check and exits while its windows are enumerated.
        win32.onEnumerateWindows = () -> win32.processes.get(PID).exitCode = 0;

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void aStaleIdentityIsNotFoundAndNoWindowIsAsked() {
        window(80, PID, true, false, false);

        ControlOutcome outcome = endTask.requestClose(new ProcessIdentity(PID, START.plusSeconds(1)));

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(win32.calls).doesNotContain("EnumWindows");
        assertThat(win32.posted).isEmpty();
    }

    /** Its windows went away but the process runs on: no claim that it is gone. */
    @Test
    void windowsThatAllWentAwayFromARunningProcessLeaveNothingToAsk() {
        window(90, PID, true, false, false);
        win32.postErrors.put(90L, WindowsEndTask.ERROR_INVALID_WINDOW_HANDLE);

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aFailedEnumerationIsANativeErrorWithItsCodeAndNotANoWindowAnswer() {
        window(95, PID, true, false, false);
        win32.enumerateError = 1450;

        ControlOutcome outcome = endTask.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(1450));
        assertThat(win32.posted).isEmpty();
        assertThat(win32.openHandles()).isEmpty();
    }
}
