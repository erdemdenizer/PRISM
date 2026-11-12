package prism.srm.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.RecoveredLimit.Verdict;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ReleaseResult;
import prism.srm.platform.ResourceLimiter;

/**
 * Native limits through Job Objects (ADR-0002, milestone 10, plan revision 4.1): one named job per
 * limited process, a hard CPU cap ({@code ENABLE | HARD_CAP}, the rate a share of the whole
 * machine) and a per-process committed-memory limit ({@code JOB_OBJECT_LIMIT_PROCESS_MEMORY}).
 *
 * <p><b>Ownership.</b> The limiter holds the handle of every job it made for as long as the process
 * lives in this session, limits or none: a job's name lives only while a handle is open, so a
 * closed handle would leave the process in a job Prism can neither reach nor tell from another
 * program's. A handle is let go only when the process has exited and the job is verified to enforce
 * nothing ({@link #forget}), at shutdown ({@link #releaseAll}), or when an assignment is proven not
 * to have happened. From the moment an assignment is attempted, no path closes the job's handle
 * unless the system proves the process is not in it.
 *
 * <p><b>Never used:</b> {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, {@code TerminateJobObject},
 * {@code TerminateProcess}, nested assignment to a job of another program, breakaway flags.
 *
 * <p><b>Threads.</b> The limit service calls it on {@code srm-limiter}; the shutdown and the exit
 * hook call {@link #releaseAll}. One lock covers every call; each holds it for a few calls of the
 * system, well under a millisecond each.
 */
public final class WindowsJobLimiter implements ResourceLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsJobLimiter.class);

    /** The process rights AssignProcessToJobObject needs, besides PROCESS_QUERY_LIMITED_INFORMATION. */
    static final int ASSIGN_RIGHTS = WinNT.PROCESS_SET_QUOTA | WinNT.PROCESS_TERMINATE;

    /**
     * Recovery opens a job of an earlier session for reading only: a job that can be opened has a
     * live holder, whose limits recovery never changes (audit of the recovery trust boundary, C).
     */
    static final int REOPEN_RIGHTS = JobNative.JOB_OBJECT_QUERY;

    static final int CAP =
            JobNative.JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JobNative.JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;

    static final String NOT_HELD_JOB =
            "it is in a Job Object Prism does not hold (another program's, or one an earlier Prism limit left);"
                    + " a cap inside it would not be a share of the whole machine";

    private final Win32 win32;
    private final WindowsProcessAccess access;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Changed only under {@link #lock}; concurrent so that a shutdown that cannot take the lock in
     * time can still name the holds it leaves (external audit item 3).
     */
    private final Map<ProcessIdentity, Hold> holds = new ConcurrentHashMap<>();

    private volatile boolean closed;
    private volatile Map<LimitKind, LimitCapability> capabilities;

    /** A job the limiter holds. Guarded by the lock. */
    private static final class Hold {
        final long job;
        NativeHold.State state;
        /** What the job is known to enforce; empty when unknown. */
        Optional<EffectiveLimits> inForce;

        Hold(long job, NativeHold.State state, Optional<EffectiveLimits> inForce) {
            this.job = job;
            this.state = state;
            this.inForce = inForce;
        }
    }

    public WindowsJobLimiter() {
        this(new JnaWin32());
    }

    WindowsJobLimiter(Win32 win32) {
        this.win32 = win32;
        this.access = new WindowsProcessAccess(win32);
    }

    // ----- Capabilities -----

    @Override
    public Map<LimitKind, LimitCapability> capabilities() {
        Map<LimitKind, LimitCapability> known = capabilities;
        if (known == null) {
            lock.lock();
            try {
                known = capabilities;
                if (known == null) {
                    known = probe();
                    capabilities = known;
                }
            } finally {
                lock.unlock();
            }
        }
        return known;
    }

    /**
     * Sets both limits on a job with no process in it and reads them back: the whole path a limit
     * takes, on nothing (E3). Only ERROR_NOT_SUPPORTED says Dynamic Fair Share Scheduling.
     */
    private Map<LimitKind, LimitCapability> probe() {
        Map<LimitKind, LimitCapability> found = new EnumMap<>(LimitKind.class);
        if (Native.POINTER_SIZE != 8) {
            LimitCapability no = LimitCapability.unavailable("native limits need a 64-bit Java runtime");
            found.put(LimitKind.CPU, no);
            found.put(LimitKind.MEMORY, no);
            return Map.copyOf(found);
        }
        Win32.Created job = win32.createJob(null);
        if (!job.ok()) {
            LimitCapability no = LimitCapability.unavailable(
                    "Job Objects could not be created here: " + win32.describe(job.error()));
            found.put(LimitKind.CPU, no);
            found.put(LimitKind.MEMORY, no);
            return Map.copyOf(found);
        }
        try {
            int cpu = win32.setCpuRate(job.handle(), CAP, 2000);
            Optional<Win32.CpuRate> rate = cpu == 0 ? win32.cpuRate(job.handle()) : Optional.empty();
            if (cpu == WinError.ERROR_NOT_SUPPORTED) {
                found.put(
                        LimitKind.CPU,
                        LimitCapability.unavailable(
                                "CPU rate control is not available on this system (Dynamic Fair Share Scheduling)"));
            } else if (cpu != 0) {
                found.put(
                        LimitKind.CPU,
                        LimitCapability.unavailable("the CPU rate control probe failed: " + win32.describe(cpu)));
            } else if (rate.isEmpty() || rate.get().flags() != CAP || rate.get().rate() != 2000) {
                found.put(
                        LimitKind.CPU,
                        LimitCapability.unavailable("the CPU rate control probe did not read back as set"));
            } else {
                found.put(LimitKind.CPU, LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT));
            }
            int memory = win32.setExtendedLimits(job.handle(), JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY, 1L << 30);
            Optional<Win32.ExtendedLimits> limits = memory == 0 ? win32.extendedLimits(job.handle()) : Optional.empty();
            if (memory != 0) {
                found.put(
                        LimitKind.MEMORY,
                        LimitCapability.unavailable("the memory limit probe failed: " + win32.describe(memory)));
            } else if (limits.isEmpty()
                    || limits.get().limitFlags() != JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY
                    || limits.get().processMemoryLimit() != 1L << 30) {
                found.put(
                        LimitKind.MEMORY,
                        LimitCapability.unavailable("the memory limit probe did not read back as set"));
            } else {
                found.put(
                        LimitKind.MEMORY, LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT));
            }
        } finally {
            // No process was ever in it: the job ends with this handle.
            win32.close(job.handle());
        }
        Map<LimitKind, LimitCapability> result = Map.copyOf(found);
        LOG.info("Native limits: CPU {}, memory {}", result.get(LimitKind.CPU), result.get(LimitKind.MEMORY));
        return result;
    }

    @Override
    public List<String> limitations() {
        List<String> missing = new ArrayList<>();
        capabilities()
                .forEach((kind, capability) -> capability
                        .reason()
                        .ifPresent(reason -> missing.add(
                                "Native " + kind.name().toLowerCase(java.util.Locale.ROOT) + " limits: " + reason)));
        return List.copyOf(missing);
    }

    @Override
    public Optional<Boolean> selfInJob() {
        return win32.selfInJob();
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilitiesFor(ProcessIdentity target) {
        Map<LimitKind, LimitCapability> system = capabilities();
        if (system.values().stream().noneMatch(LimitCapability::available)) {
            return system;
        }
        lock.lock();
        try {
            if (holds.containsKey(target)) {
                return system;
            }
            Optional<String> refusal = refusal(target);
            if (refusal.isEmpty()) {
                return system;
            }
            Map<LimitKind, LimitCapability> found = new EnumMap<>(LimitKind.class);
            system.forEach((kind, capability) ->
                    found.put(kind, capability.available() ? LimitCapability.unavailable(refusal.get()) : capability));
            return Map.copyOf(found);
        } finally {
            lock.unlock();
        }
    }

    /** Why a new job could not be made for the process, read without changing anything; empty if it could. */
    private Optional<String> refusal(ProcessIdentity target) {
        WindowsProcessAccess.Opening opening = access.open(target, ASSIGN_RIGHTS);
        if (opening.failure().isPresent()) {
            ControlOutcome failure = opening.failure().get();
            return Optional.of(
                    switch (failure.result()) {
                        case ACCESS_DENIED -> "the system refused: Prism may not set limits on this process";
                        case PROCESS_NOT_FOUND -> "the process has exited";
                        default ->
                            "Prism could not open the process ("
                                    + failure.detail().orElse("") + ")";
                    });
        }
        try (PinnedProcess process = opening.process().orElseThrow()) {
            Optional<Boolean> inJob = win32.inJob(process.handle(), OptionalLong.empty());
            if (inJob.isEmpty()) {
                return Optional.of("Prism could not tell whether it is in a Job Object ("
                        + win32.describe(win32.lastError()) + ")");
            }
            return inJob.get() ? Optional.of(NOT_HELD_JOB) : Optional.empty();
        }
    }

    @Override
    public Optional<NativeHold> hold(ProcessIdentity target) {
        lock.lock();
        try {
            Hold hold = holds.get(target);
            return hold == null ? Optional.empty() : Optional.of(new NativeHold(hold.state, hold.inForce));
        } finally {
            lock.unlock();
        }
    }

    // ----- The first assignment -----

    @Override
    public ApplyResult apply(ProcessIdentity target, LimitSpec spec) {
        lock.lock();
        try {
            return applyLocked(target, spec);
        } finally {
            lock.unlock();
        }
    }

    private ApplyResult applyLocked(ProcessIdentity target, LimitSpec spec) {
        if (closed) {
            return ApplyResult.notAssigned(ControlOutcome.of(OperationResult.UNSUPPORTED, "Prism is closing"), false);
        }
        if (holds.containsKey(target)) {
            // The caller changes a job it holds; an assignment would make a second one.
            return ApplyResult.notAssigned(
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "Prism holds a job for this process already"),
                    false);
        }
        if (spec.none()) {
            // Membership cannot be undone: a process is never put in a job for no limit.
            return ApplyResult.notAssigned(
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "no limit to apply"), false);
        }
        Optional<String> unavailable = unavailableFor(spec);
        if (unavailable.isPresent()) {
            return ApplyResult.notAssigned(ControlOutcome.of(OperationResult.UNSUPPORTED, unavailable.get()), false);
        }
        // S1: the process, by its identity, with the rights the assignment needs.
        WindowsProcessAccess.Opening opening = access.open(target, ASSIGN_RIGHTS);
        if (opening.failure().isPresent()) {
            ControlOutcome failure = opening.failure().get();
            return ApplyResult.notAssigned(failure, failure.result() != OperationResult.PROCESS_NOT_FOUND);
        }
        try (PinnedProcess process = opening.process().orElseThrow()) {
            // S2: a process in a job Prism does not hold is not assigned (plan D1).
            Optional<Boolean> inJob = win32.inJob(process.handle(), OptionalLong.empty());
            if (inJob.isEmpty()) {
                int error = win32.lastError();
                return ApplyResult.notAssigned(
                        ControlOutcome.of(
                                OperationResult.NATIVE_ERROR, error, "IsProcessInJob failed: " + win32.describe(error)),
                        true);
            }
            if (inJob.get()) {
                return ApplyResult.notAssigned(ControlOutcome.of(OperationResult.UNSUPPORTED, NOT_HELD_JOB), true);
            }
            // S3: a new job of the deterministic name; an existing one of that name is never used.
            Win32.Created created = win32.createJob(JobNames.of(target));
            if (!created.ok()) {
                String why = created.error() == WinError.ERROR_INVALID_HANDLE
                        ? "the job name is taken by another kind of object"
                        : "CreateJobObject failed: " + win32.describe(created.error());
                return ApplyResult.notAssigned(
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, created.error(), why), true);
            }
            if (created.existed()) {
                win32.close(created.handle());
                return ApplyResult.notAssigned(
                        ControlOutcome.of(
                                OperationResult.NATIVE_ERROR,
                                WinError.ERROR_ALREADY_EXISTS,
                                "a job with this name exists already (another Prism instance?)"),
                        true);
            }
            return assign(target, spec, process, created.handle());
        }
    }

    /**
     * S4 to S7 on a job this call created. Before the assignment is attempted the empty job is
     * closed on every failure (it ends with its handle). From the attempt on, the job is in {@link
     * #holds} as uncertain before the call is made, so that nothing thrown, an {@code Error}
     * included, can lose it; it is closed only where the system proves the process is not in it, or
     * after the limits of a process that exited at once were cleared and read back as none.
     */
    private ApplyResult assign(ProcessIdentity target, LimitSpec spec, PinnedProcess process, long job) {
        boolean attempted = false;
        boolean close = true;
        try {
            // S4: the limits on the empty job, read back.
            Optional<ControlOutcome> failed = set(job, spec.effective(), true);
            if (failed.isPresent()) {
                return ApplyResult.notAssigned(failed.get(), true);
            }
            Optional<EffectiveLimits> before = read(job);
            if (before.isEmpty() || !before.get().equals(spec.effective())) {
                // S4b: only what Prism set, nothing that could end a process on assignment.
                return ApplyResult.notAssigned(
                        ControlOutcome.of(
                                OperationResult.NATIVE_ERROR,
                                "the job did not read back as set: "
                                        + before.map(EffectiveLimits::describe).orElse("unreadable")),
                        true);
            }
            // S5
            if (closed) {
                return ApplyResult.notAssigned(
                        ControlOutcome.of(OperationResult.UNSUPPORTED, "Prism is closing"), false);
            }
            Hold hold = new Hold(job, NativeHold.State.UNCERTAIN, Optional.empty());
            holds.put(target, hold);
            close = false;
            attempted = true;
            // S6: irreversible from here.
            int error = win32.assignToJob(job, process.handle());
            Optional<Boolean> member = win32.inJob(process.handle(), OptionalLong.of(job));
            if (error != 0 && member.equals(Optional.of(false))) {
                // Proven not assigned: the job holds no process and ends with this handle.
                holds.remove(target);
                close = true;
                boolean exited = process.exited();
                ControlOutcome outcome = exited
                        ? ControlOutcome.of(
                                OperationResult.PROCESS_NOT_FOUND, error, "the process exited before it was assigned")
                        : ControlOutcome.of(
                                error == WinError.ERROR_ACCESS_DENIED
                                        ? OperationResult.ACCESS_DENIED
                                        : OperationResult.NATIVE_ERROR,
                                error,
                                "AssignProcessToJobObject failed: " + win32.describe(error));
                return ApplyResult.notAssigned(outcome, !exited);
            }
            if (error != 0 || !member.equals(Optional.of(true))) {
                // A failed call with the process in the job, or no answer: the plan counts both uncertain.
                LOG.warn(
                        "The assignment of PID {} to its job could not be told: assign={}, member={}",
                        target.pid(),
                        error,
                        member);
                return new ApplyResult(
                        ApplyResult.Phase.UNCERTAIN,
                        ControlOutcome.of(
                                OperationResult.NATIVE_ERROR,
                                error,
                                "whether the assignment took effect could not be told"),
                        Optional.empty(),
                        false);
            }
            hold.state = NativeHold.State.ASSIGNED;
            // S7: in the job; alive, and holding what was asked?
            if (process.exited()) {
                Optional<EffectiveLimits> left = clearAndRead(job);
                if (left.isPresent() && left.get().none()) {
                    holds.remove(target);
                    close = true;
                    return new ApplyResult(
                            ApplyResult.Phase.ASSIGNED_RELEASED,
                            ControlOutcome.of(
                                    OperationResult.PROCESS_NOT_FOUND, "the process exited as it was assigned"),
                            left,
                            false);
                }
                hold.inForce = left;
                return new ApplyResult(
                        ApplyResult.Phase.ASSIGNED_MISMATCH,
                        ControlOutcome.of(
                                OperationResult.PROCESS_NOT_FOUND,
                                "the process exited; its job still carries limits Prism could not clear"),
                        left,
                        false);
            }
            Optional<EffectiveLimits> observed = read(job);
            hold.inForce = observed;
            if (observed.equals(Optional.of(spec.effective()))) {
                return new ApplyResult(ApplyResult.Phase.ENFORCED, ControlOutcome.success("enforced"), observed, false);
            }
            return new ApplyResult(
                    ApplyResult.Phase.ASSIGNED_MISMATCH,
                    ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            "assigned, but the system holds "
                                    + observed.map(EffectiveLimits::describe).orElse("limits it could not read")),
                    observed,
                    false);
        } catch (RuntimeException unexpected) {
            if (!attempted) {
                return ApplyResult.notAssigned(
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, unexpected.toString()), false);
            }
            Hold hold = holds.get(target);
            if (hold != null) {
                hold.state = NativeHold.State.UNCERTAIN;
                hold.inForce = Optional.empty();
            }
            LOG.warn("The assignment of PID {} ended unexpectedly; its job is kept", target.pid(), unexpected);
            return new ApplyResult(
                    ApplyResult.Phase.UNCERTAIN,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, unexpected.toString()),
                    Optional.empty(),
                    false);
        } finally {
            if (close) {
                // Never attempted, or provably not assigned: the job ends with this handle.
                win32.close(job);
            }
        }
    }

    // ----- Changes of a job the limiter holds -----

    @Override
    public ChangeResult change(ProcessIdentity target, LimitSpec spec) {
        lock.lock();
        try {
            return changeLocked(target, spec);
        } finally {
            lock.unlock();
        }
    }

    private ChangeResult changeLocked(ProcessIdentity target, LimitSpec spec) {
        Hold hold = holds.get(target);
        if (hold == null) {
            return new ChangeResult(
                    ChangeResult.Phase.NO_HOLD,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "Prism holds no job for this process"),
                    Optional.empty());
        }
        if (closed) {
            return notDone(OperationResult.UNSUPPORTED, "Prism is closing");
        }
        Optional<String> unavailable = unavailableFor(spec);
        if (unavailable.isPresent()) {
            return notDone(OperationResult.UNSUPPORTED, unavailable.get());
        }
        // C2: the process by its identity; an exited one is let go through forget.
        WindowsProcessAccess.Opening opening = access.open(target, 0);
        if (opening.failure()
                .filter(f -> f.result() == OperationResult.PROCESS_NOT_FOUND)
                .isPresent()) {
            return notDone(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
        }
        try {
            if (hold.state == NativeHold.State.UNCERTAIN) {
                Optional<ChangeResult> resolved = resolve(target, hold, opening);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
            // C3: what the job holds now; nothing is set unless it can be read first.
            boolean setSomething = false;
            int firstError = 0;
            try {
                Optional<EffectiveLimits> before = read(hold.job);
                if (before.isEmpty()) {
                    return notDone(OperationResult.NATIVE_ERROR, "the limits could not be read; nothing was changed");
                }
                EffectiveLimits wanted = spec.effective();
                EffectiveLimits from = before.get();
                // C4, C5: each kind by its own call, only where it changes.
                if (!from.cpuRate().equals(wanted.cpuRate()) || from.foreign().isPresent()) {
                    setSomething = true;
                    int error = setCpu(hold.job, wanted.cpuRate());
                    firstError = firstError == 0 ? error : firstError;
                }
                if (!from.memoryBytes().equals(wanted.memoryBytes())
                        || from.foreign().isPresent()) {
                    setSomething = true;
                    int error = setMemory(hold.job, wanted.memoryBytes());
                    firstError = firstError == 0 ? error : firstError;
                }
                // C6
                Optional<EffectiveLimits> after = setSomething ? read(hold.job) : before;
                if (after.isEmpty()) {
                    hold.inForce = Optional.empty();
                    return new ChangeResult(
                            ChangeResult.Phase.UNVERIFIED,
                            changeFailure(firstError, "the limits could not be read back"),
                            Optional.empty());
                }
                hold.inForce = after;
                if (after.get().equals(wanted)) {
                    return new ChangeResult(ChangeResult.Phase.APPLIED, ControlOutcome.success("changed"), after);
                }
                return new ChangeResult(
                        ChangeResult.Phase.PARTIAL,
                        changeFailure(
                                firstError, "the system holds " + after.get().describe()),
                        after);
            } catch (RuntimeException unexpected) {
                LOG.warn("A change of the limits of PID {} ended unexpectedly", target.pid(), unexpected);
                if (!setSomething) {
                    return notDone(OperationResult.NATIVE_ERROR, unexpected.toString());
                }
                hold.inForce = Optional.empty();
                return new ChangeResult(
                        ChangeResult.Phase.UNVERIFIED,
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, unexpected.toString()),
                        Optional.empty());
            }
        } finally {
            opening.process().ifPresent(PinnedProcess::close);
        }
    }

    /**
     * An uncertain assignment asked again with the process open: in the job, it becomes an assigned
     * one; provably not in it, the job is let go (it holds no process) and NO_HOLD says so; when
     * nothing can be told, nothing changes.
     */
    private Optional<ChangeResult> resolve(ProcessIdentity target, Hold hold, WindowsProcessAccess.Opening opening) {
        if (opening.process().isEmpty()) {
            return Optional.of(notDone(OperationResult.NATIVE_ERROR, "an uncertain assignment could not be checked"));
        }
        Optional<Boolean> member = win32.inJob(opening.process().get().handle(), OptionalLong.of(hold.job));
        if (member.equals(Optional.of(true))) {
            hold.state = NativeHold.State.ASSIGNED;
            hold.inForce = read(hold.job);
            return Optional.empty();
        }
        if (member.equals(Optional.of(false))) {
            holds.remove(target);
            win32.close(hold.job);
            return Optional.of(new ChangeResult(
                    ChangeResult.Phase.NO_HOLD,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "the earlier assignment did not take effect"),
                    Optional.empty()));
        }
        return Optional.of(notDone(OperationResult.NATIVE_ERROR, "an uncertain assignment could not be checked"));
    }

    /** The outcome of a change that did not end as asked, with the code of the first call that failed. */
    private ControlOutcome changeFailure(int error, String detail) {
        if (error == 0) {
            return ControlOutcome.of(OperationResult.NATIVE_ERROR, detail);
        }
        ControlOutcome cause = error == WinError.ERROR_NOT_SUPPORTED
                ? cpuFailure(error)
                : ControlOutcome.of(OperationResult.NATIVE_ERROR, error, win32.describe(error));
        return ControlOutcome.of(
                cause.result(), error, detail + " (" + cause.detail().orElse("") + ")");
    }

    private static ChangeResult notDone(OperationResult result, String detail) {
        return new ChangeResult(ChangeResult.Phase.NOT_DONE, ControlOutcome.of(result, detail), Optional.empty());
    }

    // ----- A process that exited -----

    @Override
    public ForgetResult forget(ProcessIdentity target) {
        lock.lock();
        try {
            Hold hold = holds.get(target);
            if (hold == null) {
                return new ForgetResult(ForgetResult.Outcome.NOT_HELD, ControlOutcome.success(), 0, Optional.empty());
            }
            WindowsProcessAccess.Opening opening = access.open(target, 0);
            if (opening.process().isPresent()) {
                opening.process().get().close();
                return new ForgetResult(
                        ForgetResult.Outcome.STILL_RUNS,
                        ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "the process still runs"),
                        -1,
                        hold.inForce);
            }
            if (opening.failure()
                    .filter(f -> f.result() == OperationResult.PROCESS_NOT_FOUND)
                    .isEmpty()) {
                // Not shown to be gone (refused, unreadable): its limits are never let go on a guess.
                return new ForgetResult(
                        ForgetResult.Outcome.STILL_RUNS, opening.failure().orElseThrow(), -1, hold.inForce);
            }
            return forgetLocked(target, hold);
        } finally {
            lock.unlock();
        }
    }

    /** F0 to F2 of plan §8.6: the job is let go only once nothing it enforces is left. */
    private ForgetResult forgetLocked(ProcessIdentity target, Hold hold) {
        OptionalInt active = win32.activeProcesses(hold.job);
        if (active.isPresent() && active.getAsInt() == 0) {
            release(target, hold);
            return new ForgetResult(
                    ForgetResult.Outcome.NOTHING_TO_RELEASE, ControlOutcome.success(), 0, Optional.empty());
        }
        if (hold.state == NativeHold.State.UNCERTAIN && active.isEmpty()) {
            return new ForgetResult(
                    ForgetResult.Outcome.UNRESOLVED,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "the job could not be read"),
                    -1,
                    Optional.empty());
        }
        int survivors = active.orElse(-1);
        Optional<EffectiveLimits> left = clearAndRead(hold.job);
        if (left.isPresent() && left.get().none()) {
            release(target, hold);
            if (survivors != 0) {
                LOG.info(
                        "The job of exited PID {} held {} process(es); Prism cleared its limits",
                        target.pid(),
                        survivors);
            }
            return new ForgetResult(ForgetResult.Outcome.RELEASED, ControlOutcome.success(), survivors, left);
        }
        hold.state = NativeHold.State.ASSIGNED;
        hold.inForce = left;
        return new ForgetResult(
                ForgetResult.Outcome.RESIDUE,
                ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        "the job still carries "
                                + left.map(EffectiveLimits::describe).orElse("limits that could not be read")),
                survivors,
                left);
    }

    private void release(ProcessIdentity target, Hold hold) {
        holds.remove(target);
        win32.close(hold.job);
    }

    // ----- Recovery of an earlier session -----

    @Override
    public RecoveredLimit recover(LimitRecord record) {
        lock.lock();
        try {
            if (holds.containsKey(record.identity())) {
                // This session assigned the process to its own job, which it does only after the system
                // said the process was in no job: the earlier limit never took effect. The job of that
                // name is this session's and is left alone.
                return recovered(
                        record,
                        Verdict.NOT_IN_JOB,
                        "this session holds the job of the process; the earlier limit never took effect");
            }
            return recoverLocked(record);
        } finally {
            lock.unlock();
        }
    }

    private RecoveredLimit recoverLocked(LimitRecord record) {
        ProcessIdentity identity = record.identity();
        Win32.Opened job = win32.openJob(REOPEN_RIGHTS, JobNames.of(identity));
        if (!job.ok()) {
            if (job.error() == WinError.ERROR_ACCESS_DENIED) {
                return recovered(record, Verdict.ACCESS_DENIED, "the job of an earlier session refused access");
            }
            if (job.error() != WinError.ERROR_FILE_NOT_FOUND) {
                return recovered(record, Verdict.FAILED, "OpenJobObject failed: " + win32.describe(job.error()));
            }
            return unreachable(record);
        }
        try {
            return reopened(record, job.handle());
        } finally {
            win32.close(job.handle());
        }
    }

    /** The name is gone, as expected once the earlier session's handles are: what can be told from the process. */
    private RecoveredLimit unreachable(LimitRecord record) {
        WindowsProcessAccess.Opening opening = access.open(record.identity(), 0);
        if (opening.failure().isPresent()) {
            ControlOutcome failure = opening.failure().get();
            if (failure.result() == OperationResult.PROCESS_NOT_FOUND) {
                return record.state() == LedgerState.CLEARED
                        ? recovered(record, Verdict.GONE, "the process exited; its limits had been cleared")
                        : recovered(
                                record,
                                Verdict.GONE_CHILDREN_UNKNOWN,
                                "the process exited; Prism cannot prove that its limit never took effect");
            }
            return record.state() == LedgerState.CLEARED
                    ? recovered(record, Verdict.NOTHING_TO_CLEAR, "its limits had been cleared")
                    : recovered(record, Verdict.UNREACHABLE, "the process could not be opened to check it");
        }
        try (PinnedProcess process = opening.process().orElseThrow()) {
            Optional<Boolean> inJob = win32.inJob(process.handle(), OptionalLong.empty());
            if (inJob.equals(Optional.of(false))) {
                return recovered(record, Verdict.NOT_IN_JOB, "the process is in no job: the limit never took effect");
            }
            if (record.state() == LedgerState.CLEARED) {
                return recovered(record, Verdict.NOTHING_TO_CLEAR, "its limits had been cleared; it stays in that job");
            }
            return recovered(record, Verdict.UNREACHABLE, "the job of an earlier session cannot be reached");
        }
    }

    /**
     * The job was opened by name, for reading only: some running program holds it. Its values and
     * the process's membership are told; nothing is changed (audit of the recovery trust boundary, C).
     */
    private RecoveredLimit reopened(LimitRecord record, long job) {
        Optional<EffectiveLimits> observed = read(job);
        if (observed.isEmpty()) {
            return recovered(record, Verdict.FAILED, "the job could not be read");
        }
        if (!observed.get().none() && !record.admits(observed.get())) {
            return recovered(
                    record,
                    Verdict.VALUES_MISMATCH,
                    "a job of Prism's name holds " + observed.get().describe());
        }
        WindowsProcessAccess.Opening opening = access.open(record.identity(), 0);
        if (opening.process().isPresent()) {
            try (PinnedProcess process = opening.process().get()) {
                Optional<Boolean> member = win32.inJob(process.handle(), OptionalLong.of(job));
                if (member.isEmpty()) {
                    return recovered(record, Verdict.FAILED, "whether the process is in the job could not be read");
                }
                if (!member.get()) {
                    Optional<Boolean> any = win32.inJob(process.handle(), OptionalLong.empty());
                    if (any.isEmpty()) {
                        return recovered(record, Verdict.FAILED, "whether the process is in a job could not be read");
                    }
                    return any.get()
                            ? recovered(
                                    record,
                                    Verdict.UNREACHABLE,
                                    "the process is in another job, which Prism cannot reach")
                            : recovered(
                                    record,
                                    Verdict.NOT_IN_JOB,
                                    "the process is in no job: the limit never took effect");
                }
            }
        } else if (opening.failure()
                .filter(f -> f.result() == OperationResult.PROCESS_NOT_FOUND)
                .isEmpty()) {
            return recovered(record, Verdict.FAILED, "the process could not be checked");
        }
        if (opening.process().isEmpty()) {
            // The process is gone; processes it started may be in the job, which a running program
            // holds and may limit. Nothing names them to block (audit review).
            return recovered(
                    record,
                    Verdict.GONE_CHILDREN_UNKNOWN,
                    "the process exited; a running program holds its job ("
                            + observed.get().describe() + "), so the processes it started may be limited");
        }
        // The name lives only while a handle is open, so a running program holds this job: perhaps a
        // Prism session that still runs. Recovery cannot prove it has ended and changes nothing, and
        // the holder may set a limit at any moment, even when none is set now.
        return recovered(
                record,
                Verdict.HELD_ELSEWHERE,
                "a running program holds this job (" + observed.get().describe() + "); Prism changes nothing");
    }

    private static RecoveredLimit recovered(LimitRecord record, Verdict verdict, String detail) {
        return new RecoveredLimit(record, verdict, detail);
    }

    // ----- Shutdown -----

    @Override
    public ReleaseResult releaseAll(Duration within) {
        closed = true;
        // An interrupt does not cut the clearing short: a cap left now lasts until its process
        // exits, and its name goes with the handles (native-api review, Stage 4).
        boolean locked = lock.tryLock();
        boolean interrupted = false;
        long deadline = System.nanoTime() + within.toNanos();
        while (!locked && System.nanoTime() < deadline) {
            try {
                locked = lock.tryLock(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            // Never an empty answer: the holds known now are not cleared, and an operation in flight
            // may yet make one more (the flag above refuses any assignment after it).
            Set<ProcessIdentity> known = Set.copyOf(holds.keySet());
            LOG.warn(
                    "Native limits were not cleared: the limiter was busy past the bound; {} job(s) known held,"
                            + " possibly more",
                    known.size());
            return ReleaseResult.timedOut(known);
        }
        try {
            Map<ProcessIdentity, Boolean> cleared = new HashMap<>();
            for (Map.Entry<ProcessIdentity, Hold> entry : List.copyOf(holds.entrySet())) {
                Hold hold = entry.getValue();
                boolean none = false;
                try {
                    none = clearAndRead(hold.job).filter(EffectiveLimits::none).isPresent();
                } catch (RuntimeException unexpected) {
                    LOG.warn(
                            "The limits of PID {} could not be cleared",
                            entry.getKey().pid(),
                            unexpected);
                } finally {
                    holds.remove(entry.getKey());
                    win32.close(hold.job);
                }
                cleared.put(entry.getKey(), none);
            }
            return new ReleaseResult(cleared, Set.of(), false);
        } finally {
            lock.unlock();
        }
    }

    // ----- The calls on a job -----

    private Optional<String> unavailableFor(LimitSpec spec) {
        if (spec.memoryBytes().isPresent() && spec.memoryBoundary() != MemoryBoundary.HARD) {
            // Job Objects limit committed memory, a hard boundary; there is no throttling boundary (milestone 12).
            return Optional.of("a throttling memory limit is not available with Job Objects");
        }
        Map<LimitKind, LimitCapability> system = capabilities();
        if (spec.cpuPercent().isPresent() && !system.get(LimitKind.CPU).available()) {
            return system.get(LimitKind.CPU).reason();
        }
        if (spec.memoryBytes().isPresent() && !system.get(LimitKind.MEMORY).available()) {
            return system.get(LimitKind.MEMORY).reason();
        }
        return Optional.empty();
    }

    /** Sets the limits of a new job, kind by kind; the first failure, if any. */
    private Optional<ControlOutcome> set(long job, EffectiveLimits limits, boolean onlyPresent) {
        if (limits.cpuRate().isPresent() || !onlyPresent) {
            int error = setCpu(job, limits.cpuRate());
            if (error != 0) {
                return Optional.of(cpuFailure(error));
            }
        }
        if (limits.memoryBytes().isPresent() || !onlyPresent) {
            int error = setMemory(job, limits.memoryBytes());
            if (error != 0) {
                return Optional.of(ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        error,
                        "the memory limit could not be set: " + win32.describe(error)));
            }
        }
        return Optional.empty();
    }

    private ControlOutcome cpuFailure(int error) {
        if (error == WinError.ERROR_NOT_SUPPORTED) {
            return ControlOutcome.of(
                    OperationResult.UNSUPPORTED,
                    error,
                    "CPU rate control is not available on this system (Dynamic Fair Share Scheduling)");
        }
        return ControlOutcome.of(
                OperationResult.NATIVE_ERROR, error, "the CPU limit could not be set: " + win32.describe(error));
    }

    /**
     * A rate, or none. Clearing with ControlFlags 0 is not documented by Microsoft Learn; it was
     * measured in milestone 10 (E0: accepted, read back as 0/0), and every clearing is verified by
     * reading it back.
     */
    private int setCpu(long job, OptionalInt rate) {
        return rate.isPresent() ? win32.setCpuRate(job, CAP, rate.getAsInt()) : win32.setCpuRate(job, 0, 0);
    }

    private int setMemory(long job, OptionalLong bytes) {
        return bytes.isPresent()
                ? win32.setExtendedLimits(job, JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY, bytes.getAsLong())
                : win32.setExtendedLimits(job, 0, 0);
    }

    /** Clears both kinds, each by its own call, and reads the job back. */
    private Optional<EffectiveLimits> clearAndRead(long job) {
        int cpu = setCpu(job, OptionalInt.empty());
        int memory = setMemory(job, OptionalLong.empty());
        if (cpu != 0 || memory != 0) {
            LOG.warn("Clearing a job's limits: CPU {}, memory {}", cpu, memory);
        }
        return read(job);
    }

    /**
     * What the job holds, kind by kind, in the terms of {@link EffectiveLimits}: a rate only for
     * exactly ENABLE | HARD_CAP, a memory limit only for exactly the process-memory flag; anything
     * else is foreign and never Prism's.
     */
    Optional<EffectiveLimits> read(long job) {
        Optional<Win32.CpuRate> rate = win32.cpuRate(job);
        Optional<Win32.ExtendedLimits> limits = win32.extendedLimits(job);
        if (rate.isEmpty() || limits.isEmpty()) {
            return Optional.empty();
        }
        List<String> foreign = new ArrayList<>();
        OptionalInt cpu = OptionalInt.empty();
        int flags = rate.get().flags();
        if (flags == CAP && rate.get().rate() >= 1 && rate.get().rate() <= 10_000) {
            cpu = OptionalInt.of(rate.get().rate());
        } else if (flags != 0) {
            foreign.add("CPU rate control 0x" + Integer.toHexString(flags) + "/"
                    + rate.get().rate());
        }
        OptionalLong memory = OptionalLong.empty();
        int limitFlags = limits.get().limitFlags();
        if ((limitFlags & JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY) != 0) {
            memory = OptionalLong.of(limits.get().processMemoryLimit());
        }
        int other = limitFlags & ~JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY;
        if (other != 0) {
            foreign.add("limit flags 0x" + Integer.toHexString(other));
        }
        return Optional.of(new EffectiveLimits(
                cpu, memory, foreign.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", foreign))));
    }
}
