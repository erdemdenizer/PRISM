package prism.srm.platform.linux;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Process control on Linux (ADR-0010). Not run on Linux yet: everything here follows the man pages
 * and the kernel source named below, and is tested against a fake of them.
 *
 * <p><b>Signals through a pidfd only.</b> End Task (SIGTERM), Force Kill (SIGKILL), Suspend
 * (SIGSTOP) and Resume (SIGCONT) open the process with {@code pidfd_open}, then check its identity
 * in {@code /proc/<pid>/stat}, then send the signal with {@code pidfd_send_signal} through that
 * descriptor. The order makes the check hold for the descriptor: a process that took the PID after
 * the descriptor was opened started after it, later than the identity's start time, so a matching
 * start time names the very process the descriptor refers to, and the signal cannot reach another
 * (pidfd_send_signal(2): "a PID file descriptor is a stable reference to a specific process").
 * Where the system cannot give that, these four actions are unavailable: Prism does not fall back
 * to {@code kill(2)}, which could reach a process that took over the PID. Whether it can is
 * probed once, on the first use of the controller, on the whole path: open a pidfd of Prism's own
 * process, send it signal 0, close it.
 * https://man7.org/linux/man-pages/man2/pidfd_open.2.html
 * https://man7.org/linux/man-pages/man2/pidfd_send_signal.2.html
 *
 * <p><b>Priority</b> has no call through a pidfd; {@link LinuxPriority} sets every thread, after the
 * same identity check. Without privilege only a higher nice value (a lower priority) can be set; a
 * lower one is refused with EACCES, also back to a value the process had.
 *
 * <p><b>Start times</b> are computed as the process listing computes them, so that identities
 * match: the boot time in seconds times 1000, plus field 22 of the stat file times 1000 over the
 * clock ticks per second. The boot time is the one the listing used, handed in by the platform
 * (OSHI reads {@code btime} once): a second reading of {@code btime} could differ by a second after
 * the clock was stepped, and no identity would match again.
 *
 * <p><b>A process whose main thread has ended</b> while other threads run shows as a zombie in its
 * stat file, and is answered as exited: safe, and stated.
 *
 * <p><b>Protection facts</b>: PID 1; a kernel thread, by the PF_KTHREAD flag in field 9 (kernel
 * threads ignore the signals of users in any case); the names {@code systemd} and {@code init},
 * as an additional defense only.
 *
 * <p>The signal numbers are those of x86 and ARM in signal(7), the only architectures on which the
 * signal actions are offered. https://man7.org/linux/man-pages/man7/signal.7.html
 */
public final class LinuxProcessController implements ProcessController {

    static final int SIGKILL = 9;
    static final int SIGTERM = 15;
    static final int SIGCONT = 18;
    static final int SIGSTOP = 19;

    /** CTL-6, the nice value of each level. */
    static final Map<PriorityLevel, Integer> NICE = Map.of(
            PriorityLevel.LOW, 19,
            PriorityLevel.BELOW_NORMAL, 10,
            PriorityLevel.NORMAL, 0,
            PriorityLevel.ABOVE_NORMAL, -5,
            PriorityLevel.HIGH, -10);

    /**
     * The levels by the priority number of the process listing, field 18 of {@code
     * /proc/<pid>/stat}, which for a process that is not real-time is its nice value plus 20.
     */
    static final Map<Integer, PriorityLevel> LISTED_PRIORITIES = Map.of(
            39, PriorityLevel.LOW,
            30, PriorityLevel.BELOW_NORMAL,
            20, PriorityLevel.NORMAL,
            15, PriorityLevel.ABOVE_NORMAL,
            10, PriorityLevel.HIGH);

    /** Names of system processes, an additional defense only (SAFE-3). */
    static final Set<String> SYSTEM_NAMES = Set.of("systemd", "init");

    private static final Logger LOG = LoggerFactory.getLogger(LinuxProcessController.class);

    private final LinuxNative calls;
    private final ProcFs proc;
    private final LongSupplier bootTimeSeconds;
    private final LinuxPriority priority;
    private Setup setup;

    /**
     * What the system offers, found out once, on the first use of the controller: the clock ticks
     * of {@code /proc}, the boot time, and whether pidfd signalling works. Not in the constructor,
     * so that making the controller touches nothing native.
     */
    private record Setup(
            long ticksPerSecond,
            long bootSeconds,
            Optional<String> pidfdMissing,
            Map<ControlAction, Capability> capabilities) {}

    /** @param bootTimeSeconds the boot time the process listing computes start times from, in seconds */
    public LinuxProcessController(LongSupplier bootTimeSeconds) {
        this(new JnaLinux(), new ProcFs(Path.of("/proc")), bootTimeSeconds);
    }

    LinuxProcessController(LinuxNative calls, ProcFs proc, LongSupplier bootTimeSeconds) {
        this.calls = calls;
        this.proc = proc;
        this.bootTimeSeconds = bootTimeSeconds;
        this.priority = new LinuxPriority(calls, proc);
    }

    /**
     * Found out once. A probe that fails unexpectedly is answered as no pidfd signalling, and not
     * tried again: the same answer on every call. A time that cannot be read leaves no start time
     * computable: no identity can be checked, and every action is refused as a native error.
     */
    private synchronized Setup setup() {
        if (setup == null) {
            Optional<String> missing;
            try {
                missing = probePidfd(calls);
            } catch (RuntimeException e) {
                LOG.warn("pidfd signalling is unavailable: probing it failed", e);
                missing = Optional.of("probing it failed: " + e);
            }
            long ticks;
            long boot;
            try {
                ticks = calls.clockTicksPerSecond();
                boot = bootTimeSeconds.getAsLong();
            } catch (RuntimeException e) {
                LOG.warn("The clock ticks or the boot time could not be read; no identity can be checked", e);
                ticks = 0;
                boot = 0;
            }
            setup = new Setup(ticks, boot, missing, Map.copyOf(capabilities(missing.isEmpty())));
        }
        return setup;
    }

    @Override
    public Map<ControlAction, Capability> capabilities() {
        return setup().capabilities();
    }

    @Override
    public List<String> limitations() {
        Setup known = setup();
        List<String> missing = new java.util.ArrayList<>();
        known.pidfdMissing()
                .ifPresent(reason -> missing.add("Process control: Linux pidfd signalling is unavailable (" + reason
                        + "); End task, Force kill, Suspend and Resume are not available. Change priority is."));
        if (known.bootSeconds() <= 0 || known.ticksPerSecond() <= 0) {
            missing.add("Process control: the boot time or the clock ticks of this system could not be read, so no"
                    + " process can be identified; every action is refused.");
        }
        return List.copyOf(missing);
    }

    @Override
    public Map<Integer, PriorityLevel> listedPriorities() {
        return LISTED_PRIORITIES;
    }

    /** Lowering the nice value needs CAP_SYS_NICE or a suitable RLIMIT_NICE of the target, which Prism cannot know ahead (setpriority(2)). */
    @Override
    public Restorability priorityRestorability() {
        return Restorability.NOT_REVERSIBLE;
    }

    @Override
    public ProtectionFacts protectionFacts(ProcessIdentity target) {
        if (target.pid() <= 1) {
            return new ProtectionFacts(true, Optional.empty(), false);
        }
        ProcFs.Reading<ProcStat> reading = proc.stat(pid(target));
        if (reading.status() != ProcFs.Status.FOUND || !sameStart(target, reading.get())) {
            return ProtectionFacts.NONE;
        }
        ProcStat stat = reading.get();
        return new ProtectionFacts(stat.kernelThread(), Optional.empty(), SYSTEM_NAMES.contains(stat.name()));
    }

    @Override
    public ControlOutcome liveness(ProcessIdentity target) {
        return verify(target, Optional.empty());
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
        if (setup().pidfdMissing().isPresent()) {
            return SuspendResult.failed(unavailable());
        }
        Opened opened = open(target);
        if (opened.failure().isPresent()) {
            return SuspendResult.failed(opened.failure().get());
        }
        int fd = opened.fd();
        boolean handedOver = false;
        try {
            if (opened.stat().stopped()) {
                // Stopped by another program, or by a tracer: Prism does not take it over, so that
                // its Resume never continues a process someone else stopped.
                return SuspendResult.stoppedElsewhere(ControlOutcome.of(
                        OperationResult.INVALID_ARGUMENT,
                        "already stopped (state " + opened.stat().state() + "); Prism does not take it over"));
            }
            LinuxNative.Call sent = calls.pidfdSendSignal(fd, SIGSTOP);
            if (sent.failed()) {
                return SuspendResult.failed(LinuxErrors.outcome("pidfd_send_signal SIGSTOP", sent.errno()));
            }
            SuspendResult held = SuspendResult.suspended(
                    ControlOutcome.success("stopped with SIGSTOP through a pidfd"),
                    new LinuxSuspension(calls, target, fd));
            handedOver = true;
            return held;
        } finally {
            if (!handedOver) {
                calls.close(fd);
            }
        }
    }

    @Override
    public ControlOutcome resume(Suspension suspension) {
        if (!(suspension instanceof LinuxSuspension stop)) {
            throw new IllegalArgumentException("Not a suspension of this controller: " + suspension);
        }
        synchronized (stop) {
            if (stop.outstanding() == 0) {
                return ControlOutcome.success("nothing left to continue");
            }
            // A process killed while it was stopped is a zombie until it is collected, and takes a
            // continue to no effect: it is answered as gone, not as resumed.
            ProcFs.Reading<ProcStat> now = proc.stat(pid(stop.identity()));
            if (now.status() == ProcFs.Status.FOUND
                    && sameStart(stop.identity(), now.get())
                    && now.get().ended()) {
                stop.spent();
                return ControlOutcome.of(
                        OperationResult.PROCESS_NOT_FOUND,
                        "the process exited while it was stopped; nothing to continue");
            }
            LinuxNative.Call sent = calls.pidfdSendSignal(stop.fd(), SIGCONT);
            if (!sent.failed()) {
                stop.spent();
                return ControlOutcome.success("continued with SIGCONT through the pidfd of the stop");
            }
            if (sent.errno() == LinuxErrors.ESRCH) {
                // The process has gone; its descriptor names no other, and nothing is left to continue.
                stop.spent();
                return ControlOutcome.of(
                        OperationResult.PROCESS_NOT_FOUND, sent.errno(), "the process has exited; nothing to continue");
            }
            return LinuxErrors.outcome("pidfd_send_signal SIGCONT", sent.errno());
        }
    }

    @Override
    public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
        int nice = NICE.get(level);
        if (setup().pidfdMissing().isPresent()) {
            ControlOutcome checked = verify(target, Optional.empty());
            if (!checked.succeeded()) {
                return checked;
            }
            return priority.setEveryThread(
                    pid(target),
                    nice,
                    () -> verify(target, Optional.empty()).result() == OperationResult.PROCESS_NOT_FOUND);
        }
        Opened opened = open(target);
        if (opened.failure().isPresent()) {
            return opened.failure().get();
        }
        int fd = opened.fd();
        try {
            return priority.setEveryThread(pid(target), nice, () -> exitedBehind(fd));
        } finally {
            calls.close(fd);
        }
    }

    /** From the stat file, which changes nothing: T is a stop by a signal, t a stop under a tracer. */
    @Override
    public StopState stopState(ProcessIdentity target) {
        if (target.pid() <= 0) {
            return StopState.UNKNOWN;
        }
        ProcFs.Reading<ProcStat> reading = proc.stat(pid(target));
        if (reading.status() != ProcFs.Status.FOUND || !sameStart(target, reading.get())) {
            return StopState.UNKNOWN;
        }
        return switch (reading.get().state()) {
            case 'T' -> StopState.STOPPED;
            case 't' -> StopState.TRACED;
            default -> StopState.RUNNING;
        };
    }

    /**
     * Opens a pidfd and checks the identity after it, as every action does; then takes over a stop
     * by a signal (state T) as a suspension that continues it through that descriptor. A process
     * under a tracer (state t) is never taken over, and one that runs has nothing to continue.
     */
    @Override
    public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
        if (setup().pidfdMissing().isPresent()) {
            return AdoptResult.of(AdoptResult.Verdict.REFUSED, unavailable());
        }
        Opened opened = open(target);
        if (opened.failure().isPresent()) {
            ControlOutcome failure = opened.failure().get();
            return AdoptResult.of(
                    failure.result() == OperationResult.PROCESS_NOT_FOUND
                            ? AdoptResult.Verdict.GONE
                            : AdoptResult.Verdict.REFUSED,
                    failure);
        }
        int fd = opened.fd();
        boolean handedOver = false;
        try {
            char state = opened.stat().state();
            if (state == 't') {
                return AdoptResult.of(
                        AdoptResult.Verdict.REFUSED,
                        ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "the process is stopped under a tracer"));
            }
            if (state != 'T') {
                return AdoptResult.of(
                        AdoptResult.Verdict.NOT_SUSPENDED,
                        ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "not stopped (state " + state + ")"));
            }
            AdoptResult adopted = AdoptResult.adopted(
                    ControlOutcome.success("stopped by a signal (state T)"), new LinuxSuspension(calls, target, fd));
            handedOver = true;
            return adopted;
        } finally {
            if (!handedOver) {
                calls.close(fd);
            }
        }
    }

    private ControlOutcome signal(ProcessIdentity target, int signal, String name) {
        if (setup().pidfdMissing().isPresent()) {
            return unavailable();
        }
        Opened opened = open(target);
        if (opened.failure().isPresent()) {
            return opened.failure().get();
        }
        try {
            LinuxNative.Call sent = calls.pidfdSendSignal(opened.fd(), signal);
            return sent.failed()
                    ? LinuxErrors.outcome("pidfd_send_signal " + name, sent.errno())
                    : ControlOutcome.success(name + " sent through a pidfd");
        } finally {
            calls.close(opened.fd());
        }
    }

    /** Whether the process a pidfd refers to has exited (ESRCH to signal 0). */
    private boolean exitedBehind(int fd) {
        LinuxNative.Call alive = calls.pidfdSendSignal(fd, 0);
        return alive.failed() && alive.errno() == LinuxErrors.ESRCH;
    }

    /** A pidfd of the process, checked against the identity after it was opened; or why there is none. */
    private record Opened(int fd, ProcStat stat, Optional<ControlOutcome> failure) {}

    private Opened open(ProcessIdentity target) {
        if (target.pid() <= 0) {
            return new Opened(-1, null, Optional.of(notOneProcess(target)));
        }
        LinuxNative.Call opened = calls.pidfdOpen(pid(target));
        if (opened.failed()) {
            return new Opened(-1, null, Optional.of(LinuxErrors.outcome("pidfd_open", opened.errno())));
        }
        int fd = (int) opened.value();
        boolean kept = false;
        try {
            ProcFs.Reading<ProcStat> reading = proc.stat(pid(target));
            ControlOutcome checked = verify(target, reading, Optional.of(fd));
            if (!checked.succeeded()) {
                return new Opened(-1, null, Optional.of(checked));
            }
            kept = true;
            return new Opened(fd, reading.get(), Optional.empty());
        } finally {
            if (!kept) {
                calls.close(fd);
            }
        }
    }

    private ControlOutcome verify(ProcessIdentity target, Optional<Integer> fd) {
        if (target.pid() <= 0) {
            return notOneProcess(target);
        }
        return verify(target, proc.stat(pid(target)), fd);
    }

    /**
     * Whether the stat file read shows the identity, alive. With a descriptor, a read that failed
     * otherwise is told apart from an exit by signal 0 through it.
     */
    private ControlOutcome verify(ProcessIdentity target, ProcFs.Reading<ProcStat> reading, Optional<Integer> fd) {
        switch (reading.status()) {
            case MISSING -> {
                return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
            }
            case DENIED -> {
                return ControlOutcome.of(OperationResult.ACCESS_DENIED, reading.detail());
            }
            case FAILED -> {
                if (fd.isPresent() && exitedBehind(fd.get())) {
                    return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
                }
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, reading.detail());
            }
            case FOUND -> {
                // Checked below.
            }
        }
        ProcStat stat = reading.get();
        OptionalLong started = startMillis(stat);
        if (started.isEmpty()) {
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, "the boot time or the clock ticks are unknown; identity unchecked");
        }
        if (started.getAsLong() != target.startTime().toEpochMilli()) {
            return ControlOutcome.of(
                    OperationResult.PROCESS_NOT_FOUND,
                    "PID " + target.pid() + " belongs to a process started at " + started.getAsLong() + " ms, not "
                            + target.startTime().toEpochMilli() + " ms");
        }
        if (stat.ended()) {
            return ControlOutcome.of(
                    OperationResult.PROCESS_NOT_FOUND, "the process has exited (state " + stat.state() + ")");
        }
        return ControlOutcome.success();
    }

    private boolean sameStart(ProcessIdentity target, ProcStat stat) {
        OptionalLong started = startMillis(stat);
        return started.isPresent() && started.getAsLong() == target.startTime().toEpochMilli();
    }

    /** The start time as the process listing computes it, in milliseconds since the epoch. */
    OptionalLong startMillis(ProcStat stat) {
        Setup known = setup();
        return ProcessIdentityReader.startMillis(known.bootSeconds(), known.ticksPerSecond(), stat.startTicks());
    }

    /** PID 0 names the caller, and no PID below names one process: never passed to a call. */
    private static ControlOutcome notOneProcess(ProcessIdentity target) {
        return ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "PID " + target.pid() + " names no single process");
    }

    private ControlOutcome unavailable() {
        return ControlOutcome.of(
                OperationResult.UNSUPPORTED,
                "pidfd signalling is unavailable: " + setup().pidfdMissing().orElse("unknown")
                        + "; kill(2) is not used");
    }

    private static int pid(ProcessIdentity target) {
        return Math.toIntExact(target.pid());
    }

    /**
     * Tries the whole pidfd path on Prism's own process: open, signal 0, close. Empty if it works;
     * otherwise why not, which is logged: at INFO where the mechanism is simply not there, as a
     * warning where the failure was not one that says so.
     */
    static Optional<String> probePidfd(LinuxNative calls) {
        Optional<String> unreachable = calls.pidfdUnreachable();
        if (unreachable.isPresent()) {
            LOG.info("pidfd signalling is not used: {}", unreachable.get());
            return unreachable;
        }
        LinuxNative.Call opened = calls.pidfdOpen(calls.ownPid());
        if (opened.failed()) {
            return Optional.of(failedProbe("pidfd_open", opened.errno()));
        }
        int fd = (int) opened.value();
        LinuxNative.Call checked;
        try {
            checked = calls.pidfdSendSignal(fd, 0);
        } finally {
            calls.close(fd);
        }
        if (checked.failed()) {
            return Optional.of(failedProbe("pidfd_send_signal", checked.errno()));
        }
        LOG.info("pidfd signalling is available");
        return Optional.empty();
    }

    private static String failedProbe(String call, int errno) {
        String reason = call + " on Prism's own process failed with " + LinuxErrors.describe(errno);
        if (LinuxErrors.meansNoPidfd(errno)) {
            LOG.info("pidfd signalling is unavailable: {}", reason);
        } else {
            LOG.warn("pidfd signalling is unavailable after an unexpected failure: {}", reason);
        }
        return reason;
    }

    private static Map<ControlAction, Capability> capabilities(boolean signals) {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        if (signals) {
            map.put(
                    ControlAction.END_TASK,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.TERMINATION_SIGNAL));
            map.put(ControlAction.FORCE_KILL, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.KILL_SIGNAL));
            map.put(ControlAction.SUSPEND, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL));
            map.put(ControlAction.RESUME, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL));
        } else {
            for (ControlAction action : List.of(
                    ControlAction.END_TASK, ControlAction.FORCE_KILL, ControlAction.SUSPEND, ControlAction.RESUME)) {
                map.put(action, Capability.UNAVAILABLE);
            }
        }
        map.put(ControlAction.CHANGE_PRIORITY, Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.NICE_VALUE));
        return map;
    }
}
