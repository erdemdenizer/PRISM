package prism.srm.platform;

import java.util.List;
import prism.srm.domain.CpuSample;
import prism.srm.domain.DiskCounters;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.NetworkCounters;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.ProcessorInfo;
import prism.srm.domain.SystemActivity;

/**
 * Raw system readings: facts, and the cumulative counters that rates are computed from. The
 * source computes no rates and keeps no history.
 *
 * <p>Every method queries the operating system, so none may be called on the JavaFX Application
 * Thread. A query that fails throws a {@link RuntimeException}; one failing metric says nothing
 * about the others, and the same call may succeed later. An instance is used by one thread at a
 * time.
 */
public interface SystemMetricsSource {

    ProcessorInfo processorInfo();

    OperatingSystemInfo operatingSystem();

    /**
     * Cumulative processor time counters. The share of them that is not idle is busy time, the
     * same quantity on every system; it is not adjusted for processor frequency.
     */
    CpuSample cpuTicks();

    MemorySample memory();

    /** Local file systems. */
    List<FileSystemUsage> fileSystems();

    /**
     * Counters of every disk the system lists, physical or not, each under a device name that is
     * unique within the reading. Each reading states the disk's layer, as far as the system
     * tells, and the epoch of its counters.
     */
    List<DiskCounters> diskCounters();

    /**
     * Counters of every network interface the system lists, physical or not, each under a name
     * that is unique within the reading. Each reading states the interface's layer, as far as the
     * system tells, and the epoch of its counters.
     */
    List<NetworkCounters> networkCounters();

    SystemActivity activity();
}
