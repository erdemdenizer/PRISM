package prism.srm.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The state of the system after one monitoring cycle. A part is absent when it has no value for
 * this cycle: its source failed, the system does not report it, or it is a rate and there is no
 * valid pair of readings to compute it from.
 */
public record SystemSnapshot(
        Optional<ProcessorInfo> processor,
        Optional<OperatingSystemInfo> operatingSystem,
        Optional<CpuUsage> cpu,
        Optional<MemorySample> memory,
        Optional<List<FileSystemUsage>> fileSystems,
        Optional<DiskRates> diskRates,
        Optional<NetworkUsage> network,
        Optional<SystemActivity> activity,
        SystemHistory history) {

    public SystemSnapshot {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(operatingSystem, "operatingSystem");
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(memory, "memory");
        fileSystems = fileSystems.map(List::copyOf);
        Objects.requireNonNull(diskRates, "diskRates");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(history, "history");
    }
}
