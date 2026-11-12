package prism.srm.app;

import java.time.Duration;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import prism.srm.application.ActivityLog;
import prism.srm.application.EarlierLimits;
import prism.srm.application.LimitService;
import prism.srm.application.LimiterScheduler;
import prism.srm.application.NativeLimitLedger;
import prism.srm.application.NativeRecovery;
import prism.srm.application.OomGuard;
import prism.srm.application.OomObservation;
import prism.srm.application.OomStateMachine;
import prism.srm.application.ProcessControlService;
import prism.srm.application.ProcessDetailsService;
import prism.srm.application.ProcessMonitor;
import prism.srm.application.ProtectionRules;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.application.SuspensionJournal;
import prism.srm.application.SystemMonitor;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.Listed;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomView;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryCheck;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspendedProcess;
import prism.srm.domain.SystemSnapshot;
import prism.srm.domain.VersionedSettings;

/**
 * Owns monitoring while the application runs: it creates the two sampling executors and the
 * executor for on-demand process details, starts the monitors, and shuts all of it down again.
 * Nothing else creates, uses or stops these executors.
 *
 * <p><b>Threads.</b> Every thread has a name and one job:
 *
 * <ul>
 *   <li>{@code srm-startup}: creates the platform, which queries the operating system and can take
 *       a second, then starts system monitoring. It ends when that runs.
 *   <li>{@code srm-system-monitor}: the system metrics cycle, once a second.
 *   <li>{@code srm-process-monitor}: the process listing, every two seconds. It has an executor
 *       of its own so that a slow listing never delays a system sample. It starts with the
 *       first system snapshot that carries the number of logical processors.
 *   <li>{@code srm-process-details}: reads the path and the command line of one process when the
 *       user interface asks for them (PROC-5). It exists from the first request on.
 *   <li>{@code srm-process-control}: carries out the actions on processes the user asks for, one at
 *       a time, and checks whether a process Prism holds suspended and a listing missed has
 *       exited (CTL-7). It exists from the first request on.
 *   <li>{@code srm-limiter}: managed processes and their CPU limits: the requests of the user,
 *       the steps of every limit, and a look at the suspension journal about once a second while
 *       limits run (ADR-0011). Its first work comes with process monitoring, whose count of
 *       logical processors the limits are measured against. OOM Guard's interventions run here
 *       too (ADR-0015): one evaluation per memory sample and per listing at most, never more than
 *       one waiting.
 *   <li>{@code srm-shutdown}: the controlled shutdown.
 * </ul>
 *
 * <p><b>The suspension journal</b> is opened at start-up, before anything is suspended; the
 * journals of earlier sessions that ended without a controlled shutdown are read then and what
 * they recorded is listed for the user, resuming nothing (ADR-0011). The controlled shutdown
 * closes it. {@link #releaseForExit()} is for the shutdown hook of the JVM.
 *
 * <p><b>Actions on processes are never dropped.</b> Their executor has one thread and a bounded
 * queue; an action it cannot take is answered as refused, never discarded unseen, and the service
 * takes at most one action per process at a time. Until the shutdown, every request gets exactly
 * one result; a request still waiting when the shutdown's time bound runs out may get none.
 *
 * <p>None of {@link #start()}, {@link #shutdown()} and {@link #requestProcessDetails} blocks, so
 * all may be called on the JavaFX Application Thread. Each sampling executor holds one periodic
 * task and nothing else, so its queue cannot grow.
 *
 * <p><b>Process details are read one at a time, the newest request first.</b> Their executor has
 * one thread and room for one waiting request. A request that arrives while one waits takes its
 * place: the user has moved on to another process by then, and the result of the request that is
 * dropped would not be shown. So a fast walk through the process table costs one read at a time
 * and never builds a queue. Process monitoring does not wait for these reads, nor they for it.
 *
 * <p><b>Processes are listed whichever page is shown.</b> The listing runs from start-up on by
 * decision, so that the idle cost measured for MON-12 is the cost the product has on every page.
 *
 * <p><b>Exit.</b> Only {@code srm-shutdown} is a non-daemon thread. The process therefore ends
 * when the shutdown sequence has finished, and the sequence is bounded in time, so a sampling
 * thread stuck in a native call cannot keep the process alive.
 *
 * <p><b>Failures.</b> A {@link RuntimeException} during start-up is recorded and reported to the
 * user interface. An {@link Error} is not caught: it ends the start-up thread and reaches the
 * handler for uncaught failures, and the user interface is told that monitoring did not start.
 *
 * <p>A monitor that ends on its own while the application runs, which only an {@link Error} in
 * its sampling loop brings about, tells this class through its termination listener. The failure
 * is recorded once and handed on, so that the user interface stops showing "Monitoring" for
 * something that has ended. Monitors that end because of {@link #shutdown()} are not failures and
 * are not reported. No monitor is started again: what an {@link Error} ended stays ended.
 */
final class MonitoringRuntime {

    /** Where the runtime is in its life. It only ever moves forward. */
    enum State {
        NEW,
        STARTING,
        RUNNING,
        /** Start-up ended without monitoring; shutdown is still to come. */
        FAILED,
        STOPPING,
        STOPPED
    }

    private static final String STARTUP_THREAD = "srm-startup";
    private static final String SYSTEM_THREAD = "srm-system-monitor";
    private static final String PROCESS_THREAD = "srm-process-monitor";
    private static final String DETAILS_THREAD = "srm-process-details";
    private static final String CONTROL_THREAD = "srm-process-control";
    private static final String LIMITER_THREAD = "srm-limiter";
    private static final String JOURNAL_THREAD = "srm-journal";
    private static final String RECOVERY_THREAD = "srm-recovery";

    /**
     * When the journals of earlier sessions are looked at again while one of them waits for its lock
     * (its session has ended; Windows lets a killed process's locks go "depending upon available
     * system resources"): after each of these delays, about two minutes in all, and no more; a
     * journal still waiting then is read at the next start.
     */
    static final List<Duration> RECOVERY_RETRIES = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            Duration.ofSeconds(8),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60));

    /** Writes and checks of the journal that may wait; the writer coalesces, so few ever do. */
    static final int JOURNAL_QUEUE = 64;

    /** The longest the shutdown waits for a step of a CPU limit to end. */
    static final Duration LIMITER_STOP_BOUND = Duration.ofSeconds(1);

    /**
     * The longest the release at an exit without the controlled shutdown takes in all (LIFE-5), every
     * wait in it counted against one deadline. On Windows a closed console gives it as long as {@code
     * SPI_GETHUNGAPPTIMEOUT}, 5000 ms by default (HandlerRoutine, Timeouts), and what runs without a
     * bound (a resume under the lock, the log) needs the rest.
     */
    static final Duration EXIT_BOUND = Duration.ofMillis(2_500);

    /** Of {@link #EXIT_BOUND}, the most the release of the suspensions may take, so that the native limits get the rest. */
    static final Duration EXIT_SUSPENSIONS_BOUND = Duration.ofMillis(1_500);

    /** The longest the shutdown hook waits for an intervention of OOM Guard begun before it stopped. */
    static final Duration ACTION_BOUND = Duration.ofMillis(250);

    /** The longest the shutdown spends restoring the priorities OOM Guard lowered (plan O7, A17). */
    static final Duration RESTORE_BOUND = Duration.ofSeconds(1);

    /** Actions and checks that may wait for the control thread; more are refused, not dropped. */
    static final int CONTROL_QUEUE = 32;

    private static final String SHUTDOWN_THREAD = "srm-shutdown";

    /**
     * Who receives what the runtime produces. Each receives on the thread that produced it and
     * must return quickly.
     *
     * @param snapshots every system snapshot, on the sampling thread
     * @param processSnapshots the process snapshots of every listing, on the process sampling thread
     * @param processDetails the result of every read of process details, on the thread that read them
     * @param controlResults the result of every action on a process; none may be dropped on the way to
     *     the user
     * @param controlProfile how each action is provided and which priority level each listed priority
     *     stands for, once, when monitoring runs
     * @param heldSuspensions the processes Prism holds suspended each time they change
     * @param onFailure called once for each failure, on the thread it happened on, with all failures so
     *     far, so that only the newest call matters to whoever shows them. The calls are made one at a
     *     time and in the order the failures were noted; the receiver must return at once, must not
     *     throw and must not call back into this class. Not called for anything that happens once a
     *     shutdown has begun.
     * @param limits what the Limits page shows, each time it changes, on the limiter thread
     * @param limitResults the result of every request about managed processes and limits; none may be
     *     dropped
     * @param recovered the processes an earlier session may have left suspended, each time the list
     *     changes
     * @param recoveryCheck whether that list is whole: {@code PENDING} when a journal of an earlier
     *     session cannot be read yet at start-up, then {@code COMPLETE} once all are read or
     *     {@code EXHAUSTED} when Prism stops looking; nothing while all were read at start-up
     * @param recoveryResults the result of every request about such a process; none may be dropped
     * @param oom what OOM Guard shows, each time it evaluates, on the limiter thread
     */
    record Listeners(
            Consumer<SystemSnapshot> snapshots,
            Consumer<List<ProcessSnapshot>> processSnapshots,
            Consumer<ProcessDetailsResult> processDetails,
            Consumer<ProcessControlResult> controlResults,
            Consumer<ControlProfile> controlProfile,
            Consumer<List<SuspendedProcess>> heldSuspensions,
            Consumer<Set<MonitoringFailure>> onFailure,
            Consumer<LimitsSnapshot> limits,
            Consumer<LimitResult> limitResults,
            Consumer<List<RecoveredSuspension>> recovered,
            Consumer<RecoveryCheck> recoveryCheck,
            Consumer<RecoveryResult> recoveryResults,
            Consumer<OomView> oom) {

        Listeners {
            Objects.requireNonNull(snapshots, "snapshots");
            Objects.requireNonNull(processSnapshots, "processSnapshots");
            Objects.requireNonNull(processDetails, "processDetails");
            Objects.requireNonNull(controlResults, "controlResults");
            Objects.requireNonNull(controlProfile, "controlProfile");
            Objects.requireNonNull(heldSuspensions, "heldSuspensions");
            Objects.requireNonNull(onFailure, "onFailure");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(limitResults, "limitResults");
            Objects.requireNonNull(recovered, "recovered");
            Objects.requireNonNull(recoveryCheck, "recoveryCheck");
            Objects.requireNonNull(recoveryResults, "recoveryResults");
            Objects.requireNonNull(oom, "oom");
        }

        /** As milestone 10 had them: what OOM Guard shows goes nowhere. */
        Listeners(
                Consumer<SystemSnapshot> snapshots,
                Consumer<List<ProcessSnapshot>> processSnapshots,
                Consumer<ProcessDetailsResult> processDetails,
                Consumer<ProcessControlResult> controlResults,
                Consumer<ControlProfile> controlProfile,
                Consumer<List<SuspendedProcess>> heldSuspensions,
                Consumer<Set<MonitoringFailure>> onFailure,
                Consumer<LimitsSnapshot> limits,
                Consumer<LimitResult> limitResults,
                Consumer<List<RecoveredSuspension>> recovered,
                Consumer<RecoveryCheck> recoveryCheck,
                Consumer<RecoveryResult> recoveryResults) {
            this(
                    snapshots,
                    processSnapshots,
                    processDetails,
                    controlResults,
                    controlProfile,
                    heldSuspensions,
                    onFailure,
                    limits,
                    limitResults,
                    recovered,
                    recoveryCheck,
                    recoveryResults,
                    view -> {});
        }
    }

    /** What the shutdown thread stops, as the runtime held it when the shutdown began. */
    private record Stoppable(
            Thread startup,
            SystemMonitor system,
            ProcessMonitor processes,
            ProcessControlService control,
            SuspensionCoordinator suspensions,
            LimitService limits,
            SuspensionCoordinator journalOwner,
            OomGuard guard) {}

    private final Supplier<PlatformSources> platform;
    private final LongSupplier nanoClock;
    private final ActivityLog activity;
    private final Listeners listeners;
    private final long shutdownBoundNanos;
    private final Thread.UncaughtExceptionHandler uncaught;
    private final ScheduledThreadPoolExecutor systemExecutor;
    private final ScheduledThreadPoolExecutor processExecutor;
    private final ThreadPoolExecutor detailsExecutor;
    private final ThreadPoolExecutor controlExecutor;
    private final ScheduledThreadPoolExecutor limiterExecutor;
    private final ThreadPoolExecutor journalExecutor;
    private final ScheduledThreadPoolExecutor recoveryExecutor;
    private final List<Duration> recoveryRetries;
    private final LimiterScheduler limiter;
    private volatile Consumer<String> beforeShutdownStep = name -> {};
    private final CountDownLatch stopped = new CountDownLatch(1);

    private final Object lock = new Object();
    // The ten below are guarded by the lock.
    private State state = State.NEW;
    private Thread startupThread;
    private SystemMonitor systemMonitor;
    private ProcessMonitor processMonitor;
    private ProcessDetailsService detailsService;
    private ProcessControlService controlService;
    private SuspensionCoordinator coordinator;
    private LimitService limitService;
    /**
     * The coordinator that writes the journal, published as soon as the journal is open: a shutdown during the recovery
     * of earlier sessions closes the journal through it, within its bound, never directly, which
     * could wait without end for a write the disk does not finish.
     */
    private SuspensionCoordinator journalOwner;
    /** The limit service once it exists, for the journal listener, which may not take the lock above. */
    private volatile LimitService limitServiceSeen;

    /** OOM Guard once it exists, for the monitor thread and the journal listener; guarded by nothing else. */
    private volatile OomGuard guardSeen;

    /**
     * The OOM settings as published, with their version (ADR-0015 section 2): written by {@link
     * #publishOomSettings} only, read once per sample by the monitor thread and once per decision by
     * OOM Guard.
     */
    private final AtomicReference<VersionedSettings> oomSettings;

    /** The OOM state machine: the system monitor's thread only, like the two below. */
    private final OomStateMachine oomMachine = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);

    private boolean oomReadingMissing;

    /** This session's native-limit ledger and the look at earlier ones (milestone 10); set at start-up. */
    private volatile NativeLimitLedger nativeLedger = NativeLimitLedger.none();

    private volatile NativeRecovery nativeRecovery;

    private final EnumSet<MonitoringFailure> failures = EnumSet.noneOf(MonitoringFailure.class);

    // The two below are used on the system sampling thread only.
    private boolean processMonitoringDecided;
    private boolean processorCountMissed;

    /**
     * @param platform creates the platform and returns its sources; called once, on the start-up
     *     thread
     * @param nanoClock monotonic clock, such as {@code System::nanoTime}
     * @param listeners who receives what the runtime produces
     * @param shutdownBound the longest the shutdown sequence waits, all its waits together
     * @param uncaught receives what ends one of the threads created here
     */
    MonitoringRuntime(
            Supplier<PlatformSources> platform,
            LongSupplier nanoClock,
            ActivityLog activity,
            Listeners listeners,
            Duration shutdownBound,
            Thread.UncaughtExceptionHandler uncaught) {
        this(platform, nanoClock, activity, listeners, shutdownBound, uncaught, RECOVERY_RETRIES);
    }

    /** As above, with other delays between the looks at a journal that waits for its lock: for tests. */
    MonitoringRuntime(
            Supplier<PlatformSources> platform,
            LongSupplier nanoClock,
            ActivityLog activity,
            Listeners listeners,
            Duration shutdownBound,
            Thread.UncaughtExceptionHandler uncaught,
            List<Duration> recoveryRetries) {
        this(platform, nanoClock, activity, listeners, shutdownBound, uncaught, recoveryRetries, OomSettings.DEFAULTS);
    }

    /**
     * @param oom the OOM settings read at start-up (SET-1): published as version 1 before anything is
     *     monitored
     */
    MonitoringRuntime(
            Supplier<PlatformSources> platform,
            LongSupplier nanoClock,
            ActivityLog activity,
            Listeners listeners,
            Duration shutdownBound,
            Thread.UncaughtExceptionHandler uncaught,
            List<Duration> recoveryRetries,
            OomSettings oom) {
        this.oomSettings = new AtomicReference<>(new VersionedSettings(1, Objects.requireNonNull(oom, "oom")));
        this.recoveryRetries = List.copyOf(recoveryRetries);
        if (this.recoveryRetries.isEmpty()) {
            throw new IllegalArgumentException("recoveryRetries must not be empty");
        }
        this.platform = Objects.requireNonNull(platform, "platform");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.uncaught = Objects.requireNonNull(uncaught, "uncaught");
        if (shutdownBound.isNegative() || shutdownBound.isZero()) {
            throw new IllegalArgumentException("shutdownBound must be positive: " + shutdownBound);
        }
        this.shutdownBoundNanos = shutdownBound.toNanos();
        this.systemExecutor = newSamplingExecutor(SYSTEM_THREAD);
        this.processExecutor = newSamplingExecutor(PROCESS_THREAD);
        this.detailsExecutor = newDetailsExecutor();
        this.controlExecutor = newControlExecutor();
        this.limiterExecutor = newScheduledExecutor(LIMITER_THREAD);
        this.recoveryExecutor = newScheduledExecutor(RECOVERY_THREAD);
        this.journalExecutor = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(JOURNAL_QUEUE),
                task -> newThread(task, JOURNAL_THREAD, true),
                new ThreadPoolExecutor.AbortPolicy());
        this.limiter = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                limiterExecutor.execute(task);
            }

            @Override
            public Future<?> schedule(Runnable task, Duration delay) {
                return limiterExecutor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            }
        };
    }

    /**
     * Starts monitoring in the background and returns at once.
     *
     * @throws IllegalStateException if the runtime was started or shut down before
     */
    void start() {
        Thread thread;
        synchronized (lock) {
            if (state != State.NEW) {
                throw new IllegalStateException("Monitoring cannot be started in state " + state);
            }
            state = State.STARTING;
            thread = newThread(this::startUp, STARTUP_THREAD, true);
            startupThread = thread;
        }
        thread.start();
    }

    /**
     * Starts the controlled shutdown on its own thread and returns at once. Calling it again, or
     * before {@link #start()}, is harmless.
     */
    void shutdown() {
        Stoppable stoppable;
        synchronized (lock) {
            if (state == State.STOPPING || state == State.STOPPED) {
                return;
            }
            // From here on no monitor is started: whoever would start one checks the state first.
            state = State.STOPPING;
            stoppable = new Stoppable(
                    startupThread,
                    systemMonitor,
                    processMonitor,
                    controlService,
                    coordinator,
                    limitService,
                    journalOwner,
                    guardSeen);
        }
        newThread(() -> shutDown(stoppable), SHUTDOWN_THREAD, false).start();
    }

    /**
     * Asks for the path and the command line of one process. Returns at once: the read runs on
     * its own thread, and its result goes to the receiver given at construction. A request that
     * is still waiting when the next one arrives is dropped in favor of the newer one.
     *
     * <p>Nothing happens while monitoring does not run. No process is listed then either, so
     * there is nothing a request could be about.
     */
    void requestProcessDetails(ProcessIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        ProcessDetailsService service;
        synchronized (lock) {
            if (state != State.RUNNING) {
                return;
            }
            service = detailsService;
        }
        service.request(identity);
    }

    /**
     * Asks for an action on a process. Returns at once: the action runs on its own thread, and its
     * result goes to the receiver given at construction. While monitoring does not run, the request
     * is answered at once as not carried out, so that whoever waits for a result gets one.
     */
    void requestProcessAction(ControlRequest request) {
        Objects.requireNonNull(request, "request");
        ProcessControlService service;
        synchronized (lock) {
            service = state == State.RUNNING ? controlService : null;
        }
        if (service == null) {
            listeners.controlResults().accept(ProcessControlService.notCarriedOut(request));
            return;
        }
        service.request(request);
    }

    /**
     * Asks to manage a process, or to change its limits. Returns at once: the request runs on the
     * limiter thread. Until process monitoring runs, and after it, the request is answered at once
     * as not carried out.
     */
    void requestLimit(LimitRequest request) {
        Objects.requireNonNull(request, "request");
        LimitService service;
        synchronized (lock) {
            service = state == State.RUNNING ? limitService : null;
        }
        if (service == null) {
            listeners.limitResults().accept(LimitService.notCarriedOut(request));
            return;
        }
        service.request(request);
    }

    /**
     * Publishes changed OOM settings (ADR-0015 section 2): the only writer. Settings equal to those in
     * force change nothing; otherwise the version goes up by one, and OOM Guard is told after the new
     * settings are in place. Returns the version in force. The JavaFX Application Thread only, the
     * single writer; returns at once.
     */
    long publishOomSettings(OomSettings next) {
        Objects.requireNonNull(next, "next");
        VersionedSettings before = oomSettings.get();
        Supplier<VersionedSettings> publication = () -> oomSettings.updateAndGet(previous ->
                previous.settings().equals(next) ? previous : new VersionedSettings(previous.version() + 1, next));
        OomGuard guard = guardSeen;
        // In order with the start of every intervention: none authorized under the old settings begins
        // after this returns (ADR-0015 §2). Before OOM Guard exists, nothing can act.
        VersionedSettings now = guard == null ? publication.get() : guard.publishSettings(publication);
        if (now.version() != before.version()) {
            if (guard != null) {
                guard.settingsChanged();
            }
            activity.record(ActivitySeverity.INFO, "OOM Guard settings changed: " + describe(next) + ".");
        }
        return now.version();
    }

    /** The OOM settings in force and their version. Any thread. */
    VersionedSettings oomSettings() {
        return oomSettings.get();
    }

    /** The user ends what OOM Guard holds on the process. Any thread; returns at once. */
    void releaseOomHold(ProcessIdentity identity) {
        OomGuard guard = guardSeen;
        if (guard != null) {
            guard.releaseHold(identity);
        }
    }

    /**
     * Runs the task on the journal's thread, which writes the settings file too (ADR-0014). Any thread.
     *
     * @throws java.util.concurrent.RejectedExecutionException once the shutdown has closed it
     */
    void executeOnJournalThread(Runnable task) {
        journalExecutor.execute(task);
    }

    private static String describe(OomSettings settings) {
        return "monitoring " + (settings.switches().monitoring() ? "on" : "off") + ", warning "
                + settings.thresholds().warningPercent() + " %, critical "
                + settings.thresholds().criticalPercent() + " %, emergency "
                + settings.thresholds().emergencyPercent() + " % or under "
                + (settings.thresholds().emergencyFloorBytes() >> 20) + " MiB; automatic priority reduction "
                + (settings.switches().priorityReduction() ? "on" : "off") + ", automatic suspend "
                + (settings.switches().autoSuspend() ? "on" : "off") + ", emergency termination "
                + (settings.switches().termination() ? "on" : "off");
    }

    /**
     * Asks to resume, or to dismiss, a process an earlier session may have left suspended. Returns
     * at once: it runs on the control thread. While monitoring does not run, it is answered at once.
     */
    void requestRecovery(RecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        ProcessControlService service;
        synchronized (lock) {
            service = state == State.RUNNING ? controlService : null;
        }
        if (service == null) {
            listeners.recoveryResults().accept(ProcessControlService.recoveryNotCarriedOut(request));
            return;
        }
        service.recover(request);
    }

    /**
     * For the shutdown hook of the JVM, when it exits without the controlled shutdown (LIFE-5): no
     * limit suspends again, what the limiter holds is released through the live suspensions, then the
     * native limits, all within one deadline of {@link #EXIT_BOUND}: the suspensions first, at most
     * {@link #EXIT_SUSPENSIONS_BOUND}, the native limits with what is left. What the user suspended
     * stays suspended. Silent and quick when nothing is held, as after a completed shutdown. Any
     * thread. Real time, not the runtime's clock: the system that ends the process counts real time.
     */
    void releaseForExit() {
        long deadline = System.nanoTime() + EXIT_BOUND.toNanos();
        SuspensionCoordinator suspensions;
        LimitService limits;
        synchronized (lock) {
            suspensions = coordinator;
            limits = limitService;
        }
        // First: the limiter thread is a daemon and may still run an evaluation of OOM Guard; from now
        // on it makes no native call. A lowered priority is not restored here (plan O7).
        OomGuard guard = guardSeen;
        if (guard != null) {
            guard.stop();
            // None begins from here on; one already begun may end within a short bound (ADR-0015 §6).
            if (!guard.awaitActionsEnded(ACTION_BOUND)) {
                activity.record(
                        ActivitySeverity.WARNING,
                        "An intervention of OOM Guard begun before the exit had not ended within "
                                + ACTION_BOUND.toMillis() + " ms; Prism exits without waiting for it.");
            }
        }
        if (limits != null) {
            limits.stopForShutdown();
        }
        if (suspensions != null) {
            suspensions.releaseForExit(Duration.ofNanos(Math.min(left(deadline), EXIT_SUSPENSIONS_BOUND.toNanos())));
        }
        if (limits != null) {
            // Milestone 10 (plan D8): the native limits too, the one chance before the names go.
            limits.releaseNativeLimits(Duration.ofNanos(left(deadline)));
        }
    }

    /** What is left in real time until {@code deadline}, never negative; for {@link #releaseForExit}. */
    private static long left(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** What has failed so far; empty while everything that was started still runs. For tests. */
    Set<MonitoringFailure> failures() {
        synchronized (lock) {
            return Set.copyOf(failures);
        }
    }

    /** Told the name of each step of the shutdown before it runs; what it throws fails that step. For tests. */
    void beforeShutdownSteps(Consumer<String> observer) {
        beforeShutdownStep = Objects.requireNonNull(observer, "observer");
    }

    /** Waits until the shutdown sequence has finished; for tests. */
    boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    /** Whether every executor has ended all its work; for tests. */
    boolean executorsTerminated() {
        return systemExecutor.isTerminated()
                && processExecutor.isTerminated()
                && detailsExecutor.isTerminated()
                && controlExecutor.isTerminated()
                && limiterExecutor.isTerminated()
                && journalExecutor.isTerminated()
                && recoveryExecutor.isTerminated();
    }

    private void startUp() {
        boolean concluded = false;
        try {
            concluded = startMonitoring();
        } catch (RuntimeException e) {
            concluded = true;
            activity.record(ActivitySeverity.ERROR, "Monitoring could not start.", e);
            startupFailed();
        } finally {
            if (!concluded) {
                // An Error is on its way out of this thread. It is not caught; the handler for
                // uncaught failures logs it. The user interface must still not wait for ever.
                activity.record(ActivitySeverity.ERROR, "Monitoring could not start: a fatal error ended start-up.");
                startupFailed();
            }
        }
    }

    /** Returns when monitoring runs, or when a shutdown made starting it pointless. */
    private boolean startMonitoring() {
        PlatformSources sources = platform.get();
        SystemMonitor system =
                new SystemMonitor(sources.systemMetrics(), systemExecutor, nanoClock, SystemMonitor.DEFAULT_INTERVAL);
        Optional<SuspensionJournal> opened = sources.journalFolder()
                .map(folder -> SuspensionJournal.open(folder, sources.instance(), SuspensionJournal.onDisk()));
        SuspensionCoordinator suspensions = new SuspensionCoordinator(
                sources.controller(), activity, controlExecutor, InstantSource.system(), opened, journalExecutor);
        suspensions.setHeldListener(listeners.heldSuspensions());
        suspensions.setRecoveredListener(listeners.recovered());
        // A journal that fails stops every limit; the limit service exists once process monitoring runs.
        suspensions.setJournalListener(this::journalFailed);
        // Milestone 10: the ledger of this session's native limits, written on the journal's thread,
        // and a look at the ledger of every ended session while its lock is held.
        NativeLimitLedger ledger = new NativeLimitLedger(
                opened.isPresent() ? sources.journalFolder() : Optional.empty(),
                sources.instance(),
                SuspensionJournal.onDisk(),
                journalExecutor);
        // Only where a Prism session can have set native limits at all: elsewhere (Linux, macOS in this
        // version) no ledger is looked for. The milestone 9 emulated path is unchanged there, except that on
        // Linux it refuses a process inside a prism-srm-limit-*.scope (milestone 12 scope-membership rule).
        NativeRecovery recovery = new NativeRecovery(
                sources.limiter(),
                ledger,
                sources.limiter().mayHaveLeftLimits() ? sources.journalFolder() : Optional.empty(),
                SuspensionJournal.onDisk(),
                activity);
        nativeLedger = ledger;
        nativeRecovery = recovery;
        suspensions.setDeadSessionListener(instance -> {
            var found = recovery.lookAt(instance);
            LimitService seen = limitServiceSeen;
            if (seen != null) {
                seen.recovered(found);
            }
        });
        ProcessControlService control = new ProcessControlService(
                sources.controller(),
                new ProtectionRules(sources.ownPid()),
                suspensions,
                activity,
                controlExecutor,
                listeners.controlResults());
        control.setRecoveryListener(listeners.recoveryResults());
        system.addListener(listeners.snapshots());
        // OOM-13: every sample, not the coalesced feed of the user interface.
        system.addListener(this::oomSample);
        system.addListener(snapshot -> startProcessMonitoring(snapshot, sources, suspensions));
        system.addTerminationListener(() -> monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        ProcessDetailsService details =
                new ProcessDetailsService(sources.processes(), detailsExecutor, listeners.processDetails());
        synchronized (lock) {
            if (state != State.STARTING) {
                // Shut down while the platform was being created. Nothing was started.
                opened.ifPresent(SuspensionJournal::close);
                return true;
            }
            journalOwner = suspensions;
        }
        reportJournal(opened);
        // Before anything is suspended in this session: what earlier sessions left is listed, not resumed.
        int waiting = suspensions.recoverEarlierSessions();
        recovery.lookAtStartUp();
        synchronized (lock) {
            if (state != State.STARTING) {
                // Shut down during the recovery; the shutdown closes the journal.
                return true;
            }
            system.start();
            systemMonitor = system;
            detailsService = details;
            controlService = control;
            coordinator = suspensions;
            state = State.RUNNING;
        }
        listeners.controlProfile().accept(control.profile());
        activity.record(
                ActivitySeverity.INFO,
                "Monitoring started: system metrics every " + SystemMonitor.DEFAULT_INTERVAL.toSeconds() + " s.");
        for (String limitation : control.limitations()) {
            activity.record(ActivitySeverity.WARNING, limitation);
        }
        if (waiting > 0) {
            activity.record(
                    ActivitySeverity.WARNING,
                    waiting + " suspension journal(s) of an earlier session of Prism cannot be read yet: that"
                            + " session has ended, but the system still holds its lock. Until Prism has read it,"
                            + " the list of processes an earlier session may have left suspended may be"
                            + " incomplete. Prism keeps trying for about two minutes.");
            listeners.recoveryCheck().accept(RecoveryCheck.PENDING);
            lookAgainForEarlierSessions(suspensions, 0);
        }
        return true;
    }

    /**
     * Looks at the journals of earlier sessions again after the delay of this attempt, on its own
     * thread: never on the JavaFX thread, never on the limiter's. What is read is listed through the
     * listener of recovered suspensions as at start-up; nothing is resumed.
     */
    private void lookAgainForEarlierSessions(SuspensionCoordinator suspensions, int attempt) {
        try {
            recoveryExecutor.schedule(
                    () -> lookAgain(suspensions, attempt),
                    recoveryRetries.get(attempt).toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // The shutdown has begun: what waits is read at the next start.
        }
    }

    private void lookAgain(SuspensionCoordinator suspensions, int attempt) {
        synchronized (lock) {
            if (state != State.RUNNING) {
                return;
            }
        }
        int waiting;
        try {
            waiting = suspensions.recoverEarlierSessions();
            // A ledger whose session's lock is still held keeps the emulated limit from the processes it
            // may name; one read meanwhile lets it go (audit item 2). An orphan that could not be proven
            // ended at start-up is looked at again here, so its barrier is not held for the session.
            NativeRecovery recovery = nativeRecovery;
            if (recovery != null) {
                recovery.lookAtOrphans();
            }
        } catch (RuntimeException failed) {
            activity.record(
                    ActivitySeverity.WARNING, "The journals of earlier sessions could not be looked at again.", failed);
            waiting = -1;
        }
        if (waiting == 0) {
            activity.record(
                    ActivitySeverity.INFO,
                    "The suspension journals of earlier sessions of Prism have all been read now; what they"
                            + " recorded is listed.");
            listeners.recoveryCheck().accept(RecoveryCheck.COMPLETE);
        } else if (attempt + 1 < recoveryRetries.size()) {
            lookAgainForEarlierSessions(suspensions, attempt + 1);
        } else {
            activity.record(
                    ActivitySeverity.WARNING,
                    "A suspension journal of an earlier session of Prism could still not be read: its file stays"
                            + " and is read at the next start. The list of processes an earlier session may have"
                            + " left suspended may be incomplete until then.");
            listeners.recoveryCheck().accept(RecoveryCheck.EXHAUSTED);
        }
    }

    private void reportJournal(Optional<SuspensionJournal> opened) {
        if (opened.isEmpty()) {
            activity.record(
                    ActivitySeverity.WARNING,
                    "There is no folder for the suspension journal: CPU limits are off, and a suspension is not"
                            + " recorded for after a crash.");
            return;
        }
        SuspensionJournal.Health health = opened.get().health();
        if (!health.healthy()) {
            activity.record(
                    ActivitySeverity.ERROR,
                    "The suspension journal cannot be written ("
                            + health.problem().orElse("") + "): CPU limits are"
                            + " off, and a suspension is not recorded for after a crash.");
        }
    }

    /**
     * The coordinator's journal listener: any thread, under the coordinator's lock, so it takes no
     * lock of its own and reads the limit service from its volatile copy.
     */
    private void journalFailed(String problem) {
        LimitService limits = limitServiceSeen;
        if (limits != null) {
            limits.journalFailed(problem);
        }
        OomGuard guard = guardSeen;
        if (guard != null) {
            guard.journalFailed(problem);
        }
    }

    /**
     * Judges one memory sample on the system monitor's thread (OOM-13) and hands the observation to
     * OOM Guard, which acts on another thread. One read of the settings for the sample: the
     * observation carries the version it was judged under. Microseconds; no native call.
     */
    private void oomSample(SystemSnapshot snapshot) {
        VersionedSettings settings = oomSettings.get();
        long now = nanoClock.getAsLong();
        OomStateMachine.Transition transition = oomMachine.observe(snapshot.memory(), now, settings.settings());
        logOom(transition);
        OomGuard guard = guardSeen;
        if (guard != null) {
            guard.observed(OomObservation.of(transition, settings, now));
        }
    }

    /** LOG-2: state changes, and a reading that stops and starts again; never a tick (LOG-3). */
    private void logOom(OomStateMachine.Transition transition) {
        switch (transition.kind()) {
            case ENTERED ->
                activity.record(
                        ActivitySeverity.WARNING,
                        "Memory pressure " + words(transition.to()) + ": "
                                + transition
                                        .reading()
                                        .map(MonitoringRuntime::available)
                                        .orElse("")
                                + " available.");
            case LEFT ->
                activity.record(
                        ActivitySeverity.INFO,
                        "Memory pressure is down to " + words(transition.to()) + ": "
                                + transition
                                        .reading()
                                        .map(MonitoringRuntime::available)
                                        .orElse("")
                                + " available.");
            case HOLD -> {
                if (!oomReadingMissing) {
                    oomReadingMissing = true;
                    activity.record(
                            ActivitySeverity.WARNING,
                            "OOM Guard has no memory reading: it holds its state and starts no new action until"
                                    + " it has one.");
                }
                return;
            }
            default -> {
                // A sample like the one before.
            }
        }
        if (oomReadingMissing && transition.kind() != OomStateMachine.Transition.Kind.OFF) {
            oomReadingMissing = false;
            activity.record(ActivitySeverity.INFO, "OOM Guard has a memory reading again.");
        }
    }

    private static String words(prism.srm.domain.OomState state) {
        return state.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String available(prism.srm.domain.OomReading reading) {
        return String.format(java.util.Locale.ROOT, "%.1f %%", reading.availablePercent());
    }

    /**
     * Starts process monitoring with the first system snapshot that has the number of logical
     * processors: the CPU share of a process is computed against it. The number is taken from the
     * snapshot, where the system sampler reads it again every cycle until it has it, so a read
     * that fails once does not cost process monitoring for the whole session.
     *
     * <p>Called on the system sampling thread only; the two flags are confined to it.
     */
    private void startProcessMonitoring(
            SystemSnapshot snapshot, PlatformSources sources, SuspensionCoordinator suspensions) {
        if (processMonitoringDecided) {
            return;
        }
        if (snapshot.processor().isEmpty()) {
            if (!processorCountMissed) {
                processorCountMissed = true;
                activity.record(
                        ActivitySeverity.WARNING,
                        "Process monitoring has not started: the number of logical processors could not be"
                                + " read. It starts when the number is known.");
            }
            return;
        }
        processMonitoringDecided = true;
        int logicalProcessors = snapshot.processor().get().logicalProcessorCount();
        ProcessMonitor processes = new ProcessMonitor(
                sources.processes(), processExecutor, nanoClock, logicalProcessors, ProcessMonitor.DEFAULT_INTERVAL);
        // OOM Guard acts on the limiter thread, which owns the managed processes (ADR-0015).
        OomGuard guard = new OomGuard(
                sources.controller(),
                new ProtectionRules(sources.ownPid()),
                suspensions,
                activity,
                limiter,
                nanoClock,
                InstantSource.system(),
                oomSettings::get,
                listeners.oom(),
                OomGuard.Tuning.DEFAULTS,
                sources.oomSignal());
        // The CPU limits are measured against the same count of logical processors as the table.
        LimitService limits = new LimitService(
                sources.controller(),
                new ProtectionRules(sources.ownPid()),
                suspensions,
                sources.processes(),
                activity,
                limiter,
                nanoClock,
                logicalProcessors,
                sources.limiter(),
                nativeLedger,
                nativeRecovery == null ? EarlierLimits.NONE : nativeRecovery,
                guard,
                listeners.limitResults(),
                listeners.limits());
        NativeRecovery recovery = nativeRecovery;
        if (recovery != null) {
            limits.recovered(recovery.found());
        }
        for (String limitation : sources.limiter().limitations()) {
            activity.record(ActivitySeverity.INFO, limitation);
        }
        // Every listing shows which processes Prism holds suspended, from Prism's own record, and
        // tells the coordinator which of them the listing missed; neither asks the system anything.
        // The limit service checks a managed process the listing missed, on its own thread.
        processes.addListener(listing -> {
            listeners.processSnapshots().accept(suspensions.observe(listing));
            // OOM Guard first: its evaluation of this listing comes before the limit service's look,
            // which shows the watched memory of the same listing (LIM-9). Nothing of the listing is kept.
            Map<ProcessIdentity, Listed> listed = new HashMap<>(listing.size() * 2);
            for (ProcessSnapshot process : listing) {
                listed.put(process.identity(), Listed.of(process));
            }
            guard.listed(listed);
            limits.listed(listing.stream().map(ProcessSnapshot::identity).collect(Collectors.toUnmodifiableSet()));
        });
        processes.addTerminationListener(() -> monitorEnded(MonitoringFailure.PROCESS_MONITORING_STOPPED));
        boolean started;
        synchronized (lock) {
            // Not after a shutdown has begun: its thread has already taken the monitors to stop.
            started = state == State.RUNNING;
            if (started) {
                processes.start();
                processMonitor = processes;
                limitService = limits;
                limitServiceSeen = limits;
                guardSeen = guard;
            }
        }
        if (started) {
            limits.announce();
            // Again, now that the listener of later looks sees this service: a look that ended
            // between the first hand-over above and this point is delivered by one side or the
            // other, never by neither (native-api review, Stage 4). Taking the same results twice
            // changes nothing.
            if (recovery != null) {
                limits.recovered(recovery.found());
            }
        }
        if (started) {
            activity.record(
                    ActivitySeverity.INFO,
                    "Process monitoring started: every " + ProcessMonitor.DEFAULT_INTERVAL.toSeconds() + " s, "
                            + logicalProcessors + " logical processors.");
        }
    }

    private void startupFailed() {
        report(MonitoringFailure.STARTUP);
    }

    /**
     * Called by a monitor's termination listener, on its sampling thread, when the monitor has
     * ended on its own. The failure that ended it is still being thrown and has been logged by
     * the monitor; this adds what it means, once.
     */
    void monitorEnded(MonitoringFailure failure) {
        String which = switch (failure) {
            case SYSTEM_MONITORING_STOPPED -> "System monitoring";
            case PROCESS_MONITORING_STOPPED -> "Process monitoring";
            case STARTUP -> throw new IllegalArgumentException("Not the end of a monitor: " + failure);
        };
        if (failure == MonitoringFailure.SYSTEM_MONITORING_STOPPED) {
            // O5: OOM Guard reads nothing more; what it holds stays until the user ends it.
            OomGuard guard = guardSeen;
            if (guard != null) {
                guard.monitorEnded();
            }
        }
        if (report(failure)) {
            activity.record(
                    ActivitySeverity.ERROR,
                    which + " stopped unexpectedly and does not resume in this session. The error that ended it"
                            + " is logged before this line."
                            + (failure == MonitoringFailure.SYSTEM_MONITORING_STOPPED && processMonitoringNeverStarted()
                                    ? " Process monitoring had not started yet and now cannot start."
                                    : ""));
        }
    }

    /**
     * Process monitoring starts with a system snapshot. Asked on the system sampling thread once
     * that thread's loop has ended, so the answer cannot change afterwards.
     */
    private boolean processMonitoringNeverStarted() {
        synchronized (lock) {
            return processMonitor == null;
        }
    }

    /**
     * Notes a failure and hands on all failures so far. Returns false, and does nothing, if the
     * failure was reported before or does not count: a start-up that fails and a monitor that
     * ends once a shutdown has begun are what the shutdown was going to do to them anyway.
     */
    private boolean report(MonitoringFailure failure) {
        synchronized (lock) {
            State whereItCounts = failure == MonitoringFailure.STARTUP ? State.STARTING : State.RUNNING;
            if (state != whereItCounts || !failures.add(failure)) {
                return false;
            }
            if (failure == MonitoringFailure.STARTUP) {
                state = State.FAILED;
            }
            if (failure == MonitoringFailure.PROCESS_MONITORING_STOPPED && controlService != null) {
                // No action that needs the process as it is now is taken any more; releasing a
                // suspension Prism holds still is, so nothing it suspended is stranded. Before the
                // failure is handed on, so whoever learns of it finds the actions narrowed already.
                controlService.observationLost();
            }
            // Handed on inside the lock: two monitors can end at the same moment on two threads,
            // and the larger set must not be overtaken by the smaller one on its way to the
            // user interface, which keeps the newest only.
            listeners.onFailure().accept(Set.copyOf(failures));
        }
        return true;
    }

    private void shutDown(Stoppable stopping) {
        Thread startup = stopping.startup();
        SystemMonitor system = stopping.system();
        ProcessMonitor processes = stopping.processes();
        ProcessControlService control = stopping.control();
        SuspensionCoordinator suspensions = stopping.suspensions();
        LimitService limits = stopping.limits();
        OomGuard guard = stopping.guard();
        long deadline = nanoClock.getAsLong() + shutdownBoundNanos;
        activity.record(ActivitySeverity.INFO, "Shutdown started.");
        List<String> failed = new ShutdownSequence(activity, beforeShutdownStep)
                .step("stop system monitoring", () -> {
                    if (system != null) {
                        system.stop();
                    }
                })
                .step("stop process monitoring", () -> {
                    if (processes != null) {
                        processes.stop();
                    }
                })
                .step("wait for start-up to end", () -> awaitStartup(startup, deadline))
                // A look at earlier journals that waits is called off; one in flight is interrupted, and
                // a journal it holds is let go and kept for the next start.
                .step("stop looking at earlier sessions", () -> {
                    recoveryExecutor.shutdownNow();
                    long bound = Math.min(deadline, nanoClock.getAsLong() + LIMITER_STOP_BOUND.toNanos());
                    awaitExecutor(recoveryExecutor, "A look at an earlier journal", RECOVERY_THREAD, bound);
                })
                // The limits first, so that nothing is suspended any more by the time Prism releases:
                // no new suspension and no new limit, then every limit ends without touching its
                // process, and its thread stops. What the limiter holds is released further down.
                .step("refuse new suspensions and limits", () -> {
                    if (guard != null) {
                        guard.stop();
                    }
                    if (suspensions != null) {
                        suspensions.refuseFurtherSuspensions();
                    }
                    if (limits != null) {
                        limits.refuseFurtherLimits();
                    }
                })
                .step("stop limiting", () -> {
                    if (limits != null) {
                        limits.stopForShutdown();
                    }
                    limiterExecutor.shutdown();
                    long bound = Math.min(deadline, nanoClock.getAsLong() + LIMITER_STOP_BOUND.toNanos());
                    awaitExecutor(limiterExecutor, "A step of a CPU limit", LIMITER_THREAD, bound);
                })
                .step("stop the sampling executors", () -> {
                    systemExecutor.shutdown();
                    processExecutor.shutdown();
                })
                // Nobody is left to show a result: a request that waits is dropped, and a read
                // in flight is interrupted at once. A native read does not answer the interrupt;
                // it is waited for below, like a sampling cycle.
                .step("stop reading process details", detailsExecutor::shutdownNow)
                // Process actions, in an order that stays right when the limiter and OOM Guard hold
                // suspensions too: nothing new is taken; what was taken comes to rest; then what
                // Prism holds by itself is released; what the user suspended stays (LIFE-1, LIFE-2).
                .step("refuse new process actions", () -> {
                    if (control != null) {
                        control.refuseFurtherRequests();
                    }
                    if (suspensions != null) {
                        suspensions.refuseFurtherSuspensions();
                    }
                })
                .step("finish process actions in flight", () -> {
                    controlExecutor.shutdown();
                    awaitExecutor(controlExecutor, "A process action", CONTROL_THREAD, deadline);
                })
                .step("release automatic suspensions", () -> {
                    if (suspensions != null) {
                        suspensions.releaseAutomaticReasons(Duration.ofNanos(remaining(deadline)));
                    }
                })
                // Plan O7: after the resumes, the priorities OOM Guard lowered, on this thread, without a
                // queue, within its own bound; only once the limiter thread has ended, whose state it reads.
                .step("restore priorities OOM Guard lowered", () -> {
                    if (guard != null && limiterExecutor.isTerminated()) {
                        guard.restorePriorities(
                                Duration.ofNanos(Math.min(remaining(deadline), RESTORE_BOUND.toNanos())));
                    } else if (guard != null) {
                        activity.record(
                                ActivitySeverity.WARNING,
                                "Priorities OOM Guard lowered were not restored: the limiter did not stop in time.");
                    }
                })
                .step("finalize suspensions", () -> {
                    if (suspensions != null) {
                        suspensions.finalizeForShutdown(Duration.ofNanos(remaining(deadline)));
                    }
                })
                // LIFE-1 step 3 (milestone 10): after the suspensions, every native limit is cleared and
                // verified, every job let go, and the ledger written; an empty ledger goes.
                .step("clear native limits", () -> {
                    if (limits != null) {
                        limits.releaseNativeLimits(Duration.ofNanos(remaining(deadline)));
                    } else {
                        nativeLedger.close(Duration.ofNanos(remaining(deadline)));
                    }
                })
                // An empty journal goes; one that names what the user left suspended stays.
                .step("close the suspension journal", () -> {
                    // Through its coordinator, within the bound, also when the shutdown came during
                    // the recovery: a direct close could wait without end behind a stalled write.
                    if (stopping.journalOwner() != null) {
                        stopping.journalOwner().closeJournal(Duration.ofNanos(remaining(deadline)));
                    }
                    journalExecutor.shutdown();
                })
                .step(
                        "wait for system monitoring in flight",
                        () -> awaitExecutor(systemExecutor, "A sampling cycle", SYSTEM_THREAD, deadline))
                .step(
                        "wait for process monitoring in flight",
                        () -> awaitExecutor(processExecutor, "A sampling cycle", PROCESS_THREAD, deadline))
                .step(
                        "wait for the journal in flight",
                        () -> awaitExecutor(journalExecutor, "A write of the journal", JOURNAL_THREAD, deadline))
                .step(
                        "wait for a read of process details in flight",
                        () -> awaitExecutor(detailsExecutor, "A read of process details", DETAILS_THREAD, deadline))
                .run();
        synchronized (lock) {
            state = State.STOPPED;
        }
        if (failed.isEmpty()) {
            activity.record(ActivitySeverity.INFO, "Shutdown complete: monitoring and its executors are stopped.");
        } else {
            activity.record(ActivitySeverity.WARNING, "Shutdown complete, with failed steps: " + failed + ".");
        }
        stopped.countDown();
    }

    /** A start-up still creating the platform is waited for, then told to stop. */
    private void awaitStartup(Thread startup, long deadline) {
        if (startup == null) {
            return;
        }
        try {
            startup.join(Duration.ofNanos(remaining(deadline)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (startup.isAlive()) {
            startup.interrupt();
            activity.record(
                    ActivitySeverity.WARNING,
                    "Start-up did not end within the shutdown time and was interrupted (thread " + STARTUP_THREAD
                            + ").");
        }
    }

    /**
     * Work in flight is waited for until the shutdown time is used up; what outlasts it is
     * interrupted, or interrupted again if it was told to stop before.
     *
     * @param work what the executor runs, as the beginning of a sentence for the log
     */
    private void awaitExecutor(ThreadPoolExecutor executor, String work, String name, long deadline) {
        boolean terminated = false;
        try {
            terminated = executor.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            executor.shutdownNow();
            activity.record(
                    ActivitySeverity.WARNING,
                    work + " did not end within the shutdown time and was interrupted (thread " + name + ").");
        }
    }

    /** What is left of the shutdown time; once it is used up, the waits return at once. */
    private long remaining(long deadline) {
        return Math.max(0, deadline - nanoClock.getAsLong());
    }

    private ScheduledThreadPoolExecutor newSamplingExecutor(String threadName) {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(1, task -> newThread(task, threadName, true));
        // A cancelled cycle leaves the queue at once, and nothing runs after shutdown().
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * One thread, and room for one request that waits. A request that finds that room taken
     * takes the place of the one waiting there, so the newest request is always served and
     * nothing queues up. After shutdown a request is dropped without a word.
     */
    private ThreadPoolExecutor newDetailsExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                task -> newThread(task, DETAILS_THREAD, true),
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /**
     * One thread and a bounded queue. A task the queue has no room for is rejected, which the
     * caller answers: an action is never dropped unseen. Nothing runs after shutdown that was not
     * taken before it.
     */
    private ThreadPoolExecutor newControlExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CONTROL_QUEUE),
                task -> newThread(task, CONTROL_THREAD, true),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * A thread of its own for delayed and immediate tasks (the limiter's, the later looks at earlier
     * journals'), nothing after shutdown. A task that ends with a failure its owner did not take,
     * which only an {@link Error} does, is handed to the handler for uncaught failures: a scheduled
     * executor would otherwise keep it in a future nobody reads.
     */
    private ScheduledThreadPoolExecutor newScheduledExecutor(String name) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> newThread(task, name, true)) {
            @Override
            protected void afterExecute(Runnable task, Throwable thrown) {
                super.afterExecute(task, thrown);
                reportFailedTask(task);
            }
        };
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private void reportFailedTask(Runnable task) {
        if (!(task instanceof Future<?> future) || !future.isDone() || future.isCancelled()) {
            return;
        }
        try {
            future.get();
        } catch (ExecutionException failed) {
            uncaught.uncaughtException(Thread.currentThread(), failed.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread newThread(Runnable task, String name, boolean daemon) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler(uncaught);
        return thread;
    }
}
