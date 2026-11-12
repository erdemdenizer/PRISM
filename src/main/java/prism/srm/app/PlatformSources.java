package prism.srm.app;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.OomSignalStatus;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.ProcessController;
import prism.srm.platform.ProcessSource;
import prism.srm.platform.ResourceLimiter;
import prism.srm.platform.SystemMetricsSource;
import prism.srm.platform.UnavailableResourceLimiter;

/**
 * What monitoring and process control use of the platform, the PID of Prism's own process, which
 * process control never acts on, and where the suspension journal of this session lives
 * (ADR-0011). On the running system it comes from {@link HostPlatform} and the JDK; a test supplies
 * fakes and a PID of its choosing instead.
 *
 * @param journalFolder the folder of the suspension journals; empty for no journal, which leaves
 *     CPU limits off
 * @param instance the name of this session's journal: its PID and start time
 * @param limiter the native limiter (milestone 10); its ledger lives beside the journal
 * @param oomSignal whether OOM Guard's memory readings are validated on this system (milestone 11,
 *     ADR-0015 section 7)
 */
record PlatformSources(
        SystemMetricsSource systemMetrics,
        ProcessSource processes,
        ProcessController controller,
        long ownPid,
        Optional<Path> journalFolder,
        String instance,
        ResourceLimiter limiter,
        OomSignalStatus oomSignal) {

    PlatformSources {
        Objects.requireNonNull(systemMetrics, "systemMetrics");
        Objects.requireNonNull(processes, "processes");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(journalFolder, "journalFolder");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(limiter, "limiter");
        Objects.requireNonNull(oomSignal, "oomSignal");
    }

    /** Sources as milestone 10 had them: OOM Guard takes no automatic action, as on an unvalidated system. */
    PlatformSources(
            SystemMetricsSource systemMetrics,
            ProcessSource processes,
            ProcessController controller,
            long ownPid,
            Optional<Path> journalFolder,
            String instance,
            ResourceLimiter limiter) {
        this(
                systemMetrics,
                processes,
                controller,
                ownPid,
                journalFolder,
                instance,
                limiter,
                OomSignalStatus.UNVALIDATED);
    }

    /** Sources with a journal and no native limiter, as milestone 9 had them. */
    PlatformSources(
            SystemMetricsSource systemMetrics,
            ProcessSource processes,
            ProcessController controller,
            long ownPid,
            Optional<Path> journalFolder,
            String instance) {
        this(systemMetrics, processes, controller, ownPid, journalFolder, instance, UnavailableResourceLimiter.here());
    }

    /** Sources without a suspension journal. */
    PlatformSources(
            SystemMetricsSource systemMetrics, ProcessSource processes, ProcessController controller, long ownPid) {
        this(systemMetrics, processes, controller, ownPid, Optional.empty(), "none");
    }

    /**
     * Creates the platform of the running system and takes its sources. This queries the
     * operating system and can take a second: never call it on the JavaFX Application Thread.
     */
    static PlatformSources ofRunningSystem() {
        HostPlatform platform = HostPlatform.create();
        ProcessHandle own = ProcessHandle.current();
        return new PlatformSources(
                platform.systemMetricsSource(),
                platform.processSource(),
                platform.processController(),
                own.pid(),
                Optional.of(HostPlatform.userFolders().state().resolve("journal")),
                SuspensionJournal.instanceName(own.pid(), own.info().startInstant()),
                platform.resourceLimiter(),
                HostPlatform.oomSignalStatus());
    }
}
