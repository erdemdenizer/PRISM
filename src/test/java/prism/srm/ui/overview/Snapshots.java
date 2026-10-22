package prism.srm.ui.overview;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.IntToDoubleFunction;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskRates;
import prism.srm.domain.MemorySample;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;

/** System snapshots with known values, for the tests and the rendered snapshots of the Overview. */
public final class Snapshots {

    public static final long KIB = 1024;
    public static final long MIB = 1024 * KIB;
    public static final long GIB = 1024 * MIB;
    public static final int CAPACITY = 60;

    private static final long MEMORY_TOTAL = 32 * GIB;

    private Snapshots() {}

    /** The first cycle: facts and memory are there, rates cannot be yet. */
    public static SystemSnapshot firstCycle() {
        MetricHistory skipped = MetricHistory.of(Double.NaN);
        return new SystemSnapshot(
                Optional.of(processor()),
                Optional.of(windows()),
                Optional.empty(),
                Optional.of(memory(11.2)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new NetworkUsage(List.of(), Optional.empty())),
                Optional.of(
                        new SystemActivity(Duration.ofHours(4).plusMinutes(12).plusSeconds(55), 392)),
                new SystemHistory(
                        skipped, List.of(), MetricHistory.of(11.2 * GIB), skipped, skipped, skipped, skipped));
    }

    /** A system in normal use after a full minute. */
    public static SystemSnapshot live() {
        return new SystemSnapshot(
                Optional.of(processor()),
                Optional.of(windows()),
                Optional.of(new CpuUsage(12.44, List.of(OptionalDouble.of(10), OptionalDouble.of(14.88)))),
                Optional.of(memory(11.2)),
                Optional.empty(),
                Optional.of(new DiskRates(1.5 * MIB, 512 * KIB)),
                Optional.of(network(Optional.of(new NetworkRates(240 * KIB, 12 * KIB)))),
                Optional.of(new SystemActivity(
                        Duration.ofDays(3).plusHours(4).plusMinutes(12).plusSeconds(55), 392)),
                new SystemHistory(
                        series(CAPACITY, i -> 14 + 9 * Math.sin(i / 5.0) + (i % 17 == 0 ? 30 : 0)),
                        List.of(),
                        series(CAPACITY, i -> (10.6 + 0.6 * Math.sin(i / 11.0)) * GIB),
                        series(CAPACITY, i -> i % 13 == 0 ? 1.5 * MIB : 180 * KIB * (1 + Math.sin(i / 3.0))),
                        series(CAPACITY, i -> i % 9 == 0 ? 512 * KIB : 40 * KIB * (1 + Math.cos(i / 4.0))),
                        series(CAPACITY, i -> 150 * KIB * (1 + Math.sin(i / 6.0)) + (i > 40 ? 90 * KIB : 0)),
                        series(CAPACITY, i -> 12 * KIB * (1 + Math.cos(i / 2.0)))));
    }

    /** Monitoring has run for a minute and the system reports nothing at all. */
    public static SystemSnapshot nothingAvailable() {
        MetricHistory skipped = series(CAPACITY, i -> Double.NaN);
        return new SystemSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new SystemHistory(skipped, List.of(), skipped, skipped, skipped, skipped, skipped));
    }

    /** The values of {@link #live()} with the memory reading missing from this one cycle. */
    public static SystemSnapshot liveWithoutMemory() {
        SystemSnapshot live = live();
        return new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                live.cpu(),
                Optional.empty(),
                live.fileSystems(),
                live.diskRates(),
                live.network(),
                live.activity(),
                live.history());
    }

    /**
     * The values of {@link #live()} on a system that reports neither memory nor a network total:
     * in each row of the Overview one panel has values and the one beside it has none.
     */
    public static SystemSnapshot partlyUnavailable() {
        SystemSnapshot live = live();
        MetricHistory skipped = series(CAPACITY, i -> Double.NaN);
        return new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                live.cpu(),
                Optional.empty(),
                live.fileSystems(),
                live.diskRates(),
                Optional.of(network(Optional.empty())),
                live.activity(),
                new SystemHistory(
                        live.history().cpuTotalPercent(),
                        List.of(),
                        skipped,
                        live.history().diskReadBytesPerSecond(),
                        live.history().diskWriteBytesPerSecond(),
                        skipped,
                        skipped));
    }

    /** Every value at an extreme: a saturated machine with very large numbers and long names. */
    public static SystemSnapshot extreme() {
        long total = 1024 * GIB;
        return new SystemSnapshot(
                Optional.of(processor()),
                Optional.of(new OperatingSystemInfo(
                        "Microsoft Windows Server 2025 Datacenter Azure Edition Core",
                        Optional.of("24H2 Insider Preview Evaluation"),
                        Optional.of("26100.32522.amd64fre.ge_release_svc_prod3.250401-1500"))),
                Optional.of(new CpuUsage(100, List.of())),
                Optional.of(new MemorySample(total, 0, Optional.empty())),
                Optional.empty(),
                Optional.of(new DiskRates(1023.9 * GIB, 1023.9 * GIB)),
                Optional.of(network(Optional.of(new NetworkRates(1023.9 * MIB, 1023.9 * MIB)))),
                Optional.of(new SystemActivity(
                        Duration.ofDays(400).plusHours(23).plusMinutes(59).plusSeconds(59), 99_999)),
                new SystemHistory(
                        series(CAPACITY, i -> 100),
                        List.of(),
                        series(CAPACITY, i -> total),
                        series(CAPACITY, i -> 1023.9 * GIB),
                        series(CAPACITY, i -> i % 2 == 0 ? 1023.9 * GIB : 0),
                        series(CAPACITY, i -> 1023.9 * MIB),
                        series(CAPACITY, i -> i < 30 ? Double.NaN : 1023.9 * MIB)));
    }

    public static MemorySample memory(double usedGib) {
        return new MemorySample(MEMORY_TOTAL, MEMORY_TOTAL - Math.round(usedGib * GIB), Optional.empty());
    }

    public static MetricHistory series(int samples, IntToDoubleFunction value) {
        double[] values = new double[samples];
        Arrays.setAll(values, value);
        return MetricHistory.of(values);
    }

    private static ProcessorInfo processor() {
        return new ProcessorInfo(16, OptionalInt.of(8), OptionalInt.of(1), Optional.of("Fake CPU"));
    }

    private static OperatingSystemInfo windows() {
        return new OperatingSystemInfo("Microsoft Windows", Optional.of("11"), Optional.of("26200"));
    }

    private static NetworkUsage network(Optional<NetworkRates> total) {
        return new NetworkUsage(
                List.of(new NetworkInterfaceUsage("eth0", "Ethernet", DeviceLayer.PHYSICAL, total)), total);
    }
}
