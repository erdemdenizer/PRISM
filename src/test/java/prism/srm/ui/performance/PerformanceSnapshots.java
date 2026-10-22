package prism.srm.ui.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SwapUsage;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.overview.Snapshots;

/**
 * System snapshots with known values for the tests and the rendered snapshots of the Performance
 * page: what the Overview's {@link Snapshots} leave out, which is every logical processor, the
 * swap space, the file systems and the interfaces.
 */
public final class PerformanceSnapshots {

    public static final long KIB = Snapshots.KIB;
    public static final long MIB = Snapshots.MIB;
    public static final long GIB = Snapshots.GIB;
    public static final int CAPACITY = Snapshots.CAPACITY;

    private static final long MEMORY_TOTAL = 32 * GIB;

    private PerformanceSnapshots() {}

    /** A system in normal use after a full minute, with the given number of logical processors. */
    public static SystemSnapshot live(int logicalProcessors) {
        SystemSnapshot base = Snapshots.live();
        return new SystemSnapshot(
                Optional.of(processor(logicalProcessors)),
                base.operatingSystem(),
                Optional.of(new CpuUsage(12.44, current(logicalProcessors))),
                Optional.of(memory(11.2, Optional.of(new SwapUsage(8 * GIB, 1288 * MIB)))),
                Optional.of(fileSystems()),
                base.diskRates(),
                Optional.of(network()),
                base.activity(),
                history(base.history(), logicalProcessors));
    }

    /** {@link #live(int)} on the machine the application is developed on: 16 logical processors. */
    public static SystemSnapshot live() {
        return live(16);
    }

    /** The first cycle: facts, memory and devices are there, rates cannot be yet. */
    public static SystemSnapshot firstCycle() {
        SystemSnapshot first = Snapshots.firstCycle();
        MetricHistory skipped = MetricHistory.of(Double.NaN);
        List<MetricHistory> perProcessor = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            perProcessor.add(skipped);
        }
        return new SystemSnapshot(
                Optional.of(processor(16)),
                first.operatingSystem(),
                Optional.empty(),
                Optional.of(memory(11.2, Optional.of(new SwapUsage(8 * GIB, 1288 * MIB)))),
                Optional.of(fileSystems()),
                Optional.empty(),
                Optional.of(new NetworkUsage(
                        List.of(
                                new NetworkInterfaceUsage("eth0", "Ethernet", DeviceLayer.PHYSICAL, Optional.empty()),
                                new NetworkInterfaceUsage("wlan0", "Wi-Fi", DeviceLayer.PHYSICAL, Optional.empty())),
                        Optional.empty())),
                first.activity(),
                new SystemHistory(
                        skipped, perProcessor, MetricHistory.of(11.2 * GIB), skipped, skipped, skipped, skipped));
    }

    /** {@link #live()} on a system without swap space or pagefile. */
    public static SystemSnapshot noSwap() {
        SystemSnapshot live = live();
        return new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                live.cpu(),
                Optional.of(memory(11.2, Optional.empty())),
                live.fileSystems(),
                live.diskRates(),
                live.network(),
                live.activity(),
                live.history());
    }

    /** {@link #live()} on a system that lists no network interface and no file system. */
    public static SystemSnapshot noDevices() {
        SystemSnapshot live = live();
        MetricHistory skipped = Snapshots.series(CAPACITY, i -> Double.NaN);
        SystemHistory history = live.history();
        return new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                live.cpu(),
                live.memory(),
                Optional.of(List.of()),
                live.diskRates(),
                Optional.of(new NetworkUsage(List.of(), Optional.empty())),
                live.activity(),
                new SystemHistory(
                        history.cpuTotalPercent(),
                        history.cpuPerProcessorPercent(),
                        history.memoryUsedBytes(),
                        history.diskReadBytesPerSecond(),
                        history.diskWriteBytesPerSecond(),
                        skipped,
                        skipped));
    }

    /**
     * {@link #live()} on a system that does not say which devices are physical, as Linux and macOS
     * do not for now: every interface has its rates, and neither total exists.
     */
    public static SystemSnapshot unknownLayers() {
        SystemSnapshot live = live();
        MetricHistory skipped = Snapshots.series(CAPACITY, i -> Double.NaN);
        SystemHistory history = live.history();
        return new SystemSnapshot(
                live.processor(),
                Optional.of(new OperatingSystemInfo("Ubuntu", Optional.of("24.04.1 LTS"), Optional.empty())),
                live.cpu(),
                live.memory(),
                Optional.of(List.of(
                        new FileSystemUsage("/dev/nvme0n1p2", "/", 468 * GIB, 212 * GIB),
                        new FileSystemUsage("/dev/nvme0n1p1", "/boot/efi", 511 * MIB, 505 * MIB))),
                Optional.empty(),
                Optional.of(new NetworkUsage(
                        List.of(
                                new NetworkInterfaceUsage("lo", "lo", DeviceLayer.UNKNOWN, rates(48 * KIB, 48 * KIB)),
                                new NetworkInterfaceUsage(
                                        "enp5s0", "enp5s0", DeviceLayer.UNKNOWN, rates(240 * KIB, 12 * KIB)),
                                new NetworkInterfaceUsage("docker0", "docker0", DeviceLayer.UNKNOWN, rates(0, 0))),
                        Optional.empty())),
                live.activity(),
                new SystemHistory(
                        history.cpuTotalPercent(),
                        history.cpuPerProcessorPercent(),
                        history.memoryUsedBytes(),
                        skipped,
                        skipped,
                        skipped,
                        skipped));
    }

    /**
     * Every value at an extreme: 64 saturated logical processors, very large numbers, and long
     * names of the processor, the file systems and the interfaces; as many interfaces as Windows
     * lists on the development machine.
     */
    public static SystemSnapshot extreme() {
        SystemSnapshot base = Snapshots.extreme();
        int processors = 64;
        long total = 1024 * GIB;
        List<OptionalDouble> saturated = new ArrayList<>();
        List<MetricHistory> perProcessor = new ArrayList<>();
        for (int index = 0; index < processors; index++) {
            saturated.add(OptionalDouble.of(100));
            perProcessor.add(Snapshots.series(CAPACITY, i -> 100));
        }
        List<NetworkInterfaceUsage> interfaces = new ArrayList<>();
        interfaces.add(new NetworkInterfaceUsage(
                "eth0",
                "Intel(R) Ethernet Controller (3) I225-V with a Very Long Vendor Description #2",
                DeviceLayer.PHYSICAL,
                rates(1023.9 * MIB, 1023.9 * MIB)));
        for (int index = 1; index < 36; index++) {
            interfaces.add(new NetworkInterfaceUsage(
                    "eth" + index,
                    "Intel(R) Ethernet Controller (3) I225-V-WFP Native MAC Layer LightWeight Filter-" + index,
                    DeviceLayer.LOGICAL,
                    rates(1023.9 * MIB, 1023.9 * MIB)));
        }
        SystemHistory history = base.history();
        return new SystemSnapshot(
                Optional.of(new ProcessorInfo(
                        processors,
                        OptionalInt.of(32),
                        OptionalInt.of(2),
                        Optional.of("AMD Ryzen Threadripper PRO 7985WX 64-Cores with a Very Long Marketing Name"))),
                base.operatingSystem(),
                Optional.of(new CpuUsage(100, saturated)),
                Optional.of(new MemorySample(total, 0, Optional.of(new SwapUsage(1024 * GIB, 1024 * GIB)))),
                Optional.of(List.of(
                        new FileSystemUsage(
                                "Local Fixed Disk With A Label Far Too Long To Fit Any Column (C:)",
                                "C:\\",
                                8 * 1024 * 1024 * GIB,
                                0),
                        new FileSystemUsage(
                                "Network share",
                                "\\\\fileserver.example.internal\\departments\\engineering\\shared\\builds",
                                1023 * GIB + 922 * MIB,
                                1023 * GIB + 922 * MIB))),
                base.diskRates(),
                Optional.of(new NetworkUsage(interfaces, Optional.of(new NetworkRates(1023.9 * MIB, 1023.9 * MIB)))),
                base.activity(),
                new SystemHistory(
                        history.cpuTotalPercent(),
                        perProcessor,
                        history.memoryUsedBytes(),
                        history.diskReadBytesPerSecond(),
                        history.diskWriteBytesPerSecond(),
                        history.networkDownloadBytesPerSecond(),
                        history.networkUploadBytesPerSecond()));
    }

    public static List<FileSystemUsage> fileSystems() {
        return List.of(
                new FileSystemUsage("Local Fixed Disk (C:)", "C:\\", 475 * GIB, 181 * GIB + 307 * MIB),
                new FileSystemUsage("Local Fixed Disk (D:)", "D:\\", 931 * GIB, 512 * GIB),
                new FileSystemUsage("Removable Disk (E:)", "E:\\", 29 * GIB + 512 * MIB, 4 * MIB));
    }

    /** Two adapters and four interfaces on top of them, listed the way Windows lists them: mixed. */
    public static NetworkUsage network() {
        return new NetworkUsage(
                List.of(
                        new NetworkInterfaceUsage(
                                "eth3",
                                "Ethernet-WFP Native MAC Layer LightWeight Filter-0000",
                                DeviceLayer.LOGICAL,
                                rates(200 * KIB, 9 * KIB)),
                        new NetworkInterfaceUsage("eth0", "Ethernet", DeviceLayer.PHYSICAL, rates(200 * KIB, 9 * KIB)),
                        new NetworkInterfaceUsage(
                                "eth5", "vEthernet (Default Switch)", DeviceLayer.LOGICAL, Optional.empty()),
                        new NetworkInterfaceUsage("wlan0", "Wi-Fi", DeviceLayer.PHYSICAL, rates(40 * KIB, 3 * KIB)),
                        new NetworkInterfaceUsage(
                                "loopback0", "Loopback Pseudo-Interface 1", DeviceLayer.LOGICAL, rates(0, 0)),
                        new NetworkInterfaceUsage(
                                "tun0", "WireGuard Tunnel", DeviceLayer.LOGICAL, rates(1.5 * MIB, 512 * KIB))),
                Optional.of(new NetworkRates(240 * KIB, 12 * KIB)));
    }

    public static MemorySample memory(double usedGib, Optional<SwapUsage> swap) {
        return new MemorySample(MEMORY_TOTAL, MEMORY_TOTAL - Math.round(usedGib * GIB), swap);
    }

    private static ProcessorInfo processor(int logicalProcessors) {
        return new ProcessorInfo(
                logicalProcessors,
                OptionalInt.of(Math.max(1, logicalProcessors / 2)),
                OptionalInt.of(1),
                Optional.of("AMD Ryzen 7 5800H with Radeon Graphics"));
    }

    /** Every processor has its own value; the third one was skipped in this cycle. */
    private static List<OptionalDouble> current(int logicalProcessors) {
        List<OptionalDouble> current = new ArrayList<>();
        for (int index = 0; index < logicalProcessors; index++) {
            current.add(index == 2 ? OptionalDouble.empty() : OptionalDouble.of((7.4 + 13 * index) % 100));
        }
        return current;
    }

    private static SystemHistory history(SystemHistory base, int logicalProcessors) {
        List<MetricHistory> perProcessor = new ArrayList<>();
        for (int index = 0; index < logicalProcessors; index++) {
            int processor = index;
            perProcessor.add(Snapshots.series(
                    CAPACITY,
                    i -> i == 30 && processor == 2
                            ? Double.NaN
                            : 50 + 45 * Math.sin(i / (3.0 + processor % 5) + processor)));
        }
        return new SystemHistory(
                base.cpuTotalPercent(),
                perProcessor,
                base.memoryUsedBytes(),
                base.diskReadBytesPerSecond(),
                base.diskWriteBytesPerSecond(),
                base.networkDownloadBytesPerSecond(),
                base.networkUploadBytesPerSecond());
    }

    private static Optional<NetworkRates> rates(double receive, double send) {
        return Optional.of(new NetworkRates(receive, send));
    }
}
