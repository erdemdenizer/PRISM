package prism.srm.ui.overview;

import java.util.Objects;
import java.util.Optional;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import prism.srm.domain.OomView;
import prism.srm.domain.OperatingSystemInfo;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.OomWords;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactReadout;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.MetricState;
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
    private final FactReadout managedCount = new FactReadout();
    private final FactReadout leftSuspendedCount = new FactReadout();
    private final FactReadout oom = new FactReadout();
    private final ReadOnlyObjectWrapper<OomWords.Tone> oomTone = new ReadOnlyObjectWrapper<>(OomWords.Tone.NONE);
    private final javafx.beans.property.ReadOnlyBooleanWrapper anyLeftSuspended =
            new javafx.beans.property.ReadOnlyBooleanWrapper(false);

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

    /** How many processes are managed (UI-1); "Reading…" until the limits are known. */
    FactReadout managedCount() {
        return managedCount;
    }

    /** How many processes an earlier session may have left suspended (ADR-0011); "Reading…" until known. */
    FactReadout leftSuspendedCount() {
        return leftSuspendedCount;
    }

    /** The state of OOM Guard (UI-1): "Normal", "Warning, 13 % available", "Off"; "Reading…" until it is known. */
    FactReadout oom() {
        return oom;
    }

    /** The tone of that state; color only while it warns. */
    ReadOnlyObjectProperty<OomWords.Tone> oomToneProperty() {
        return oomTone.getReadOnlyProperty();
    }

    void showOom(OomView view) {
        oom.show(Optional.of(new OomWords(formatter).overview(view)), 1);
        oomTone.set(view.monitorEnded() ? OomWords.Tone.NONE : OomWords.tone(view));
    }

    /** The managed processes, from the limits as they are now. */
    void showManaged(int count) {
        managedCount.show(Optional.of(formatter.count(count)), 1);
    }

    /** The processes an earlier session may have left suspended, as listed now. */
    void showLeftSuspended(int count) {
        leftSuspendedCount.show(Optional.of(formatter.count(count)), 1);
        anyLeftSuspended.set(count > 0);
    }

    /** Whether an earlier session may have left any process suspended: the fact is shown only then. */
    javafx.beans.property.ReadOnlyBooleanProperty anyLeftSuspendedProperty() {
        return anyLeftSuspended.getReadOnlyProperty();
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
        // What has not come by now is not being read any more (OP-07): it is unavailable, not "Reading…",
        // until it comes after all. What has come stays; it is not from system monitoring.
        for (FactReadout fact : new FactReadout[] {managedCount, oom, leftSuspendedCount}) {
            if (fact.stateProperty().get() == MetricState.WAITING) {
                fact.showUnavailable();
            }
        }
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
