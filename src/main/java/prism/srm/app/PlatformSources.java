package prism.srm.app;

import java.util.Objects;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.ProcessSource;
import prism.srm.platform.SystemMetricsSource;

/**
 * The sources monitoring reads from. On the running system they come from {@link HostPlatform};
 * a test supplies fakes instead.
 */
record PlatformSources(SystemMetricsSource systemMetrics, ProcessSource processes) {

    PlatformSources {
        Objects.requireNonNull(systemMetrics, "systemMetrics");
        Objects.requireNonNull(processes, "processes");
    }

    /**
     * Creates the platform of the running system and takes its sources. This queries the
     * operating system and can take a second: never call it on the JavaFX Application Thread.
     */
    static PlatformSources ofRunningSystem() {
        HostPlatform platform = HostPlatform.create();
        return new PlatformSources(platform.systemMetricsSource(), platform.processSource());
    }
}
