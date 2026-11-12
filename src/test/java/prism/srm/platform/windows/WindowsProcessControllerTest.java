package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.platform.Suspension;

class WindowsProcessControllerTest {

    private static final Instant START = Instant.parse("2026-10-08T09:15:30Z");
    private static final int PID = 6_000;
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, START);

    private final FakeWin32 win32 = new FakeWin32();
    private final List<Long> destroyed = new ArrayList<>();
    private Optional<Boolean> destroyAnswer = Optional.of(true);
    private final WindowsProcessController controller = new WindowsProcessController(win32, pid -> {
        destroyed.add(pid);
        return destroyAnswer;
    });

    WindowsProcessControllerTest() {
        win32.process(PID, START);
    }

    /** The table of PRODUCT_SPEC section 6, Windows column (CAP-3, CAP-5). */
    @Test
    void theCapabilitiesAreThoseOfTheSpecificationWithTheirMechanisms() {
        assertThat(controller.capabilities())
                .containsEntry(
                        ControlAction.END_TASK,
                        Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST))
                .containsEntry(
                        ControlAction.FORCE_KILL,
                        Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.PROCESS_HANDLE_TERMINATE))
                .containsEntry(
                        ControlAction.SUSPEND,
                        Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION))
                .containsEntry(
                        ControlAction.RESUME,
                        Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION))
                .containsEntry(
                        ControlAction.CHANGE_PRIORITY,
                        Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PRIORITY_CLASS))
                .hasSize(5);
    }

    @Test
    void theIdleAndTheKernelProcessAreSystemProcessesByTheirPidWithoutBeingOpened() {
        assertThat(controller.protectionFacts(new ProcessIdentity(0, START)).systemProcess())
                .isTrue();
        assertThat(controller.protectionFacts(new ProcessIdentity(4, START)).systemProcess())
                .isTrue();
        assertThat(win32.calls).isEmpty();
    }

    @Test
    void criticalityAndTheExecutableNameAreReadFromTheProcess() {
        win32.processes.get(PID).critical = Optional.of(true);
        win32.processes.get(PID).path = Optional.of("C:\\Windows\\System32\\CSRSS.EXE");

        ProtectionFacts facts = controller.protectionFacts(TARGET);

        assertThat(facts).isEqualTo(new ProtectionFacts(false, Optional.of(true), true));
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void anOrdinaryProcessHasNoProtectingFacts() {
        assertThat(controller.protectionFacts(TARGET)).isEqualTo(new ProtectionFacts(false, Optional.of(false), false));
    }

    @Test
    void factsThatCannotBeReadAreAbsent() {
        win32.processes.get(PID).openError = 5;

        assertThat(controller.protectionFacts(TARGET)).isEqualTo(ProtectionFacts.NONE);
    }

    @Test
    void forceKillAsksTheJdkOnlyOnceTheProcessIsOpenWithTheRightToTerminate() {
        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(win32.calls.get(0)).isEqualTo("OpenProcess 6000 0x1001");
        assertThat(destroyed).containsExactly(6_000L);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aRefusedTerminationIsAccessDeniedAndTheJdkIsNotAsked() {
        win32.processes.get(PID).openError = 5;

        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(destroyed).isEmpty();
    }

    @Test
    void aFalseFromTheJdkForAProcessThatExitedIsNotFound() {
        WindowsProcessController exiting = new WindowsProcessController(win32, pid -> {
            win32.processes.get(PID).exitCode = 1;
            return Optional.of(false);
        });

        assertThat(exiting.terminate(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void aFalseFromTheJdkForAProcessThatRunsOnIsANativeError() {
        destroyAnswer = Optional.of(false);

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(win32.openHandles()).isEmpty();
    }

    @Test
    void aStaleIdentityIsNeverTerminated() {
        assertThat(controller
                        .terminate(new ProcessIdentity(PID, START.plusSeconds(1)))
                        .result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(destroyed).isEmpty();
    }

    @Test
    void aSuspensionThisControllerDidNotMakeIsNotResumed() {
        Suspension foreign = new Suspension() {
            @Override
            public ProcessIdentity identity() {
                return TARGET;
            }

            @Override
            public int threads() {
                return 1;
            }

            @Override
            public int outstanding() {
                return 1;
            }

            @Override
            public void close() {}
        };

        assertThat(controller.resume(foreign).result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(win32.calls).isEmpty();
    }

    @Test
    void theFileNameOfAWindowsPathIsItsLastPartInLowerCaseWithoutALocale() {
        assertThat(WindowsProcessController.fileName("C:\\Windows\\System32\\WinLogon.EXE"))
                .isEqualTo("winlogon.exe");
        assertThat(WindowsProcessController.fileName("lsass.exe")).isEqualTo("lsass.exe");
        assertThat(WindowsProcessController.fileName("C:/x/SMSS.EXE")).isEqualTo("smss.exe");
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            // In Turkish "I" lowers to a dotless i: a name compared that way would never match.
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(WindowsProcessController.fileName("C:\\Windows\\System32\\WININIT.EXE"))
                    .isEqualTo("wininit.exe");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    /** The common case for a process of another user: it may be queried, but not terminated. */
    @Test
    void aProcessThatGrantsQueriesButNotTerminationIsAccessDeniedAndTheJdkIsNotAsked() {
        win32.processes.get(PID).deniedRights = com.sun.jna.platform.win32.WinNT.PROCESS_TERMINATE;

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(destroyed).isEmpty();
        assertThat(win32.openHandles()).isEmpty();
    }

    /** The JDK finds no live process although the pinned handle says it runs: nothing is claimed. */
    @Test
    void noProcessFromTheJdkForAProcessThatRunsOnIsANativeError() {
        destroyAnswer = Optional.empty();

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(destroyed).containsExactly((long) PID);
        assertThat(win32.openHandles()).isEmpty();
    }

    /** CTL-6: the base priority of a process of normal thread priority in each class ("Scheduling Priorities"). */
    @Test
    void theListedPrioritiesAreTheBasePrioritiesOfTheFiveClasses() {
        assertThat(controller.listedPriorities())
                .isEqualTo(java.util.Map.of(
                        4, prism.srm.domain.PriorityLevel.LOW,
                        6, prism.srm.domain.PriorityLevel.BELOW_NORMAL,
                        8, prism.srm.domain.PriorityLevel.NORMAL,
                        10, prism.srm.domain.PriorityLevel.ABOVE_NORMAL,
                        13, prism.srm.domain.PriorityLevel.HIGH));
    }
}
