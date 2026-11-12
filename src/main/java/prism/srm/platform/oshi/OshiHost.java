package prism.srm.platform.oshi;

import java.util.Optional;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import prism.srm.platform.DiskLayerSource;
import prism.srm.platform.HostSystem;
import prism.srm.platform.ProcessCpuTimeSource;
import prism.srm.platform.ProcessSource;
import prism.srm.platform.ProcessVirtualSizeSource;
import prism.srm.platform.SystemMetricsSource;

/**
 * Owner of the long-lived OSHI objects. {@link SystemInfo} and the objects obtained from it are
 * created here, once, and every OSHI-backed source is created here from those same objects, so
 * that sources never set up OSHI a second time.
 */
public final class OshiHost {

    private final SystemMetricsSource systemMetricsSource;
    private final ProcessSource processSource;
    private final OperatingSystem os;

    /**
     * Sets up OSHI, which queries the operating system: call it off the JavaFX Application Thread.
     *
     * @param system the running system, for the choices that OSHI's behavior makes system-specific
     * @param diskLayers the system's knowledge of disk layers, which OSHI does not have
     * @param windowsVirtualSizes the virtual sizes of processes on Windows, which OSHI does not
     *     have there; not asked on any other system
     * @param cpuTimes the processor time of one process from the system, where OSHI's lookup of one
     *     process costs too much to make once a second; empty where OSHI's is used
     */
    public OshiHost(
            HostSystem system,
            DiskLayerSource diskLayers,
            ProcessVirtualSizeSource windowsVirtualSizes,
            Optional<ProcessCpuTimeSource> cpuTimes) {
        SystemInfo systemInfo = new SystemInfo();
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        os = systemInfo.getOperatingSystem();
        systemMetricsSource = new OshiSystemMetricsSource(hardware, os, system, diskLayers, System::nanoTime);
        processSource = new OshiProcessSource(os::getProcesses, os::getProcess, system, windowsVirtualSizes, cpuTimes);
    }

    /** Returns the source of system metrics; the same instance on every call. */
    public SystemMetricsSource systemMetricsSource() {
        return systemMetricsSource;
    }

    /** Returns the source of process readings; the same instance on every call. */
    public ProcessSource processSource() {
        return processSource;
    }

    /**
     * The boot time OSHI computes the start times of processes from, in seconds since the epoch;
     * on Linux it reads {@code btime} once and keeps it. A controller that checks an identity
     * computes its start time from the same value, so that the two always agree.
     */
    public long bootTimeSeconds() {
        return os.getSystemBootTime();
    }
}
