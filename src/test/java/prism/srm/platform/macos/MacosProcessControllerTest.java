package prism.srm.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/** {@link MacosProcessController} against {@link FakeMacos}: the check before every call, and the results. */
class MacosProcessControllerTest {

    private static final int PID = 7_000;
    private static final Instant START = Instant.parse("2026-10-08T09:15:30.123Z");
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, START);

    private final FakeMacos macos = new FakeMacos();
    private final MacosProcessController controller = new MacosProcessController(macos);

    MacosProcessControllerTest() {
        macos.process(PID, START.toEpochMilli(), "demo");
    }

    /** PRODUCT_SPEC section 6, macOS column (CAP-3, CAP-5). */
    @Test
    void theCapabilitiesAreThoseOfTheSpecificationWithTheirMechanisms() {
        assertThat(controller.capabilities())
                .isEqualTo(Map.of(
                        ControlAction.END_TASK,
                        Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.TERMINATION_SIGNAL),
                        ControlAction.FORCE_KILL,
                        Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.KILL_SIGNAL),
                        ControlAction.SUSPEND,
                        Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL),
                        ControlAction.RESUME,
                        Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL),
                        ControlAction.CHANGE_PRIORITY,
                        Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.NICE_VALUE)));
        assertThat(controller.limitations()).isEmpty();
    }

    /** The listing shows a Mach priority on macOS, not the nice value: no level is named for its numbers. */
    @Test
    void noListedPriorityStandsForALevel() {
        assertThat(controller.listedPriorities()).isEmpty();
    }

    /** signal(3) and bsd/sys/proc.h. */
    @Test
    void theNumbersAreThoseOfTheHeaders() {
        assertThat(new int[] {
                    MacosProcessController.SIGKILL,
                    MacosProcessController.SIGTERM,
                    MacosProcessController.SIGSTOP,
                    MacosProcessController.SIGCONT
                })
                .containsExactly(9, 15, 17, 19);
        assertThat(MacosProcessController.SSTOP).isEqualTo(4);
        assertThat(MacosProcessController.SZOMB).isEqualTo(5);
        assertThat(MacosProcessController.P_SYSTEM).isEqualTo(0x200);
        assertThat(MacosProcessController.NICE)
                .isEqualTo(Map.of(
                        PriorityLevel.LOW, 19,
                        PriorityLevel.BELOW_NORMAL, 10,
                        PriorityLevel.NORMAL, 0,
                        PriorityLevel.ABOVE_NORMAL, -5,
                        PriorityLevel.HIGH, -10));
    }

    @Test
    void endTaskChecksTheIdentityThenSendsTheTerminationSignal() {
        assertThat(controller.requestClose(TARGET).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(macos.calls).containsExactly("sysctl " + PID, "kill " + PID + " 15");
    }

    @Test
    void forceKillSendsTheKillSignal() {
        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(macos.calls).containsExactly("sysctl " + PID, "kill " + PID + " 9");
    }

    @Test
    void aPidThatBelongsToAnotherProcessIsNeverSignalled() {
        ProcessIdentity stale = new ProcessIdentity(PID, START.minusMillis(1));

        for (ControlOutcome outcome : new ControlOutcome[] {
            controller.requestClose(stale),
            controller.terminate(stale),
            controller.suspend(stale).outcome(),
            controller.changePriority(stale, PriorityLevel.LOW)
        }) {
            assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        }
        assertThat(macos.calls).allMatch(call -> call.startsWith("sysctl"));
    }

    /** xnu's kill succeeds on a zombie; Prism answers it as gone before it is asked. */
    @Test
    void aZombieIsAnsweredAsGoneWithoutASignal() {
        macos.processes.get(PID).state = MacosProcessController.SZOMB;

        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill"));
    }

    @Test
    void aProcessThatIsNotThereIsNotFound() {
        macos.processes.remove(PID);

        assertThat(controller.requestClose(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    /** The structure guard: an entry that is not of the PID asked for is a native error, and nothing is sent. */
    @Test
    void anEntryOfAnotherPidIsANativeError() {
        macos.reportedPid = PID + 1;

        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill"));
    }

    @Test
    void aFailedSysctlIsReportedWithItsCode() {
        macos.sysctlErrno = MacosErrors.ENOMEM;

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.nativeCode()).hasValue(MacosErrors.ENOMEM);
    }

    @ParameterizedTest
    @ValueSource(ints = {MacosErrors.EPERM, MacosErrors.EACCES})
    void aRefusedSignalIsAccessDenied(int errno) {
        macos.killErrno.put(PID, errno);

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).hasValue(errno);
    }

    @Test
    void suspendStopsAndResumeChecksAgainThenContinues() {
        SuspendResult suspended = controller.suspend(TARGET);

        assertThat(suspended.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        Suspension stop = suspended.suspension().orElseThrow();
        assertThat(stop.outstanding()).isEqualTo(1);
        assertThat(stop.threads()).isEqualTo(1);
        assertThat(macos.processes.get(PID).state).isEqualTo(MacosProcessController.SSTOP);

        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(stop.outstanding()).isZero();
        assertThat(macos.calls)
                .containsExactly("sysctl " + PID, "kill " + PID + " 17", "sysctl " + PID, "kill " + PID + " 19");
    }

    @Test
    void aProcessStoppedAlreadyIsNotSuspended() {
        macos.processes.get(PID).state = MacosProcessController.SSTOP;

        SuspendResult result = controller.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(result.stoppedElsewhere()).isTrue();
        assertThat(result.suspension()).isEmpty();
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill"));
    }

    // ----- After a crash: the stop state and taking over (ADR-0011) -----

    @Test
    void theStopStateComesFromTheEntryAndATracedProcessIsTold() {
        assertThat(controller.stopState(TARGET)).isEqualTo(prism.srm.platform.StopState.RUNNING);
        macos.processes.get(PID).state = MacosProcessController.SSTOP;
        assertThat(controller.stopState(TARGET)).isEqualTo(prism.srm.platform.StopState.STOPPED);
        macos.processes.get(PID).flags = MacosProcessController.P_TRACED;
        assertThat(controller.stopState(TARGET)).isEqualTo(prism.srm.platform.StopState.TRACED);
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill"));
    }

    @Test
    void aStopIsTakenOverAndContinuedAfterTheIdentityIsCheckedAgain() {
        macos.processes.get(PID).state = MacosProcessController.SSTOP;

        prism.srm.platform.AdoptResult result = controller.adopt(TARGET, Optional.empty());

        assertThat(result.verdict()).isEqualTo(prism.srm.platform.AdoptResult.Verdict.ADOPTED);
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill"));
        assertThat(controller.resume(result.suspension().orElseThrow()).succeeded())
                .isTrue();
        assertThat(macos.calls).anyMatch(call -> call.startsWith("kill") && call.endsWith(" 19"));
    }

    @Test
    void aTracedOrRunningProcessIsNotTakenOver() {
        macos.processes.get(PID).state = MacosProcessController.SSTOP;
        macos.processes.get(PID).flags = MacosProcessController.P_TRACED;
        assertThat(controller.adopt(TARGET, Optional.empty()).verdict())
                .isEqualTo(prism.srm.platform.AdoptResult.Verdict.REFUSED);

        macos.processes.get(PID).flags = 0;
        macos.processes.get(PID).state = 2;
        assertThat(controller.adopt(TARGET, Optional.empty()).verdict())
                .isEqualTo(prism.srm.platform.AdoptResult.Verdict.NOT_SUSPENDED);
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill"));
    }

    @Test
    void aPidOfAnotherProcessIsGone() {
        macos.processes.get(PID).state = MacosProcessController.SSTOP;

        assertThat(controller
                        .adopt(new ProcessIdentity(PID, START.plusSeconds(1)), Optional.empty())
                        .verdict())
                .isEqualTo(prism.srm.platform.AdoptResult.Verdict.GONE);
    }

    /** The PID now names another process: the continue is not sent, and Prism lets go of the stop. */
    @Test
    void resumeAfterThePidPassedToAnotherProcessSendsNothing() {
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        macos.process(PID, START.toEpochMilli() + 5_000, "other").state = MacosProcessController.SSTOP;
        macos.calls.clear();

        ControlOutcome outcome = controller.resume(stop);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(stop.outstanding()).isZero();
        assertThat(macos.calls).containsExactly("sysctl " + PID);
        assertThat(macos.processes.get(PID).state).isEqualTo(MacosProcessController.SSTOP);
    }

    @Test
    void aRefusedContinueKeepsTheStopForAnotherTry() {
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        macos.killErrno.put(PID, MacosErrors.EPERM);

        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(stop.outstanding()).isEqualTo(1);

        macos.killErrno.clear();
        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(stop.outstanding()).isZero();
    }

    @Test
    void aContinueThatFindsNoProcessLetsGoOfTheStop() {
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        macos.killErrno.put(PID, MacosErrors.ESRCH);

        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(stop.outstanding()).isZero();
    }

    @Test
    void aSuspensionOfAnotherControllerIsRejected() {
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

        assertThatThrownBy(() -> controller.resume(foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(PriorityLevel.class)
    void aLevelIsSetAndReadBack(PriorityLevel level) {
        ControlOutcome outcome = controller.changePriority(TARGET, level);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(macos.processes.get(PID).nice).isEqualTo(MacosProcessController.NICE.get(level));
        assertThat(macos.calls)
                .containsExactly(
                        "sysctl " + PID,
                        "setpriority " + PID + " " + MacosProcessController.NICE.get(level),
                        "getpriority " + PID);
    }

    /** CTL-6: raising without privilege is EACCES; another user's process is EPERM. */
    @ParameterizedTest
    @ValueSource(ints = {MacosErrors.EACCES, MacosErrors.EPERM})
    void aRefusedChangeIsAccessDenied(int errno) {
        macos.setPriorityErrno.put(PID, errno);

        ControlOutcome outcome = controller.changePriority(TARGET, PriorityLevel.HIGH);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).hasValue(errno);
    }

    @Test
    void aValueTheSystemDoesNotHoldIsANativeError() {
        macos.stubborn.add(PID);

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.NATIVE_ERROR);
    }

    /** getpriority may return -1 as a value; only its errno says it failed. */
    @Test
    void aReadBackThatFailsIsMappedByItsErrno() {
        macos.getPriorityErrno = MacosErrors.ESRCH;

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void kernelTaskAndLaunchdAreSystemProcesses(int pid) {
        assertThat(controller.protectionFacts(new ProcessIdentity(pid, START)).systemProcess())
                .isTrue();
    }

    @Test
    void theSystemFlagMakesASystemProcess() {
        macos.processes.get(PID).flags = MacosProcessController.P_SYSTEM | 0x4;

        assertThat(controller.protectionFacts(TARGET).systemProcess()).isTrue();
    }

    @Test
    void aListedNameIsListedAndAnOrdinaryProcessIsNot() {
        assertThat(controller.protectionFacts(TARGET)).isEqualTo(new ProtectionFacts(false, Optional.empty(), false));
        macos.processes.get(PID).executable = "WindowServer";

        assertThat(controller.protectionFacts(TARGET)).isEqualTo(new ProtectionFacts(false, Optional.empty(), true));
    }

    @Test
    void theFactsOfAnotherProcessUnderThePidAreNotGiven() {
        macos.processes.get(PID).flags = MacosProcessController.P_SYSTEM;

        assertThat(controller.protectionFacts(new ProcessIdentity(PID, START.plusMillis(1))))
                .isEqualTo(ProtectionFacts.NONE);
    }

    @Test
    void livenessTellsAliveGoneAndReplacedApart() {
        assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(controller
                        .liveness(new ProcessIdentity(PID, START.plusMillis(1)))
                        .result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        macos.processes.get(PID).state = MacosProcessController.SZOMB;
        assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        macos.processes.remove(PID);
        assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void theErrnoTableIsThatOfTheHeaders() {
        assertThat(new int[] {
                    MacosErrors.EPERM, MacosErrors.ESRCH, MacosErrors.ENOMEM, MacosErrors.EACCES, MacosErrors.EINVAL
                })
                .containsExactly(1, 3, 12, 13, 22);
        assertThat(MacosErrors.result(MacosErrors.ESRCH)).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(MacosErrors.result(MacosErrors.EPERM)).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(MacosErrors.result(MacosErrors.EACCES)).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(MacosErrors.result(MacosErrors.EINVAL)).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(MacosErrors.result(MacosErrors.ENOMEM)).isEqualTo(OperationResult.NATIVE_ERROR);
    }

    /** A read that failed is no exit: Prism keeps the stop it holds, and the coordinator keeps it too. */
    @Test
    void aFailedReadIsNeverTakenForAnExit() {
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        for (Runnable failure :
                new Runnable[] {() -> macos.shortAnswer = true, () -> macos.sysctlErrno = MacosErrors.ESRCH}) {
            macos.shortAnswer = false;
            macos.sysctlErrno = 0;
            failure.run();

            assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.NATIVE_ERROR);
            assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.NATIVE_ERROR);
            assertThat(stop.outstanding()).isEqualTo(1);
        }
        macos.shortAnswer = false;
        macos.sysctlErrno = 0;
        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
    }

    /** kill(0) would signal Prism's own process group: PID 0 and below are never passed on. */
    @Test
    void pidZeroIsNeverActedOn() {
        ProcessIdentity zero = new ProcessIdentity(0, START);
        macos.process(0, START.toEpochMilli(), "kernel_task");

        assertThat(controller.terminate(zero).result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.changePriority(zero, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(macos.calls).noneMatch(call -> call.startsWith("kill") || call.startsWith("setpriority"));
    }
}
