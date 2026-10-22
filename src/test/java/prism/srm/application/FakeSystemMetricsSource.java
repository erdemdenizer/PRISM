package prism.srm.application;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import prism.srm.domain.CpuSample;
import prism.srm.domain.CpuTicks;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SwapUsage;
import prism.srm.domain.SystemActivity;
import prism.srm.platform.SystemMetricsSource;

/**
 * A source whose readings are set by the test. A metric given a failure throws it instead of
 * returning its reading. Fields are plain: a test that samples on another thread sets them before
 * starting it.
 */
public final class FakeSystemMetricsSource implements SystemMetricsSource {

    public enum Metric {
        PROCESSOR,
        OPERATING_SYSTEM,
        CPU,
        MEMORY,
        FILE_SYSTEMS,
        DISKS,
        NETWORK,
        ACTIVITY
    }

    static final long GIB = 1024L * 1024 * 1024;

    ProcessorInfo processorInfo = new ProcessorInfo(2, OptionalInt.of(1), OptionalInt.of(1), Optional.of("Fake CPU"));
    OperatingSystemInfo operatingSystem = new OperatingSystemInfo("FakeOS", Optional.of("1"), Optional.of("100"));
    CpuSample cpu = new CpuSample(new CpuTicks(0, 0), List.of(new CpuTicks(0, 0), new CpuTicks(0, 0)));
    MemorySample memory = new MemorySample(8 * GIB, 6 * GIB, Optional.of(new SwapUsage(2 * GIB, GIB)));
    List<FileSystemUsage> fileSystems = List.of(new FileSystemUsage("root", "/", 100 * GIB, 40 * GIB));
    List<DiskCounters> disks = List.of(disk("disk0", 0, 0));
    List<NetworkCounters> interfaces = List.of(nic("eth0", "Fake Ethernet", 0, 0));
    SystemActivity activity = new SystemActivity(Duration.ofHours(1), 100);

    /** Runs at the start of every CPU reading, which the sampler takes once per cycle. */
    public Runnable onCpuReading = () -> {};

    /** Runs while the disk counters are being read, to let a test make the read take time. */
    Runnable duringDiskRead = () -> {};

    /** A physical disk whose counters continue those of every other reading made with this method. */
    static DiskCounters disk(String device, long bytesRead, long bytesWritten) {
        return new DiskCounters(device, DeviceLayer.PHYSICAL, 1, bytesRead, bytesWritten);
    }

    /** A physical interface whose counters continue those of every other reading made with this method. */
    static NetworkCounters nic(String name, String displayName, long bytesReceived, long bytesSent) {
        return new NetworkCounters(name, displayName, DeviceLayer.PHYSICAL, 1, bytesReceived, bytesSent);
    }

    private final Map<Metric, Throwable> failures = new EnumMap<>(Metric.class);
    private final Map<Metric, Integer> reads = new EnumMap<>(Metric.class);

    public void fail(Metric metric, Throwable failure) {
        failures.put(metric, failure);
    }

    public void recover(Metric metric) {
        failures.remove(metric);
    }

    public int reads(Metric metric) {
        return reads.getOrDefault(metric, 0);
    }

    @Override
    public ProcessorInfo processorInfo() {
        return answer(Metric.PROCESSOR, processorInfo);
    }

    @Override
    public OperatingSystemInfo operatingSystem() {
        return answer(Metric.OPERATING_SYSTEM, operatingSystem);
    }

    @Override
    public CpuSample cpuTicks() {
        onCpuReading.run();
        return answer(Metric.CPU, cpu);
    }

    @Override
    public MemorySample memory() {
        return answer(Metric.MEMORY, memory);
    }

    @Override
    public List<FileSystemUsage> fileSystems() {
        return answer(Metric.FILE_SYSTEMS, fileSystems);
    }

    @Override
    public List<DiskCounters> diskCounters() {
        duringDiskRead.run();
        return answer(Metric.DISKS, disks);
    }

    @Override
    public List<NetworkCounters> networkCounters() {
        return answer(Metric.NETWORK, interfaces);
    }

    @Override
    public SystemActivity activity() {
        return answer(Metric.ACTIVITY, activity);
    }

    private <T> T answer(Metric metric, T reading) {
        reads.merge(metric, 1, Integer::sum);
        Throwable failure = failures.get(metric);
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return reading;
    }
}
