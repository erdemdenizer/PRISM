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
            print(
                    "Own PID %d, started %s by ProcessHandle; %d logical processors; cadence %s",
                    ownPid,
                    ProcessHandle.current().info().startInstant().orElse(null),
                    logicalProcessors,
                    ProcessMonitor.DEFAULT_INTERVAL);

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
            printIdentities(snapshots);
        } else if (!own.get().identity().equals(ownIdentity)) {
            problems.add("cycle " + cycle + ": own identity changed from " + ownIdentity + " to "
                    + own.get().identity());
        }

        Duration ownCpu = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
        long now = System.nanoTime();
        String expected = ownCpuAtPreviousCycle == null
                ? "-"
                : String.format(
                        Locale.ROOT,
                        "%.1f%%",
                        100.0
                                * ownCpu.minus(ownCpuAtPreviousCycle).toNanos()
                                / ((now - nanosAtPreviousCycle) * (double) logicalProcessors));
        ownCpuAtPreviousCycle = ownCpu;
        nanosAtPreviousCycle = now;

        print(
                "#%d %s  %d processes, listed in %.1f ms | this JVM: CPU %s (by ProcessHandle %s), load %s",
                cycle,
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                snapshots.size(),
                source.lastListingNanos / 1e6,
                percent(own.get().cpuPercent()),
                expected,
                loadRunning.get() ? BUSY_THREADS + " busy threads" : "none");
        print(
                "     resident %s MiB, private %s MiB, virtual %s MiB, threads %s, priority %s, state %s, user %s,"
                        + " read %s B/s, write %s B/s",
                mib(own.get().residentBytes()),
                mib(own.get().privateResidentBytes()),
                mib(own.get().virtualBytes()),
                own.get().threadCount().isPresent() ? own.get().threadCount().getAsInt() : "-",
                own.get().priority().isPresent() ? own.get().priority().getAsInt() : "-",
                own.get().state().map(Enum::name).orElse("-"),
                own.get().user().orElse("-"),
                rate(own.get().ioReadBytesPerSecond()),
                rate(own.get().ioWriteBytesPerSecond()));
        print(
                "     virtual size %s bytes, I/O read and write as the system accounts process I/O",
                own.get().virtualBytes().isPresent() ? own.get().virtualBytes().getAsLong() : "-");

        watchChild(snapshots);
        if (cycle == 2) {
            System.gc();
            heapAtSecondCycle = usedHeap();
            jvmCpuAtSecondCycle = ownCpu;
            nanosAtSecondCycle = now;
            printAbsences(snapshots);
        }
        if (cycle == 2 || cycle == CYCLES) {
            printVirtualSizes(snapshots);
        }
        if (cycle == 2 || cycle == LOAD_UNTIL_CYCLE - 1 || cycle == CYCLES) {
            printTopTen(snapshots);
        }
        if (BUSY_THREADS > 0 && cycle == LOAD_FROM_CYCLE) {
            startLoad();
        }
        if (cycle == LOAD_UNTIL_CYCLE) {
            loadRunning.set(false);
        }
        if (START_CHILD && cycle == CHILD_AT_CYCLE) {
            startChild();
        }
    }

    /** Printed so that two runs of the probe can be compared: a live process must keep its start time. */
    private void printIdentities(List<ProcessSnapshot> snapshots) {
        print(
                "IDENTITY this JVM: pid %d start %s (epoch ms %d)",
                ownPid, ownIdentity.startTime(), epochMilli(ownIdentity));
        snapshots.stream()
                .filter(snapshot -> snapshot.identity().pid() != ownPid)
                .sorted(Comparator.comparing((ProcessSnapshot snapshot) ->
                                snapshot.identity().startTime())
                        .thenComparingLong(snapshot -> snapshot.identity().pid()))
                .limit(8)
                .forEach(snapshot -> print(
                        "IDENTITY oldest: pid %d %s start %s (epoch ms %d)",
                        snapshot.identity().pid(),
                        snapshot.name(),
                        snapshot.identity().startTime(),
                        epochMilli(snapshot.identity())));
        ProcessHandle.current()
                .parent()
                .ifPresent(parent -> snapshots.stream()
                        .filter(snapshot -> snapshot.identity().pid() == parent.pid())
                        .findFirst()
                        .ifPresent(snapshot -> print(
                                "IDENTITY parent: pid %d %s start %s, by ProcessHandle %s",
                                parent.pid(),
                                snapshot.name(),
                                snapshot.identity().startTime(),
                                parent.info().startInstant().orElse(null))));
    }

    private void printAbsences(List<ProcessSnapshot> snapshots) {
        print(
                "     of %d processes: no CPU value %d, no user %d, no resident memory %d, no private memory %d,"
                        + " no virtual size %d, no state %d, no thread count %d, no I/O rate %d",
                snapshots.size(),
                snapshots.stream().filter(s -> s.cpuPercent().isEmpty()).count(),
                snapshots.stream().filter(s -> s.user().isEmpty()).count(),
                snapshots.stream().filter(s -> s.residentBytes().isEmpty()).count(),
                snapshots.stream()
                        .filter(s -> s.privateResidentBytes().isEmpty())
                        .count(),
                snapshots.stream().filter(s -> s.virtualBytes().isEmpty()).count(),
                snapshots.stream().filter(s -> s.state().isEmpty()).count(),
                snapshots.stream().filter(s -> s.threadCount().isEmpty()).count(),
                snapshots.stream()
                        .filter(s -> s.ioReadBytesPerSecond().isEmpty())
                        .count());
        double total =
                snapshots.stream().mapToDouble(s -> s.cpuPercent().orElse(0)).sum();
        print("     sum of all process CPU values: %.1f%% (the idle process included, so close to 100)", total);
    }

    /** Long-lived system processes, whose virtual size hardly moves, to compare with the system's tools. */
    private void printVirtualSizes(List<ProcessSnapshot> snapshots) {
        snapshots.stream()
                .sorted(Comparator.comparing((ProcessSnapshot snapshot) ->
                                snapshot.identity().startTime())
                        .thenComparingLong(snapshot -> snapshot.identity().pid()))
                .limit(8)
                .forEach(snapshot -> print(
                        "VIRTUAL %s pid %d %s: %s bytes",
                        LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                        snapshot.identity().pid(),
                        snapshot.name(),
                        snapshot.virtualBytes().isPresent()
                                ? snapshot.virtualBytes().getAsLong()
                                : "-"));
    }

    private void printTopTen(List<ProcessSnapshot> snapshots) {
        print("     top ten by CPU:");
        snapshots.stream()
                .filter(snapshot -> snapshot.cpuPercent().isPresent())
                .sorted(Comparator.comparingDouble((ProcessSnapshot snapshot) ->
                                snapshot.cpuPercent().getAsDouble())
                        .reversed())
                .limit(10)
                .forEach(snapshot -> print(
                        "       %6.2f%%  %-28s pid %-6d %8s MiB  %4s threads  user %s",
                        snapshot.cpuPercent().getAsDouble(),
                        snapshot.name(),
                        snapshot.identity().pid(),
                        mib(snapshot.residentBytes()),
                        snapshot.threadCount().isPresent()
                                ? snapshot.threadCount().getAsInt()
                                : "-",
                        snapshot.user().orElse("-")));
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
                "     started %d busy threads: expect about %.1f%% of the machine for this JVM",
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
                    "     started a child JVM, pid %d, that sleeps %d s and exits by itself",
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
            print(
                    "     child pid %d appeared: %s, start %s, by ProcessHandle %s, CPU %s (first sight, so none)",
                    child.pid(),
                    listed.get().name(),
                    childIdentity.startTime(),
                    child.info().startInstant().orElse(null),
                    percent(listed.get().cpuPercent()));
        } else if (listed.isEmpty() && childSeen && !childSeenGone) {
            childSeenGone = true;
            print(
                    "     child pid %d is gone from the snapshot (alive by Process API: %s)",
                    child.pid(), child.isAlive());
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
        ProcessHandle.current()
                .parent()
                .ifPresent(parent -> source.processes().stream()
                        .filter(reading -> reading.identity().pid() == parent.pid())
                        .findFirst()
                        .ifPresent(reading -> printDetails("parent " + reading.name(), reading.identity(), false)));
        source.processes().stream()
                .filter(reading -> reading.user().isEmpty())
                .sorted(Comparator.comparingLong(
                        (ProcessReading reading) -> reading.identity().pid()))
                .limit(3)
                .forEach(reading -> printDetails("no-user process " + reading.name(), reading.identity(), false));
        if (childIdentity != null) {
            print("details of the exited child: %s (empty expected)", source.details(childIdentity));
        }
        ProcessIdentity samePidOtherStart =
                new ProcessIdentity(ownPid, ownIdentity.startTime().plusMillis(1));
        Optional<ProcessDetails> wrong = source.details(samePidOtherStart);
        print("details for own PID with a start time 1 ms later: %s (empty expected)", wrong);
        if (wrong.isPresent()) {
            problems.add("details were returned for an identity that does not exist");
        }

        printVirtualSizeSource();

        List<Double> steady = enumerationMillis.subList(1, enumerationMillis.size()).stream()
                .sorted()
                .toList();
        print(
                "Enumeration over %d cycles after the first (first %.0f ms): median %.1f ms, mean %.1f ms,"
                        + " 95th %.1f ms, worst %.1f ms",
                steady.size(),
                enumerationMillis.get(0),
                steady.get(steady.size() / 2),
                steady.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                steady.get(Math.min(steady.size() - 1, (int) Math.ceil(steady.size() * 0.95) - 1)),
                steady.get(steady.size() - 1));
        double elapsedSeconds = (System.nanoTime() - nanosAtSecondCycle) / 1e9;
        double cpuSeconds = ProcessHandle.current()
                        .info()
                        .totalCpuDuration()
                        .orElse(Duration.ZERO)
                        .minus(jvmCpuAtSecondCycle)
                        .toNanos()
                / 1e9;
        System.gc();
        print(
                "This JVM from cycle 2 to the end: %.3f s CPU in %.1f s = %.2f%% of one processor, %.3f%% of the"
                        + " machine%s",
                cpuSeconds,
                elapsedSeconds,
                100 * cpuSeconds / elapsedSeconds,
                100 * cpuSeconds / elapsedSeconds / logicalProcessors,
                BUSY_THREADS > 0 ? " (includes the artificial load)" : "");
        print(
                "Heap in use after GC: %.1f MiB at cycle 2, %.1f MiB at the end",
                heapAtSecondCycle / MIB, usedHeap() / MIB);
    }

    /** On Windows, what the separate read of the virtual sizes costs and which processes it misses. */
    private void printVirtualSizeSource() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        WindowsProcessVirtualSizes virtualSizes = new WindowsProcessVirtualSizes();
        List<Double> millis = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            virtualSizes.virtualSizes();
            millis.add((System.nanoTime() - start) / 1e6);
        }
        millis.sort(Comparator.naturalOrder());
        print(
                "Virtual size source, 30 reads: median %.1f ms, mean %.1f ms, worst %.1f ms",
                millis.get(millis.size() / 2),
                millis.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                millis.get(millis.size() - 1));
        List<ProcessReading> readings = source.processes();
        List<ProcessReading> without = readings.stream()
                .filter(reading -> reading.virtualBytes().isEmpty())
                .toList();
        print(
                "Virtual size present for %d of %d processes; without: %s",
                readings.size() - without.size(),
                readings.size(),
                without.stream()
                        .limit(6)
                        .map(reading ->
                                reading.name() + " (pid " + reading.identity().pid() + ")")
                        .toList());
        if (readings.stream()
                .noneMatch(reading -> reading.identity().equals(ownIdentity)
                        && reading.virtualBytes().isPresent())) {
            problems.add("this JVM has no virtual size");
        }
    }

    private void printDetails(String what, ProcessIdentity identity, boolean mustHavePath) {
        long start = System.nanoTime();
        Optional<ProcessDetails> details = source.details(identity);
        double millis = (System.nanoTime() - start) / 1e6;
        if (details.isEmpty()) {
            print("details of %s (pid %d): none, in %.1f ms", what, identity.pid(), millis);
            if (mustHavePath) {
                problems.add("no details for " + what);
            }
            return;
        }
        String commandLine = details.get().commandLine().orElse("(absent)");
        print(
                "details of %s (pid %d) in %.1f ms: path %s | command line %s",
                what,
                identity.pid(),
                millis,
                details.get().executablePath().orElse("(absent)"),
                commandLine.length() > 90
                        ? commandLine.substring(0, 90) + "... (" + commandLine.length() + " chars)"
                        : commandLine);
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
