package prism.srm.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.application.ControlMessages.Wording;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.FallbackOffer;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitReport;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsNotice;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.NativeConsentOffer;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.Residue;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ProcessController;
import prism.srm.platform.ProcessSource;
import prism.srm.platform.ReleaseResult;
import prism.srm.platform.ResourceLimiter;
import prism.srm.platform.UnavailableResourceLimiter;

/**
 * Managed processes and their CPU and memory limits (MGD-1 to MGD-4, LIM-1 to LIM-5, LIM-8, UI-4).
 *
 * <p><b>Thread.</b> Every request, every step of every limit and every look at the journal runs
 * on the limiter thread ({@link LimiterScheduler}), in order; the registry and the limits need no
 * lock. {@link #request}, {@link #listed}, {@link #journalFailed} and the shutdown calls may come
 * from any thread. Results and snapshots are given to the listeners on the limiter thread, except a
 * request refused at once (shutting down, too many waiting), which is answered on the caller's
 * thread; listeners must return quickly and never call back into the service.
 *
 * <p><b>Native operations (risk D6).</b> A native assignment, change or forget can block for
 * seconds (a Linux adoption waits for the systemd user manager), and the limiter thread also runs
 * the emulated steps and OOM Guard. So the native part alone runs on the native-limit worker
 * ({@code srm-native-limit} in the product), one operation at a time in the order handed over, and
 * its answer comes back to the limiter thread, which keeps all of this service's state. Everything
 * before the native part (the checks, the journal and the ledger's health) and after it (what the
 * answer means, the result) stays on the limiter thread. While an operation for a process is in
 * flight, whatever else comes for that process (a request, its exit, a run that ended with it) waits
 * for it in order, and its requests stay counted among the pending ones; requests for other
 * processes are carried out meanwhile. The Limits page shows what was held before the operation,
 * and an emulated limit treats the process as possibly capped. OOM Guard's watch of resident memory
 * stays as it was before the operation, neither dropped nor armed again; where the operation may set
 * a native memory limit it is paused, acting on nothing until the operation has ended. Once a step
 * that may set a CPU cap has ended, the barrier against OOM Guard's hold and an incomplete release is
 * checked again, and a cap set meanwhile is withdrawn (review S2); a first assignment checks the
 * journal again on the worker, right before it is recorded and asked. At shutdown an operation not
 * yet handed to the worker is never asked, and its request is answered as refused.
 *
 * <p><b>Which limit (LIM-3, milestone 10).</b> Where the system offers native limits (Job Objects on
 * Windows) a CPU limit is native first. The emulated duty cycle runs instead only where the system
 * offers no native CPU limit (unchanged from milestone 9, plan D11), or where the native one was
 * not applied to this process and the user accepted the emulated one, which states its cost and
 * passes the admission rule of ADR-0011. A memory limit is native or not at all in this version.
 *
 * <p><b>One controller at a time.</b> An emulated limit never runs while a native CPU cap is, or may
 * be, in force on the process: an assignment that could not be told, a change that could not be
 * read back, limits read back otherwise than asked, a job an earlier session left. And a native CPU
 * cap never starts while the emulated limit still holds threads it could not release (plan D15).
 *
 * <p><b>The journal first</b> (ADR-0011): an emulated limit starts only once its entry is in the
 * journal. A native limit starts only once its record is in the native-limit ledger (ADR-0012).
 */
public final class LimitService {

    private static final Logger LOG = LoggerFactory.getLogger(LimitService.class);

    /** The most limits that run at once: each stops and continues its process several times a second. */
    public static final int MOST_LIMITS = 16;

    /**
     * The most limits that run at once where a suspension goes thread by thread (Windows), on a
     * machine of many logical processors: each lists the threads of the whole system for every
     * suspension, about a tenth of a logical processor while it holds its process back (ADR-0011,
     * the Windows performance exception). The number bounds how many run, not what they cost.
     */
    public static final int MOST_PER_THREAD_LIMITS = 4;

    private static final String CONTROL_PREFIX = "Process control: ";

    /** The most requests that wait for the limiter thread; more are answered at once. */
    public static final int MOST_PENDING = 32;

    private final ProcessController controller;
    private final ProtectionRules rules;
    private final SuspensionCoordinator coordinator;
    private final ProcessSource source;
    private final ActivityLog activity;
    private final LimiterScheduler scheduler;
    private final LongSupplier nanoClock;
    private final int processors;
    private final DutyCycleController.Tuning tuning;
    private final NativeLimitController natives;
    /** OOM Guard, which this service tells about the managed processes (milestone 11); empty in tests of earlier milestones. */
    private final Optional<OomGuard> guard;
    /** Runs the native part of every native operation, one at a time (risk D6); inline in tests of earlier milestones. */
    private final Executor nativeWork;

    private final Consumer<LimitResult> results;
    private final Consumer<LimitsSnapshot> snapshots;

    // The limiter thread only.
    private final ManagedProcessRegistry registry = new ManagedProcessRegistry();
    /** Why the CPU limit of a process ended or was not applied, until the user changes its settings. */
    private final Map<ProcessIdentity, String> failures = new HashMap<>();
    /** Why the memory limit of a process was not applied, or is not what was asked. */
    private final Map<ProcessIdentity, String> memoryFailures = new HashMap<>();
    /** The explanation that applies to a CPU failure. */
    private final Map<ProcessIdentity, LimitNote> failureNotes = new HashMap<>();
    /** Processes for which the emulated CPU limit is offered and waits for the user. */
    private final Set<ProcessIdentity> offered = new HashSet<>();
    /** How each kind could be provided per process, as last probed. */
    private final Map<ProcessIdentity, Map<LimitKind, LimitCapability>> probed = new HashMap<>();
    /** Processes an earlier session may still cap natively: no second limiter for them. */
    private final Map<ProcessIdentity, RecoveredLimit> priorSession = new LinkedHashMap<>();
    /** Jobs of exited processes that still carry limits PRISM could not clear. */
    private final Map<ProcessIdentity, Residue> residues = new LinkedHashMap<>();
    /** Processes no longer managed whose job PRISM still holds, until they exit. */
    private final Map<ProcessIdentity, String> unmanagedHolds = new LinkedHashMap<>();
    /** Why the native memory limit was not applied, where PRISM offers to watch the resident memory instead (O9). */
    private final Map<ProcessIdentity, String> residentOffered = new HashMap<>();
    /** Processes whose user accepted the watched resident memory after the native memory limit failed. */
    private final Set<ProcessIdentity> residentApproved = new HashSet<>();
    /** One more each time the user sets a watched memory limit again: a new watch for OOM Guard (LIM-9). */
    private final Map<ProcessIdentity, Long> watchGenerations = new HashMap<>();
    /** The native operation in flight, or waiting for the worker, of each process: at most one per process (D6). */
    private final Map<ProcessIdentity, InFlight> inFlight = new HashMap<>();
    /** The native operations waiting for the worker, oldest first. */
    private final ArrayDeque<InFlight> nativeQueue = new ArrayDeque<>();
    /** Whether the worker has an operation of this service, handed over and not yet concluded. */
    private boolean nativeBusy;

    /** Written on the limiter thread, cancelled from the shutdown's. */
    private volatile Future<?> watch;

    // Any thread.
    private final Map<ProcessIdentity, LimitRun> runs = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean exitCheckPending = new AtomicBoolean();
    private final AtomicInteger watched = new AtomicInteger();
    private final FailureLog failuresLogged = new FailureLog(LOG);
    private volatile boolean refusing;
    private volatile LimitsSnapshot latest = LimitsSnapshot.EMPTY;
    private volatile Optional<Boolean> selfInJob = Optional.empty();

    /**
     * Without native limits, as in milestone 9.
     *
     * @param processors the logical processors the CPU shares are measured against, as the process
     *     table measures them (PROC-2)
     * @param results receives one result for every request, on the limiter thread
     * @param snapshots receives what the Limits page shows whenever it changes, on the limiter thread
     */
    public LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                DutyCycleController.Tuning.DEFAULTS,
                new NativeLimitController(UnavailableResourceLimiter.here(), NativeLimitLedger.none()),
                results,
                snapshots);
    }

    /**
     * With the native limiter of the system and the ledger of this session (milestone 10).
     */
    public LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            ResourceLimiter limiter,
            NativeLimitLedger ledger,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                limiter,
                ledger,
                EarlierLimits.NONE,
                results,
                snapshots);
    }

    /**
     * With what recovery knows of earlier sessions' native limits too (audit item 2): no emulated
     * limit over a native cap another session may have left.
     */
    public LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            ResourceLimiter limiter,
            NativeLimitLedger ledger,
            EarlierLimits earlier,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                DutyCycleController.Tuning.DEFAULTS,
                new NativeLimitController(limiter, ledger, earlier),
                Optional.empty(),
                results,
                snapshots);
    }

    /**
     * With OOM Guard too (milestone 11): the service tells it the managed processes and the memory
     * limits it watches, on the limiter thread, and never the other way round.
     */
    public LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            ResourceLimiter limiter,
            NativeLimitLedger ledger,
            EarlierLimits earlier,
            OomGuard guard,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                limiter,
                ledger,
                earlier,
                guard,
                Runnable::run,
                results,
                snapshots);
    }

    /**
     * As the product runs it (risk D6): the native part of every native operation on {@code nativeWork}, a worker of
     * its own, so that an operation that blocks never holds up the limiter thread.
     *
     * @param nativeWork runs one native operation at a time, handed over in order; it may refuse only once the
     *     service is shutting down
     */
    public LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            ResourceLimiter limiter,
            NativeLimitLedger ledger,
            EarlierLimits earlier,
            OomGuard guard,
            Executor nativeWork,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                DutyCycleController.Tuning.DEFAULTS,
                new NativeLimitController(limiter, ledger, earlier),
                Optional.of(guard),
                nativeWork,
                results,
                snapshots);
    }

    LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            DutyCycleController.Tuning tuning,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                tuning,
                new NativeLimitController(UnavailableResourceLimiter.here(), NativeLimitLedger.none()),
                Optional.empty(),
                results,
                snapshots);
    }

    LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            DutyCycleController.Tuning tuning,
            NativeLimitController natives,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                tuning,
                natives,
                Optional.empty(),
                results,
                snapshots);
    }

    LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            DutyCycleController.Tuning tuning,
            NativeLimitController natives,
            Optional<OomGuard> guard,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this(
                controller,
                rules,
                coordinator,
                source,
                activity,
                scheduler,
                nanoClock,
                processors,
                tuning,
                natives,
                guard,
                Runnable::run,
                results,
                snapshots);
    }

    LimitService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ProcessSource source,
            ActivityLog activity,
            LimiterScheduler scheduler,
            LongSupplier nanoClock,
            int processors,
            DutyCycleController.Tuning tuning,
            NativeLimitController natives,
            Optional<OomGuard> guard,
            Executor nativeWork,
            Consumer<LimitResult> results,
            Consumer<LimitsSnapshot> snapshots) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.source = Objects.requireNonNull(source, "source");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (processors < 1) {
            throw new IllegalArgumentException("At least one logical processor: " + processors);
        }
        this.processors = processors;
        this.tuning = Objects.requireNonNull(tuning, "tuning");
        this.natives = Objects.requireNonNull(natives, "natives");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.nativeWork = Objects.requireNonNull(nativeWork, "nativeWork");
        this.results = Objects.requireNonNull(results, "results");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /**
     * What a CPU limit is on this system (LIM-3, CAP-2): native where the system offers a native CPU
     * limit; otherwise the emulated duty cycle where PRISM can suspend and resume processes;
     * otherwise unavailable with the reason.
     */
    public LimitCapability cpuCapability() {
        return nativeCpuSystem() ? nativeCapability(LimitKind.CPU) : emulatedCapability();
    }

    /**
     * What a memory limit is on this system: native where the system offers one; otherwise the resident
     * memory OOM Guard watches (LIM-9), where its readings are validated; otherwise unavailable with
     * the reason (CAP-2, CAP-3).
     */
    public LimitCapability memoryCapability() {
        LimitCapability own = nativeMemoryCapability();
        if (own.available()) {
            return own;
        }
        if (policyAvailable()) {
            return LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.RESIDENT_POLICY);
        }
        return guard.isPresent() ? LimitCapability.unavailable(NOT_VALIDATED) : own;
    }

    /** Why a watched memory limit is not offered on a system whose memory readings are not validated (O17b). */
    static final String NOT_VALIDATED = "not available on this system: memory signals are not validated";

    private LimitCapability nativeMemoryCapability() {
        return natives.limiter().capabilities().get(LimitKind.MEMORY);
    }

    /** Whether OOM Guard can watch resident memory here (LIM-9, plan O17b). */
    private boolean policyAvailable() {
        return guard.map(oom -> oom.signal() == OomSignalStatus.VALIDATED).orElse(false);
    }

    /**
     * What the system's limiter reports for a kind; where it offers none of that kind but holds a
     * job for the other, the mechanism of that job (milestone 10, CAP-1: from the limiter, never
     * from the system's name).
     */
    private LimitCapability nativeCapability(LimitKind kind) {
        Map<LimitKind, LimitCapability> reported = natives.limiter().capabilities();
        LimitCapability own = reported.get(kind);
        if (own != null && own.available()) {
            return own;
        }
        return reported.values().stream()
                .filter(LimitCapability::available)
                .findFirst()
                .orElse(own);
    }

    /** What holds this system's native limits, in a word: "scope" on Linux, "job" with Job Objects (risk D8). */
    private String holder() {
        return LimitMessages.holder(nativeCapability(LimitKind.CPU));
    }

    private boolean nativeCpuSystem() {
        return natives.limiter().capabilities().get(LimitKind.CPU).available();
    }

    private boolean nativeMemorySystem() {
        return nativeMemoryCapability().available();
    }

    /** The emulated duty cycle where PRISM can suspend and resume processes (milestone 9). */
    private LimitCapability emulatedCapability() {
        Map<ControlAction, Capability> table = controller.capabilities();
        Capability suspend = table.getOrDefault(ControlAction.SUSPEND, Capability.UNAVAILABLE);
        Capability resume = table.getOrDefault(ControlAction.RESUME, Capability.UNAVAILABLE);
        if (suspend.available() && resume.available()) {
            return LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.DUTY_CYCLE);
        }
        // A limitation says what it is about ("Process control: …"); here the sentence says that already.
        List<String> limitations = controller.limitations().stream()
                .map(text -> text.startsWith(CONTROL_PREFIX) ? text.substring(CONTROL_PREFIX.length()) : text)
                .toList();
        return LimitCapability.unavailable(
                limitations.isEmpty()
                        ? "PRISM cannot suspend and resume processes on this system"
                        : String.join("; ", limitations));
    }

    /** What the Limits page shows now. Any thread. */
    public LimitsSnapshot snapshot() {
        return latest;
    }

    /**
     * How many CPU limits may run at once with a suspension of this mechanism on a machine of this
     * many logical processors: with per-thread suspension one per four logical processors, at
     * least one and at most {@link #MOST_PER_THREAD_LIMITS}; with any other mechanism
     * {@link #MOST_LIMITS}. A disabled limit runs nothing and takes no place.
     */
    static int mostActiveLimits(Optional<ControlMechanism> suspension, int processors) {
        if (suspension.filter(ControlMechanism.PER_THREAD_SUSPENSION::equals).isPresent()) {
            return Math.max(1, Math.min(MOST_PER_THREAD_LIMITS, processors / 4));
        }
        return MOST_LIMITS;
    }

    /** How many CPU limits may run at once here. Any thread. */
    public int mostActiveLimits() {
        return mostActiveLimits(suspensionMechanism(), processors);
    }

    private Optional<ControlMechanism> suspensionMechanism() {
        return controller
                .capabilities()
                .getOrDefault(ControlAction.SUSPEND, Capability.UNAVAILABLE)
                .mechanism();
    }

    private boolean perThreadSuspension() {
        return suspensionMechanism()
                .filter(ControlMechanism.PER_THREAD_SUSPENSION::equals)
                .isPresent();
    }

    /**
     * Refuses an emulated limit that would run beside as many as may run at once; empty when it may
     * start. The one rule of every way an emulated limit starts, on the limiter thread, which takes
     * the requests one at a time: the count cannot change between the check and the start. A
     * refusal changes nothing. Native limits run nothing and take no place.
     */
    private Optional<LimitResult> admission(LimitRequest request) {
        int most = mostActiveLimits();
        if (runs.containsKey(request.identity()) || runs.size() < most) {
            return Optional.empty();
        }
        return Optional.of(answer(
                request,
                OperationResult.INVALID_ARGUMENT,
                "at most " + most + " limits at once",
                perThreadSuspension()
                        ? LimitMessages.tooManyPerThreadLimits(request, most, processors)
                        : LimitMessages.tooManyLimits(request, most)));
    }

    /**
     * Gives the listener the limits as they are, on the limiter thread: what a CPU limit is on this
     * system, before anything is managed. Any thread; returns at once.
     */
    public void announce() {
        try {
            scheduler.execute(() -> {
                selfInJob = natives.limiter().selfInJob();
                if (selfInJob.orElse(false)) {
                    activity.record(
                            ActivitySeverity.INFO,
                            "PRISM runs inside a Job Object; processes started by the same program cannot be limited"
                                    + " natively.");
                }
                publish();
            });
        } catch (RejectedExecutionException shutDown) {
            // Nothing to show any more.
        }
    }

    /** Takes a request and returns at once; may be called from any thread. */
    public void request(LimitRequest request) {
        Objects.requireNonNull(request, "request");
        if (refusing) {
            deliver(LimitMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "not attempted"),
                    LimitMessages.shuttingDown(request)));
            return;
        }
        if (pending.incrementAndGet() > MOST_PENDING) {
            pending.decrementAndGet();
            deliver(LimitMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "not attempted"),
                    LimitMessages.tooManyPending(request)));
            return;
        }
        try {
            scheduler.execute(() -> run(request));
        } catch (RejectedExecutionException shutDown) {
            pending.decrementAndGet();
            deliver(LimitMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "not attempted"),
                    LimitMessages.shuttingDown(request)));
        }
    }

    /**
     * A new process listing, from the monitoring thread: a managed process it does not show is
     * checked, on the limiter thread, and dropped when it has exited (MGD-2); so is a job PRISM still
     * holds for an exited or unmanaged process. Returns at once.
     */
    public void listed(Set<ProcessIdentity> listing) {
        if ((latest.processes().isEmpty() && watched.get() == 0)
                || refusing
                || !exitCheckPending.compareAndSet(false, true)) {
            return;
        }
        Set<ProcessIdentity> shown = Set.copyOf(listing);
        try {
            scheduler.execute(() -> {
                exitCheckPending.set(false);
                dropExited(shown);
            });
        } catch (RejectedExecutionException shutDown) {
            exitCheckPending.set(false);
        }
    }

    /**
     * What the start found of native limits earlier sessions left (LIFE-4, plan §8.4): logged, and a
     * process that may still be capped gets no second limiter. Any thread.
     */
    public void recovered(List<RecoveredLimit> found) {
        if (found.isEmpty()) {
            return;
        }
        List<RecoveredLimit> copy = List.copyOf(found);
        try {
            scheduler.execute(() -> {
                for (RecoveredLimit limit : copy) {
                    if (!limit.blocksAnotherLimiter()) {
                        continue;
                    }
                    InFlight busy = inFlight.get(limit.record().identity());
                    if (busy == null) {
                        priorSessionFound(limit);
                    } else {
                        // After the native operation in flight for the process, as if it had come after it (D6).
                        busy.deferred.add(() -> {
                            priorSessionFound(limit);
                            watched.set(watchedCount());
                        });
                    }
                }
                watched.set(watchedCount());
                publish();
            });
        } catch (RejectedExecutionException shutDown) {
            // Nothing to show any more.
        }
    }

    private void priorSessionFound(RecoveredLimit limit) {
        ProcessIdentity identity = limit.record().identity();
        priorSession.put(identity, limit);
        offered.remove(identity);
        if (stopLimit(identity)) {
            // Found late (audit item 2): the emulated limit stops through its own path,
            // which releases only what it holds; a process the user suspended stays so.
            failures.put(identity, "an earlier PRISM session may still cap it natively");
            failureNotes.put(identity, LimitNote.PRIOR_SESSION);
            activity.record(
                    ActivitySeverity.WARNING,
                    "The emulated CPU limit of "
                            + limit.record().name() + " (PID " + identity.pid()
                            + ") was stopped: an earlier PRISM session may still cap it natively ("
                            + limit.record().describe() + "). PRISM runs no second limiter on it.");
        }
    }

    /**
     * The journal failed (the coordinator's journal listener): every emulated limit stops. Any
     * thread; the limits stop on the limiter thread, and meanwhile each one stops at its next step.
     * Native limits are not touched: the system enforces them and the ledger records them.
     */
    public void journalFailed(String problem) {
        try {
            scheduler.execute(() -> stopAllForJournal(Optional.of(problem)));
        } catch (RejectedExecutionException shutDown) {
            // Shutting down: the shutdown sequence releases every limit.
        }
    }

    /** From now on every new request is refused (shutdown). */
    public void refuseFurtherLimits() {
        refusing = true;
    }

    /**
     * Shutdown: every emulated limit ends at its next step without touching its process, and the
     * pending steps are called off. Any thread. The shutdown sequence releases what the limiter
     * holds, then clears the native limits ({@link #releaseNativeLimits}).
     */
    public void stopForShutdown() {
        refusing = true;
        runs.values().forEach(LimitRun::stopForShutdown);
        Future<?> pendingWatch = watch;
        if (pendingWatch != null) {
            pendingWatch.cancel(false);
        }
    }

    /**
     * Shutdown and the exit hook (LIFE-1 step 3, LIFE-5): every native limit cleared and verified,
     * every job let go, the ledger written and, when empty, removed. Any thread; within the bound.
     * Says what is known to be left: never "nothing" for a clearing that could not begin.
     */
    public ReleaseResult releaseNativeLimits(Duration within) {
        refusing = true;
        long deadline = System.nanoTime() + within.toNanos();
        ReleaseResult result = natives.releaseAll(within);
        if (result.timedOut()) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "Native limits were not cleared at the exit: the limiter was busy past the time bound. "
                            + result.notCleared()
                            + " process(es) known, possibly more, may stay limited until they exit; the next"
                            + " start lists them.");
        } else if (result.notCleared() > 0) {
            activity.record(
                    ActivitySeverity.WARNING,
                    result.notCleared()
                            + " native limit(s) could not be verified as cleared at the exit; they may stay until"
                            + " their processes exit, and the next start lists them.");
        }
        natives.ledger().close(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        return result;
    }

    /** The answer to a request that reached no limit service, because process monitoring does not run. */
    public static LimitResult notCarriedOut(LimitRequest request) {
        return LimitMessages.result(
                request,
                ControlOutcome.of(OperationResult.UNSUPPORTED, "monitoring is not running"),
                LimitMessages.notMonitoring(request));
    }

    /** Whether an emulated limit is at work on the process: for tests and the shutdown log. */
    boolean limiting(ProcessIdentity identity) {
        return runs.containsKey(identity);
    }

    // ----- On the limiter thread -----

    /**
     * One native operation per process at a time (risk D6): a request for a process whose operation is in flight
     * waits for it, in order, and stays counted among the pending ones.
     */
    private void run(LimitRequest request) {
        whenFree(request.identity(), () -> carryOutAndAnswer(request));
    }

    private void carryOutAndAnswer(LimitRequest request) {
        LimitResult result = LimitMessages.result(
                request,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "the request ended without a result"),
                LimitMessages.failed(request));
        boolean concluded = false;
        boolean handed = false;
        try {
            Carried carried = carryOut(request);
            handed = carried.result().isEmpty();
            result = carried.result().orElse(result);
            concluded = true;
        } catch (RuntimeException e) {
            result = failure(request, e);
            concluded = true;
        } finally {
            if (handed) {
                // Answered once its native step has ended; meanwhile the page and OOM Guard see it as in flight.
                pushToGuard();
                publish();
            } else {
                pending.decrementAndGet();
                if (concluded) {
                    record(result);
                }
                pushToGuard();
                deliver(result);
                publish();
            }
        }
    }

    /** A request that ended with an unexpected failure: never a success. */
    private LimitResult failure(LimitRequest request, RuntimeException e) {
        warnOnce(LimitMessages.actionName(request.action()), e);
        return LimitMessages.result(
                request, ControlOutcome.of(OperationResult.NATIVE_ERROR, e.toString()), LimitMessages.failed(request));
    }

    /** The end of a request that was carried out: counted, logged, told to OOM Guard and answered. */
    private void finish(LimitResult result) {
        pending.decrementAndGet();
        try {
            record(result);
            pushToGuard();
        } finally {
            // Answered whatever went wrong before (review C2): a request never goes unanswered.
            deliver(result);
            publish();
        }
    }

    private Carried carryOut(LimitRequest request) {
        if (refusing) {
            return Carried.answered(
                    answer(request, OperationResult.UNSUPPORTED, "shutting down", LimitMessages.shuttingDown(request)));
        }
        return switch (request.action()) {
            case MANAGE -> Carried.answered(manage(request));
            case UNMANAGE -> unmanage(request);
            case SET_CPU_LIMIT -> setCpuLimit(request);
            case REMOVE_CPU_LIMIT -> removeCpuLimit(request);
            case ENABLE -> enable(request);
            case DISABLE -> disable(request);
            case SET_MEMORY_LIMIT -> setMemoryLimit(request);
            case REMOVE_MEMORY_LIMIT -> removeMemoryLimit(request);
            case ACCEPT_EMULATED_CPU -> Carried.answered(acceptEmulated(request));
            case SET_OOM -> Carried.answered(setOom(request));
            case ACCEPT_RESIDENT_POLICY -> Carried.answered(acceptResidentPolicy(request));
        };
    }

    private LimitResult manage(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        if (registry.find(identity).isPresent()) {
            return answer(
                    request,
                    OperationResult.INVALID_ARGUMENT,
                    "managed already",
                    LimitMessages.alreadyManaged(request));
        }
        Optional<ProtectionRules.Refusal> refusal = rules.refusal(identity);
        if (refusal.isEmpty()) {
            ControlOutcome alive = controller.liveness(identity);
            if (alive.result() == OperationResult.PROCESS_NOT_FOUND) {
                return LimitMessages.result(request, alive, LimitMessages.gone(request));
            }
            refusal = rules.refusal(controller.protectionFacts(identity));
        }
        if (refusal.isPresent()) {
            return answer(
                    request,
                    OperationResult.ACCESS_DENIED,
                    "refused by PRISM: " + refusal.get(),
                    LimitMessages.refused(request, refusal.get()));
        }
        registry.add(identity, request.name());
        unmanagedHolds.remove(identity);
        probe(identity);
        watched.set(watchedCount());
        return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.managed(request));
    }

    /** How each kind could be provided for the process now (LIM-3), kept for the Limits page. */
    private Map<LimitKind, LimitCapability> probe(ProcessIdentity identity) {
        Map<LimitKind, LimitCapability> found;
        if (priorSession.containsKey(identity)) {
            LimitCapability no = LimitCapability.unavailable(
                    "an earlier PRISM session may still cap it natively; no second limiter is started");
            found = Map.of(LimitKind.CPU, no, LimitKind.MEMORY, no);
        } else {
            found = natives.limiter().capabilitiesFor(identity);
        }
        probed.put(identity, found);
        return found;
    }

    private Carried unmanage(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> removed = registry.find(identity);
        if (removed.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        boolean hadLimit = stopLimit(identity);
        if (natives.hold(identity).isPresent()) {
            return nativeChange(request, LimitSpec.NONE, cleared -> {
                if (cleared.kind() == NativeStep.Kind.GONE) {
                    return goneNow(request, cleared);
                }
                if (cleared.kind() != NativeStep.Kind.DONE) {
                    // Its limits stay visible: a process whose job still carries them stays managed (plan D19).
                    failures.put(identity, "its native limits could not be cleared: " + describe(cleared));
                    failureNotes.put(identity, LimitNote.NATIVE_UNSETTLED);
                    return LimitMessages.result(
                            request,
                            ControlOutcome.of(OperationResult.INVALID_ARGUMENT, describe(cleared)),
                            LimitMessages.cannotClear(request, inForce(cleared)));
                }
                unmanagedHolds.put(identity, removed.get().name());
                forgetManaged(identity);
                return LimitMessages.result(
                        request,
                        ControlOutcome.success(),
                        LimitMessages.unmanagedNative(request, nativeCapability(LimitKind.CPU)));
            });
        }
        forgetManaged(identity);
        return Carried.answered(
                LimitMessages.result(request, ControlOutcome.success(), LimitMessages.unmanaged(request, hadLimit)));
    }

    private void forgetManaged(ProcessIdentity identity) {
        registry.remove(identity);
        failures.remove(identity);
        memoryFailures.remove(identity);
        failureNotes.remove(identity);
        offered.remove(identity);
        probed.remove(identity);
        residentOffered.remove(identity);
        residentApproved.remove(identity);
        watchGenerations.remove(identity);
        watched.set(watchedCount());
    }

    private Carried setCpuLimit(LimitRequest request) {
        int percent = request.percent().orElseThrow();
        if (!ManagedSettings.validCpuPercent(percent)) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "out of range", LimitMessages.invalidPercent(request)));
        }
        Optional<ManagedProcess> process = registry.find(request.identity());
        if (process.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        Optional<LimitResult> earlier = priorSessionRefusal(request);
        if (earlier.isPresent()) {
            return Carried.answered(earlier.get());
        }
        if (!nativeCpuSystem()) {
            return Carried.answered(setEmulatedCpuLimit(request, process.get(), percent));
        }
        ProcessIdentity identity = request.identity();
        ManagedSettings settings = process.get().settings();
        if (!settings.enabled()) {
            stopLimit(identity);
            registry.update(identity, settings.withCpuLimit(OptionalInt.of(percent)));
            clearCpuFailure(identity);
            return Carried.answered(LimitMessages.result(
                    request, ControlOutcome.success(), LimitMessages.limitStored(request, percent)));
        }
        // A consent the native limit needs is asked before anything changes, so an emulated limit the user chose keeps
        // running while the user decides (LIM-6). The spec is the one asked for once the emulated limit has stopped.
        Optional<LimitResult> consent =
                consentFirst(request, nativeSpecOf(settings.withCpuLimit(OptionalInt.of(percent)), false));
        if (consent.isPresent()) {
            return Carried.answered(consent.get());
        }
        // Barrier (plan D15): the emulated limit stops first and must have released everything.
        stopLimit(identity);
        Optional<LimitResult> barrier = barrier(request);
        if (barrier.isPresent()) {
            return Carried.answered(barrier.get());
        }
        LimitSpec spec = nativeSpec(identity, settings.withCpuLimit(OptionalInt.of(percent)));
        Optional<NativeHold> before = natives.hold(identity);
        return nativeStep(request, spec, step -> {
            Optional<Carried> barred = barrierAfter(request, step, asFound(before));
            if (barred.isPresent()) {
                return barred.get();
            }
            return Carried.answered(
                    switch (step.kind()) {
                        case DONE -> {
                            registry.update(identity, settings.withCpuLimit(OptionalInt.of(percent)));
                            clearCpuFailure(identity);
                            yield LimitMessages.result(
                                    request,
                                    ControlOutcome.success(),
                                    LimitMessages.limitedNatively(request, percent, nativeCapability(LimitKind.CPU)));
                        }
                        case NOT_APPLIED -> notApplied(request, process.get(), percent, step);
                        default -> failedNatively(request, step);
                    });
        });
    }

    /** Milestone 9: on a system without a native CPU limit, the emulated duty cycle, unchanged. */
    private LimitResult setEmulatedCpuLimit(LimitRequest request, ManagedProcess process, int percent) {
        LimitCapability capability = emulatedCapability();
        if (!capability.available()) {
            String reason = capability.reason().orElseThrow();
            return answer(request, OperationResult.UNSUPPORTED, reason, LimitMessages.unavailable(request, reason));
        }
        ProcessIdentity identity = request.identity();
        // A disabled process only keeps the percentage: it starts nothing and needs no place.
        if (process.settings().enabled()) {
            Optional<LimitResult> refused = admission(request);
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        ManagedProcess changed = registry.update(identity, process.settings().withCpuLimit(OptionalInt.of(percent)))
                .orElseThrow();
        failures.remove(identity);
        if (!changed.settings().enabled()) {
            stopLimit(identity);
            return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.limitStored(request, percent));
        }
        return start(request, changed, percent);
    }

    /**
     * The native CPU limit was not applied (MILESTONES §10): the emulated one runs if the user chose
     * it for this process and nothing native may cap it; otherwise it is offered, with its cost, and
     * nothing runs until the user accepts. The percentage is kept: nothing native is in force.
     */
    private LimitResult notApplied(LimitRequest request, ManagedProcess process, int percent, NativeStep step) {
        ProcessIdentity identity = process.identity();
        String reason = step.outcome().detail().orElse(step.outcome().result().name());
        if (!step.fallbackEligible()) {
            return failedNatively(request, step);
        }
        if (process.settings().emulatedCpuApproved() && emulatedAllowed(identity)) {
            Optional<LimitResult> refused = admission(request);
            if (refused.isPresent()) {
                return refused.get();
            }
            ManagedProcess changed = registry.update(
                            identity, process.settings().withCpuLimit(OptionalInt.of(percent)))
                    .orElseThrow();
            clearCpuFailure(identity);
            LimitResult started = start(request, changed, percent);
            return started.outcome().succeeded()
                    ? LimitMessages.result(request, started.outcome(), LimitMessages.emulatedChosen(request, percent))
                    : started;
        }
        registry.update(identity, process.settings().withCpuLimit(OptionalInt.of(percent)));
        Optional<String> earlier = natives.earlier().mayStillCap(identity);
        if (earlier.isPresent()) {
            // Not offered while another session's native cap may be in force (audit item 2).
            failures.put(identity, "the native limit was not applied (" + reason + "); " + earlier.get());
            failureNotes.put(identity, LimitNote.PRIOR_SESSION);
            offered.remove(identity);
            return LimitMessages.result(
                    request, step.outcome(), LimitMessages.earlierUnsettled(request, earlier.get()));
        }
        Optional<String> scope = natives.limiter().scopeBarrier(identity);
        if (scope.isPresent()) {
            failures.put(identity, "the native limit was not applied (" + reason + "); " + scope.get());
            failureNotes.put(identity, LimitNote.IN_PRISM_SCOPE);
            offered.remove(identity);
            return LimitMessages.result(request, step.outcome(), LimitMessages.inPrismScope(request, scope.get()));
        }
        failures.put(identity, "the native limit was not applied: " + reason);
        failureNotes.put(identity, LimitNote.FALLBACK_OFFERED);
        offered.add(identity);
        return LimitMessages.offered(
                request,
                step.outcome(),
                LimitMessages.nativeNotApplied(request, reason),
                new FallbackOffer(reason, mostActiveLimits(), processors, perThreadSuspension()));
    }

    /** A native step that did not complete: never a success, never a fallback (plan §10). */
    private LimitResult failedNatively(LimitRequest request, NativeStep step) {
        ProcessIdentity identity = request.identity();
        return switch (step.kind()) {
            case GONE -> goneNow(request, step);
            case LEDGER ->
                LimitMessages.result(request, step.outcome(), LimitMessages.ledgerFailed(request, describe(step)));
            case UNSETTLED -> {
                String what = describe(step);
                failures.put(identity, what);
                failureNotes.put(identity, LimitNote.NATIVE_UNSETTLED);
                Wording wording = switch (step.phase()) {
                    case UNCERTAIN -> LimitMessages.uncertain(request, nativeCapability(LimitKind.CPU));
                    case UNVERIFIED -> LimitMessages.unverified(request, candidates(identity));
                    case PARTIAL -> LimitMessages.partial(request, inForce(step));
                    default -> LimitMessages.mismatch(request, inForce(step));
                };
                yield LimitMessages.result(request, step.outcome(), wording);
            }
            case NOT_APPLIED, NOT_DONE, DONE ->
                LimitMessages.result(request, step.outcome(), LimitMessages.nativeFailed(request, describe(step)));
            case CONSENT -> consentAsked(request, step.consent().orElseThrow());
        };
    }

    /**
     * The limiter needs a consent this request does not carry (LIM-6, ADR-0016 decisions 4 and 6): the request is
     * answered with what the user must confirm, and nothing else happens. Nothing native was asked, no setting is
     * changed, no failure is noted, no emulated limit starts and none is offered; the user interface sends the same
     * request again with the consents once the user confirms them. Nothing of it is kept for the process.
     */
    private LimitResult consentAsked(LimitRequest request, NativeConsentOffer offer) {
        return LimitMessages.consentNeeded(
                request,
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "the user's consent is needed: " + offer.kinds()),
                offer);
    }

    /**
     * Asks the limiter, before anything changes, whether the native limits a request leads to need a consent it does
     * not carry (LIM-6): if so, the request is answered with the offer at once, before an emulated limit is stopped,
     * a barrier is checked or a record is written. Reads only; the limiter checks again when it is asked.
     */
    private Optional<LimitResult> consentFirst(LimitRequest request, LimitSpec spec) {
        if (spec.none()) {
            return Optional.empty();
        }
        return natives.limiter()
                .consentNeeded(request.identity(), spec, request.consents())
                .map(offer -> consentAsked(request, offer));
    }

    private LimitResult goneNow(LimitRequest request, NativeStep step) {
        processExited(request.identity());
        return LimitMessages.result(request, step.outcome(), LimitMessages.gone(request));
    }

    /** Starts the emulated limit of the process, replacing one at work: only once its entry is in the journal. */
    private LimitResult start(LimitRequest request, ManagedProcess process, int percent) {
        ProcessIdentity identity = process.identity();
        stopLimit(identity);
        if (!emulatedAllowed(identity)) {
            // Never two controllers: a native cap may be in force (plan §9 invariant, audit item 2).
            return emulatedRefused(request);
        }
        if (!coordinator.journalHealthy() && !coordinator.retryJournal()) {
            return journalRefusal(request, identity);
        }
        if (!coordinator.declare(
                identity, process.name(), SuspensionReason.CPU_LIMITER, "CPU limit " + percent + " %")) {
            // Not written: nothing may suspend for it, and a later write must not carry it either.
            coordinator.dropDeclaration(identity, SuspensionReason.CPU_LIMITER);
            return journalRefusal(request, identity);
        }
        LimitRun run = new LimitRun(
                identity,
                process.name(),
                percent / 100.0,
                coordinator,
                source,
                scheduler,
                nanoClock,
                processors,
                tuning,
                new RunListener());
        runs.put(identity, run);
        run.start();
        if (run.phase() != LimitRun.Phase.ENDED) {
            watchJournal();
        }
        return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.limited(request, percent));
    }

    private LimitResult journalRefusal(LimitRequest request, ProcessIdentity identity) {
        String problem = coordinator.journalProblem().orElse("the journal could not be written");
        failures.put(identity, "journal: " + problem);
        failureNotes.put(identity, LimitNote.JOURNAL);
        return LimitMessages.result(
                request,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "suspension journal: " + problem),
                LimitMessages.journalFailed(request, problem));
    }

    private Carried removeCpuLimit(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        if (process.get().settings().cpuLimitPercent().isEmpty() && !nativeCpuMayBeInForce(identity)) {
            return Carried.answered(
                    answer(request, OperationResult.INVALID_ARGUMENT, "no limit", LimitMessages.noLimit(request)));
        }
        stopLimit(identity);
        offered.remove(identity);
        Optional<NativeHold> hold = natives.hold(identity);
        if (hold.isPresent() && hold.get().mayCapCpu()) {
            LimitSpec spec = nativeSpec(identity, process.get().settings().withCpuLimit(OptionalInt.empty()));
            return nativeChange(request, spec, step -> {
                if (step.kind() == NativeStep.Kind.GONE) {
                    return goneNow(request, step);
                }
                if (step.kind() != NativeStep.Kind.DONE) {
                    failures.put(identity, "the CPU limit could not be removed: " + describe(step));
                    failureNotes.put(identity, LimitNote.NATIVE_UNSETTLED);
                    return LimitMessages.result(
                            request, step.outcome(), LimitMessages.cannotClear(request, inForce(step)));
                }
                return cpuLimitRemoved(request, process.get());
            });
        }
        return Carried.answered(cpuLimitRemoved(request, process.get()));
    }

    private LimitResult cpuLimitRemoved(LimitRequest request, ManagedProcess process) {
        clearCpuFailure(process.identity());
        registry.update(process.identity(), process.settings().withCpuLimit(OptionalInt.empty()));
        return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.limitRemoved(request));
    }

    private Carried setMemoryLimit(LimitRequest request) {
        long bytes = request.bytes().orElseThrow();
        if (!ManagedSettings.validMemoryBytes(bytes)) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "out of range", LimitMessages.invalidBytes(request)));
        }
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        Optional<LimitResult> earlier = priorSessionRefusal(request);
        if (earlier.isPresent()) {
            return Carried.answered(earlier.get());
        }
        String text = EffectiveLimits.bytes(bytes);
        if (!nativeMemorySystem()) {
            if (!policyAvailable()) {
                String reason = memoryCapability().reason().orElse("no native memory limit here");
                return Carried.answered(answer(
                        request,
                        OperationResult.UNSUPPORTED,
                        reason,
                        LimitMessages.memoryUnavailable(request, reason)));
            }
            // LIM-9: the resident memory is watched, never capped; consent is setting the limit (plan O9).
            ManagedSettings current = process.get().settings();
            registry.update(identity, current.withMemoryLimit(OptionalLong.of(bytes)));
            memoryFailures.remove(identity);
            newWatch(identity);
            return Carried.answered(LimitMessages.result(
                    request,
                    ControlOutcome.success("watched, not capped"),
                    current.enabled()
                            ? LimitMessages.memoryWatched(request, text)
                            : LimitMessages.memoryStored(request, text)));
        }
        ManagedSettings settings = process.get().settings();
        if (!settings.enabled()) {
            registry.update(identity, settings.withMemoryLimit(OptionalLong.of(bytes)));
            memoryFailures.remove(identity);
            return Carried.answered(
                    LimitMessages.result(request, ControlOutcome.success(), LimitMessages.memoryStored(request, text)));
        }
        LimitSpec spec = nativeSpec(identity, settings.withMemoryLimit(OptionalLong.of(bytes)));
        Optional<LimitResult> consent = consentFirst(request, spec);
        if (consent.isPresent()) {
            return Carried.answered(consent.get());
        }
        return nativeStep(request, spec, step -> Carried.answered(memoryStepEnded(request, settings, bytes, step)));
    }

    private LimitResult memoryStepEnded(LimitRequest request, ManagedSettings settings, long bytes, NativeStep step) {
        ProcessIdentity identity = request.identity();
        String text = EffectiveLimits.bytes(bytes);
        return switch (step.kind()) {
            case DONE -> {
                registry.update(identity, settings.withMemoryLimit(OptionalLong.of(bytes)));
                memoryFailures.remove(identity);
                residentOffered.remove(identity);
                MemoryBoundary boundary =
                        step.observed().map(EffectiveLimits::memoryBoundary).orElse(memoryBoundary());
                yield LimitMessages.result(
                        request,
                        ControlOutcome.success(),
                        LimitMessages.memoryLimited(request, text, nativeCapability(LimitKind.MEMORY), boundary));
            }
            case NOT_APPLIED -> {
                String reason =
                        step.outcome().detail().orElse(step.outcome().result().name());
                Optional<String> inScope =
                        natives.earlier().mayStillCap(identity).isEmpty()
                                ? natives.limiter().scopeBarrier(identity)
                                : Optional.empty();
                if (inScope.isPresent()) {
                    yield LimitMessages.result(
                            request, step.outcome(), LimitMessages.inPrismScope(request, inScope.get()));
                }
                if (!policyAvailable()
                        || !step.fallbackEligible()
                        || earlierBarrier(identity).isPresent()) {
                    yield LimitMessages.result(
                            request, step.outcome(), LimitMessages.memoryUnavailable(request, reason));
                }
                // Plan O9: the watched resident memory is offered, never assumed (ADR-0012 section 8).
                registry.update(identity, settings.withMemoryLimit(OptionalLong.of(bytes)));
                memoryFailures.remove(identity);
                if (residentApproved.contains(identity)) {
                    newWatch(identity);
                    yield LimitMessages.result(
                            request,
                            ControlOutcome.success("watched, not capped"),
                            LimitMessages.memoryWatched(request, text));
                }
                residentOffered.put(identity, reason);
                yield LimitMessages.result(request, step.outcome(), LimitMessages.residentOffered(request, reason));
            }
            default -> {
                if (step.kind() == NativeStep.Kind.UNSETTLED) {
                    memoryFailures.put(identity, describe(step));
                }
                yield failedNatively(request, step);
            }
        };
    }

    private Carried removeMemoryLimit(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        Optional<NativeHold> hold = mayHold(identity);
        boolean inForce = hold.isPresent()
                && (!hold.get().settled()
                        || hold.get().inForce().orElseThrow().memoryBytes().isPresent());
        if (process.get().settings().memoryLimitBytes().isEmpty() && !inForce) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "no limit", LimitMessages.noMemoryLimit(request)));
        }
        if (inForce) {
            LimitSpec spec = nativeSpec(identity, process.get().settings().withMemoryLimit(OptionalLong.empty()));
            return nativeChange(request, spec, step -> {
                if (step.kind() == NativeStep.Kind.GONE) {
                    return goneNow(request, step);
                }
                if (step.kind() != NativeStep.Kind.DONE) {
                    memoryFailures.put(identity, "the memory limit could not be removed: " + describe(step));
                    return LimitMessages.result(
                            request, step.outcome(), LimitMessages.cannotClear(request, inForce(step)));
                }
                return memoryLimitRemoved(request, process.get());
            });
        }
        return Carried.answered(memoryLimitRemoved(request, process.get()));
    }

    private LimitResult memoryLimitRemoved(LimitRequest request, ManagedProcess process) {
        ProcessIdentity identity = process.identity();
        boolean wasWatched = watchedLimit(process).isPresent();
        memoryFailures.remove(identity);
        residentOffered.remove(identity);
        registry.update(identity, process.settings().withMemoryLimit(OptionalLong.empty()));
        return LimitMessages.result(
                request,
                ControlOutcome.success(),
                wasWatched
                        ? LimitMessages.memoryNoLongerWatched(request)
                        : LimitMessages.memoryRemoved(request, nativeCapability(LimitKind.MEMORY)));
    }

    private Carried enable(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        // A watched memory limit that is enabled again is a new watch (LIM-9).
        newWatch(identity);
        ManagedSettings settings = process.get().settings();
        if (!nativeCpuSystem() && !nativeMemorySystem()) {
            return Carried.answered(enableEmulated(request, process.get()));
        }
        Optional<LimitResult> earlier = settings.nativeSpec().none() ? Optional.empty() : priorSessionRefusal(request);
        if (earlier.isPresent()) {
            return Carried.answered(earlier.get());
        }
        OptionalInt percent = settings.cpuLimitPercent();
        LimitSpec spec = nativeSpec(identity, settings);
        if (spec.none()) {
            if (percent.isPresent()) {
                // A CPU limit on a system whose native CPU limit is unavailable: the emulated one, as in milestone 9.
                return Carried.answered(enableEmulated(request, process.get()));
            }
            registry.update(identity, settings.withEnabled(true));
            clearCpuFailure(identity);
            return Carried.answered(
                    LimitMessages.result(request, ControlOutcome.success(), LimitMessages.enabled(request, false)));
        }
        Optional<LimitResult> consent = consentFirst(request, spec);
        if (consent.isPresent()) {
            return Carried.answered(consent.get());
        }
        Optional<LimitResult> barrier = barrier(request);
        if (barrier.isPresent()) {
            return Carried.answered(barrier.get());
        }
        Optional<NativeHold> before = natives.hold(identity);
        return nativeStep(request, spec, step -> {
            // A refused Enable leaves the process disabled, as it was.
            Optional<Carried> barred = barrierAfter(request, step, asFound(before));
            return barred.orElseGet(() -> Carried.answered(enableStepEnded(request, settings, step)));
        });
    }

    private LimitResult enableStepEnded(LimitRequest request, ManagedSettings settings, NativeStep step) {
        ProcessIdentity identity = request.identity();
        OptionalInt percent = settings.cpuLimitPercent();
        return switch (step.kind()) {
            case DONE -> {
                registry.update(identity, settings.withEnabled(true));
                clearCpuFailure(identity);
                memoryFailures.remove(identity);
                yield LimitMessages.result(
                        request,
                        ControlOutcome.success(),
                        LimitMessages.enabledNative(request, nativeCapability(LimitKind.CPU)));
            }
            case NOT_APPLIED -> {
                if (percent.isEmpty() || !step.fallbackEligible()) {
                    if (percent.isEmpty()) {
                        memoryFailures.put(identity, describe(step));
                    }
                    yield failedNatively(request, step);
                }
                if (settings.emulatedCpuApproved() && emulatedAllowed(identity)) {
                    // Checked before anything changes: a refused Enable leaves the process disabled (ADR-0011).
                    Optional<LimitResult> refused = admission(request);
                    if (refused.isPresent()) {
                        yield refused.get();
                    }
                    ManagedProcess changed = registry.update(identity, settings.withEnabled(true))
                            .orElseThrow();
                    clearCpuFailure(identity);
                    yield start(request, changed, percent.getAsInt());
                }
                ManagedProcess changed =
                        registry.update(identity, settings.withEnabled(true)).orElseThrow();
                yield notApplied(request, changed, percent.getAsInt(), step);
            }
            case UNSETTLED -> {
                registry.update(identity, settings.withEnabled(true));
                yield failedNatively(request, step);
            }
            default -> failedNatively(request, step);
        };
    }

    /** Milestone 9: Enable on a system without native limits. */
    private LimitResult enableEmulated(LimitRequest request, ManagedProcess process) {
        OptionalInt percent = process.settings().cpuLimitPercent();
        if (percent.isPresent()) {
            // Checked before anything changes: a refused Enable leaves the process disabled.
            LimitCapability capability = emulatedCapability();
            if (!capability.available()) {
                String reason = capability.reason().orElseThrow();
                return answer(request, OperationResult.UNSUPPORTED, reason, LimitMessages.unavailable(request, reason));
            }
            Optional<LimitResult> refused = admission(request);
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        ManagedProcess changed = registry.update(
                        request.identity(), process.settings().withEnabled(true))
                .orElseThrow();
        failures.remove(request.identity());
        if (percent.isEmpty()) {
            return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.enabled(request, false));
        }
        LimitResult started = start(request, changed, percent.getAsInt());
        return started.outcome().succeeded()
                ? LimitMessages.result(request, started.outcome(), LimitMessages.enabled(request, true))
                : started;
    }

    private Carried disable(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return Carried.answered(answer(
                    request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request)));
        }
        boolean hadLimit = stopLimit(identity);
        offered.remove(identity);
        if (natives.hold(identity).isPresent()) {
            return nativeChange(request, LimitSpec.NONE, step -> {
                if (step.kind() == NativeStep.Kind.GONE) {
                    return goneNow(request, step);
                }
                if (step.kind() != NativeStep.Kind.DONE) {
                    // Enabled stays: the limits are still, or may still be, in force (plan D19).
                    failures.put(identity, "could not disable: " + describe(step));
                    failureNotes.put(identity, LimitNote.NATIVE_UNSETTLED);
                    return LimitMessages.result(
                            request, step.outcome(), LimitMessages.cannotClear(request, inForce(step)));
                }
                clearCpuFailure(identity);
                memoryFailures.remove(identity);
                registry.update(identity, process.get().settings().withEnabled(false));
                return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.disabledNative(request));
            });
        }
        clearCpuFailure(identity);
        memoryFailures.remove(identity);
        registry.update(identity, process.get().settings().withEnabled(false));
        return Carried.answered(
                LimitMessages.result(request, ControlOutcome.success(), LimitMessages.disabled(request, hadLimit)));
    }

    /** The user accepts the emulated CPU limit offered after the native one was not applied. */
    private LimitResult acceptEmulated(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return answer(request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request));
        }
        if (!offered.contains(identity)) {
            return answer(
                    request,
                    OperationResult.INVALID_ARGUMENT,
                    "no fallback offered",
                    LimitMessages.noFallbackOffered(request));
        }
        if (!emulatedAllowed(identity)) {
            return emulatedRefused(request);
        }
        OptionalInt percent = process.get().settings().cpuLimitPercent();
        if (percent.isEmpty()) {
            offered.remove(identity);
            return answer(request, OperationResult.INVALID_ARGUMENT, "no limit", LimitMessages.noLimit(request));
        }
        ManagedSettings approved = process.get().settings().withEmulatedCpuApproved(true);
        if (approved.enabled()) {
            Optional<LimitResult> refused = admission(request);
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        ManagedProcess changed = registry.update(identity, approved).orElseThrow();
        offered.remove(identity);
        clearCpuFailure(identity);
        if (!approved.enabled()) {
            return LimitMessages.result(
                    request, ControlOutcome.success(), LimitMessages.limitStored(request, percent.getAsInt()));
        }
        LimitResult started = start(request, changed, percent.getAsInt());
        return started.outcome().succeeded()
                ? LimitMessages.result(
                        request, started.outcome(), LimitMessages.emulatedChosen(request, percent.getAsInt()))
                : started;
    }

    /** MGD-3: OOM management, OOM priority and emergency action of a managed process (milestone 11). */
    private LimitResult setOom(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return answer(request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request));
        }
        OomManagement oom = request.oom().orElseThrow();
        registry.update(identity, process.get().settings().withOom(oom));
        // OOM-9: a choice of Terminate is kept while the switch is off; it is only not in effect.
        Optional<String> notInEffect =
                guard.flatMap(oomGuard -> oomGuard.effective(oom).notInEffect());
        return LimitMessages.result(request, ControlOutcome.success(), LimitMessages.oomSet(request, oom, notInEffect));
    }

    /** The user accepts the watched resident memory offered after the native memory limit was not applied (O9). */
    private LimitResult acceptResidentPolicy(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<ManagedProcess> process = registry.find(identity);
        if (process.isEmpty()) {
            return answer(request, OperationResult.INVALID_ARGUMENT, "not managed", LimitMessages.notManaged(request));
        }
        if (!residentOffered.containsKey(identity) || !policyAvailable()) {
            return answer(
                    request,
                    OperationResult.INVALID_ARGUMENT,
                    "no watched memory offered",
                    LimitMessages.noResidentOffered(request));
        }
        OptionalLong bytes = process.get().settings().memoryLimitBytes();
        if (bytes.isEmpty()) {
            residentOffered.remove(identity);
            return answer(request, OperationResult.INVALID_ARGUMENT, "no limit", LimitMessages.noMemoryLimit(request));
        }
        residentOffered.remove(identity);
        residentApproved.add(identity);
        newWatch(identity);
        return LimitMessages.result(
                request,
                ControlOutcome.success("watched, not capped"),
                LimitMessages.memoryWatched(request, EffectiveLimits.bytes(bytes.getAsLong())));
    }

    // ----- OOM Guard -----

    private void newWatch(ProcessIdentity identity) {
        watchGenerations.merge(identity, 1L, Long::sum);
    }

    /**
     * The memory limit OOM Guard watches on the process: one set and enabled, where the system has no
     * native memory limit or the user chose the watched one after the native one failed, and only
     * while no native memory limit is, or may be, in force (CAP-3).
     */
    private OptionalLong watchedLimit(ManagedProcess process) {
        ProcessIdentity identity = process.identity();
        ManagedSettings settings = process.settings();
        if (!policyAvailable()
                || !settings.enabled()
                || settings.memoryLimitBytes().isEmpty()) {
            return OptionalLong.empty();
        }
        if (nativeMemorySystem() && !residentApproved.contains(identity)) {
            return OptionalLong.empty();
        }
        // While a native operation is in flight, what was held before it (D6): the watch neither drops nor comes back
        // because of the operation, which would arm it again (a new crossing out of nothing). Only its answer changes
        // the watch. Never the limiter's live view of a hold mid-operation either. Whether the watch acts meanwhile
        // is watchPaused's.
        Optional<NativeHold> hold = shownHold(identity);
        boolean nativeMemory = hold.isPresent()
                && (!hold.get().settled()
                        || hold.get().inForce().orElseThrow().memoryBytes().isPresent());
        return nativeMemory ? OptionalLong.empty() : settings.memoryLimitBytes();
    }

    /**
     * Whether a native operation in flight for the process may set a native memory limit (D6, CAP-3): then OOM Guard
     * keeps the watch as it stands, armed or not, but acts on it only once the operation has ended.
     */
    private boolean mayLandMemory(ProcessIdentity identity) {
        InFlight busy = inFlight.get(identity);
        return busy != null
                && busy.spec.filter(spec -> spec.memoryBytes().isPresent()).isPresent();
    }

    /** Tells OOM Guard the managed processes as they are now. Limiter thread. */
    private void pushToGuard() {
        if (guard.isEmpty()) {
            return;
        }
        List<OomGuard.Managed> managed = new ArrayList<>();
        for (ManagedProcess process : registry.all()) {
            OptionalLong watchedLimit = watchedLimit(process);
            managed.add(new OomGuard.Managed(
                    process,
                    watchedLimit,
                    watchGenerations.getOrDefault(process.identity(), 0L),
                    watchedLimit.isPresent() && mayLandMemory(process.identity())));
        }
        guard.get().managedChanged(managed);
    }

    // ----- The native steps -----

    /** What a native step came to, in the terms the requests answer by. */
    private record NativeStep(
            Kind kind,
            Phase phase,
            ControlOutcome outcome,
            Optional<EffectiveLimits> observed,
            boolean fallbackEligible,
            Optional<NativeConsentOffer> consent) {

        NativeStep(
                Kind kind,
                Phase phase,
                ControlOutcome outcome,
                Optional<EffectiveLimits> observed,
                boolean fallbackEligible) {
            this(kind, phase, outcome, observed, fallbackEligible, Optional.empty());
        }

        /** The limiter needs a consent the request does not carry: nothing was asked of the system (LIM-6). */
        static NativeStep consent(ControlOutcome outcome, NativeConsentOffer offer) {
            return new NativeStep(Kind.CONSENT, Phase.CONSENT, outcome, Optional.empty(), false, Optional.of(offer));
        }

        /** Which answer of the limiter or the ledger the step came from. */
        enum Phase {
            NONE,
            LEDGER,
            ENFORCED,
            GONE,
            NOT_ASSIGNED,
            MISMATCH,
            UNCERTAIN,
            APPLIED,
            PARTIAL,
            UNVERIFIED,
            NOT_DONE,
            NO_HOLD,
            CONSENT
        }

        enum Kind {
            /** The job holds exactly what was asked (or nothing, for a clearing without a job). */
            DONE,
            /** Nothing was assigned (proven); the fallback may be offered when eligible. */
            NOT_APPLIED,
            /** Something is or may be in force that is not what was asked. */
            UNSETTLED,
            /** Nothing was changed. */
            NOT_DONE,
            /** The ledger cannot record it. */
            LEDGER,
            /** The process exited. */
            GONE,
            /** A consent the request does not carry is needed; nothing was changed, nothing is offered instead. */
            CONSENT
        }
    }

    /** The native spec a process's settings ask for, limited to the kinds this system provides natively. */
    private LimitSpec nativeSpec(ProcessIdentity identity, ManagedSettings settings) {
        // The emulated limit holds the CPU: the job's spec leaves it alone.
        return nativeSpecOf(settings, runs.containsKey(identity));
    }

    /** {@link #nativeSpec}, with the CPU left alone where {@code emulatedHoldsCpu}. */
    private LimitSpec nativeSpecOf(ManagedSettings settings, boolean emulatedHoldsCpu) {
        OptionalInt cpu = nativeCpuSystem() && !emulatedHoldsCpu ? settings.cpuLimitPercent() : OptionalInt.empty();
        OptionalLong memory = nativeMemorySystem() ? settings.memoryLimitBytes() : OptionalLong.empty();
        return new LimitSpec(cpu, memory, memoryBoundary());
    }

    /**
     * The memory boundary the system's native memory limit is (risk D14): a soft one, Linux's {@code memory.high}
     * through a systemd scope (ADR-0016 decision 4, the default there), asks to throttle; a hard one, a Job Object's
     * commit limit, asks for the hard boundary, the only one Windows has. {@code memory.max} on Linux needs a
     * separate consent and is never asked for here.
     */
    private MemoryBoundary memoryBoundary() {
        return nativeMemoryCapability().level() == CapabilityLevel.NATIVE_SOFT
                ? MemoryBoundary.THROTTLE
                : MemoryBoundary.HARD;
    }

    /**
     * A first assignment or a change of the job PRISM holds, whichever applies (plan S0a). The checks before the
     * system is asked run here, on the limiter thread; the native part runs on the worker, and {@code then} turns
     * what it came to into the request's result once it is back on the limiter thread (risk D6).
     */
    private Carried nativeStep(LimitRequest request, LimitSpec spec, Function<NativeStep, Carried> then) {
        ProcessIdentity identity = request.identity();
        if (natives.hold(identity).isPresent()) {
            return handOver(request, spec, () -> {
                Optional<ChangeResult> changed = natives.change(identity, request.name(), spec, request.consents());
                return () -> {
                    NativeStep step = changeStep(spec, changed);
                    if (step.kind() == NativeStep.Kind.CONSENT) {
                        return Carried.answered(
                                consentAsked(request, step.consent().orElseThrow()));
                    }
                    if (step.phase() != NativeStep.Phase.NO_HOLD) {
                        return then.apply(step);
                    }
                    // An uncertain assignment proven not to have happened: a first assignment now.
                    return firstAssignment(request, spec, then);
                };
            });
        }
        return firstAssignment(request, spec, then);
    }

    private Carried firstAssignment(LimitRequest request, LimitSpec spec, Function<NativeStep, Carried> then) {
        ProcessIdentity identity = request.identity();
        if (spec.none()) {
            return then.apply(new NativeStep(
                    NativeStep.Kind.DONE,
                    NativeStep.Phase.NONE,
                    ControlOutcome.success(),
                    Optional.of(EffectiveLimits.NONE),
                    false));
        }
        if (!natives.ledger().healthy()) {
            return then.apply(new NativeStep(
                    NativeStep.Kind.LEDGER, NativeStep.Phase.LEDGER, natives.notRecorded(), Optional.empty(), false));
        }
        if (!coordinator.journalHealthy() && !coordinator.retryJournal()) {
            // Without a healthy journal this session holds no lock a later start could find it by,
            // so its ledger would never be read (plan section 6.4).
            return then.apply(journalStep());
        }
        return handOver(request, spec, () -> {
            // Again on the worker, right before the record and the ask (review C1): the journal may have failed
            // while the operation waited. The coordinator's health is safe to read from any thread; no retry is made
            // here, as a retry writes.
            if (!coordinator.journalHealthy()) {
                return () -> then.apply(journalStep());
            }
            Optional<ApplyResult> recorded = natives.apply(identity, request.name(), spec, request.consents());
            return () -> {
                NativeStep step = assignStep(identity, recorded);
                // A consent not given (LIM-6): answered as such, whatever the request; nothing changed, nothing starts.
                return step.kind() == NativeStep.Kind.CONSENT
                        ? Carried.answered(consentAsked(request, step.consent().orElseThrow()))
                        : then.apply(step);
            };
        });
    }

    /** A first assignment refused because the suspension journal is not healthy; nothing was asked. */
    private NativeStep journalStep() {
        return new NativeStep(
                NativeStep.Kind.LEDGER,
                NativeStep.Phase.LEDGER,
                ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        "suspension journal: " + coordinator.journalProblem().orElse("not available")),
                Optional.empty(),
                false);
    }

    /**
     * Review S2: the barrier of plan D15 again once a step that may set a CPU cap has ended, as OOM Guard may have
     * suspended the process, or a release of it may have stayed incomplete, while the step waited or ran. OOM Guard
     * keeps acting meanwhile: a process it must hold is not left alone for an adoption's seconds. Where the barrier
     * now refuses, a cap that was set is withdrawn to what was held before ({@code asFound}), on the worker like any
     * change, and the request is answered with the refusal; a cap that could not be withdrawn is said to be in force.
     * A step that set nothing is answered with the refusal and starts nothing, no emulated limit either. Empty where
     * the barrier lets the step stand, or where the step's own answer says what may be in force (uncertain,
     * mismatched, not done).
     */
    private Optional<Carried> barrierAfter(LimitRequest request, NativeStep step, LimitSpec asFound) {
        if (step.kind() != NativeStep.Kind.DONE && step.kind() != NativeStep.Kind.NOT_APPLIED) {
            return Optional.empty();
        }
        Optional<LimitResult> refused = barrier(request);
        if (refused.isEmpty()) {
            return Optional.empty();
        }
        if (step.kind() == NativeStep.Kind.NOT_APPLIED) {
            return refused.map(Carried::answered);
        }
        ProcessIdentity identity = request.identity();
        LimitResult refusal = refused.get();
        return Optional.of(nativeChange(request, asFound, withdrawn -> {
            if (withdrawn.kind() == NativeStep.Kind.GONE) {
                return goneNow(request, withdrawn);
            }
            if (withdrawn.kind() != NativeStep.Kind.DONE) {
                failures.put(
                        identity,
                        "a CPU limit set while PRISM held the process could not be withdrawn: " + describe(withdrawn));
                failureNotes.put(identity, LimitNote.NATIVE_UNSETTLED);
                return LimitMessages.result(
                        request, withdrawn.outcome(), LimitMessages.cannotClear(request, inForce(withdrawn)));
            }
            return refusal;
        }));
    }

    /** What was known to be in force before a step, as limits to change back to; none of a kind not known. */
    private LimitSpec asFound(Optional<NativeHold> before) {
        MemoryBoundary boundary = before.filter(NativeHold::settled)
                .flatMap(NativeHold::inForce)
                .map(EffectiveLimits::memoryBoundary)
                .orElse(MemoryBoundary.HARD);
        return new LimitSpec(cpuHeld(before), memoryHeld(before), boundary);
    }

    /** The CPU cap known to be in force before a step, as a whole percentage; none when unknown or none. */
    private static OptionalInt cpuHeld(Optional<NativeHold> before) {
        OptionalInt cpu = before.filter(NativeHold::settled)
                .flatMap(NativeHold::inForce)
                .map(EffectiveLimits::cpuPercent)
                .orElse(OptionalInt.empty());
        return cpu.isPresent() && ManagedSettings.validCpuPercent(cpu.getAsInt()) ? cpu : OptionalInt.empty();
    }

    /** The memory limit known to be in force before a step; none when unknown or none. */
    private static OptionalLong memoryHeld(Optional<NativeHold> before) {
        OptionalLong memory = before.filter(NativeHold::settled)
                .flatMap(NativeHold::inForce)
                .map(EffectiveLimits::memoryBytes)
                .orElse(OptionalLong.empty());
        return memory.isPresent() && ManagedSettings.validMemoryBytes(memory.getAsLong())
                ? memory
                : OptionalLong.empty();
    }

    /** What a first assignment came to; on the limiter thread, once it has ended. */
    private NativeStep assignStep(ProcessIdentity identity, Optional<ApplyResult> recorded) {
        if (recorded.isEmpty()) {
            return new NativeStep(
                    NativeStep.Kind.LEDGER, NativeStep.Phase.LEDGER, natives.notRecorded(), Optional.empty(), false);
        }
        ApplyResult applied = recorded.get();
        Optional<NativeHold> hold = natives.hold(identity);
        probed.put(identity, hold.isPresent() ? natives.limiter().capabilities() : probe(identity));
        return switch (applied.phase()) {
            case ENFORCED ->
                new NativeStep(
                        NativeStep.Kind.DONE, NativeStep.Phase.ENFORCED, applied.outcome(), applied.observed(), false);
            case NOT_ASSIGNED -> {
                if (applied.outcome().result() == OperationResult.PROCESS_NOT_FOUND) {
                    yield new NativeStep(
                            NativeStep.Kind.GONE, NativeStep.Phase.GONE, applied.outcome(), Optional.empty(), false);
                }
                yield new NativeStep(
                        NativeStep.Kind.NOT_APPLIED,
                        NativeStep.Phase.NOT_ASSIGNED,
                        applied.outcome(),
                        Optional.empty(),
                        applied.fallbackEligible());
            }
            case ASSIGNED_RELEASED ->
                new NativeStep(NativeStep.Kind.GONE, NativeStep.Phase.GONE, applied.outcome(), Optional.empty(), false);
            case ASSIGNED_MISMATCH ->
                applied.outcome().result() == OperationResult.PROCESS_NOT_FOUND
                        ? new NativeStep(
                                NativeStep.Kind.GONE,
                                NativeStep.Phase.GONE,
                                applied.outcome(),
                                applied.observed(),
                                false)
                        : new NativeStep(
                                NativeStep.Kind.UNSETTLED,
                                NativeStep.Phase.MISMATCH,
                                applied.outcome(),
                                applied.observed(),
                                false);
            case UNCERTAIN ->
                new NativeStep(
                        NativeStep.Kind.UNSETTLED,
                        NativeStep.Phase.UNCERTAIN,
                        applied.outcome(),
                        Optional.empty(),
                        false);
            case CONSENT_REQUIRED ->
                NativeStep.consent(applied.outcome(), applied.consent().orElseThrow());
        };
    }

    /** A change of the job PRISM holds, on the worker; {@code then} on the limiter thread once it has ended (D6). */
    private Carried nativeChange(LimitRequest request, LimitSpec spec, Function<NativeStep, LimitResult> then) {
        return handOver(request, spec, () -> {
            Optional<ChangeResult> changed =
                    natives.change(request.identity(), request.name(), spec, request.consents());
            return () -> {
                NativeStep step = changeStep(spec, changed);
                return Carried.answered(
                        step.kind() == NativeStep.Kind.CONSENT
                                ? consentAsked(request, step.consent().orElseThrow())
                                : then.apply(step));
            };
        });
    }

    /** What a change came to, in the terms the requests answer by. */
    private NativeStep changeStep(LimitSpec spec, Optional<ChangeResult> recorded) {
        if (recorded.isEmpty()) {
            return new NativeStep(
                    NativeStep.Kind.LEDGER, NativeStep.Phase.LEDGER, natives.notRecorded(), Optional.empty(), false);
        }
        ChangeResult changed = recorded.get();
        return switch (changed.phase()) {
            case APPLIED ->
                new NativeStep(
                        NativeStep.Kind.DONE, NativeStep.Phase.APPLIED, changed.outcome(), changed.observed(), false);
            case PARTIAL ->
                new NativeStep(
                        NativeStep.Kind.UNSETTLED,
                        NativeStep.Phase.PARTIAL,
                        changed.outcome(),
                        changed.observed(),
                        false);
            case UNVERIFIED ->
                new NativeStep(
                        NativeStep.Kind.UNSETTLED,
                        NativeStep.Phase.UNVERIFIED,
                        changed.outcome(),
                        Optional.empty(),
                        false);
            case NOT_DONE ->
                changed.outcome().result() == OperationResult.PROCESS_NOT_FOUND
                        ? new NativeStep(
                                NativeStep.Kind.GONE, NativeStep.Phase.GONE, changed.outcome(), Optional.empty(), false)
                        : new NativeStep(
                                NativeStep.Kind.NOT_DONE,
                                NativeStep.Phase.NOT_DONE,
                                changed.outcome(),
                                Optional.empty(),
                                false);
            case NO_HOLD ->
                new NativeStep(
                        spec.none() ? NativeStep.Kind.DONE : NativeStep.Kind.NOT_DONE,
                        NativeStep.Phase.NO_HOLD,
                        changed.outcome(),
                        Optional.of(EffectiveLimits.NONE),
                        false);
            case CONSENT_REQUIRED ->
                NativeStep.consent(changed.outcome(), changed.consent().orElseThrow());
        };
    }

    // ----- The native-limit worker (risk D6) -----

    /**
     * What carrying out a request came to on the limiter thread: its result, or none yet, because its native part was
     * handed to the worker and the request is answered once that has ended. Empty, too, for work that answers nothing.
     */
    private record Carried(Optional<LimitResult> result) {
        static final Carried HANDED = new Carried(Optional.empty());
        static final Carried NOTHING = new Carried(Optional.empty());

        static Carried answered(LimitResult result) {
            return new Carried(Optional.of(result));
        }
    }

    /**
     * A native operation for one process, handed to the worker. Made, queued and concluded on the limiter thread; only
     * {@link #work} runs on the worker, and it touches nothing of the service but the native controller.
     */
    private static final class InFlight {
        final ProcessIdentity identity;
        /** The request it answers; empty for the forget of an exited process and the retry of a residue. */
        final Optional<LimitRequest> request;
        /** The limits it asks for; empty for a forget. */
        final Optional<LimitSpec> spec;
        /** What PRISM held for the process when it was handed over: what the Limits page shows meanwhile. */
        final Optional<NativeHold> before;
        /** The native part, on the worker; it returns what the limiter thread then does with its answer. */
        final Supplier<Supplier<Carried>> work;
        /** What the limiter thread does when the native part threw. */
        final Function<RuntimeException, Carried> failed;
        /** What came for the process meanwhile, run in order once this has ended. */
        final ArrayDeque<Runnable> deferred = new ArrayDeque<>();

        InFlight(
                ProcessIdentity identity,
                Optional<LimitRequest> request,
                Optional<LimitSpec> spec,
                Optional<NativeHold> before,
                Supplier<Supplier<Carried>> work,
                Function<RuntimeException, Carried> failed) {
            this.identity = identity;
            this.request = request;
            this.spec = spec;
            this.before = before;
            this.work = work;
            this.failed = failed;
        }
    }

    /** The hold of a process whose native operation is in flight, for what must not count on its limits. */
    private static final NativeHold IN_FLIGHT = new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty());

    /**
     * The hold the Limits page shows for a process: while a native operation for it is in flight, what was held
     * before it, as the operation's answer is not known yet.
     */
    private Optional<NativeHold> shownHold(ProcessIdentity identity) {
        InFlight busy = inFlight.get(identity);
        return busy != null ? busy.before : natives.hold(identity);
    }

    /**
     * The hold for what needs to know what may be in force (OOM Guard's watch, an emulated limit): while a native
     * operation for the process is in flight, an uncertain one, as its limits may be landing.
     */
    private Optional<NativeHold> mayHold(ProcessIdentity identity) {
        return inFlight.containsKey(identity) ? Optional.of(IN_FLIGHT) : natives.hold(identity);
    }

    /** Runs {@code work} now, or once the native operation in flight for the process has ended, in order. */
    private void whenFree(ProcessIdentity identity, Runnable work) {
        InFlight busy = inFlight.get(identity);
        if (busy != null) {
            busy.deferred.add(work);
        } else {
            work.run();
        }
    }

    private Carried handOver(LimitRequest request, LimitSpec spec, Supplier<Supplier<Carried>> work) {
        return handOver(new InFlight(
                request.identity(),
                Optional.of(request),
                Optional.of(spec),
                natives.hold(request.identity()),
                work,
                failed -> Carried.answered(failure(request, failed))));
    }

    /** Queues the operation for the worker; the process counts as in flight from now until it is concluded. */
    private Carried handOver(InFlight operation) {
        inFlight.put(operation.identity, operation);
        nativeQueue.add(operation);
        dispatchNext();
        return Carried.HANDED;
    }

    /**
     * Hands the oldest waiting operation to the worker when it has none of this service's: one at a time, in order.
     * Once the service is shutting down, or the worker refuses, an operation waiting is never asked.
     */
    private void dispatchNext() {
        while (!nativeBusy && !nativeQueue.isEmpty()) {
            InFlight next = nativeQueue.poll();
            if (refusing) {
                abandon(next);
                continue;
            }
            nativeBusy = true;
            try {
                nativeWork.execute(() -> runNative(next));
            } catch (RejectedExecutionException stopped) {
                nativeBusy = false;
                abandon(next);
            }
        }
    }

    /**
     * Shutdown: an operation the worker never had is not asked. Its request is answered as refused, and what waited
     * for it runs (a request is refused the same way, as the service is shutting down); the shutdown's release clears
     * what is held.
     */
    private void abandon(InFlight operation) {
        inFlight.remove(operation.identity, operation);
        try {
            operation.request.ifPresent(request -> finish(answer(
                    request, OperationResult.UNSUPPORTED, "shutting down", LimitMessages.shuttingDown(request))));
        } catch (RuntimeException e) {
            warnOnce("Refusing a native limit operation at shutdown", e);
        } finally {
            resume(operation);
        }
    }

    /** On the worker: the native part, then what it came to handed back to the limiter thread. */
    private void runNative(InFlight operation) {
        Supplier<Carried> answered;
        try {
            answered = operation.work.get();
        } catch (RuntimeException e) {
            answered = () -> operation.failed.apply(e);
        }
        Supplier<Carried> conclusion = answered;
        try {
            scheduler.execute(() -> concluded(operation, conclusion));
        } catch (RejectedExecutionException stopped) {
            // The limiter has stopped (shutdown): what the operation did is in the native controller's limiter and
            // ledger, and the shutdown's release clears it. Nobody is left to be answered.
            LOG.info(
                    "A native limit operation for PID {} ended after the limiter had stopped; the shutdown clears"
                            + " what it left.",
                    operation.identity.pid());
        }
    }

    /** On the limiter thread: the operation has ended; its request is answered, and what waited for it runs. */
    private void concluded(InFlight operation, Supplier<Carried> conclusion) {
        inFlight.remove(operation.identity, operation);
        nativeBusy = false;
        boolean answered = false;
        try {
            Carried carried;
            try {
                carried = conclusion.get();
            } catch (RuntimeException e) {
                carried = operation.failed.apply(e);
            }
            Optional<LimitResult> result = operation.request.isPresent() ? carried.result() : Optional.empty();
            if (result.isPresent()) {
                answered = true;
                finish(result.get());
            } else {
                pushToGuard();
                publish();
            }
        } catch (RuntimeException e) {
            // Review C2: logged, and what waited for the operation and the operations after it still run.
            warnOnce("Concluding a native limit operation", e);
            if (!answered && operation.request.isPresent() && !inFlight.containsKey(operation.identity)) {
                // Neither its conclusion nor its failure could answer it: answered here, never left waiting.
                pending.decrementAndGet();
                deliver(failure(operation.request.get(), e));
            }
        } finally {
            try {
                resume(operation);
            } finally {
                dispatchNext();
            }
        }
    }

    /**
     * What waited for an operation runs in order; once one hands over again, the rest wait for that one. One that
     * fails is logged and the rest still run (review C2).
     */
    private void resume(InFlight operation) {
        if (operation.deferred.isEmpty()) {
            return;
        }
        while (!operation.deferred.isEmpty()) {
            Runnable next = operation.deferred.poll();
            try {
                whenFree(operation.identity, next);
            } catch (RuntimeException e) {
                warnOnce("Work that waited for a native limit operation", e);
            }
        }
        try {
            pushToGuard();
        } finally {
            publish();
        }
    }

    /**
     * The barrier of plan D15: no native CPU cap over threads the emulated limit could not release,
     * nor while an automatic reason or a limit run still holds the process.
     */
    private Optional<LimitResult> barrier(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Set<SuspensionReason> heldFor = coordinator.heldFor(identity);
        boolean automatic = heldFor.stream().anyMatch(SuspensionReason::automatic);
        if (!runs.containsKey(identity) && !automatic && !coordinator.incomplete(identity)) {
            return Optional.empty();
        }
        if (!runs.containsKey(identity) && heldFor.contains(SuspensionReason.OOM_GUARD)) {
            // Milestone 11: not the emulated limit, OOM Guard holds it; the limit is set once it lets go.
            return Optional.of(LimitMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "held by OOM Guard"),
                    LimitMessages.heldByOomGuard(request)));
        }
        failures.put(identity, "the emulated limit could not release every thread");
        failureNotes.put(identity, LimitNote.RELEASE_INCOMPLETE);
        return Optional.of(LimitMessages.result(
                request,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "the emulated limit still holds threads"),
                LimitMessages.releaseIncomplete(request, 1)));
    }

    /**
     * Whether the emulated CPU limit may run on the process (plan §9 invariant): no native CPU cap
     * is, or may be, in force on it: PRISM holds no job for it, or one known to cap nothing; and no
     * earlier session may still cap it.
     */
    private boolean emulatedAllowed(ProcessIdentity identity) {
        if (priorSession.containsKey(identity) || earlierBarrier(identity).isPresent()) {
            return false;
        }
        return mayHold(identity).map(hold -> !hold.mayCapCpu()).orElse(true);
    }

    /**
     * The barrier of audit item 2: an earlier (or another) PRISM session's native cap may be in force
     * on the process while recovery has not settled its ledger; then no emulated limit is offered or
     * started. Whatever today's probe says (final source audit): a native CPU limit PRISM cannot make
     * now, after Dynamic Fair Share Scheduling was turned on or a probe failed, says nothing of a
     * kernel cap an earlier session made. Where no ledger of another session exists, as on Linux and
     * macOS, nothing is unsettled and the milestone 9 path is unchanged. Asks only what recovery has
     * already read.
     */
    private Optional<String> earlierBarrier(ProcessIdentity identity) {
        // The identity rule first (recovery), then the scope-membership rule, independent of any record (plan Rev 2.2
        // §4.5 b): a process inside a scope a PRISM session created gets no second limiter.
        return natives.earlier()
                .mayStillCap(identity)
                .or(() -> natives.limiter().scopeBarrier(identity));
    }

    /** Why the emulated limit may not run on the process, in words. */
    private LimitResult emulatedRefused(LimitRequest request) {
        ProcessIdentity identity = request.identity();
        Optional<String> earlier = priorSession.containsKey(identity)
                ? Optional.empty()
                : natives.earlier().mayStillCap(identity);
        Optional<String> scope = natives.limiter().scopeBarrier(identity);
        if (earlier.isEmpty() && scope.isPresent()) {
            failures.put(identity, "no emulated limit: " + scope.get());
            failureNotes.put(identity, LimitNote.IN_PRISM_SCOPE);
            offered.remove(identity);
            return LimitMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, scope.get()),
                    LimitMessages.inPrismScope(request, scope.get()));
        }
        if (earlier.isPresent()) {
            // Said on the row too, as for a native limit not applied: never "not at work" in silence.
            failures.put(identity, "no emulated limit yet: " + earlier.get());
            failureNotes.put(identity, LimitNote.PRIOR_SESSION);
            offered.remove(identity);
            return LimitMessages.result(
                    request,
                    ControlOutcome.of(
                            OperationResult.UNSUPPORTED, "an earlier session's native limits are not settled"),
                    LimitMessages.earlierUnsettled(request, earlier.get()));
        }
        return LimitMessages.result(
                request,
                ControlOutcome.of(OperationResult.UNSUPPORTED, "a native CPU cap may be in force"),
                LimitMessages.nativeUnknown(request, candidates(identity)));
    }

    private boolean nativeCpuMayBeInForce(ProcessIdentity identity) {
        return mayHold(identity).map(NativeHold::mayCapCpu).orElse(false);
    }

    private Optional<LimitResult> priorSessionRefusal(LimitRequest request) {
        RecoveredLimit earlier = priorSession.get(request.identity());
        if (earlier == null) {
            return Optional.empty();
        }
        return Optional.of(LimitMessages.result(
                request,
                ControlOutcome.of(OperationResult.UNSUPPORTED, "an earlier session may still cap it natively"),
                LimitMessages.priorSession(request, earlier.record().describe())));
    }

    private void clearCpuFailure(ProcessIdentity identity) {
        failures.remove(identity);
        failureNotes.remove(identity);
        offered.remove(identity);
    }

    private String candidates(ProcessIdentity identity) {
        RecoveredLimit earlier = priorSession.get(identity);
        if (earlier != null) {
            return earlier.record().describe();
        }
        Optional<LimitRecord> record = natives.ledger().find(identity);
        Optional<NativeHold> hold = shownHold(identity);
        if (hold.flatMap(NativeHold::inForce).isPresent()) {
            return hold.get().inForce().get().describe();
        }
        return record.map(LimitRecord::describe).orElse("unknown");
    }

    private String inForce(NativeStep step) {
        return step.observed().map(EffectiveLimits::describe).orElseGet(() -> "unknown");
    }

    private static String describe(NativeStep step) {
        return step.outcome().detail().orElse(step.outcome().result().name());
    }

    /** Ends the emulated limit at work on the process, releasing it; whether there was one. */
    private boolean stopLimit(ProcessIdentity identity) {
        LimitRun run = runs.get(identity);
        if (run == null) {
            return false;
        }
        run.cancel();
        return true;
    }

    private void stopAllForJournal(Optional<String> problem) {
        for (LimitRun run : new ArrayList<>(runs.values())) {
            run.stopForJournal(problem);
        }
        publish();
    }

    private void dropExited(Set<ProcessIdentity> listing) {
        boolean changed = false;
        Set<ProcessIdentity> candidates = new HashSet<>(registry.missingFrom(listing));
        for (ProcessIdentity identity : unmanagedHolds.keySet()) {
            if (!listing.contains(identity)) {
                candidates.add(identity);
            }
        }
        for (ProcessIdentity identity : priorSession.keySet()) {
            if (!listing.contains(identity)) {
                candidates.add(identity);
            }
        }
        for (ProcessIdentity identity : candidates) {
            if (inFlight.containsKey(identity)) {
                // Its native operation ends first (D6); the next listing looks again.
                continue;
            }
            ControlOutcome alive = controller.liveness(identity);
            if (alive.result() != OperationResult.PROCESS_NOT_FOUND) {
                continue;
            }
            LimitRun run = runs.get(identity);
            if (run != null) {
                // Nothing is left to stop; the cancel withdraws the journal entry.
                run.cancel();
            }
            processExited(identity);
            changed = true;
        }
        // Jobs of exited processes that still carry limits: tried again with every listing, on the worker, one try
        // per process at a time.
        for (ProcessIdentity identity : List.copyOf(residues.keySet())) {
            if (!inFlight.containsKey(identity)) {
                retryResidue(identity);
            }
        }
        if (changed) {
            watched.set(watchedCount());
            pushToGuard();
            publish();
        } else if (registry.all().stream()
                .anyMatch(process -> watchedLimit(process).isPresent())) {
            // The resident memory OOM Guard watches changes with every listing (LIM-9).
            publish();
        }
    }

    private void retryResidue(ProcessIdentity identity) {
        handOver(new InFlight(
                identity,
                Optional.empty(),
                Optional.empty(),
                natives.hold(identity),
                () -> {
                    ForgetResult retried = natives.forget(identity);
                    return () -> {
                        if (retried.released() && residues.containsKey(identity)) {
                            String label = label(identity);
                            residues.remove(identity);
                            watched.set(watchedCount());
                            activity.record(
                                    ActivitySeverity.INFO,
                                    "PRISM cleared the limits the " + holder() + " of " + label
                                            + " still carried for the processes it started.");
                        }
                        return Carried.NOTHING;
                    };
                },
                failed -> {
                    // The residue stays and is tried again with the next listing.
                    warnOnce("Clearing the job of an exited process", failed);
                    return Carried.NOTHING;
                }));
    }

    /**
     * The one exit path of a process (plan bulgu 2, §8.6): its job, if PRISM holds one, is cleared
     * and verified before it is let go; its record goes only then. A job that still carries limits
     * stays a residue and is retried; nothing is discarded silently. What is not native is done at
     * once; clearing the job runs on the worker (D6), and what it came to is kept once it has ended.
     */
    private void processExited(ProcessIdentity identity) {
        String name = registry.find(identity)
                .map(ManagedProcess::name)
                .orElse(unmanagedHolds.getOrDefault(identity, "PID " + identity.pid()));
        Optional<NativeHold> held = natives.hold(identity);
        unmanagedHolds.remove(identity);
        boolean carried = priorSession.remove(identity) != null;
        registry.remove(identity).ifPresent(this::recordExit);
        forgetManaged(identity);
        if (held.isEmpty()) {
            if (carried) {
                carriedRecordExited(identity);
            }
            return;
        }
        handOver(new InFlight(
                identity,
                Optional.empty(),
                Optional.empty(),
                held,
                () -> {
                    ForgetResult forgotten = natives.forget(identity);
                    return () -> {
                        forgotten(identity, name, forgotten);
                        if (carried) {
                            carriedRecordExited(identity);
                        }
                        return Carried.NOTHING;
                    };
                },
                failed -> {
                    warnOnce("Clearing the job of an exited process", failed);
                    forgotten(
                            identity,
                            name,
                            new ForgetResult(
                                    ForgetResult.Outcome.UNRESOLVED,
                                    ControlOutcome.of(OperationResult.NATIVE_ERROR, failed.toString()),
                                    -1,
                                    Optional.empty()));
                    if (carried) {
                        carriedRecordExited(identity);
                    }
                    return Carried.NOTHING;
                }));
    }

    /**
     * The record recovery carried into this session's ledger: the process it named has exited, so the next start has
     * nothing to say about it (native-api review, Stage 4).
     */
    private void carriedRecordExited(ProcessIdentity identity) {
        natives.forgetCarried(identity);
    }

    /** What letting go of the job of an exited process came to: a job that still carries limits stays a residue. */
    private void forgotten(ProcessIdentity identity, String name, ForgetResult forgotten) {
        if (!forgotten.released()) {
            residues.put(
                    identity,
                    new Residue(
                            identity,
                            name,
                            forgotten.inForce().orElse(EffectiveLimits.NONE),
                            forgotten.survivors(),
                            Instant.now()));
            watched.set(watchedCount());
            activity.record(
                    ActivitySeverity.WARNING,
                    "The " + holder() + " of " + name + " (PID " + identity.pid()
                            + "), which has exited, still carries limits PRISM"
                            + " could not clear ("
                            + forgotten.inForce().map(EffectiveLimits::describe).orElse("unknown")
                            + "); the processes it started stay limited. PRISM keeps trying.");
        } else if (forgotten.survivors() > 0) {
            activity.record(
                    ActivitySeverity.INFO,
                    "The " + holder() + " of " + name + " (PID " + identity.pid() + ") still counted "
                            + forgotten.survivors()
                            + (forgotten.survivors() == 1 ? " process" : " processes")
                            + " after it exited; PRISM cleared its limits for them.");
        }
    }

    private String label(ProcessIdentity identity) {
        Residue residue = residues.get(identity);
        return (residue == null ? "PID " + identity.pid() : residue.name() + " (PID " + identity.pid() + ")");
    }

    private int watchedCount() {
        return unmanagedHolds.size() + residues.size() + priorSession.size();
    }

    private void recordExit(ManagedProcess process) {
        activity.record(
                ActivitySeverity.INFO,
                process.name() + " (PID " + process.identity().pid() + ") has exited and is no longer managed.");
    }

    /** About once a second while limits run, the journal on disk is checked; a failure stops them all. */
    private void watchJournal() {
        Future<?> current = watch;
        if (current != null && !current.isDone()) {
            return;
        }
        try {
            watch = scheduler.schedule(this::checkJournal, LimitRun.INTERVAL);
        } catch (RejectedExecutionException shutDown) {
            // The limits end at their next step.
        }
    }

    private void checkJournal() {
        watch = null;
        if (runs.isEmpty() || refusing) {
            return;
        }
        if (!coordinator.verifyJournal()) {
            stopAllForJournal(coordinator.journalProblem());
            return;
        }
        watchJournal();
    }

    private final class RunListener implements LimitRun.Listener {
        @Override
        public void changed(LimitRun run) {
            publish();
        }

        @Override
        public void ended(LimitRun run, LimitRun.End end, Optional<String> reason) {
            runs.remove(run.identity(), run);
            ProcessIdentity identity = run.identity();
            Optional<ManagedProcess> process = registry.find(identity);
            String target =
                    process.map(p -> p.name() + " (PID " + identity.pid() + ")").orElse("PID " + identity.pid());
            switch (end) {
                case CANCELLED -> coordinator.withdraw(identity, SuspensionReason.CPU_LIMITER);
                case EXITED -> {
                    coordinator.withdraw(identity, SuspensionReason.CPU_LIMITER);
                    // The one exit path: a native memory limit's job is let go here too, after a native
                    // operation in flight for the process (D6).
                    whenFree(identity, () -> {
                        processExited(identity);
                        watched.set(watchedCount());
                    });
                }
                case FAILED -> {
                    coordinator.withdraw(identity, SuspensionReason.CPU_LIMITER);
                    String why = reason.orElse("the limit failed");
                    failures.put(identity, why);
                    activity.record(
                            ActivitySeverity.ERROR,
                            "The CPU limit of " + target + " stopped: " + why + ". PRISM no longer stops it.");
                }
                case JOURNAL -> {
                    // The entry on disk stays as evidence (ADR-0011); the coordinator has told the
                    // activity log. A later write of a retried journal does not carry it.
                    coordinator.dropDeclaration(identity, SuspensionReason.CPU_LIMITER);
                    failures.put(identity, "journal: " + reason.orElse("the journal failed"));
                    failureNotes.put(identity, LimitNote.JOURNAL);
                }
                case SHUTDOWN -> {
                    // The shutdown sequence releases and withdraws.
                }
            }
            pushToGuard();
            publish();
        }
    }

    // ----- What the user is shown -----

    private void publish() {
        LimitCapability cpu = cpuCapability();
        LimitCapability memory = memoryCapability();
        List<ManagedProcessView> views = new ArrayList<>();
        for (ManagedProcess process : registry.all()) {
            Map<LimitKind, LimitCapability> capabilities = new EnumMap<>(LimitKind.class);
            capabilities.putAll(probed.getOrDefault(process.identity(), Map.of()));
            if (!nativeCpuSystem()) {
                capabilities.put(LimitKind.CPU, cpu);
            }
            capabilities.putIfAbsent(LimitKind.CPU, cpu);
            capabilities.putIfAbsent(LimitKind.MEMORY, memory);
            Optional<FallbackOffer> offer = offered.contains(process.identity())
                    ? Optional.of(new FallbackOffer(
                            failures.getOrDefault(process.identity(), "")
                                    .replaceFirst("^the native limit was not applied: ", ""),
                            mostActiveLimits(),
                            processors,
                            perThreadSuspension()))
                    : Optional.empty();
            views.add(new ManagedProcessView(
                    process,
                    cpuReport(process, cpu),
                    memoryReport(process, memory),
                    capabilities,
                    offer,
                    Optional.ofNullable(residentOffered.get(process.identity()))));
        }
        Optional<String> limitsOff = coordinator.journalHealthy()
                ? Optional.empty()
                : Optional.of((nativeCpuSystem() ? "Emulated limits are off" : "Limits are off")
                        + ": PRISM cannot record them where it would find them after a crash"
                        + coordinator
                                .journalProblem()
                                .map(problem -> " (" + problem + ").")
                                .orElse("."));
        LimitsSnapshot snapshot = new LimitsSnapshot(views, cpu, memory, limitsOff, notices());
        latest = snapshot;
        try {
            snapshots.accept(snapshot);
        } catch (RuntimeException e) {
            warnOnce("The listener for limits", e);
        }
    }

    private List<LimitsNotice> notices() {
        List<LimitsNotice> notices = new ArrayList<>();
        if ((nativeCpuSystem() || nativeMemorySystem()) && !natives.ledger().healthy()) {
            notices.add(new LimitsNotice(
                    LimitsNotice.Kind.LEDGER_FAILED,
                    "New native limits are refused: PRISM cannot record them where it would find them after a crash ("
                            + natives.ledger().problem().orElse("") + "). Limits already in force stay."));
        }
        for (Residue residue : residues.values()) {
            notices.add(new LimitsNotice(
                    LimitsNotice.Kind.RESIDUE,
                    "The " + holder() + " of " + residue.name() + " (PID "
                            + residue.exited().pid()
                            + "), which has exited, still carries"
                            + " limits PRISM could not clear ("
                            + residue.inForce().describe() + "); "
                            + (residue.survivors() < 0
                                    ? "the processes"
                                    : residue.survivors() + (residue.survivors() == 1 ? " process" : " processes"))
                            + " it started stay limited. PRISM keeps trying."));
        }
        for (RecoveredLimit earlier : priorSession.values()) {
            notices.add(new LimitsNotice(
                    LimitsNotice.Kind.PRIOR_SESSION,
                    earlier.record().name() + " (PID "
                            + earlier.record().identity().pid() + ") may still be capped natively"
                            + " by an earlier PRISM session ("
                            + earlier.record().describe() + "). No second limiter is"
                            + " offered; that limit lasts until it exits."));
        }
        // Said whatever today's native CPU capability, as the barrier holds whatever it is.
        natives.earlier()
                .pending()
                .ifPresent(why -> notices.add(new LimitsNotice(
                        LimitsNotice.Kind.RECOVERY_PENDING,
                        "Not settled yet: " + why + ". Until it is, no emulated limit is offered for processes"
                                + " that ran while it was written.")));
        if (selfInJob.orElse(false)) {
            notices.add(
                    new LimitsNotice(
                            LimitsNotice.Kind.SELF_IN_JOB,
                            "PRISM runs inside a Job Object; processes started by the same program cannot be limited natively."));
        }
        if (!nativeCpuSystem()
                && natives.limiter().capabilities().get(LimitKind.CPU).reason().isPresent()
                && nativeMemorySystem()) {
            notices.add(new LimitsNotice(
                    LimitsNotice.Kind.NATIVE_UNAVAILABLE,
                    "Native CPU limits are not available on this system ("
                            + natives.limiter()
                                    .capabilities()
                                    .get(LimitKind.CPU)
                                    .reason()
                                    .get()
                            + "); CPU limits here are emulated."));
        }
        return notices;
    }

    private LimitReport cpuReport(ManagedProcess process, LimitCapability capability) {
        ProcessIdentity identity = process.identity();
        OptionalInt requested = process.settings().cpuLimitPercent();
        Optional<NativeHold> hold = shownHold(identity);
        boolean nativeCpu = hold.flatMap(NativeHold::inForce)
                .flatMap(limits -> limits.cpuRate().isPresent() ? Optional.of(limits) : Optional.empty())
                .isPresent();
        RecoveredLimit earlier = priorSession.get(identity);
        if (requested.isEmpty()
                && earlier == null
                && !failures.containsKey(identity)
                && !nativeCpu
                && !(hold.isPresent() && !hold.get().settled())) {
            return LimitReport.none(LimitKind.CPU, capabilityFor(identity, LimitKind.CPU, capability));
        }
        if (earlier != null) {
            return report(
                    requested,
                    LimitCapability.unavailable("an earlier PRISM session may still cap it natively"),
                    LimitStatus.UNAVAILABLE,
                    Optional.of("an earlier PRISM session may still cap it natively ("
                            + earlier.record().describe() + ")"),
                    LimitNote.PRIOR_SESSION);
        }
        LimitRun run = runs.get(identity);
        if (run != null) {
            LimitRun.State state = run.state();
            LimitCapability emulated = LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.DUTY_CYCLE);
            LimitNote note =
                    process.settings().emulatedCpuApproved() && nativeCpuSystem() && state.note() == LimitNote.NONE
                            ? LimitNote.EMULATED_BY_CHOICE
                            : state.note();
            return new LimitReport(
                    LimitKind.CPU,
                    requested,
                    emulated,
                    state.status(),
                    state.detail(),
                    note,
                    percent(state.measured()),
                    percent(state.lowest()));
        }
        if (!capability.available()) {
            return report(requested, capability, LimitStatus.UNAVAILABLE, capability.reason(), LimitNote.NONE);
        }
        String failure = failures.get(identity);
        if (failure != null) {
            LimitNote note = failureNotes.getOrDefault(
                    identity, failure.startsWith("journal: ") ? LimitNote.JOURNAL : LimitNote.NONE);
            LimitCapability shown = note == LimitNote.FALLBACK_OFFERED
                    ? capabilityFor(identity, LimitKind.CPU, capability)
                    : nativeCpuSystem() ? nativeCapability(LimitKind.CPU) : capability;
            return report(requested, shown, LimitStatus.FAILED, Optional.of(failure), note);
        }
        if (hold.isPresent() && !hold.get().settled()) {
            return report(
                    requested,
                    nativeCapability(LimitKind.CPU),
                    LimitStatus.FAILED,
                    Optional.of("in force: " + candidates(identity)),
                    LimitNote.NATIVE_UNSETTLED);
        }
        if (!process.settings().enabled()) {
            return report(requested, capability, LimitStatus.INACTIVE, Optional.of("disabled"), LimitNote.DISABLED);
        }
        if (nativeCpu) {
            EffectiveLimits limits = hold.get().inForce().orElseThrow();
            boolean asked = requested.isPresent() && limits.cpuPercent().equals(requested);
            return report(
                    requested,
                    nativeCapability(LimitKind.CPU),
                    asked ? LimitStatus.ACTIVE_ENFORCED : LimitStatus.FAILED,
                    Optional.of((asked ? "enforced: " : "requested " + requested.orElse(0) + " %, in force: ")
                            + limits.withMemoryBytes(OptionalLong.empty()).describe()),
                    asked ? LimitNote.NONE : LimitNote.NATIVE_UNSETTLED);
        }
        return report(requested, capability, LimitStatus.INACTIVE, Optional.of("not at work"), LimitNote.NOT_AT_WORK);
    }

    private LimitReport memoryReport(ManagedProcess process, LimitCapability capability) {
        ProcessIdentity identity = process.identity();
        OptionalLong requested = process.settings().memoryLimitBytes();
        Optional<NativeHold> hold = shownHold(identity);
        Optional<EffectiveLimits> inForce = hold.flatMap(NativeHold::inForce);
        boolean nativeMemory =
                inForce.filter(limits -> limits.memoryBytes().isPresent()).isPresent();
        LimitCapability shown = capabilityFor(identity, LimitKind.MEMORY, capability);
        if (requested.isEmpty() && !nativeMemory) {
            return LimitReport.none(LimitKind.MEMORY, shown);
        }
        String failure = memoryFailures.get(identity);
        if (failure != null) {
            return memory(
                    requested,
                    nativeMemorySystem() ? nativeCapability(LimitKind.MEMORY) : shown,
                    LimitStatus.FAILED,
                    Optional.of(failure),
                    LimitNote.NATIVE_UNSETTLED);
        }
        if (hold.isPresent() && !hold.get().settled()) {
            return memory(
                    requested,
                    nativeCapability(LimitKind.MEMORY),
                    LimitStatus.FAILED,
                    Optional.of("in force: " + candidates(identity)),
                    LimitNote.NATIVE_UNSETTLED);
        }
        if (!process.settings().enabled()) {
            return memory(requested, shown, LimitStatus.INACTIVE, Optional.of("disabled"), LimitNote.DISABLED);
        }
        Optional<OomGuard.PolicyStatus> policy = watchedLimit(process).isPresent()
                ? guard.flatMap(oomGuard -> oomGuard.policyStatus(identity))
                : Optional.empty();
        if (policy.isPresent()) {
            return policyReport(requested, policy.get());
        }
        String offeredBecause = residentOffered.get(identity);
        if (offeredBecause != null && !nativeMemory) {
            return memory(
                    requested,
                    nativeCapability(LimitKind.MEMORY),
                    LimitStatus.FAILED,
                    Optional.of("the native limit was not applied: " + offeredBecause),
                    LimitNote.FALLBACK_OFFERED);
        }
        if (nativeMemory) {
            long bytes = inForce.get().memoryBytes().getAsLong();
            boolean asked = requested.isPresent() && requested.getAsLong() == bytes;
            return memory(
                    requested,
                    nativeCapability(LimitKind.MEMORY),
                    asked ? LimitStatus.ACTIVE_ENFORCED : LimitStatus.FAILED,
                    Optional.of((asked ? "enforced: at most " : "in force: at most ") + EffectiveLimits.bytes(bytes)
                            + " committed"),
                    asked ? LimitNote.NONE : LimitNote.NATIVE_UNSETTLED);
        }
        if (!shown.available()) {
            return memory(requested, shown, LimitStatus.UNAVAILABLE, shown.reason(), LimitNote.NONE);
        }
        return memory(requested, shown, LimitStatus.INACTIVE, Optional.of("not at work"), LimitNote.NOT_AT_WORK);
    }

    /** A watched resident memory limit (LIM-9): never called a cap or enforced (CAP-3). */
    private static LimitReport policyReport(OptionalLong requested, OomGuard.PolicyStatus policy) {
        String resident = policy.residentBytes().isPresent()
                ? EffectiveLimits.bytes(policy.residentBytes().getAsLong()) + " resident"
                : "resident memory not read yet";
        LimitNote note = policy.held()
                ? LimitNote.POLICY_HOLD_USER_ENDS
                : policy.acted() ? LimitNote.POLICY_ACTED : LimitNote.NONE;
        String detail = "watched, not a cap: " + resident
                + (policy.exceeded() ? ", above the limit" : "")
                + policy.notNow().map(why -> "; " + why).orElse("");
        return memory(
                requested,
                LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.RESIDENT_POLICY),
                policy.exceeded() ? LimitStatus.ACTIVE_ABOVE_TARGET : LimitStatus.ACTIVE_NOT_THROTTLING,
                Optional.of(detail),
                note);
    }

    private LimitCapability capabilityFor(ProcessIdentity identity, LimitKind kind, LimitCapability system) {
        if (kind == LimitKind.CPU && !nativeCpuSystem()) {
            return system;
        }
        if (kind == LimitKind.MEMORY && !nativeMemorySystem()) {
            return system;
        }
        if (shownHold(identity).isPresent()) {
            return system.available() ? nativeCapability(kind) : system;
        }
        LimitCapability found = probed.getOrDefault(identity, Map.of()).get(kind);
        return found != null ? found : system;
    }

    private static LimitReport report(
            OptionalInt requested,
            LimitCapability capability,
            LimitStatus status,
            Optional<String> detail,
            LimitNote note) {
        return new LimitReport(
                LimitKind.CPU,
                requested,
                capability,
                status,
                detail,
                note,
                OptionalDouble.empty(),
                OptionalDouble.empty());
    }

    private static LimitReport memory(
            OptionalLong requested,
            LimitCapability capability,
            LimitStatus status,
            Optional<String> detail,
            LimitNote note) {
        return new LimitReport(
                LimitKind.MEMORY,
                OptionalInt.empty(),
                capability,
                status,
                detail,
                note,
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                requested);
    }

    private static OptionalDouble percent(OptionalDouble share) {
        return share.isPresent() ? OptionalDouble.of(share.getAsDouble() * 100) : OptionalDouble.empty();
    }

    private static LimitResult answer(LimitRequest request, OperationResult result, String detail, Wording wording) {
        return LimitMessages.result(request, ControlOutcome.of(result, detail), wording);
    }

    /** The activity log gets the user's sentence and, for whoever reads the log, what lies behind it. */
    private void record(LimitResult result) {
        ControlOutcome outcome = result.outcome();
        StringBuilder entry = new StringBuilder(
                        LimitMessages.actionName(result.request().action()))
                .append(": ")
                .append(result.text());
        outcome.detail().ifPresent(detail -> entry.append(" [").append(detail).append(']'));
        activity.record(ControlMessages.severity(outcome.result()), entry.toString());
    }

    private void deliver(LimitResult result) {
        try {
            results.accept(result);
        } catch (RuntimeException e) {
            warnOnce("The listener for limit results", e);
        }
    }

    private void warnOnce(String what, RuntimeException failure) {
        synchronized (failuresLogged) {
            failuresLogged.warnOnce(what, failure);
        }
    }
}
