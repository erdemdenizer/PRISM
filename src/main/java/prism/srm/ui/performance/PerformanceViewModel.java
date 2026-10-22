package prism.srm.ui.performance;

import java.util.Objects;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.PageStatus;
import prism.srm.ui.metric.SystemReadouts;

/**
 * Turns system snapshots into what the Performance page shows. It holds no JavaFX node and does
 * no monitoring: it is given each snapshot on the JavaFX Application Thread and sets its
 * properties from it.
 *
 * <p>Only the section on screen is brought up to date with each snapshot. The other three cost
 * nothing while they are not shown; a section that is selected is filled from the newest snapshot
 * at once, so it never shows what it held when it was last on screen.
 */
final class PerformanceViewModel {

    private final PageStatus status = new PageStatus();
    private final ReadOnlyObjectWrapper<Section> selected = new ReadOnlyObjectWrapper<>(Section.CPU);
    private final CpuModel cpu;
    private final MemoryModel memory;
    private final DiskModel disk;
    private final NetworkModel network;

    /**
     * The newest snapshot this page was given; {@code null} before the first one and once
     * monitoring is not running. The page is given snapshots only while it is on screen, so
     * whoever puts it on screen gives it the newest one first.
     */
    private SystemSnapshot latest;

    PerformanceViewModel(ValueFormatter formatter) {
        Objects.requireNonNull(formatter, "formatter");
        SystemReadouts readouts = new SystemReadouts(formatter);
        cpu = new CpuModel(formatter, readouts);
        memory = new MemoryModel(formatter, readouts);
        disk = new DiskModel(formatter, readouts);
        network = new NetworkModel(formatter, readouts);
    }

    /** Whether snapshots arrive, and the sentence that says why not. */
    PageStatus status() {
        return status;
    }

    /** The section on screen. */
    ReadOnlyObjectProperty<Section> selectedProperty() {
        return selected.getReadOnlyProperty();
    }

    CpuModel cpu() {
        return cpu;
    }

    MemoryModel memory() {
        return memory;
    }

    DiskModel disk() {
        return disk;
    }

    NetworkModel network() {
        return network;
    }

    /** Puts another section on screen and fills it from the newest snapshot. */
    void select(Section section) {
        Objects.requireNonNull(section, "section");
        if (section == selected.get()) {
            return;
        }
        if (latest != null) {
            // Filled before it is shown, so the section never appears with what it held before.
            model(section).update(latest);
        }
        selected.set(section);
    }

    /** Shows a snapshot. */
    void update(SystemSnapshot snapshot) {
        latest = Objects.requireNonNull(snapshot, "snapshot");
        status.live();
        model(selected.get()).update(snapshot);
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
     * Shows that system monitoring has ended while the application ran. The last values, the
     * histories and the rows are taken away: left in place, they would look current.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    void monitoringStopped(String logFolder) {
        status.monitoringStopped(logFolder);
        showNothing();
    }

    private void showNothing() {
        latest = null;
        for (Section section : Section.values()) {
            model(section).showNothing();
        }
    }

    private SectionModel model(Section section) {
        return switch (section) {
            case CPU -> cpu;
            case MEMORY -> memory;
            case DISK -> disk;
            case NETWORK -> network;
        };
    }
}
