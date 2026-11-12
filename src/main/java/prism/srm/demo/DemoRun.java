package prism.srm.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * One run of the workload, from the start line to the end line: allocate the memory, start the
 * workers, say every few seconds what is going on, stop everything when the duration is over or a
 * stop is requested, and say why it ended. It runs on the calling thread and creates only the worker
 * threads, which it joins before it returns.
 *
 * <p>Everything it prints is one {@code event=...} line of {@code key=value} pairs, ASCII only, so a
 * person can read it next to Prism and a test can look for the keys.
 */
final class DemoRun {

    /**
     * Why a run ended, with the exit code that goes with it. After a signal the exit code is not
     * the run's to choose: the JVM exits with its own code for the signal once the hook returns.
     */
    enum Reason {
        COMPLETED("completed", 0),
        ALLOCATION_FAILED("allocation-failed", 1),
        INTERRUPTED("interrupted", 0);

        private final String text;
        private final int exitCode;

        Reason(String text, int exitCode) {
            this.text = text;
            this.exitCode = exitCode;
        }

        int exitCode() {
            return exitCode;
        }

        String exitText() {
            return this == INTERRUPTED ? "signal" : Integer.toString(exitCode);
        }
    }

    static final Duration DEFAULT_STATUS_INTERVAL = Duration.ofSeconds(5);
    static final String WORKER_THREAD_PREFIX = "prism-demo-cpu-";
    private static final Duration WORKER_STOP_BOUND = Duration.ofSeconds(2);

    private static final long RAMP_STEP_BYTES = 256 * Quantities.MIB;
    private static final long RAMP_INTERVAL_NANOS = Duration.ofMillis(500).toNanos();

    private final DemoArguments arguments;
    private final HostFacts host;
    private final MemoryWorkload memory;
    private final LongSupplier processCpuTimeNanos;
    private final PrintStream out;
    private final Duration statusInterval;
    private final boolean interactive;

    private final AtomicBoolean stopWorkers = new AtomicBoolean();
    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicReference<Reason> reason = new AtomicReference<>(Reason.COMPLETED);
    private final AtomicBoolean oomRamp = new AtomicBoolean();
    private final ReentrantLock waitLock = new ReentrantLock();
    private final Condition wakeCondition = waitLock.newCondition();

    /**
     * @param memory the memory to hold, not yet allocated; created on the thread that will run
     * @param processCpuTimeNanos the CPU time of this process, or a negative number when unknown
     * @param statusInterval how often a status line is printed
     */
    DemoRun(
            DemoArguments arguments,
            HostFacts host,
            MemoryWorkload memory,
            LongSupplier processCpuTimeNanos,
            PrintStream out,
            Duration statusInterval) {
        this(arguments, host, memory, processCpuTimeNanos, out, statusInterval, false);
    }

    DemoRun(
            DemoArguments arguments,
            HostFacts host,
            MemoryWorkload memory,
            LongSupplier processCpuTimeNanos,
            PrintStream out,
            Duration statusInterval,
            boolean interactive) {
        if (statusInterval.isZero() || statusInterval.isNegative()) {
            throw new IllegalArgumentException("status interval must be positive");
        }
        this.arguments = arguments;
        this.host = host;
        this.memory = memory;
        this.processCpuTimeNanos = processCpuTimeNanos;
        this.out = out;
        this.statusInterval = statusInterval;
        this.interactive = interactive;
    }

    /** Runs to the end and returns the exit code. Call once, on the thread that created the memory. */
    int run() {
        long start = System.nanoTime();
        try {
            printStart();
            MemoryWorkload.Allocation allocation = memory.allocate();
            printMemory(allocation);
            int workersAlive = 0;
            if (allocation.failed()) {
                reason.set(Reason.ALLOCATION_FAILED);
            } else {
                if (interactive) {
                    startInteractiveListener();
                }
                List<Thread> workers = startWorkers();
                waitForTheEnd(start, allocation.allocatedBytes());
                workersAlive = stopWorkers(workers);
            }
            Reason why = reason.get();
            printEnd(why, start, workersAlive);
            return why.exitCode();
        } finally {
            // Whatever ended the run, also a failure nothing expected: no worker goes on, nothing
            // stays held, and whoever waits for the end is let go. The JVM then exits by itself.
            stopWorkers.set(true);
            memory.close();
            ended.countDown();
        }
    }

    /** Ends the run early, from any thread. Does nothing once the run has ended. */
    void requestStop() {
        if (ended.getCount() > 0) {
            reason.compareAndSet(Reason.COMPLETED, Reason.INTERRUPTED);
        }
        stopWorkers.set(true);
        stopRequested.countDown();
        signalWake();
    }

    /** Waits until the run has printed its end line and released everything, or the time is up. */
    boolean awaitEnd(Duration bound) {
        try {
            return ended.await(bound.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    boolean interrupted() {
        return reason.get() == Reason.INTERRUPTED;
    }

    private List<Thread> startWorkers() {
        CpuPlan plan = arguments.cpuPlan();
        List<Thread> workers = new ArrayList<>(plan.workers());
        for (int i = 1; i <= plan.workers(); i++) {
            Thread worker = new Thread(
                    new CpuWorker(new DutyCycle(plan.dutyCycle(), CpuWorker.PERIOD_NANOS), stopWorkers),
                    WORKER_THREAD_PREFIX + i);
            workers.add(worker);
            worker.start();
        }
        return workers;
    }

    private void waitForTheEnd(long start, long initialMemoryHeld) {
        long memoryHeld = initialMemoryHeld;
        long deadline = start + arguments.duration().toNanos();
        long nextStatus = start + statusInterval.toNanos();
        long nextRamp = start;
        long previousWall = start;
        long previousCpu = processCpuTimeNanos.getAsLong();
        long safetyCeiling =
                host.physicalMemoryBytes() > 0 ? (host.physicalMemoryBytes() * 85L) / 100L : Long.MAX_VALUE;
        while (stopRequested.getCount() > 0) {
            long now = System.nanoTime();
            if (interactive && oomRamp.get() && now >= nextRamp) {
                if (memoryHeld + RAMP_STEP_BYTES > safetyCeiling) {
                    oomRamp.set(false);
                    out.println("[INTERACTIVE] Safety ceiling reached ("
                            + Quantities.formatBytes(memoryHeld)
                            + "). OOM ramp paused.");
                } else if (memory.growBy(RAMP_STEP_BYTES)) {
                    memoryHeld = memory.allocatedBytes();
                    out.println("[INTERACTIVE] OOM ramp: allocated=" + Quantities.formatBytes(memoryHeld));
                    nextRamp = System.nanoTime() + RAMP_INTERVAL_NANOS;
                } else {
                    oomRamp.set(false);
                    out.println("[INTERACTIVE] Allocation refused by OS / memory limit at "
                            + Quantities.formatBytes(memoryHeld) + "!");
                }
                now = System.nanoTime();
            }
            long untilDeadline = deadline - now;
            if (untilDeadline <= 0) {
                return;
            }
            long wait = Math.min(untilDeadline, nextStatus - now);
            if (interactive && oomRamp.get()) {
                wait = Math.min(wait, Math.max(0L, nextRamp - now));
            }
            try {
                if (wait > 0 && awaitWake(wait)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                requestStop();
                return;
            }
            now = System.nanoTime();
            if (now >= nextStatus) {
                long cpu = processCpuTimeNanos.getAsLong();
                printStatus(start, now, deadline, cpuPercent(previousCpu, cpu, previousWall, now), memoryHeld);
                previousWall = now;
                previousCpu = cpu;
                nextStatus += statusInterval.toNanos();
                if (nextStatus <= now) {
                    nextStatus = now + statusInterval.toNanos();
                }
            }
        }
    }

    private boolean awaitWake(long waitNanos) throws InterruptedException {
        waitLock.lock();
        try {
            if (stopRequested.getCount() == 0) {
                return true;
            }
            wakeCondition.awaitNanos(waitNanos);
            return stopRequested.getCount() == 0;
        } finally {
            waitLock.unlock();
        }
    }

    private void signalWake() {
        waitLock.lock();
        try {
            wakeCondition.signalAll();
        } finally {
            waitLock.unlock();
        }
    }

    private void startInteractiveListener() {
        out.println("[INTERACTIVE] Press [R] + Enter at any time to ramp memory (+256MiB/0.5s) and trigger OOM Guard."
                + " Press [S] + Enter to pause.");
        Thread listener = new Thread(
                () -> {
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    try {
                        String line;
                        while (stopRequested.getCount() > 0 && (line = reader.readLine()) != null) {
                            String cmd = line.trim().toLowerCase(Locale.ROOT);
                            if ("r".equals(cmd)) {
                                if (oomRamp.compareAndSet(false, true)) {
                                    out.println("[INTERACTIVE] OOM ramp triggered! Growing memory rapidly...");
                                    signalWake();
                                }
                            } else if ("s".equals(cmd)) {
                                if (oomRamp.compareAndSet(true, false)) {
                                    out.println("[INTERACTIVE] OOM ramp paused.");
                                    signalWake();
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // Input stream closed; stop listening cleanly.
                    }
                },
                "prism-demo-interactive");
        listener.setDaemon(true);
        listener.start();
    }

    /** Tells the workers to stop and waits for them, within a bound. Returns how many are still alive. */
    private int stopWorkers(List<Thread> workers) {
        stopWorkers.set(true);
        long deadline = System.nanoTime() + WORKER_STOP_BOUND.toNanos();
        int alive = 0;
        for (Thread worker : workers) {
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining > 0) {
                    worker.join(Duration.ofNanos(remaining));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    private void printStart() {
        CpuPlan plan = arguments.cpuPlan();
        out.println(String.format(
                Locale.ROOT,
                "event=start pid=%d processors=%d physical=%s cap=%s cpu=%s workers=%d duty=%.2f memory=%s"
                        + " duration=%s mode=%s",
                ProcessHandle.current().pid(),
                host.logicalProcessors(),
                host.physicalMemoryBytes() > 0 ? Quantities.formatBytes(host.physicalMemoryBytes()) : "unknown",
                host.memoryCap().isPresent()
                        ? Quantities.formatBytes(host.memoryCap().getAsLong())
                        : "unknown",
                Quantities.formatPercent(arguments.cpuPercent()),
                plan.workers(),
                plan.dutyCycle(),
                Quantities.formatBytes(arguments.memoryBytes()),
                Quantities.formatSeconds(arguments.duration()),
                arguments.mode()));
    }

    private void printMemory(MemoryWorkload.Allocation allocation) {
        if (allocation.requestedBytes() == 0) {
            return;
        }
        if (allocation.failed()) {
            out.println(String.format(
                    Locale.ROOT,
                    "event=memory status=failed requested=%s allocated=%s chunks=%d millis=%d error=%s",
                    Quantities.formatBytes(allocation.requestedBytes()),
                    Quantities.formatBytes(allocation.allocatedBytes()),
                    allocation.chunks(),
                    allocation.millis(),
                    allocation.failure().orElse("")));
        } else {
            out.println(String.format(
                    Locale.ROOT,
                    "event=memory status=allocated bytes=%d chunks=%d millis=%d",
                    allocation.allocatedBytes(),
                    allocation.chunks(),
                    allocation.millis()));
        }
    }

    private void printStatus(long start, long now, long deadline, String cpuSelf, long memoryHeld) {
        out.println(String.format(
                Locale.ROOT,
                "event=status elapsed=%ds remaining=%ds cpu-self=%s memory=%s",
                Math.round((now - start) / 1e9),
                Math.max(0, Math.round((deadline - now) / 1e9)),
                cpuSelf,
                Quantities.formatBytes(memoryHeld)));
    }

    private void printEnd(Reason why, long start, int workersAlive) {
        StringBuilder line = new StringBuilder(String.format(
                Locale.ROOT,
                "event=end reason=%s elapsed=%ds exit=%s",
                why.text,
                Math.round((System.nanoTime() - start) / 1e9),
                why.exitText()));
        if (workersAlive > 0) {
            line.append(" workers-alive=").append(workersAlive);
        }
        out.println(line);
    }

    private String cpuPercent(long previousCpu, long cpu, long previousWall, long wall) {
        return machineShare(previousCpu, cpu, previousWall, wall, host.logicalProcessors());
    }

    /**
     * The share of the whole machine a process used between two readings of its CPU time, in the
     * unit of PROC-2: CPU time over wall time over the logical processors. A dash when a reading
     * is missing or the clock did not advance.
     */
    static String machineShare(
            long previousCpuNanos, long cpuNanos, long previousWallNanos, long wallNanos, int processors) {
        if (previousCpuNanos < 0 || cpuNanos < 0 || wallNanos <= previousWallNanos) {
            return "-";
        }
        double share = 100.0 * (cpuNanos - previousCpuNanos) / ((wallNanos - previousWallNanos) * (double) processors);
        return Quantities.formatPercent(share);
    }
}
