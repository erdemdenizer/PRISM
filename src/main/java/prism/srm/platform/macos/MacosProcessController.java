package prism.srm.platform.macos;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.ProcessController;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * Process control on macOS (ADR-0010). Not run on macOS yet: everything here follows Apple's man
 * pages and the xnu source named below, and is tested against a fake of them.
 *
 * <p><b>Check, then act.</b> Every action reads the process's entry with sysctl ({@link
 * KinfoProc}), checks that its PID and start time are those of the identity and that it has not
 * ended, and only then calls {@code kill(2)} or {@code setpriority(2)} with the PID. macOS has no
 * documented interface that refers to one process for a signal (the audit-token calls of libproc
 * are private), so the check and the call are two system calls, not one: a PID reused between them
 * would be acted on. For that, the process must exit and be collected and the kernel must hand out
 * every other PID up to 99999 (it allocates them one after another and wraps to 100) within
 * microseconds. This race is stated, not closed (ADR-0010).
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_fork.c
 *
 * <p>A zombie is answered as gone: kill(2) succeeds on a zombie in xnu, which would report a
 * signal that reached nothing. A process stopped already (SSTOP), by a signal or a debugger, is
 * not suspended again: Prism does not take over a stop it did not make.
 *
 * <p><b>Priority</b> is the nice value of the process. Without privilege it can only be raised (a
 * lower priority): a lower value is refused with EACCES, also back to one the process had before.
 * It is read back with getpriority. The process listing does not show the nice value on macOS (it
 * shows a Mach priority), so no level is named for its numbers.
 *
 * <p><b>Protection facts</b>: PID 0 (kernel_task) and 1 (launchd, which xnu does not let even root
 * signal); the P_SYSTEM flag; a few names, as an additional defense only.
 */
public final class MacosProcessController implements ProcessController {

    /** The signal numbers of macOS, signal(3). */
    static final int SIGKILL = 9;

    static final int SIGTERM = 15;
    static final int SIGSTOP = 17;
    static final int SIGCONT = 19;

    /** {@code p_stat} values, bsd/sys/proc.h. */
    static final int SSTOP = 4;

    static final int SZOMB = 5;

    /** {@code P_SYSTEM}, bsd/sys/proc.h: "Sys proc: no sigs, stats or swap". */
    static final int P_SYSTEM = 0x00000200;

    /** {@code P_TRACED}, bsd/sys/proc.h: "Debugged process being traced". */
    static final int P_TRACED = 0x00000800;

    /** CTL-6, the nice value of each level. */
    static final Map<PriorityLevel, Integer> NICE = Map.of(
            PriorityLevel.LOW, 19,
            PriorityLevel.BELOW_NORMAL, 10,
            PriorityLevel.NORMAL, 0,
            PriorityLevel.ABOVE_NORMAL, -5,
            PriorityLevel.HIGH, -10);

    /** Names of system processes, an additional defense only (SAFE-3). */
    static final Set<String> SYSTEM_NAMES = Set.of("launchd", "kernel_task", "WindowServer", "loginwindow");

    private static final Map<ControlAction, Capability> CAPABILITIES = Map.copyOf(capabilityTable());

    private final MacosNative calls;

    public MacosProcessController() {
        this(new JnaMacos());
    }

    MacosProcessController(MacosNative calls) {
        this.calls = calls;
    }

    @Override
    public Map<ControlAction, Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Map<Integer, PriorityLevel> listedPriorities() {
        return Map.of();
    }

    /** Only the super-user may lower the nice value (setpriority(2)), and the listing does not show the level a process had. */
    @Override
    public Restorability priorityRestorability() {
        return Restorability.NOT_REVERSIBLE;
    }

    @Override
    public ProtectionFacts protectionFacts(ProcessIdentity target) {
        if (target.pid() == 0 || target.pid() == 1) {
            return new ProtectionFacts(true, Optional.empty(), false);
        }
        MacosNative.Reading reading = calls.processInfo(pid(target));
        if (!verify(target, reading).succeeded()) {
            return ProtectionFacts.NONE;
        }
        boolean system = (reading.info().orElseThrow().flags() & P_SYSTEM) != 0;
        boolean listed =
                calls.executable(pid(target)).map(SYSTEM_NAMES::contains).orElse(false);
        return new ProtectionFacts(system, Optional.empty(), listed);
    }

    @Override
    public ControlOutcome liveness(ProcessIdentity target) {
        return verify(target, calls.processInfo(pid(target)));
    }

    @Override
    public ControlOutcome requestClose(ProcessIdentity target) {
        return signal(target, SIGTERM, "SIGTERM");
    }

    @Override
    public ControlOutcome terminate(ProcessIdentity target) {
        return signal(target, SIGKILL, "SIGKILL");
    }

    @Override
    public SuspendResult suspend(ProcessIdentity target) {
        MacosNative.Reading reading = calls.processInfo(pid(target));
        ControlOutcome checked = verify(target, reading);
        if (!checked.succeeded()) {
            return SuspendResult.failed(checked);
        }
        if (reading.info().orElseThrow().state() == SSTOP) {
            return SuspendResult.stoppedElsewhere(ControlOutcome.of(
                    OperationResult.INVALID_ARGUMENT, "already stopped (SSTOP); Prism does not take it over"));
        }
        MacosNative.Call sent = calls.kill(pid(target), SIGSTOP);
        if (sent.failed()) {
            return SuspendResult.failed(MacosErrors.outcome("kill SIGSTOP", sent.errno()));
        }
        return SuspendResult.suspended(
                ControlOutcome.success("stopped with SIGSTOP after the identity was checked"),
                new MacosSuspension(target));
    }

    @Override
    public ControlOutcome resume(Suspension suspension) {
        if (!(suspension instanceof MacosSuspension stop)) {
            throw new IllegalArgumentException("Not a suspension of this controller: " + suspension);
        }
        synchronized (stop) {
            if (stop.outstanding() == 0) {
                return ControlOutcome.success("nothing left to continue");
            }
            ControlOutcome checked = verify(stop.identity(), calls.processInfo(pid(stop.identity())));
            if (checked.result() == OperationResult.PROCESS_NOT_FOUND) {
                // The process Prism stopped has gone; the PID names no process of Prism's any more.
                stop.spent();
                return checked;
            }
            if (!checked.succeeded()) {
                return checked;
            }
            MacosNative.Call sent = calls.kill(pid(stop.identity()), SIGCONT);
            if (!sent.failed()) {
                stop.spent();
                return ControlOutcome.success("continued with SIGCONT after the identity was checked");
            }
            if (sent.errno() == MacosErrors.ESRCH) {
                stop.spent();
                return ControlOutcome.of(
                        OperationResult.PROCESS_NOT_FOUND, sent.errno(), "the process has exited; nothing to continue");
            }
            return MacosErrors.outcome("kill SIGCONT", sent.errno());
        }
    }

    @Override
    public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
        int nice = NICE.get(level);
        ControlOutcome checked = verify(target, calls.processInfo(pid(target)));
        if (!checked.succeeded()) {
            return checked;
        }
        MacosNative.Call set = calls.setPriority(pid(target), nice);
        if (set.failed()) {
            return MacosErrors.outcome("setpriority", set.errno());
        }
        MacosNative.Call read = calls.getPriority(pid(target));
        if (read.failed()) {
            return MacosErrors.outcome("getpriority", read.errno());
        }
        if (read.value() != nice) {
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, "set nice " + nice + ", but the system holds " + read.value());
        }
        return ControlOutcome.success("nice " + nice);
    }

    /** From the process entry, which changes nothing: SSTOP, and the P_TRACED flag. */
    @Override
    public StopState stopState(ProcessIdentity target) {
        MacosNative.Reading reading = calls.processInfo(pid(target));
        if (!verify(target, reading).succeeded()) {
            return StopState.UNKNOWN;
        }
        MacosNative.ProcessInfo info = reading.info().orElseThrow();
        if ((info.flags() & P_TRACED) != 0) {
            return StopState.TRACED;
        }
        return info.state() == SSTOP ? StopState.STOPPED : StopState.RUNNING;
    }

    /**
     * Checks the identity, then takes over a stop (SSTOP) as a suspension that continues it after
     * the identity is checked again. A traced process is never taken over, and one that runs has
     * nothing to continue. The same check-then-act race as every action holds (ADR-0010).
     */
    @Override
    public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
        MacosNative.Reading reading = calls.processInfo(pid(target));
        ControlOutcome checked = verify(target, reading);
        if (!checked.succeeded()) {
            return AdoptResult.of(
                    checked.result() == OperationResult.PROCESS_NOT_FOUND
                            ? AdoptResult.Verdict.GONE
                            : AdoptResult.Verdict.REFUSED,
                    checked);
        }
        MacosNative.ProcessInfo info = reading.info().orElseThrow();
        if ((info.flags() & P_TRACED) != 0) {
            return AdoptResult.of(
                    AdoptResult.Verdict.REFUSED,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "the process is traced by a debugger"));
        }
        if (info.state() != SSTOP) {
            return AdoptResult.of(
                    AdoptResult.Verdict.NOT_SUSPENDED,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "not stopped (state " + info.state() + ")"));
        }
        return AdoptResult.adopted(ControlOutcome.success("stopped (SSTOP)"), new MacosSuspension(target));
    }

    private ControlOutcome signal(ProcessIdentity target, int signal, String name) {
        ControlOutcome checked = verify(target, calls.processInfo(pid(target)));
        if (!checked.succeeded()) {
            return checked;
        }
        MacosNative.Call sent = calls.kill(pid(target), signal);
        return sent.failed()
                ? MacosErrors.outcome("kill " + name, sent.errno())
                : ControlOutcome.success(name + " sent after the identity was checked");
    }

    /** Whether the entry read shows the identity, alive. */
    private static ControlOutcome verify(ProcessIdentity target, MacosNative.Reading reading) {
        if (target.pid() <= 0) {
            // kill(0) signals the caller's process group and -1 every process it may; PRIO_PROCESS 0
            // is the caller. Never passed on, whatever the facts said before.
            return ControlOutcome.of(
                    OperationResult.INVALID_ARGUMENT, "PID " + target.pid() + " names no single process");
        }
        if (reading.missingProcess()) {
            return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
        }
        if (reading.status() == MacosNative.Status.FAILED) {
            // Nothing is known about the process: a native error, never an exit, so that a stop
            // Prism holds is not let go on a read that failed.
            return reading.errno() != 0
                    ? ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            reading.errno(),
                            reading.detail() + " failed: " + MacosErrors.describe(reading.errno()))
                    : ControlOutcome.of(OperationResult.NATIVE_ERROR, reading.detail());
        }
        MacosNative.ProcessInfo info = reading.info().get();
        if (info.pid() != target.pid()) {
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR,
                    "sysctl returned the entry of PID " + info.pid() + " for PID " + target.pid());
        }
        if (info.startMillis() != target.startTime().toEpochMilli()) {
            return ControlOutcome.of(
                    OperationResult.PROCESS_NOT_FOUND,
                    "PID " + target.pid() + " belongs to a process started at " + info.startMillis() + " ms, not "
                            + target.startTime().toEpochMilli() + " ms");
        }
        if (info.state() == SZOMB) {
            return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited (zombie)");
        }
        return ControlOutcome.success();
    }

    private static int pid(ProcessIdentity target) {
        return Math.toIntExact(target.pid());
    }

    private static Map<ControlAction, Capability> capabilityTable() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        map.put(
                ControlAction.END_TASK,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.TERMINATION_SIGNAL));
        map.put(ControlAction.FORCE_KILL, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.KILL_SIGNAL));
        map.put(ControlAction.SUSPEND, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL));
        map.put(ControlAction.RESUME, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL));
        map.put(ControlAction.CHANGE_PRIORITY, Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.NICE_VALUE));
        return map;
    }
}
