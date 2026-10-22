package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MonitoringFailure;
import prism.srm.ui.shell.StatusBar.Monitoring;

/** Which line the status bar shows for what has failed. Needs no JavaFX toolkit. */
class MonitoringStatusTest {

    private static final Set<MonitoringFailure> NOTHING = Set.of();

    @Test
    void withoutAFailureTheStatusFollowsWhetherASnapshotHasArrived() {
        assertThat(Monitoring.of(NOTHING, false)).isEqualTo(Monitoring.STARTING);
        assertThat(Monitoring.of(NOTHING, true)).isEqualTo(Monitoring.RUNNING);
    }

    @Test
    void aFailedStartIsNotMonitoring() {
        assertThat(Monitoring.of(Set.of(MonitoringFailure.STARTUP), false)).isEqualTo(Monitoring.COULD_NOT_START);
    }

    /** The Overview still shows live values, so this must not read as "Monitoring" or as stopped. */
    @Test
    void theEndOfProcessMonitoringAloneIsDegradedMonitoring() {
        Set<MonitoringFailure> processes = Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED);

        assertThat(Monitoring.of(processes, true)).isEqualTo(Monitoring.DEGRADED);
        // Also when it ends before the first snapshot was shown.
        assertThat(Monitoring.of(processes, false)).isEqualTo(Monitoring.DEGRADED);
    }

    @Test
    void theEndOfSystemMonitoringIsSaidAsSuchWhileProcessesAreStillListed() {
        assertThat(Monitoring.of(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED), true))
                .isEqualTo(Monitoring.SYSTEM_STOPPED);
    }

    @Test
    void whenBothHaveEndedMonitoringHasStopped() {
        assertThat(Monitoring.of(
                        EnumSet.of(
                                MonitoringFailure.SYSTEM_MONITORING_STOPPED,
                                MonitoringFailure.PROCESS_MONITORING_STOPPED),
                        true))
                .isEqualTo(Monitoring.STOPPED);
    }

    /**
     * The rule behind the shell dropping a snapshot that arrives after the news that system
     * monitoring has ended: such a snapshot was sent before and must not bring values back.
     */
    @Test
    void systemValuesAreLiveOnlyWhileSystemMonitoringRuns() {
        assertThat(Monitoring.systemValuesAreLive(NOTHING)).isTrue();
        assertThat(Monitoring.systemValuesAreLive(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED)))
                .isTrue();
        assertThat(Monitoring.systemValuesAreLive(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED)))
                .isFalse();
        assertThat(Monitoring.systemValuesAreLive(Set.of(MonitoringFailure.STARTUP)))
                .isFalse();
        assertThat(Monitoring.systemValuesAreLive(EnumSet.allOf(MonitoringFailure.class)))
                .isFalse();
    }

    /** The rule behind the shell dropping a listing that arrives after process monitoring ended. */
    @Test
    void processValuesAreLiveOnlyWhileProcessMonitoringRuns() {
        assertThat(Monitoring.processValuesAreLive(NOTHING)).isTrue();
        // The process listing does not depend on the system metrics once it runs.
        assertThat(Monitoring.processValuesAreLive(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED)))
                .isTrue();
        assertThat(Monitoring.processValuesAreLive(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED)))
                .isFalse();
        assertThat(Monitoring.processValuesAreLive(Set.of(MonitoringFailure.STARTUP)))
                .isFalse();
        assertThat(Monitoring.processValuesAreLive(EnumSet.allOf(MonitoringFailure.class)))
                .isFalse();
    }

    /**
     * Process monitoring starts with a system snapshot. The Processes page says that it may never
     * get a listing only while that can be true: system monitoring has ended and no listing has
     * arrived. Once one has, the end of system monitoring is nothing to the process listing.
     */
    @Test
    void processesMayNeverBeListedOnlyIfSystemMonitoringEndedBeforeTheFirstListing() {
        Set<MonitoringFailure> system = Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED);

        assertThat(Monitoring.processesMayNeverBeListed(system, false)).isTrue();
        assertThat(Monitoring.processesMayNeverBeListed(system, true)).isFalse();
        assertThat(Monitoring.processesMayNeverBeListed(NOTHING, false)).isFalse();
        assertThat(Monitoring.processesMayNeverBeListed(NOTHING, true)).isFalse();
        // These have their own sentence on the page: the start failed, or process monitoring ended.
        assertThat(Monitoring.processesMayNeverBeListed(Set.of(MonitoringFailure.STARTUP), false))
                .isFalse();
        assertThat(Monitoring.processesMayNeverBeListed(EnumSet.allOf(MonitoringFailure.class), false))
                .isFalse();
        assertThat(Monitoring.processesMayNeverBeListed(
                        EnumSet.of(
                                MonitoringFailure.SYSTEM_MONITORING_STOPPED,
                                MonitoringFailure.PROCESS_MONITORING_STOPPED),
                        false))
                .isFalse();
    }

    @Test
    void everyStateHasItsOwnWords() {
        assertThat(Monitoring.values()).extracting(Monitoring::text).doesNotHaveDuplicates();
        assertThat(Monitoring.RUNNING.text()).isEqualTo("Monitoring");
        assertThat(Monitoring.DEGRADED.text())
                .isEqualTo(
                        "Monitoring degraded: process monitoring stopped unexpectedly. Restart Prism to restore it.");
        assertThat(Monitoring.SYSTEM_STOPPED.text()).isEqualTo("System monitoring stopped unexpectedly");
        assertThat(Monitoring.STOPPED.text()).isEqualTo("Monitoring stopped unexpectedly");
        assertThat(Monitoring.COULD_NOT_START.text()).isEqualTo("Monitoring could not start");
    }
}
