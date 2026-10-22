package prism.srm.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import prism.srm.application.DeviceRateTracker.Counters;
import prism.srm.domain.CpuSample;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.DiskRates;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.platform.SystemMetricsSource;

/**
 * Takes one reading of every system metric and turns it, together with the previous reading,
 * into a snapshot: current values, rates, and the bounded histories.
 *
 * <p>Each metric is read on its own, so a source that fails for one of them leaves that one
 * absent and the rest intact. A failed reading does not replace the previous good one; the next
 * rate is then computed over the longer interval.
 *
 * <p>Reading the counters of all disks or interfaces takes time, sometimes more than a hundred
 * milliseconds, and the source does not say at which moment inside that time each counter was
 * taken. A reading is therefore dated at the midpoint of the time the read took, which halves the
 * largest possible error compared with dating it at either end.
 *
 * <p>Used by one thread at a time.
 */
final class SystemSampler {

    private final SystemMetricsSource source;
    private final LongSupplier nanoClock;
    private final FailureLog failures = new FailureLog(LoggerFactory.getLogger(SystemSampler.class));

    private final DeviceRateTracker diskRateTracker = new DeviceRateTracker();
    private final DeviceRateTracker networkRateTracker = new DeviceRateTracker();

    private final int historyCapacity;
    private final BoundedHistory cpuTotalHistory;
    private final BoundedHistory memoryUsedHistory;
    private final BoundedHistory diskReadHistory;
    private final BoundedHistory diskWriteHistory;
    private final BoundedHistory networkDownloadHistory;
    private final BoundedHistory networkUploadHistory;
    private List<BoundedHistory> cpuPerProcessorHistory = List.of();

    // Static facts are read until the source provides them, then kept.
    private Optional<ProcessorInfo> processor = Optional.empty();
    private Optional<OperatingSystemInfo> operatingSystem = Optional.empty();
    private Optional<CpuSample> previousCpu = Optional.empty();

    /**
     * @param nanoClock monotonic clock, such as {@code System::nanoTime}
     * @param historyCapacity number of samples each history keeps
     */
    SystemSampler(SystemMetricsSource source, LongSupplier nanoClock, int historyCapacity) {
        this.source = source;
        this.nanoClock = nanoClock;
        this.historyCapacity = historyCapacity;
        cpuTotalHistory = new BoundedHistory(historyCapacity);
        memoryUsedHistory = new BoundedHistory(historyCapacity);
        diskReadHistory = new BoundedHistory(historyCapacity);
        diskWriteHistory = new BoundedHistory(historyCapacity);
        networkDownloadHistory = new BoundedHistory(historyCapacity);
        networkUploadHistory = new BoundedHistory(historyCapacity);
    }

    SystemSnapshot sample() {
        if (processor.isEmpty()) {
            processor = read("Processor information", source::processorInfo);
        }
        if (operatingSystem.isEmpty()) {
            operatingSystem = read("Operating system information", source::operatingSystem);
        }
        Optional<CpuUsage> cpu = sampleCpu();
        Optional<MemorySample> memory = read("Memory reading", source::memory);
        Optional<List<FileSystemUsage>> fileSystems = read("File system reading", source::fileSystems);
        Optional<DiskRates> diskRates = sampleDiskRates();
        Optional<NetworkUsage> network = sampleNetwork();
        Optional<SystemActivity> activity = read("System activity reading", source::activity);

        return new SystemSnapshot(
                processor,
                operatingSystem,
                cpu,
                memory,
                fileSystems,
                diskRates,
                network,
                activity,
                recordHistory(cpu, memory, diskRates, network));
    }

    private Optional<CpuUsage> sampleCpu() {
        Optional<CpuSample> current = read("CPU reading", source::cpuTicks);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Optional<CpuUsage> usage = previousCpu.flatMap(previous -> RateCalculator.cpuUsage(previous, current.get()));
        previousCpu = current;
        return usage;
    }

    private Optional<DiskRates> sampleDiskRates() {
        long readStartNanos = nanoClock.getAsLong();
        Optional<Map<String, Counters>> counters = read("Disk activity reading", () -> {
            Map<String, Counters> byDevice = new LinkedHashMap<>();
            for (DiskCounters disk : source.diskCounters()) {
                putUnique(
                        byDevice,
                        disk.device(),
                        new Counters(disk.layer(), disk.epoch(), disk.bytesRead(), disk.bytesWritten()));
            }
            return byDevice;
        });
        long readEndNanos = nanoClock.getAsLong();
        if (counters.isEmpty()) {
            return Optional.empty();
        }
        return diskRateTracker
                .update(counters.get(), RateCalculator.midpoint(readStartNanos, readEndNanos))
                .total()
                .map(total -> new DiskRates(total.inbound(), total.outbound()));
    }

    private Optional<NetworkUsage> sampleNetwork() {
        Map<String, Counters> counters = new LinkedHashMap<>();
        long readStartNanos = nanoClock.getAsLong();
        Optional<List<NetworkCounters>> reading = read("Network reading", () -> {
            List<NetworkCounters> interfaces = source.networkCounters();
            for (NetworkCounters networkInterface : interfaces) {
                putUnique(
                        counters,
                        networkInterface.name(),
                        new Counters(
                                networkInterface.layer(),
                                networkInterface.epoch(),
                                networkInterface.bytesReceived(),
                                networkInterface.bytesSent()));
            }
            return interfaces;
        });
        long readEndNanos = nanoClock.getAsLong();
        if (reading.isEmpty()) {
            return Optional.empty();
        }
        DeviceRateTracker.Result rates =
                networkRateTracker.update(counters, RateCalculator.midpoint(readStartNanos, readEndNanos));
        List<NetworkInterfaceUsage> interfaces = new ArrayList<>();
        for (NetworkCounters networkInterface : reading.get()) {
            interfaces.add(new NetworkInterfaceUsage(
                    networkInterface.name(),
                    networkInterface.displayName(),
                    networkInterface.layer(),
                    Optional.ofNullable(rates.perDevice().get(networkInterface.name()))
                            .map(SystemSampler::toNetworkRates)));
        }
        return Optional.of(new NetworkUsage(interfaces, rates.total().map(SystemSampler::toNetworkRates)));
    }

    private static NetworkRates toNetworkRates(DeviceRateTracker.Rates rates) {
        return new NetworkRates(rates.inbound(), rates.outbound());
    }

    private static void putUnique(Map<String, Counters> byDevice, String device, Counters counters) {
        if (byDevice.put(device, counters) != null) {
            throw new IllegalStateException("Device listed twice in one reading: " + device);
        }
    }

    /**
     * Appends exactly one entry, a value or a gap, to every history, so they stay aligned. The
     * per-processor histories join once the processor count is known.
     */
    private SystemHistory recordHistory(
            Optional<CpuUsage> cpu,
            Optional<MemorySample> memory,
            Optional<DiskRates> diskRates,
            Optional<NetworkUsage> network) {
        Optional<NetworkRates> networkTotal = network.flatMap(NetworkUsage::total);
        append(cpuTotalHistory, cpu.map(CpuUsage::totalPercent));
        append(memoryUsedHistory, memory.map(sample -> (double) sample.usedBytes()));
        append(diskReadHistory, diskRates.map(DiskRates::readBytesPerSecond));
        append(diskWriteHistory, diskRates.map(DiskRates::writeBytesPerSecond));
        append(networkDownloadHistory, networkTotal.map(NetworkRates::receiveBytesPerSecond));
        append(networkUploadHistory, networkTotal.map(NetworkRates::sendBytesPerSecond));
        recordPerProcessorHistory(cpu);

        List<MetricHistory> perProcessor = new ArrayList<>(cpuPerProcessorHistory.size());
        for (BoundedHistory history : cpuPerProcessorHistory) {
            perProcessor.add(history.snapshot());
        }
        return new SystemHistory(
                cpuTotalHistory.snapshot(),
                perProcessor,
                memoryUsedHistory.snapshot(),
                diskReadHistory.snapshot(),
                diskWriteHistory.snapshot(),
                networkDownloadHistory.snapshot(),
                networkUploadHistory.snapshot());
    }

    private void recordPerProcessorHistory(Optional<CpuUsage> cpu) {
        int processors =
                previousCpu.map(reading -> reading.perProcessor().size()).orElse(cpuPerProcessorHistory.size());
        if (processors != cpuPerProcessorHistory.size()) {
            // First reading, or the number of logical processors changed: the old series no
            // longer describe the same processors.
            List<BoundedHistory> histories = new ArrayList<>(processors);
            for (int i = 0; i < processors; i++) {
                histories.add(new BoundedHistory(historyCapacity));
            }
            cpuPerProcessorHistory = histories;
        }
        for (int i = 0; i < processors; i++) {
            OptionalDouble percent =
                    cpu.isPresent() ? cpu.get().perProcessorPercent().get(i) : OptionalDouble.empty();
            if (percent.isPresent()) {
                cpuPerProcessorHistory.get(i).add(percent.getAsDouble());
            } else {
                cpuPerProcessorHistory.get(i).addSkipped();
            }
        }
    }

    private static void append(BoundedHistory history, Optional<Double> sample) {
        if (sample.isPresent()) {
            history.add(sample.get());
        } else {
            history.addSkipped();
        }
    }

    private <T> Optional<T> read(String what, Supplier<T> reading) {
        try {
            return Optional.of(reading.get());
        } catch (RuntimeException e) {
            // The way a query fails according to the contract of SystemMetricsSource. An Error is
            // deliberately not caught: it is not a failed reading but a broken runtime.
            failures.warnOnce(what, e);
            return Optional.empty();
        }
    }
}
