package prism.srm.app;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import prism.srm.application.ActivityLog;
import prism.srm.application.ProcessDetailsService;
import prism.srm.application.ProcessMonitor;
import prism.srm.application.SystemMonitor;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.SystemSnapshot;
import prism.srm.platform.ProcessSource;

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
 *   <li>{@code srm-shutdown}: the controlled shutdown.
 * </ul>
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
    private static final String SHUTDOWN_THREAD = "srm-shutdown";

    private final Supplier<PlatformSources> platform;
    private final LongSupplier nanoClock;
    private final ActivityLog activity;
    private final Consumer<SystemSnapshot> snapshots;
    private final Consumer<List<ProcessSnapshot>> processSnapshots;
    private final Consumer<ProcessDetailsResult> processDetails;
    private final Consumer<Set<MonitoringFailure>> onFailure;
    private final long shutdownBoundNanos;
    private final Thread.UncaughtExceptionHandler uncaught;
    private final ScheduledThreadPoolExecutor systemExecutor;
    private final ScheduledThreadPoolExecutor processExecutor;
    private final ThreadPoolExecutor detailsExecutor;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private final Object lock = new Object();
    // The six below are guarded by the lock.
    private State state = State.NEW;
    private Thread startupThread;
    private SystemMonitor systemMonitor;
    private ProcessMonitor processMonitor;
    private ProcessDetailsService detailsService;
    private final EnumSet<MonitoringFailure> failures = EnumSet.noneOf(MonitoringFailure.class);

    // The two below are used on the system sampling thread only.
    private boolean processMonitoringDecided;
    private boolean processorCountMissed;

    /**
     * @param platform creates the platform and returns its sources; called once, on the start-up
     *     thread
     * @param nanoClock monotonic clock, such as {@code System::nanoTime}
     * @param snapshots receives every system snapshot on the sampling thread and must return
     *     quickly
     * @param processSnapshots receives the process snapshots of every listing on the process
     *     sampling thread and must return quickly
     * @param processDetails receives the result of every read of process details, on the thread
     *     that read them, and must return quickly
     * @param onFailure called once for each failure, on the thread it happened on, with all
     *     failures so far, so that only the newest call matters to whoever shows them. The calls
     *     are made one at a time and in the order the failures were noted; the receiver must
     *     return at once, must not throw and must not call back into this class. Not called for
     *     anything that happens once a shutdown has begun.
     * @param shutdownBound the longest the shutdown sequence waits, all its waits together
     * @param uncaught receives what ends one of the threads created here
     */
    MonitoringRuntime(
            Supplier<PlatformSources> platform,
            LongSupplier nanoClock,
            ActivityLog activity,
            Consumer<SystemSnapshot> snapshots,
            Consumer<List<ProcessSnapshot>> processSnapshots,
            Consumer<ProcessDetailsResult> processDetails,
            Consumer<Set<MonitoringFailure>> onFailure,
            Duration shutdownBound,
            Thread.UncaughtExceptionHandler uncaught) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.processSnapshots = Objects.requireNonNull(processSnapshots, "processSnapshots");
        this.processDetails = Objects.requireNonNull(processDetails, "processDetails");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.uncaught = Objects.requireNonNull(uncaught, "uncaught");
        if (shutdownBound.isNegative() || shutdownBound.isZero()) {
            throw new IllegalArgumentException("shutdownBound must be positive: " + shutdownBound);
        }
        this.shutdownBoundNanos = shutdownBound.toNanos();
        this.systemExecutor = newSamplingExecutor(SYSTEM_THREAD);
        this.processExecutor = newSamplingExecutor(PROCESS_THREAD);
        this.detailsExecutor = newDetailsExecutor();
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
        Thread startup;
        SystemMonitor system;
        ProcessMonitor processes;
        synchronized (lock) {
            if (state == State.STOPPING || state == State.STOPPED) {
                return;
            }
            // From here on no monitor is started: whoever would start one checks the state first.
            state = State.STOPPING;
            startup = startupThread;
            system = systemMonitor;
            processes = processMonitor;
        }
        newThread(() -> shutDown(startup, system, processes), SHUTDOWN_THREAD, false)
                .start();
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

    /** Waits until the shutdown sequence has finished; for tests. */
    boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    /** Whether every executor has ended all its work; for tests. */
    boolean executorsTerminated() {
        return systemExecutor.isTerminated() && processExecutor.isTerminated() && detailsExecutor.isTerminated();
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
        system.addListener(snapshots);
        system.addListener(snapshot -> startProcessMonitoring(snapshot, sources.processes()));
        system.addTerminationListener(() -> monitorEnded(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        ProcessDetailsService details = new ProcessDetailsService(sources.processes(), detailsExecutor, processDetails);
        synchronized (lock) {
            if (state != State.STARTING) {
                // Shut down while the platform was being created. Nothing was started.
                return true;
            }
            system.start();
            systemMonitor = system;
            detailsService = details;
            state = State.RUNNING;
        }
        activity.record(
                ActivitySeverity.INFO,
                "Monitoring started: system metrics every " + SystemMonitor.DEFAULT_INTERVAL.toSeconds() + " s.");
        return true;
    }

    /**
     * Starts process monitoring with the first system snapshot that has the number of logical
     * processors: the CPU share of a process is computed against it. The number is taken from the
     * snapshot, where the system sampler reads it again every cycle until it has it, so a read
     * that fails once does not cost process monitoring for the whole session.
     *
     * <p>Called on the system sampling thread only; the two flags are confined to it.
     */
    private void startProcessMonitoring(SystemSnapshot snapshot, ProcessSource source) {
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
                source, processExecutor, nanoClock, logicalProcessors, ProcessMonitor.DEFAULT_INTERVAL);
        processes.addListener(processSnapshots);
        processes.addTerminationListener(() -> monitorEnded(MonitoringFailure.PROCESS_MONITORING_STOPPED));
        boolean started;
        synchronized (lock) {
            // Not after a shutdown has begun: its thread has already taken the monitors to stop.
            started = state == State.RUNNING;
            if (started) {
                processes.start();
                processMonitor = processes;
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
            // Handed on inside the lock: two monitors can end at the same moment on two threads,
            // and the larger set must not be overtaken by the smaller one on its way to the
            // user interface, which keeps the newest only.
            onFailure.accept(Set.copyOf(failures));
        }
        return true;
    }

    private void shutDown(Thread startup, SystemMonitor system, ProcessMonitor processes) {
        long deadline = nanoClock.getAsLong() + shutdownBoundNanos;
        activity.record(ActivitySeverity.INFO, "Shutdown started.");
        List<String> failed = new ShutdownSequence(activity)
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
                .step("stop the sampling executors", () -> {
                    systemExecutor.shutdown();
                    processExecutor.shutdown();
                })
                // Nobody is left to show a result: a request that waits is dropped, and a read
                // in flight is interrupted at once. A native read does not answer the interrupt;
                // it is waited for below, like a sampling cycle.
                .step("stop reading process details", detailsExecutor::shutdownNow)
                .step(
                        "wait for system monitoring in flight",
                        () -> awaitExecutor(systemExecutor, "A sampling cycle", SYSTEM_THREAD, deadline))
                .step(
                        "wait for process monitoring in flight",
                        () -> awaitExecutor(processExecutor, "A sampling cycle", PROCESS_THREAD, deadline))
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

    private Thread newThread(Runnable task, String name, boolean daemon) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler(uncaught);
        return thread;
    }
}
