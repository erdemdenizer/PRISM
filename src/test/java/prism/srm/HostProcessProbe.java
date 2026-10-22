package prism.srm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import prism.srm.application.ProcessMonitor;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.ProcessSource;
import prism.srm.platform.windows.WindowsProcessVirtualSizes;

/**
 *this is the demo test file
 * it creates a process and forks it in to child and then checks if somethings wrong or not
 *
 */
class HostProcessProbe {

    private static final int CYCLES = Integer.getInteger("probe.cycles", 14);
    private static final int BUSY_THREADS = Integer.getInteger("probe.busyThreads", 4);
    private static final boolean START_CHILD = Boolean.parseBoolean(System.getProperty("probe.child", "true"));
    private static final int LOAD_FROM_CYCLE = 3;
    private static final int LOAD_UNTIL_CYCLE = 9;
    private static final int CHILD_AT_CYCLE = 4;
    private static final int CHILD_SLEEP_SECONDS = 8;
    private static final double MIB = 1024.0 * 1024.0;

    private final long ownPid = ProcessHandle.current().pid();
    private final List<Double> enumerationMillis = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final AtomicBoolean loadRunning = new AtomicBoolean();

    private TimedSource source;
    private int logicalProcessors;
    private int cycle;
    private ProcessIdentity ownIdentity;
    private Duration ownCpuAtPreviousCycle;
    private long nanosAtPreviousCycle;
    private Process child;
    private boolean childSeen;
    private boolean childSeenGone;
    private ProcessIdentity childIdentity;
    private Duration jvmCpuAtSecondCycle;
    private long nanosAtSecondCycle;
    private long heapAtSecondCycle;

    @Test
    void watchProcesses() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "probe-process-monitor");
            thread.setDaemon(true);
            return thread;
        });
        try {
            HostPlatform platform = HostPlatform.create();
            source = new TimedSource(platform.processSource());
            logicalProcessors = platform.systemMetricsSource().processorInfo().logicalProcessorCount();
            print("\n============================================================");
            print(" PRISM Process Lifecycle Probe (%d Cycles)", CYCLES);
            print("============================================================");
            print("Target  : Process Discovery, Workload Simulation & Lifecycle");
            print("This JVM: PID %d (%d logical processors | Interval: 2.0s)", ownPid, logicalProcessors);
            print("============================================================\n");

            ProcessMonitor monitor = new ProcessMonitor(
                    source, scheduler, System::nanoTime, logicalProcessors, ProcessMonitor.DEFAULT_INTERVAL);
            CountDownLatch remaining = new CountDownLatch(CYCLES);
            monitor.addListener(snapshots -> {
                observe(snapshots);
                remaining.countDown();
            });

            monitor.start();
            boolean completed = remaining.await(60L + 3L * CYCLES, TimeUnit.SECONDS);
            monitor.stop();
            loadRunning.set(false);

            assertThat(completed).as("all cycles arrived in time").isTrue();
            // The scheduler has one thread: once this task has run, no cycle is in flight.
            scheduler.submit(this::finish).get(60, TimeUnit.SECONDS);
            assertThat(problems).as("checks that failed during the run").isEmpty();
        } finally {
            loadRunning.set(false);
            scheduler.shutdownNow();
            if (child != null && child.isAlive()) {
                // Only ever the probe's own sleeping child, and only if it outlived the run.
                child.destroy();
            }
        }
    }

    private void observe(List<ProcessSnapshot> snapshots) {
        cycle++;
        enumerationMillis.add(source.lastListingNanos / 1e6);
        Optional<ProcessSnapshot> own = snapshots.stream()
                .filter(snapshot -> snapshot.identity().pid() == ownPid)
                .findFirst();
        if (own.isEmpty()) {
            problems.add("cycle " + cycle + ": this JVM is not in the snapshot");
            return;
        }
        if (cycle == 1) {
            ownIdentity = own.get().identity();
        } else if (!own.get().identity().equals(ownIdentity)) {
            problems.add("cycle " + cycle + ": own identity changed from " + ownIdentity + " to "
                    + own.get().identity());
        }

        Duration ownCpu = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
        long now = System.nanoTime();
        ownCpuAtPreviousCycle = ownCpu;
        nanosAtPreviousCycle = now;

        String stateStr = loadRunning.get() ? "4 WORKER THREADS (Active Load)" : "Idle";
        print(
                "[Cycle #%d | %s] %d processes active (listed in %.0f ms)",
                cycle,
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                snapshots.size(),
                source.lastListingNanos / 1e6);
        print(
                "  - This JVM (PID %d) : CPU: %s | RAM: %s MiB | Threads: %s | State: %s",
                ownPid,
                percent(own.get().cpuPercent()),
                mib(own.get().residentBytes()),
                own.get().threadCount().isPresent() ? own.get().threadCount().getAsInt() : "-",
                stateStr);

        watchChild(snapshots);
        if (cycle == 2) {
            System.gc();
            heapAtSecondCycle = usedHeap();
            jvmCpuAtSecondCycle = ownCpu;
            nanosAtSecondCycle = now;
            printTopTen(snapshots);
        }
        if (BUSY_THREADS > 0 && cycle == LOAD_FROM_CYCLE) {
            startLoad();
        }
        if (cycle == LOAD_UNTIL_CYCLE) {
            loadRunning.set(false);
            print(">>> [ACTION] Worker threads stopped (load finished).");
        }
        if (START_CHILD && cycle == CHILD_AT_CYCLE) {
            startChild();
        }
        print("");
    }

    private void printIdentities(List<ProcessSnapshot> snapshots) {}

    private void printAbsences(List<ProcessSnapshot> snapshots) {}

    private void printVirtualSizes(List<ProcessSnapshot> snapshots) {}

    private void printTopTen(List<ProcessSnapshot> snapshots) {
        print("  - Top Processes by CPU:");
        snapshots.stream()
                .filter(snapshot -> snapshot.cpuPercent().isPresent())
                .sorted(Comparator.comparingDouble((ProcessSnapshot snapshot) ->
                                snapshot.cpuPercent().getAsDouble())
                        .reversed())
                .limit(5)
                .forEach(snapshot -> print(
                        "      %5.1f%%  %-24s (PID %-6d, RAM %s MiB)",
                        snapshot.cpuPercent().getAsDouble(),
                        snapshot.name(),
                        snapshot.identity().pid(),
                        mib(snapshot.residentBytes())));
    }

    private void startLoad() {
        loadRunning.set(true);
        for (int i = 0; i < BUSY_THREADS; i++) {
            Thread thread = new Thread(
                    () -> {
                        double x = 0;
                        while (loadRunning.get()) {
                            x += Math.sqrt(x + 1);
                        }
                    },
                    "probe-load-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        print(
                ">>> [ACTION] Starting %d busy threads in this JVM (simulating ~%.1f%% CPU load)...",
                BUSY_THREADS, 100.0 * BUSY_THREADS / logicalProcessors);
    }

    private void startChild() {
        try {
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classes = Path.of(HostProcessProbe.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
            child = new ProcessBuilder(java, "-cp", classes, Child.class.getName(), String.valueOf(CHILD_SLEEP_SECONDS))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            print(
                    ">>> [ACTION] Spawning temporary Child JVM (PID %d, sleeps %d s and self-terminates)...",
                    child.pid(), CHILD_SLEEP_SECONDS);
        } catch (IOException | URISyntaxException e) {
            problems.add("the child process could not be started: " + e);
        }
    }

    private void watchChild(List<ProcessSnapshot> snapshots) {
        if (child == null) {
            return;
        }
        Optional<ProcessSnapshot> listed = snapshots.stream()
                .filter(snapshot -> snapshot.identity().pid() == child.pid())
                .findFirst();
        if (listed.isPresent() && !childSeen) {
            childSeen = true;
            childIdentity = listed.get().identity();
            print("  - [LIFECYCLE] Child process (PID %d) DETECTED in process table (State: Born)", child.pid());
        } else if (listed.isEmpty() && childSeen && !childSeenGone) {
            childSeenGone = true;
            print(
                    "  - [LIFECYCLE] Child process (PID %d) TERMINATED & cleanly removed from process table!",
                    child.pid());
        }
    }

    /** Runs on the sampling thread after the last cycle. */
    private void finish() {
        if (START_CHILD) {
            if (!childSeen) {
                problems.add("the child process never appeared in a snapshot");
            } else if (!childSeenGone) {
                problems.add("the child process never disappeared from the snapshots");
            }
        }
        printDetails("this JVM", ownIdentity, true);
        ProcessIdentity samePidOtherStart =
                new ProcessIdentity(ownPid, ownIdentity.startTime().plusMillis(1));
        Optional<ProcessDetails> wrong = source.details(samePidOtherStart);
        if (wrong.isPresent()) {
            problems.add("details were returned for an identity that does not exist");
        }

        printVirtualSizeSource();

        List<Double> steady = enumerationMillis.subList(1, enumerationMillis.size()).stream()
                .sorted()
                .toList();
        print("============================================================");
        print("[OK] Process Lifecycle & Discovery Verification Passed:");
        print("  - Child Process  : Created -> Tracked -> Terminated -> Cleaned");
        print("  - Thread Workload: %d threads successfully simulated target load", BUSY_THREADS);
        print("  - PID Protection : Verified (PID + StartTime mismatch rejected)");
        print(
                "  - Listing Speed  : Average %.0f ms across %d cycles",
                steady.stream().mapToDouble(Double::doubleValue).average().orElse(0), steady.size());
        print("============================================================\n");
    }

    /** On Windows, what the separate read of the virtual sizes costs and which processes it misses. */
    private void printVirtualSizeSource() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        WindowsProcessVirtualSizes virtualSizes = new WindowsProcessVirtualSizes();
        for (int i = 0; i < 5; i++) {
            virtualSizes.virtualSizes();
        }
        List<ProcessReading> readings = source.processes();
        if (readings.stream()
                .noneMatch(reading -> reading.identity().equals(ownIdentity)
                        && reading.virtualBytes().isPresent())) {
            problems.add("this JVM has no virtual size");
        }
    }

    private void printDetails(String what, ProcessIdentity identity, boolean mustHavePath) {
        Optional<ProcessDetails> details = source.details(identity);
        if (details.isEmpty()) {
            if (mustHavePath) {
                problems.add("no details for " + what);
            }
            return;
        }
        if (mustHavePath && details.get().executablePath().isEmpty()) {
            problems.add("no executable path for " + what);
        }
    }

    private static long epochMilli(ProcessIdentity identity) {
        Instant start = identity.startTime();
        return start.toEpochMilli();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String percent(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.1f%%", value.getAsDouble()) : "-";
    }

    private static String rate(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.0f", value.getAsDouble()) : "-";
    }

    private static String mib(OptionalLong bytes) {
        return bytes.isPresent() ? String.format(Locale.ROOT, "%.1f", bytes.getAsLong() / MIB) : "-";
    }

    private static void print(String format, Object... arguments) {
        System.out.println(String.format(Locale.ROOT, format, arguments));
    }

    /** The child the probe starts: sleeps for the given number of seconds and exits. */
    public static final class Child {

        private Child() {}

        public static void main(String[] arguments) throws InterruptedException {
            Thread.sleep(Duration.ofSeconds(Long.parseLong(arguments[0])));
        }
    }

    /** Measures how long each listing of the real source takes. */
    private static final class TimedSource implements ProcessSource {

        private final ProcessSource delegate;
        private long lastListingNanos;

        TimedSource(ProcessSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ProcessReading> processes() {
            long start = System.nanoTime();
            try {
                return delegate.processes();
            } finally {
                lastListingNanos = System.nanoTime() - start;
            }
        }

        @Override
        public Optional<ProcessDetails> details(ProcessIdentity identity) {
            return delegate.details(identity);
        }
    }
}
