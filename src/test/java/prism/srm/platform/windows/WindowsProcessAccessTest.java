package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/** The one way to a process: PID and start time, or nothing. */
class WindowsProcessAccessTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30.123Z");
    private static final ProcessIdentity DEMO = new ProcessIdentity(4_100, START);

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsProcessAccess access = new WindowsProcessAccess(win32);

    @Test
    void theProcessOfTheIdentityIsOpenedWithTheRightsAskedForAndHeldUntilClosed() {
        win32.process(4_100, START);

        WindowsProcessAccess.Opening opening = access.open(DEMO, 0x0200);

        assertThat(opening.failure()).isEmpty();
        assertThat(win32.calls).containsExactly("OpenProcess 4100 0x1200");
        assertThat(win32.openHandles()).containsExactly("process 4100");
        opening.process().get().close();
        opening.process().get().close();
        assertThat(win32.openHandles()).isEmpty();
        assertThat(win32.doubleCloses()).isEmpty();
    }

    /** The creation time is compared in the milliseconds of the listing, truncated as it truncates. */
    @Test
    void aCreationTimeWithinTheSameMillisecondIsTheSameProcess() {
        win32.process(4_100, START).creation =
                FakeWin32.filetime(START) + 8_000; // 1234 + 8000 hundred-nanosecond units: still 0.9 ms

        assertThat(access.liveness(DEMO)).isEqualTo(ControlOutcome.success());
        assertThat(win32.openHandles()).isEmpty();
    }

    /** PROC-3: a PID that another process took over is not the identity. */
    @Test
    void aPidThatBelongsToAProcessStartedAtAnotherTimeIsNotFound() {
        win32.process(4_100, START.plusMillis(1));

        WindowsProcessAccess.Opening opening = access.open(DEMO, 0);

        assertThat(opening.process()).isEmpty();
        assertThat(opening.failure().get().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(win32.openHandles())
                .as("the handle of the other process is closed again")
                .isEmpty();
    }

    @Test
    void aProcessThatHasExitedButIsStillOpenSomewhereIsNotFound() {
        win32.process(4_100, START).exitCode = 0;

        assertThat(access.liveness(DEMO).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void accessDeniedIsAccessDeniedWithItsCode() {
        win32.process(4_100, START).openError = 5;

        ControlOutcome outcome = access.liveness(DEMO);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(5));
        assertThat(win32.calls).doesNotContain("Toolhelp processes");
    }

    /** Another error says nothing by itself: the process list decides whether the PID exists. */
    @Test
    void anotherErrorForAPidTheSystemDoesNotListIsNotFound() {
        ControlOutcome outcome = access.liveness(DEMO);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(87));
        assertThat(win32.calls).containsExactly("OpenProcess 4100 0x1000", "Toolhelp processes");
    }

    @Test
    void anUnknownErrorForAPidTheSystemListsIsANativeErrorWithItsCodeKept() {
        win32.process(4_100, START).openError = 1450;

        ControlOutcome outcome = access.liveness(DEMO);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(1450));
        assertThat(outcome.detail().get()).contains("error 1450", "the PID is listed");
    }

    @Test
    void anErrorWhenTheProcessListCannotBeReadIsANativeError() {
        win32.listedFromProcesses = false;
        win32.listed = Optional.empty();

        ControlOutcome outcome = access.liveness(DEMO);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(87));
    }

    @Test
    void anErrorForAPidTheListDoesNotHoldIsNotFoundEvenWhenTheCodeIsUnknown() {
        win32.listedFromProcesses = false;
        win32.listed = Optional.of(Map.of(77, true));

        assertThat(access.liveness(DEMO).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void anExitCodeThatCannotBeReadIsANativeErrorAndTheHandleIsClosed() {
        win32.process(4_100, START).exitCodeFails = true;

        assertThat(access.liveness(DEMO).result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aPidLargerThanWindowsHasIsNotFoundWithoutAskingTheSystem() {
        ProcessIdentity tooLarge = new ProcessIdentity(0x1_0000_0000L, START);

        assertThat(access.liveness(tooLarge).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(win32.calls).isEmpty();
    }
}
