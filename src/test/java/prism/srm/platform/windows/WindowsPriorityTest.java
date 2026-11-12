package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;

/**
 * CTL-6 on Windows: five priority classes, never Realtime, and only what the system holds counts.
 *
 * <p>Windows only. The native calls are faked, but the mapping of the levels reads the class
 * constants of jna-platform's {@code Kernel32}, and reading them loads {@code kernel32}, which
 * exists on Windows alone (the first GitHub run: UnsatisfiedLinkError on Ubuntu and macOS). The
 * production code is selected on Windows only, so this coverage belongs to the Windows job, where it
 * runs whole.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsPriorityTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30Z");
    private static final int PID = 3_333;
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, START);
    /** https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setpriorityclass */
    private static final int REALTIME_PRIORITY_CLASS = 0x100;

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsPriority priority = new WindowsPriority(win32, new WindowsProcessAccess(win32));

    WindowsPriorityTest() {
        win32.process(PID, START);
    }

    @Test
    void theFiveLevelsAreTheFiveDocumentedClasses() {
        assertThat(WindowsPriority.priorityClass(PriorityLevel.LOW)).isEqualTo(0x40);
        assertThat(WindowsPriority.priorityClass(PriorityLevel.BELOW_NORMAL)).isEqualTo(0x4000);
        assertThat(WindowsPriority.priorityClass(PriorityLevel.NORMAL)).isEqualTo(0x20);
        assertThat(WindowsPriority.priorityClass(PriorityLevel.ABOVE_NORMAL)).isEqualTo(0x8000);
        assertThat(WindowsPriority.priorityClass(PriorityLevel.HIGH)).isEqualTo(0x80);
    }

    @ParameterizedTest
    @EnumSource(PriorityLevel.class)
    void noLevelIsRealtime(PriorityLevel level) {
        assertThat(WindowsPriority.priorityClass(level)).isNotEqualTo(REALTIME_PRIORITY_CLASS);
    }

    @ParameterizedTest
    @EnumSource(PriorityLevel.class)
    void aLevelIsSetWithTheRightToSetInformationAndReadBack(PriorityLevel level) {
        ControlOutcome outcome = priority.changePriority(TARGET, level);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(win32.processes.get(PID).priorityClass).isEqualTo(WindowsPriority.priorityClass(level));
        assertThat(win32.calls.get(0)).isEqualTo("OpenProcess 3333 0x1200");
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aRefusedChangeIsAccessDenied() {
        win32.processes.get(PID).setPriorityError = 5;

        ControlOutcome outcome = priority.changePriority(TARGET, PriorityLevel.HIGH);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).isEqualTo(OptionalInt.of(5));
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void anotherFailureIsANativeError() {
        win32.processes.get(PID).setPriorityError = 87;

        assertThat(priority.changePriority(TARGET, PriorityLevel.HIGH).result())
                .isEqualTo(OperationResult.NATIVE_ERROR);
    }

    /** No optimistic success: a class the system does not hold is not reported as set. */
    @Test
    void aClassTheSystemDoesNotHoldAfterwardsIsANativeErrorNotASuccess() {
        win32.processes.get(PID).heldClass = 0x20;

        ControlOutcome outcome = priority.changePriority(TARGET, PriorityLevel.HIGH);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.detail().get()).contains("0x20", "0x80");
    }

    @Test
    void aClassThatCannotBeReadBackIsANativeError() {
        win32.processes.get(PID).heldClass = 0;

        assertThat(priority.changePriority(TARGET, PriorityLevel.LOW).result()).isEqualTo(OperationResult.NATIVE_ERROR);
    }

    @Test
    void aStaleIdentityChangesNothing() {
        ControlOutcome outcome =
                priority.changePriority(new ProcessIdentity(PID, START.minusSeconds(5)), PriorityLevel.LOW);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(win32.calls).noneMatch(call -> call.startsWith("SetPriorityClass"));
        assertThat(win32.processes.get(PID).priorityClass).isEqualTo(0x20);
    }
}
