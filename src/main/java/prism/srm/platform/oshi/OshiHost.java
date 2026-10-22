package prism.srm.platform.oshi;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;
import prism.srm.platform.DiskLayerSource;
import prism.srm.platform.HostSystem;
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

    /**
     * Sets up OSHI, which queries the operating system: call it off the JavaFX Application Thread.
     *
     * @param system the running system, for the choices that OSHI's behavior makes system-specific
     * @param diskLayers the system's knowledge of disk layers, which OSHI does not have
     * @param windowsVirtualSizes the virtual sizes of processes on Windows, which OSHI does not
     *     have there; not asked on any other system
     */
    public OshiHost(HostSystem system, DiskLayerSource diskLayers, ProcessVirtualSizeSource windowsVirtualSizes) {
        SystemInfo systemInfo = new SystemInfo();
        HardwareAbstractionLayer hardware = systemInfo.getHardware();
        OperatingSystem os = systemInfo.getOperatingSystem();
        systemMetricsSource = new OshiSystemMetricsSource(hardware, os, system, diskLayers, System::nanoTime);
        processSource = new OshiProcessSource(os::getProcesses, os::getProcess, system, windowsVirtualSizes);
    }

    /** Returns the source of system metrics; the same instance on every call. */
    public SystemMetricsSource systemMetricsSource() {
        return systemMetricsSource;
    }

    /** Returns the source of process readings; the same instance on every call. */
    public ProcessSource processSource() {
        return processSource;
    }
}
