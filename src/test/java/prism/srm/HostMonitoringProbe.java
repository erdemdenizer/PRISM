package prism.srm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import prism.srm.application.SystemMonitor;
import prism.srm.domain.CpuSample;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemSnapshot;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.SystemMetricsSource;

/**
 * Manual check against the real system: prints ten consecutive samples from the real metrics
 * source for comparison with the operating system's own tools, and what the sampling costs.
 *
 * <p>The class name keeps it out of the default build. Run it with {@code ./mvnw -q test
 * -Dtest=HostMonitoringProbe}; add {@code -Dprobe.samples=60} for a longer run when measuring
 * cost.
 */
class HostMonitoringProbe {

    private static final int SAMPLES = Integer.getInteger("probe.samples", 10);
    private static final double MIB = 1024.0 * 1024.0;

    @Test
    void printSamples() throws InterruptedException {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "probe-system-monitor");
            thread.setDaemon(true);
            return thread;
        });
        try {
            TimedSource source = new TimedSource(HostPlatform.create().systemMetricsSource());

            SystemMonitor monitor =
                    new SystemMonitor(source, scheduler, System::nanoTime, SystemMonitor.DEFAULT_INTERVAL);
            CountDownLatch remaining = new CountDownLatch(SAMPLES);
            monitor.addListener(snapshot -> {
                if (remaining.getCount() == SAMPLES) {
                    printFacts(snapshot);
                }
                printSample(SAMPLES - remaining.getCount() + 1, snapshot, source);
                remaining.countDown();
            });

            monitor.start();
            boolean completed = remaining.await(30L + 2L * SAMPLES, TimeUnit.SECONDS);
            monitor.stop();

            assertThat(completed).as("all samples arrived in time").isTrue();
            print("============================================================");
            print("✓ Probe Completed: %d samples collected successfully.", SAMPLES);
            print("============================================================\n");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void printFacts(SystemSnapshot snapshot) {
        print("\n============================================================");
        print(" PRISM System Monitoring Probe (%d Samples)", SAMPLES);
        print("============================================================");
        print(
                "OS   : %s",
                snapshot.operatingSystem()
                        .map(os -> os.name() + " (" + os.version().orElse("") + ")")
                        .orElse("-"));
        print(
                "CPU  : %s (%d logical processors)",
                snapshot.processor().flatMap(ProcessorInfo::name).orElse("CPU"),
                snapshot.processor().map(ProcessorInfo::logicalProcessorCount).orElse(1));
        print(
                "Disk : %s",
                snapshot.fileSystems()
                        .map(fs -> fs.stream()
                                .map(f -> f.mountPoint() + " "
                                        + String.format(
                                                Locale.ROOT,
                                                "%.1f/%.1f GB",
                                                f.usableBytes() / MIB / 1024,
                                                f.totalBytes() / MIB / 1024))
                                .collect(Collectors.joining("; ")))
                        .orElse("-"));
        print("============================================================\n");
    }

    private static void printSample(long number, SystemSnapshot snapshot, TimedSource source) {
        String cpu = snapshot.cpu()
                .map(usage -> String.format(Locale.ROOT, "%.1f%%", usage.totalPercent()))
                .orElse("-");
        String memory = snapshot.memory()
                .map(sample -> String.format(
                        Locale.ROOT,
                        "%.1f / %.1f GB (%.1f%%) | Available: %.1f GB",
                        sample.usedBytes() / MIB / 1024,
                        sample.totalBytes() / MIB / 1024,
                        sample.usedPercent(),
                        sample.availableBytes() / MIB / 1024))
                .orElse("-");
        String disk = snapshot.diskRates()
                .map(rates -> String.format(
                        Locale.ROOT,
                        "Read: %.0f KB/s | Write: %.0f KB/s",
                        rates.readBytesPerSecond() / 1024,
                        rates.writeBytesPerSecond() / 1024))
                .orElse("-");
        String network = snapshot.network()
                .flatMap(usage -> usage.total())
                .map(rates -> String.format(
                        Locale.ROOT,
                        "Down: %.1f KB/s | Up: %.1f KB/s",
                        rates.receiveBytesPerSecond() / 1024,
                        rates.sendBytesPerSecond() / 1024))
                .orElse("-");
        String activity = snapshot.activity()
                .map(system -> String.format(
                        Locale.ROOT,
                        "%d processes | Uptime: %d s",
                        system.processCount(),
                        system.uptime().toSeconds()))
                .orElse("-");

        print("[Sample #%d | %s]", number, LocalTime.now().truncatedTo(ChronoUnit.SECONDS));
        print("  • CPU Usage : %s", cpu);
        print("  • Memory    : %s", memory);
        print("  • Disk I/O  : %s", disk);
        print("  • Network   : %s", network);
        print("  • System    : %s", activity);
        print("");
    }

    private static String percent(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%3.0f", value.getAsDouble()) : "  -";
    }

    private static Duration processCpuTime() {
        return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
    }

    private static double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    private static void print(String format, Object... arguments) {
        System.out.println(String.format(Locale.ROOT, format, arguments));
    }

    /** Adds up the time spent inside the real source, so the cost of one cycle can be printed. */
    private static final class TimedSource implements SystemMetricsSource {

        private final SystemMetricsSource delegate;
        private long cycleNanos;
        private long lastDiskReadNanos;
        private long lastNetworkReadNanos;

        TimedSource(SystemMetricsSource delegate) {
            this.delegate = delegate;
        }

        long takeCycleNanos() {
            long nanos = cycleNanos;
            cycleNanos = 0;
            return nanos;
        }

        private <T> T timed(Supplier<T> reading) {
            long start = System.nanoTime();
            try {
                return reading.get();
            } finally {
                cycleNanos += System.nanoTime() - start;
            }
        }

        @Override
        public ProcessorInfo processorInfo() {
            return timed(delegate::processorInfo);
        }

        @Override
        public OperatingSystemInfo operatingSystem() {
            return timed(delegate::operatingSystem);
        }

        @Override
        public CpuSample cpuTicks() {
            return timed(delegate::cpuTicks);
        }

        @Override
        public MemorySample memory() {
            return timed(delegate::memory);
        }

        @Override
        public List<FileSystemUsage> fileSystems() {
            return timed(delegate::fileSystems);
        }

        @Override
        public List<DiskCounters> diskCounters() {
            long start = System.nanoTime();
            try {
                return timed(delegate::diskCounters);
            } finally {
                lastDiskReadNanos = System.nanoTime() - start;
            }
        }

        @Override
        public List<NetworkCounters> networkCounters() {
            long start = System.nanoTime();
            try {
                return timed(delegate::networkCounters);
            } finally {
                lastNetworkReadNanos = System.nanoTime() - start;
            }
        }

        @Override
        public SystemActivity activity() {
            return timed(delegate::activity);
        }
    }
}
