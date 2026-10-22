package prism.srm.platform;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import prism.srm.platform.linux.LinuxUserFolders;
import prism.srm.platform.macos.MacosUserFolders;
import prism.srm.platform.oshi.OshiHost;
import prism.srm.platform.windows.WindowsDiskLayers;
import prism.srm.platform.windows.WindowsProcessVirtualSizes;
import prism.srm.platform.windows.WindowsUserFolders;

/**
 * Entry point to the operating-system boundary and its composition root. It detects the running
 * system once, creates the implementations of the platform interfaces for it, and hands them out,
 * so nothing outside this package names an implementation package.
 *
 * <p>One instance is created at start-up and kept. It owns the long-lived objects of the
 * implementations, so every source it hands out shares them.
 *
 * <p>The static methods answer what must be known before that instance exists and before logging
 * is set up: they query nothing but system properties and the environment, and neither they nor
 * what they return use a logger.
 */
public final class HostPlatform {

    private final OshiHost oshi;

    private HostPlatform(OshiHost oshi) {
        this.oshi = oshi;
    }

    /**
     * Detects the running system and creates its implementations. This queries the operating
     * system: call it off the JavaFX Application Thread.
     */
    public static HostPlatform create() {
        HostSystem system = HostSystem.current();
        // Where the system has no source of disk layers yet, every disk stays UNKNOWN.
        DiskLayerSource diskLayers = system == HostSystem.WINDOWS ? new WindowsDiskLayers() : Map::of;
        // Only Windows needs a source of its own for virtual sizes; elsewhere it is never asked.
        ProcessVirtualSizeSource virtualSizes =
                system == HostSystem.WINDOWS ? new WindowsProcessVirtualSizes() : Map::of;
        return new HostPlatform(new OshiHost(system, diskLayers, virtualSizes));
    }

    /** Returns the per-user folders of the running system. Does not throw. */
    public static UserFolders userFolders() {
        return userFolders(HostSystem.current(), System::getenv, home(System.getProperty("user.home", "")));
    }

    /**
     * Returns the home folder as a path. A value the file system cannot express as a path gives
     * the empty path, which stands for the working directory: this runs before logging exists,
     * so a failure here could not be reported and would end the application without a trace.
     */
    static Path home(String userHome) {
        try {
            return Path.of(userHome);
        } catch (InvalidPathException unusable) {
            return Path.of("");
        }
    }

    static UserFolders userFolders(HostSystem system, UnaryOperator<String> environment, Path home) {
        return switch (system) {
            case WINDOWS -> new WindowsUserFolders(environment, home);
            case LINUX -> new LinuxUserFolders(environment, home);
            case MACOS -> new MacosUserFolders(home);
            case OTHER -> new HomeFolderUserFolders(home);
        };
    }

    /**
     * Describes the detected system for the log: the family Prism selected its implementations
     * for, and the system properties it was detected from.
     */
    public static String systemDescription() {
        return systemDescription(
                HostSystem.current(),
                System.getProperty("os.name", ""),
                System.getProperty("os.version", ""),
                System.getProperty("os.arch", ""));
    }

    static String systemDescription(HostSystem system, String osName, String osVersion, String osArch) {
        String family = switch (system) {
            case WINDOWS -> "Windows";
            case LINUX -> "Linux";
            case MACOS -> "macOS";
            case OTHER -> "unsupported system";
        };
        return family + " (os.name=" + osName + ", os.version=" + osVersion + ", os.arch=" + osArch + ")";
    }

    /** Returns the source of system metrics; the same instance on every call. */
    public SystemMetricsSource systemMetricsSource() {
        return oshi.systemMetricsSource();
    }

    /** Returns the source of process readings; the same instance on every call. */
    public ProcessSource processSource() {
        return oshi.processSource();
    }
}
