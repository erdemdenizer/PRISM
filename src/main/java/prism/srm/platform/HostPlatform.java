package prism.srm.platform;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import prism.srm.domain.OomSignalStatus;
import prism.srm.platform.linux.LinuxProcessController;
import prism.srm.platform.linux.LinuxScopeLimiter;
import prism.srm.platform.linux.LinuxUserFolders;
import prism.srm.platform.macos.MacosProcessController;
import prism.srm.platform.macos.MacosUserFolders;
import prism.srm.platform.oshi.OshiHost;
import prism.srm.platform.windows.WindowsConsoleClose;
import prism.srm.platform.windows.WindowsDiskLayers;
import prism.srm.platform.windows.WindowsJobLimiter;
import prism.srm.platform.windows.WindowsProcessController;
import prism.srm.platform.windows.WindowsProcessCpuTimes;
import prism.srm.platform.windows.WindowsProcessVirtualSizes;
import prism.srm.platform.windows.WindowsUserFolders;
import prism.srm.platform.windows.WindowsWindowChrome;

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
    private final ProcessController processController;
    private final ResourceLimiter resourceLimiter;

    private HostPlatform(OshiHost oshi, ProcessController processController, ResourceLimiter resourceLimiter) {
        this.oshi = oshi;
        this.processController = processController;
        this.resourceLimiter = resourceLimiter;
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
        // OSHI's lookup of one process builds its maps of every process on Windows, so the
        // processor time of one process comes from the system there; elsewhere OSHI reads one.
        Optional<ProcessCpuTimeSource> cpuTimes =
                system == HostSystem.WINDOWS ? Optional.of(new WindowsProcessCpuTimes()) : Optional.empty();
        OshiHost oshi = new OshiHost(system, diskLayers, virtualSizes, cpuTimes);
        return new HostPlatform(
                oshi, controllerFor(system, oshi::bootTimeSeconds), limiterFor(system, oshi::bootTimeSeconds));
    }

    /**
     * The controller of other processes the system gets. Making one touches nothing native: the
     * Linux and macOS controllers load their libraries and probe the system on first use. A system
     * Prism has no controller for gets one that reports every action as unavailable.
     *
     * @param bootTimeSeconds the boot time the process listing computes start times from, which
     *     the Linux controller computes them from too
     */
    static ProcessController controllerFor(HostSystem system, LongSupplier bootTimeSeconds) {
        return switch (system) {
            case WINDOWS -> new WindowsProcessController();
            case LINUX -> new LinuxProcessController(bootTimeSeconds);
            case MACOS -> new MacosProcessController();
            case OTHER -> new UnavailableProcessController();
        };
    }

    /**
     * The native limiter the system gets (milestone 10): Job Objects on Windows; on Linux the scope
     * limiter of milestone 12 in its product form, which admits no process (K16, ADR-0016); elsewhere a
     * limiter that reports native limits as unavailable, with the reason, so that the limit service
     * runs the emulated CPU limit (LIM-7). Making one touches nothing native: the Windows limiter
     * probes the system on first use.
     */
    static ResourceLimiter limiterFor(HostSystem system, LongSupplier bootTimeSeconds) {
        return switch (system) {
            case WINDOWS -> new WindowsJobLimiter();
            // Milestone 12: the systemd scope mechanism exists, but no desktop application is eligible (K16); the
            // product's limiter reports every kind unavailable with that reason and keeps the scope-membership rule.
            case LINUX -> LinuxScopeLimiter.product(bootTimeSeconds);
            case MACOS, OTHER -> UnavailableResourceLimiter.here();
        };
    }

    /**
     * Whether OOM Guard's memory readings are validated on the running system (ADR-0015 §7): the
     * available physical memory and the resident memory of a process. Reads nothing.
     */
    public static OomSignalStatus oomSignalStatus() {
        return oomSignalStatus(HostSystem.current());
    }

    /**
     * Fails closed: a system is validated only on reviewed evidence (ADR-0015 §7).
     *
     * <ul>
     *   <li>Windows: "available" is the standby, free and zero page lists (PERFORMANCE_INFORMATION),
     *       and the resident memory is the working set. Both are checked: the working set in milestone
     *       5, and both signals by the tests of milestone 11, run on Windows 11.
     *   <li>Linux: not validated. {@code MemAvailable} (proc(5)) and the RSS are documented. The
     *       milestone 11 tests that check how they respond have passed in CI on Linux (from commit
     *       3d6e4eb on, docs/evidence/m11-ci-cross-platform-3d6e4eb.md). That evidence is not yet
     *       accepted (ADR-0015 §7 items 3 and 4), so the table does not change.
     *   <li>macOS: OSHI reads free plus inactive pages, which Apple does not define as available
     *       memory. In CI that reading fell by only about a quarter of the memory a process took.
     *   <li>Another system: not validated.
     * </ul>
     *
     * Monitoring, warnings and the log work everywhere; only automatic interventions need a validated
     * system.
     */
    static OomSignalStatus oomSignalStatus(HostSystem system) {
        return switch (system) {
            case WINDOWS -> OomSignalStatus.VALIDATED;
            case LINUX, MACOS, OTHER -> OomSignalStatus.UNVALIDATED;
        };
    }

    /** Returns the per-user folders of the running system. Does not throw. */
    public static UserFolders userFolders() {
        return userFolders(HostSystem.current(), System::getenv, home(System.getProperty("user.home", "")));
    }

    /**
     * LIFE-5: runs {@code release} when the console this process runs in is closed, before the system
     * ends the process; on Windows the JVM's shutdown hooks may not get that far. Only Windows needs and
     * has it; the packaged application has no console, so there it is never called. The release must be
     * bounded well below the five seconds the system waits by default.
     *
     * @return whether such a handler is in place
     */
    public static boolean onConsoleClose(Runnable release) {
        return switch (HostSystem.current()) {
            case WINDOWS -> WindowsConsoleClose.register(release);
            case LINUX, MACOS, OTHER -> false;
        };
    }

    /**
     * The colors of the frame the system draws around the application's windows (UI lab v2): Windows
     * from build 22000 lets them be set; elsewhere the frame stays as the system draws it. Making one
     * touches nothing native; the Windows one checks the build on first use.
     */
    public static WindowChrome windowChrome() {
        return switch (HostSystem.current()) {
            case WINDOWS -> new WindowsWindowChrome();
            case LINUX, MACOS, OTHER -> WindowChrome.UNAVAILABLE;
        };
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

    /** Returns the controller of other processes; the same instance on every call. */
    public ProcessController processController() {
        return processController;
    }

    /** Returns the native limiter; the same instance on every call. */
    public ResourceLimiter resourceLimiter() {
        return resourceLimiter;
    }
}
