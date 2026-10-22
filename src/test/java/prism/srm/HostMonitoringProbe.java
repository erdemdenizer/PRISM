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
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.NetworkInterfaceUsage;
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
            long creationStart = System.nanoTime();
            TimedSource source = new TimedSource(HostPlatform.create().systemMetricsSource());
            print("Source created in %.0f ms", millisSince(creationStart));

            SystemMonitor monitor =
                    new SystemMonitor(source, scheduler, System::nanoTime, SystemMonitor.DEFAULT_INTERVAL);
            CountDownLatch remaining = new CountDownLatch(SAMPLES);
            Duration[] cpuTimeAtFirstSample = new Duration[1];
            long[] nanosAtFirstSample = new long[1];
            monitor.addListener(snapshot -> {
                if (remaining.getCount() == SAMPLES) {
                    printFacts(snapshot);
                    cpuTimeAtFirstSample[0] = processCpuTime();
                    nanosAtFirstSample[0] = System.nanoTime();
                }
                printSample(SAMPLES - remaining.getCount() + 1, snapshot, source);
                remaining.countDown();
            });

            monitor.start();
            boolean completed = remaining.await(30L + 2L * SAMPLES, TimeUnit.SECONDS);
            monitor.stop();

            assertThat(completed).as("all samples arrived in time").isTrue();
            double elapsedSeconds = (System.nanoTime() - nanosAtFirstSample[0]) / 1e9;
            double cpuSeconds = processCpuTime().minus(cpuTimeAtFirstSample[0]).toNanos() / 1e9;
            int processors = Runtime.getRuntime().availableProcessors();
            print(
                    "This JVM between the first and the last sample: %.3f s CPU in %.1f s = %.2f%% of one"
                            + " processor, %.3f%% of the machine (%d logical processors)",
                    cpuSeconds,
                    elapsedSeconds,
                    100 * cpuSeconds / elapsedSeconds,
                    100 * cpuSeconds / elapsedSeconds / processors,
                    processors);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static void printFacts(SystemSnapshot snapshot) {
        print(
                "Processor: %s",
                snapshot.processor()
                        .map(processor -> String.format(
                                Locale.ROOT,
                                "%s | logical %d | physical %s | packages %s",
                                processor.name().orElse("(name not reported)"),
                                processor.logicalProcessorCount(),
                                processor.physicalCoreCount().isPresent()
                                        ? processor.physicalCoreCount().getAsInt()
                                        : "(not reported)",
                                processor.packageCount().isPresent()
                                        ? processor.packageCount().getAsInt()
                                        : "(not reported)"))
                        .orElse("unavailable"));
        print(
                "Operating system: %s",
                snapshot.operatingSystem()
                        .map(os -> String.format(
                                Locale.ROOT,
                                "%s | version %s | build %s",
                                os.name(),
                                os.version().orElse("(not reported)"),
                                os.build().orElse("(not reported)")))
                        .orElse("unavailable"));
        print(
                "File systems: %s",
                snapshot.fileSystems()
                        .map(fileSystems -> fileSystems.stream()
                                .map(fileSystem -> String.format(
                                        Locale.ROOT,
                                        "%s %.1f of %.1f GiB usable",
                                        fileSystem.mountPoint(),
                                        fileSystem.usableBytes() / MIB / 1024,
                                        fileSystem.totalBytes() / MIB / 1024))
                                .collect(Collectors.joining("; ")))
                        .orElse("unavailable"));
        if (snapshot.network().isEmpty()) {
            print("Interfaces: unavailable");
            return;
        }
        List<NetworkInterfaceUsage> interfaces = snapshot.network().get().interfaces();
        for (DeviceLayer layer : DeviceLayer.values()) {
            List<NetworkInterfaceUsage> ofLayer = interfaces.stream()
                    .filter(networkInterface -> networkInterface.layer() == layer)
                    .toList();
            print(
                    "Interfaces %s (%d): %s",
                    layer,
                    ofLayer.size(),
                    ofLayer.stream()
                            .map(networkInterface ->
                                    networkInterface.name() + " (" + networkInterface.displayName() + ")")
                            .collect(Collectors.joining("; ")));
        }
    }

    private static void printSample(long number, SystemSnapshot snapshot, TimedSource source) {
        String cpu = snapshot.cpu()
                .map(usage -> String.format(
                        Locale.ROOT,
                        "%5.1f%% [%s]",
                        usage.totalPercent(),
                        usage.perProcessorPercent().stream()
                                .map(HostMonitoringProbe::percent)
                                .collect(Collectors.joining(" "))))
                .orElse("-");
        String memory = snapshot.memory()
                .map(sample -> String.format(
                        Locale.ROOT,
                        "used %.0f of %.0f MiB (%.1f%%), available %.0f MiB, swap %s",
                        sample.usedBytes() / MIB,
                        sample.totalBytes() / MIB,
                        sample.usedPercent(),
                        sample.availableBytes() / MIB,
                        sample.swap()
                                .map(swap -> String.format(
                                        Locale.ROOT,
                                        "%.0f of %.0f MiB",
                                        swap.usedBytes() / MIB,
                                        swap.totalBytes() / MIB))
                                .orElse("none")))
                .orElse("-");
        String disk = snapshot.diskRates()
                .map(rates -> String.format(
                        Locale.ROOT,
                        "read %.0f KiB/s, write %.0f KiB/s",
                        rates.readBytesPerSecond() / 1024,
                        rates.writeBytesPerSecond() / 1024))
                .orElse("-");
        String network = snapshot.network()
                .flatMap(usage -> usage.total())
                .map(rates -> String.format(
                        Locale.ROOT,
                        "down %.1f KiB/s, up %.1f KiB/s",
                        rates.receiveBytesPerSecond() / 1024,
                        rates.sendBytesPerSecond() / 1024))
                .orElse("-");
        String activity = snapshot.activity()
                .map(system -> String.format(
                        Locale.ROOT,
                        "uptime %d s, %d processes",
                        system.uptime().toSeconds(),
                        system.processCount()))
                .orElse("-");
        print("#%d  %s  CPU %s", number, LocalTime.now().truncatedTo(ChronoUnit.SECONDS), cpu);
        print("     memory %s", memory);
        print("     disk %s | network %s | %s", disk, network, activity);
        print(
                "     history %d samples | source time this cycle %.1f ms (disk read %.1f ms, network read %.1f ms)",
                snapshot.history().cpuTotalPercent().size(),
                source.takeCycleNanos() / 1e6,
                source.lastDiskReadNanos / 1e6,
                source.lastNetworkReadNanos / 1e6);
        String busiestLogical = snapshot.network().map(usage -> usage.interfaces()).orElse(List.of()).stream()
                .filter(networkInterface -> networkInterface.layer() != DeviceLayer.PHYSICAL)
                .filter(networkInterface -> networkInterface
                        .rates()
                        .map(rates -> rates.receiveBytesPerSecond() + rates.sendBytesPerSecond() > 0)
                        .orElse(false))
                .map(networkInterface -> String.format(
                        Locale.ROOT,
                        "%s %.1f/%.1f",
                        networkInterface.name(),
                        networkInterface.rates().orElseThrow().receiveBytesPerSecond() / 1024,
                        networkInterface.rates().orElseThrow().sendBytesPerSecond() / 1024))
                .collect(Collectors.joining(", "));
        print(
                "     non-physical interfaces with traffic, not in the total (down/up KiB/s): %s",
                busiestLogical.isEmpty() ? "none" : busiestLogical);
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
