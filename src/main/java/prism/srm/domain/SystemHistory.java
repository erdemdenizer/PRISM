package prism.srm.domain;

import java.util.List;
import java.util.Objects;

/**
 * Bounded histories of the system metrics. They advance together, one sample per monitoring
 * cycle.
 *
 * @param cpuPerProcessorPercent one history per logical processor. The list is empty until the
 *     processor count is known, and its histories start over if that count changes, so they can
 *     be shorter than the others.
 */
public record SystemHistory(
        MetricHistory cpuTotalPercent,
        List<MetricHistory> cpuPerProcessorPercent,
        MetricHistory memoryUsedBytes,
        MetricHistory diskReadBytesPerSecond,
        MetricHistory diskWriteBytesPerSecond,
        MetricHistory networkDownloadBytesPerSecond,
        MetricHistory networkUploadBytesPerSecond) {

    public SystemHistory {
        Objects.requireNonNull(cpuTotalPercent, "cpuTotalPercent");
        cpuPerProcessorPercent = List.copyOf(cpuPerProcessorPercent);
        Objects.requireNonNull(memoryUsedBytes, "memoryUsedBytes");
        Objects.requireNonNull(diskReadBytesPerSecond, "diskReadBytesPerSecond");
        Objects.requireNonNull(diskWriteBytesPerSecond, "diskWriteBytesPerSecond");
        Objects.requireNonNull(networkDownloadBytesPerSecond, "networkDownloadBytesPerSecond");
        Objects.requireNonNull(networkUploadBytesPerSecond, "networkUploadBytesPerSecond");
    }
}
