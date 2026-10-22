package prism.srm.ui.overview;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactReadout;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.PageStatus;
import prism.srm.ui.metric.SystemReadouts;

/**
 * Turns system snapshots into what the Overview page shows. It holds no JavaFX node and does no
 * monitoring: it is given each snapshot on the JavaFX Application Thread and sets its properties
 * from it.
 */
final class OverviewViewModel {

    private final ValueFormatter formatter;
    private final SystemReadouts readouts;
    private final PageStatus status = new PageStatus();
    private final MetricReadout cpu = new MetricReadout();
    private final MetricReadout memory = new MetricReadout();
    private final MetricReadout disk = new MetricReadout();
    private final MetricReadout network = new MetricReadout();
    private final FactReadout operatingSystem = new FactReadout();
    private final FactReadout uptime = new FactReadout();
    private final FactReadout processCount = new FactReadout();

    OverviewViewModel(ValueFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.readouts = new SystemReadouts(formatter);
    }

    /** Whether snapshots arrive, and the sentence that says why not. */
    PageStatus status() {
        return status;
    }

    MetricReadout cpu() {
        return cpu;
    }

    MetricReadout memory() {
        return memory;
    }

    MetricReadout disk() {
        return disk;
    }

    MetricReadout network() {
        return network;
    }

    FactReadout operatingSystem() {
        return operatingSystem;
    }

    FactReadout uptime() {
        return uptime;
    }

    FactReadout processCount() {
        return processCount;
    }

    /** Shows a snapshot. */
    void update(SystemSnapshot snapshot) {
        status.live();
        readouts.showCpu(cpu, snapshot);
        readouts.showMemory(memory, snapshot);
        readouts.showDisk(disk, snapshot);
        readouts.showNetwork(network, snapshot);
        updateFacts(snapshot.operatingSystem(), snapshot.activity(), SystemReadouts.cycles(snapshot));
    }

    /**
     * Shows that monitoring could not start.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    void startupFailed(String logFolder) {
        status.startupFailed(logFolder);
        showNothing();
    }

    /**
     * Shows that system monitoring has ended while the application ran. The last values and the
     * histories are taken away: left in place, they would look current.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    void monitoringStopped(String logFolder) {
        status.monitoringStopped(logFolder);
        showNothing();
    }

    private void showNothing() {
        for (MetricReadout readout : new MetricReadout[] {cpu, memory, disk, network}) {
            SystemReadouts.showNothing(readout);
        }
        operatingSystem.showUnavailable();
        uptime.showUnavailable();
        processCount.showUnavailable();
    }

    private void updateFacts(Optional<OperatingSystemInfo> system, Optional<SystemActivity> activity, int cycles) {
        operatingSystem.show(system.map(OverviewViewModel::describe), cycles);
        uptime.show(activity.map(now -> formatter.uptime(now.uptime())), cycles);
        processCount.show(activity.map(now -> formatter.count(now.processCount())), cycles);
    }

    private static String describe(OperatingSystemInfo system) {
        StringBuilder text = new StringBuilder(system.name());
        system.version().ifPresent(version -> text.append(' ').append(version));
        system.build().ifPresent(build -> text.append(" (build ").append(build).append(')'));
        return text.toString();
    }
}
