package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

/** {@link LinuxProcessController} against {@link FakeLinux}: the order of the calls, the results, and every descriptor closed. */
class LinuxProcessControllerTest {

    private static final int PID = 6_000;
    private static final long START_TICKS = 123_456;
    private static final ProcessIdentity TARGET = new ProcessIdentity(PID, FakeProc.start(START_TICKS));

    @TempDir
    Path root;

    private FakeProc proc;
    private FakeLinux linux;
    private LinuxProcessController controller;

    @BeforeEach
    void setUp() {
        proc = new FakeProc(root);
        linux = new FakeLinux(proc);
        proc.process(PID, "demo", START_TICKS, 6_001, 6_002);
        controller = new LinuxProcessController(linux, new ProcFs(root), () -> FakeProc.BOOT_TIME_SECONDS);
    }

    /** Closing a descriptor twice could close another thread's file: never. */
    @org.junit.jupiter.api.AfterEach
    void noDescriptorIsClosedTwice() {
        assertThat(linux.doubleCloses()).isZero();
    }

    /** Makes the controller probe, then forgets the calls of the probe. */
    private void probed() {
        controller.capabilities();
        linux.calls.clear();
    }

    // ----- Capabilities and the probe -----

    /** PRODUCT_SPEC section 6, Linux column, with pidfd signalling (CAP-3, CAP-5). */
    @Test
    void withPidfdSignallingEveryActionIsOfferedWithItsMechanism() {
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

    /** The probe tries the whole path on Prism's own process, and closes what it opened. */
    @Test
    void theProbeOpensSignalsAndClosesOnPrismsOwnProcessOnce() {
        controller.capabilities();
        controller.capabilities();
        controller.limitations();

        assertThat(linux.calls).containsExactly("pidfd_open " + linux.ownPid, "pidfd_send_signal 10 0", "close 10");
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void makingTheControllerTouchesNothing() {
        assertThat(linux.calls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {LinuxErrors.ENOSYS, LinuxErrors.EPERM, LinuxErrors.ENODEV, LinuxErrors.EMFILE})
    void whenPidfdCannotBeOpenedTheFourSignalActionsAreUnavailableAndPriorityIsNot(int errno) {
        linux.openErrno = errno;

        assertSignalActionsUnavailable();
        assertThat(controller.limitations())
                .singleElement()
                .asString()
                .contains("pidfd signalling is unavailable")
                .contains(LinuxErrors.describe(errno))
                .contains("Change priority is");
    }

    /** Opening alone is not enough: the probe also sends signal 0, and closes the descriptor all the same. */
    @Test
    void whenTheProbeSignalFailsTheActionsAreUnavailableAndTheDescriptorIsClosed() {
        linux.probeSignalErrno = LinuxErrors.EPERM;

        assertSignalActionsUnavailable();
        assertThat(linux.openFds()).isEmpty();
        assertThat(controller.limitations()).singleElement().asString().contains("pidfd_send_signal");
    }

    @Test
    void onAnArchitectureWithoutVerifiedNumbersNothingIsTriedAndTheSignalActionsAreUnavailable() {
        linux.unreachable = Optional.of("the system call numbers of the architecture riscv64 are not verified");

        assertSignalActionsUnavailable();
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd"));
        assertThat(controller.limitations()).singleElement().asString().contains("riscv64");
    }

    /** Without pidfd no signal reaches any process: no fallback to kill(2), which this seam does not even have. */
    @Test
    void withoutPidfdTheSignalActionsAnswerUnsupportedAndSendNothing() {
        linux.openErrno = LinuxErrors.ENOSYS;
        probed();

        assertThat(controller.requestClose(TARGET).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.suspend(TARGET).outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(linux.calls).isEmpty();
        assertThat(proc.state(PID)).isEqualTo('S');
    }

    private void assertSignalActionsUnavailable() {
        Map<ControlAction, Capability> capabilities = controller.capabilities();
        for (ControlAction action : new ControlAction[] {
            ControlAction.END_TASK, ControlAction.FORCE_KILL, ControlAction.SUSPEND, ControlAction.RESUME
        }) {
            assertThat(capabilities.get(action)).as(action.name()).isEqualTo(Capability.UNAVAILABLE);
        }
        assertThat(capabilities.get(ControlAction.CHANGE_PRIORITY))
                .isEqualTo(Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.NICE_VALUE));
    }

    // ----- Signals -----

    @Test
    void endTaskOpensChecksSignalsAndClosesInThatOrder() {
        probed();

        ControlOutcome outcome = controller.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(linux.calls).containsExactly("pidfd_open " + PID, "pidfd_send_signal 11 15", "close 11");
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void forceKillSendsTheKillSignal() {
        probed();

        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(linux.calls).contains("pidfd_send_signal 11 9");
        assertThat(proc.state(PID)).isEqualTo('Z');
        assertThat(linux.openFds()).isEmpty();
    }

    /**
     * The identity is checked after the descriptor is opened: here the PID passes to another process
     * between the open and the check, and nothing is sent.
     */
    @Test
    void aPidTakenOverRightAfterTheOpenIsNotSignalled() {
        probed();
        linux.afterOpen = () -> {
            proc.remove(PID);
            proc.process(PID, "other", START_TICKS + 500);
        };

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd_send_signal"));
        assertThat(proc.state(PID)).isEqualTo('S');
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aPidThatBelongsToAnotherProcessIsNotSignalled() {
        probed();
        ProcessIdentity stale = new ProcessIdentity(PID, FakeProc.start(START_TICKS - 1));

        for (ControlOutcome outcome : new ControlOutcome[] {
            controller.requestClose(stale),
            controller.terminate(stale),
            controller.suspend(stale).outcome(),
            controller.changePriority(stale, PriorityLevel.LOW)
        }) {
            assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        }
        assertThat(linux.calls)
                .noneMatch(call -> call.startsWith("pidfd_send_signal") || call.startsWith("setpriority"));
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aZombieIsAnsweredAsGone() {
        probed();
        proc.state(PID, 'Z');

        assertThat(controller.requestClose(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd_send_signal"));
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aProcessGoneBeforeTheOpenIsNotFound() {
        probed();
        proc.remove(PID);

        ControlOutcome outcome = controller.requestClose(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(outcome.nativeCode()).hasValue(LinuxErrors.ESRCH);
    }

    @Test
    void aRefusedSignalIsAccessDeniedWithItsCode() {
        probed();
        linux.signalErrno.put(PID, LinuxErrors.EPERM);

        ControlOutcome outcome = controller.terminate(TARGET);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).hasValue(LinuxErrors.EPERM);
        assertThat(linux.openFds()).isEmpty();
    }

    /** At run time EPERM concerns the target, never the mechanism: the actions stay offered. */
    @Test
    void aRefusalAtRunTimeLeavesTheMechanismAvailable() {
        probed();
        linux.signalErrno.put(PID, LinuxErrors.EPERM);
        controller.requestClose(TARGET);

        assertThat(controller.capabilities().get(ControlAction.END_TASK).available())
                .isTrue();
        assertThat(controller.limitations()).isEmpty();
    }

    // ----- Suspend and Resume -----

    @Test
    void suspendStopsTheProcessAndHoldsItsDescriptorUntilResume() {
        probed();

        SuspendResult suspended = controller.suspend(TARGET);

        assertThat(suspended.outcome().result()).isEqualTo(OperationResult.SUCCESS);
        Suspension stop = suspended.suspension().orElseThrow();
        assertThat(stop.identity()).isEqualTo(TARGET);
        assertThat(stop.threads()).isEqualTo(1);
        assertThat(stop.outstanding()).isEqualTo(1);
        assertThat(proc.state(PID)).isEqualTo('T');
        assertThat(linux.openFds()).containsExactly(11);

        ControlOutcome resumed = controller.resume(stop);

        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(linux.calls).containsSubsequence("pidfd_send_signal 11 19", "pidfd_send_signal 11 18", "close 11");
        assertThat(stop.outstanding()).isZero();
        assertThat(proc.state(PID)).isEqualTo('S');
        assertThat(linux.openFds()).isEmpty();
    }

    /** Stopped by another program or a tracer: Prism takes nothing over, so it never continues it. */
    @ParameterizedTest
    @ValueSource(chars = {'T', 't'})
    void aProcessStoppedAlreadyIsNotSuspended(char state) {
        probed();
        proc.state(PID, state);

        SuspendResult result = controller.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(result.stoppedElsewhere())
                .as("told apart from the other refusals")
                .isTrue();
        assertThat(result.suspension()).isEmpty();
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd_send_signal"));
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aRefusalForAnotherReasonIsNotAStopElsewhere() {
        ProcessIdentity zero = new ProcessIdentity(0, FakeProc.start(START_TICKS));

        SuspendResult result = controller.suspend(zero);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(result.stoppedElsewhere()).isFalse();
    }

    // ----- After a crash: the stop state and taking over (ADR-0011) -----

    @Test
    void theStopStateIsReadFromTheStatFileWithoutAnyCall() {
        probed();
        assertThat(controller.stopState(TARGET)).isEqualTo(prism.srm.platform.StopState.RUNNING);
        proc.state(PID, 'T');
        assertThat(controller.stopState(TARGET)).isEqualTo(prism.srm.platform.StopState.STOPPED);
        proc.state(PID, 't');
        assertThat(controller.stopState(TARGET)).isEqualTo(prism.srm.platform.StopState.TRACED);
        assertThat(controller.stopState(new ProcessIdentity(PID, FakeProc.start(START_TICKS + 5))))
                .as("another process's PID")
                .isEqualTo(prism.srm.platform.StopState.UNKNOWN);
        assertThat(linux.calls).isEmpty();
    }

    @Test
    void aStopByASignalIsTakenOverThroughAPidfdAndContinuedThroughIt() {
        probed();
        proc.state(PID, 'T');

        prism.srm.platform.AdoptResult result = controller.adopt(TARGET, Optional.empty());

        assertThat(result.verdict()).isEqualTo(prism.srm.platform.AdoptResult.Verdict.ADOPTED);
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd_send_signal"));
        assertThat(controller.resume(result.suspension().orElseThrow()).succeeded())
                .isTrue();
        assertThat(linux.calls).anyMatch(call -> call.startsWith("pidfd_send_signal") && call.endsWith(" 18"));
        assertThat(linux.openFds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(chars = {'t', 'R', 'S'})
    void aTracedOrRunningProcessIsNotTakenOverAndNothingIsSent(char state) {
        probed();
        proc.state(PID, state);

        prism.srm.platform.AdoptResult result = controller.adopt(TARGET, Optional.empty());

        assertThat(result.verdict())
                .isEqualTo(
                        state == 't'
                                ? prism.srm.platform.AdoptResult.Verdict.REFUSED
                                : prism.srm.platform.AdoptResult.Verdict.NOT_SUSPENDED);
        assertThat(result.suspension()).isEmpty();
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd_send_signal"));
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aPidThatBelongsToAnotherProcessIsGoneAndNotTakenOver() {
        probed();
        proc.state(PID, 'T');

        prism.srm.platform.AdoptResult result =
                controller.adopt(new ProcessIdentity(PID, FakeProc.start(START_TICKS + 5)), Optional.empty());

        assertThat(result.verdict()).isEqualTo(prism.srm.platform.AdoptResult.Verdict.GONE);
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aRefusedStopHoldsNothing() {
        probed();
        linux.signalErrno.put(PID, LinuxErrors.EPERM);

        SuspendResult result = controller.suspend(TARGET);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.suspension()).isEmpty();
        assertThat(linux.openFds()).isEmpty();
    }

    /** The continue goes through the descriptor of the stop, which names only the process that was stopped. */
    @Test
    void resumeAfterTheProcessWasReplacedReachesNothingAndLetsGo() {
        probed();
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        proc.remove(PID);
        proc.process(PID, "other", START_TICKS + 900);
        proc.state(PID, 'T');

        ControlOutcome outcome = controller.resume(stop);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(stop.outstanding()).isZero();
        assertThat(proc.state(PID)).as("the other process stays as it was").isEqualTo('T');
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aRefusedContinueKeepsTheStopForAnotherTry() {
        probed();
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        linux.signalErrno.put(PID, LinuxErrors.EPERM);

        ControlOutcome refused = controller.resume(stop);

        assertThat(refused.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(stop.outstanding()).isEqualTo(1);
        assertThat(linux.openFds()).containsExactly(11);

        linux.signalErrno.remove(PID);
        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(stop.outstanding()).isZero();
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aResumeOfAStopUndoneAlreadyDoesNothing() {
        probed();
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        controller.resume(stop);
        linux.calls.clear();

        assertThat(controller.resume(stop).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(linux.calls).isEmpty();
    }

    /** Closing lets go of the descriptor and leaves the process stopped (LIFE-2); twice is harmless. */
    @Test
    void closingAStopReleasesItsDescriptorOnceAndLeavesTheProcessStopped() {
        probed();
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();

        stop.close();
        stop.close();

        assertThat(linux.openFds()).isEmpty();
        assertThat(linux.doubleCloses()).isZero();
        assertThat(proc.state(PID)).isEqualTo('T');
    }

    @Test
    void aSuspensionOfAnotherControllerIsRejected() {
        probed();
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

    // ----- Priority -----

    /** CTL-6, Linux column. */
    @Test
    void theNiceValuesOfTheLevelsAreThoseOfTheSpecification() {
        assertThat(LinuxProcessController.NICE)
                .isEqualTo(Map.of(
                        PriorityLevel.LOW, 19,
                        PriorityLevel.BELOW_NORMAL, 10,
                        PriorityLevel.NORMAL, 0,
                        PriorityLevel.ABOVE_NORMAL, -5,
                        PriorityLevel.HIGH, -10));
    }

    /** The listing shows field 18 of the stat file, the nice value plus 20, for each level. */
    @Test
    void theListedPrioritiesAreTheNiceValuesPlusTwenty() {
        for (PriorityLevel level : PriorityLevel.values()) {
            assertThat(controller.listedPriorities().get(LinuxProcessController.NICE.get(level) + 20))
                    .isEqualTo(level);
        }
        assertThat(controller.listedPriorities()).hasSize(5);
    }

    @ParameterizedTest
    @EnumSource(PriorityLevel.class)
    void everyThreadIsSetAndReadBack(PriorityLevel level) {
        probed();
        int nice = LinuxProcessController.NICE.get(level);

        ControlOutcome outcome = controller.changePriority(TARGET, level);

        assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
        for (int tid : new int[] {PID, 6_001, 6_002}) {
            assertThat(proc.nice(PID, tid)).as("thread " + tid).isEqualTo(nice);
        }
        assertThat(linux.openFds()).isEmpty();
    }

    /** A thread started while the first threads were set is found by the second reading and set too. */
    @Test
    void aThreadStartedMeanwhileIsSetToo() {
        probed();
        linux.afterSetPriority = tid -> {
            if (tid == 6_002 && proc.processOf(6_003) == 0) {
                proc.addThread(PID, 6_003, 0);
            }
        };

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.SUCCESS);
        assertThat(proc.nice(PID, 6_003)).isEqualTo(19);
    }

    @Test
    void aThreadThatEndedMeanwhileIsPassedOver() {
        probed();
        linux.priorityErrno.put(6_001, LinuxErrors.ESRCH);
        linux.afterSetPriority = tid -> {
            if (tid == PID && proc.processOf(6_001) != 0) {
                proc.removeThread(PID, 6_001);
            }
        };

        assertThat(controller.changePriority(TARGET, PriorityLevel.BELOW_NORMAL).result())
                .isEqualTo(OperationResult.SUCCESS);
    }

    /** CTL-6: raising a priority without privilege is EACCES at the first thread, and nothing changed. */
    @ParameterizedTest
    @ValueSource(ints = {LinuxErrors.EACCES, LinuxErrors.EPERM})
    void aRefusalAtTheFirstThreadIsAccessDeniedAndChangesNothing(int errno) {
        probed();
        linux.priorityErrno.put(PID, errno);

        ControlOutcome outcome = controller.changePriority(TARGET, PriorityLevel.HIGH);

        assertThat(outcome.result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(outcome.nativeCode()).hasValue(errno);
        assertThat(proc.nice(PID, 6_001)).isZero();
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aRefusalAfterSomeThreadsIsANativeErrorThatSaysHowFarItGot() {
        probed();
        linux.priorityErrno.put(6_001, LinuxErrors.EPERM);

        ControlOutcome outcome = controller.changePriority(TARGET, PriorityLevel.LOW);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("after 1 thread(s)"));
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void aThreadThatDoesNotHoldTheValueMakesItANativeError() {
        probed();
        linux.stubbornThreads.add(6_002);

        ControlOutcome outcome = controller.changePriority(TARGET, PriorityLevel.LOW);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("2 of 3"));
    }

    @Test
    void aProcessThatExitedDuringTheChangeIsNotFound() {
        probed();
        linux.afterSetPriority = tid -> {
            if (tid == 6_002) {
                proc.remove(PID);
            }
        };

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(linux.openFds()).isEmpty();
    }

    /** Priority does not need pidfd: without it the identity is checked in /proc before and after. */
    @Test
    void withoutPidfdPriorityIsStillSetAfterTheIdentityIsChecked() {
        linux.openErrno = LinuxErrors.ENOSYS;
        probed();

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.SUCCESS);
        assertThat(linux.calls).noneMatch(call -> call.startsWith("pidfd"));
        assertThat(proc.nice(PID, PID)).isEqualTo(19);
    }

    // ----- Facts -----

    @Test
    void pidOneIsASystemProcess() {
        assertThat(controller
                        .protectionFacts(new ProcessIdentity(1, FakeProc.start(1)))
                        .systemProcess())
                .isTrue();
    }

    @Test
    void aKernelThreadIsASystemProcessByItsFlag() {
        proc.process(2, "kthreadd", 1);
        proc.flags(2, ProcStat.PF_KTHREAD | 0x40);

        assertThat(controller
                        .protectionFacts(new ProcessIdentity(2, FakeProc.start(1)))
                        .systemProcess())
                .isTrue();
    }

    @Test
    void theNameSystemdIsListedAndAnOrdinaryProcessIsNot() {
        proc.process(800, "systemd", 40);

        assertThat(controller.protectionFacts(new ProcessIdentity(800, FakeProc.start(40))))
                .isEqualTo(new ProtectionFacts(false, Optional.empty(), true));
        assertThat(controller.protectionFacts(TARGET)).isEqualTo(new ProtectionFacts(false, Optional.empty(), false));
    }

    @Test
    void theFactsOfAnotherProcessUnderThePidAreNotGiven() {
        proc.flags(PID, ProcStat.PF_KTHREAD);

        assertThat(controller.protectionFacts(new ProcessIdentity(PID, FakeProc.start(START_TICKS + 1))))
                .isEqualTo(ProtectionFacts.NONE);
    }

    @Test
    void livenessTellsTheIdentityAliveGoneAndReplacedApart() {
        assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(controller
                        .liveness(new ProcessIdentity(PID, FakeProc.start(START_TICKS + 1)))
                        .result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        proc.state(PID, 'Z');
        assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        proc.remove(PID);
        assertThat(controller.liveness(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void theStartTimeIsComputedAsTheListingComputesIt() {
        assertThat(controller.startMillis(new ProcFs(root).stat(PID).get()))
                .hasValue(TARGET.startTime().toEpochMilli());
    }

    /** A probe that throws is answered as no pidfd signalling, once: the same answer on every call. */
    @Test
    void aProbeThatThrowsMakesTheSignalActionsUnavailableAndIsNotTriedAgain() {
        linux.openThrows = new IllegalStateException("no libc here");
        LinuxProcessController throwing = controller;

        assertThat(throwing.capabilities().get(ControlAction.END_TASK)).isEqualTo(Capability.UNAVAILABLE);
        assertThat(throwing.capabilities().get(ControlAction.CHANGE_PRIORITY).available())
                .isTrue();
        assertThat(throwing.limitations()).singleElement().asString().contains("probing it failed");
        assertThat(throwing.terminate(TARGET).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(linux.calls).as("probed once").containsOnlyOnce("pidfd_open " + linux.ownPid);
    }

    /** Killed while stopped, the process is a zombie that takes a continue to no effect: gone, not resumed. */
    @Test
    void resumeOfAProcessKilledWhileStoppedIsNotFound() {
        probed();
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        proc.state(PID, 'Z');

        ControlOutcome outcome = controller.resume(stop);

        assertThat(outcome.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(stop.outstanding()).isZero();
        assertThat(linux.openFds()).isEmpty();
        assertThat(linux.calls).doesNotContain("pidfd_send_signal 11 18");
    }

    /** Without the boot time no start time can be computed: no identity matches, nothing is sent. */
    @Test
    void withoutABootTimeEveryActionIsRefusedAndNothingIsSent() {
        LinuxProcessController blind = new LinuxProcessController(linux, new ProcFs(root), () -> 0);
        blind.capabilities();
        linux.calls.clear();

        for (ControlOutcome outcome : new ControlOutcome[] {
            blind.requestClose(TARGET),
            blind.terminate(TARGET),
            blind.suspend(TARGET).outcome(),
            blind.changePriority(TARGET, PriorityLevel.LOW)
        }) {
            assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        }
        assertThat(linux.calls)
                .noneMatch(call -> call.startsWith("pidfd_send_signal") || call.startsWith("setpriority"));
        assertThat(linux.openFds()).isEmpty();
        assertThat(blind.liveness(TARGET).result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(blind.limitations()).singleElement().asString().contains("boot time");
    }

    @Test
    void aStatFileWithoutItsFormAfterTheOpenSendsNothing() {
        probed();
        linux.afterOpen = () -> proc.corrupt(PID);

        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(linux.calls).doesNotContain("pidfd_send_signal 11 9");
        assertThat(linux.openFds()).isEmpty();
    }

    /** A read that failed is told apart from an exit by signal 0 through the descriptor. */
    @Test
    void aStatFileUnreadableBecauseTheProcessExitedIsNotFound() {
        probed();
        linux.afterOpen = () -> {
            proc.corrupt(PID);
            proc.forget(PID);
        };

        assertThat(controller.terminate(TARGET).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(linux.calls).doesNotContain("pidfd_send_signal 11 9");
        assertThat(linux.openFds()).isEmpty();
    }

    @Test
    void withoutPidfdAStaleIdentityGetsNoPriority() {
        linux.openErrno = LinuxErrors.ENOSYS;
        probed();

        assertThat(controller
                        .changePriority(new ProcessIdentity(PID, FakeProc.start(START_TICKS + 1)), PriorityLevel.LOW)
                        .result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(linux.calls).noneMatch(call -> call.startsWith("setpriority"));
    }

    @Test
    void withoutPidfdAProcessThatExitsDuringTheChangeIsNotFound() {
        linux.openErrno = LinuxErrors.ENOSYS;
        probed();
        linux.afterSetPriority = tid -> {
            if (tid == 6_002) {
                proc.remove(PID);
            }
        };

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    /** PID 0 names the caller and is never passed to a call, whatever the facts said before. */
    @Test
    void pidZeroIsNeverActedOn() {
        probed();
        ProcessIdentity zero = new ProcessIdentity(0, FakeProc.start(1));

        assertThat(controller.terminate(zero).result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.suspend(zero).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.changePriority(zero, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.protectionFacts(zero).systemProcess()).isTrue();
        assertThat(linux.calls).isEmpty();
    }

    /** A stop let go of by close is not resumed through a closed descriptor: the misuse is loud. */
    @Test
    void resumeAfterCloseIsRefusedLoudly() {
        probed();
        Suspension stop = controller.suspend(TARGET).suspension().orElseThrow();
        stop.close();

        assertThatThrownBy(() -> controller.resume(stop)).isInstanceOf(IllegalStateException.class);
        assertThat(linux.calls).doesNotContain("pidfd_send_signal 11 18");
    }

    /**
     * A thread that still runs but cannot be read back is unverified: the value is not reported as
     * held, though every thread that could be read holds it.
     */
    @Test
    void aRunningThreadThatCannotBeReadBackLeavesTheValueUnverified() {
        probed();
        linux.afterSetPriority = tid -> {
            if (tid == 6_002) {
                proc.corruptThread(PID, 6_002);
            }
        };

        ControlOutcome outcome = controller.changePriority(TARGET, PriorityLevel.LOW);

        assertThat(outcome.result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(outcome.detail())
                .hasValueSatisfying(detail -> assertThat(detail)
                        .contains("1 running thread(s) could not be read back")
                        .contains("2 of 2 read hold nice 19"));
        assertThat(linux.openFds()).isEmpty();
    }

    /** A thread that ended after it was set needs no value: the others holding it is a success. */
    @Test
    void aThreadThatEndedAfterItWasSetDoesNotStandInTheWay() {
        probed();
        linux.afterSetPriority = tid -> {
            if (tid == 6_002) {
                proc.removeThread(PID, 6_001);
            }
        };

        assertThat(controller.changePriority(TARGET, PriorityLevel.LOW).result())
                .isEqualTo(OperationResult.SUCCESS);
    }
}
