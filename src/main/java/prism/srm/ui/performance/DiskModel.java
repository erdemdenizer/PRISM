package prism.srm.ui.performance;

import java.util.List;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.MetricReadout;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.SystemReadouts;

/**
 * The Disk section: the read and write rate summed over the physical disks, with their 60-second
 * history, and the capacity of every local file system.
 *
 * <p>The rates are a total and nothing else: a snapshot has no rates of single disks. The total
 * is absent when the system does not say which disks are physical (ADR-0005).
 */
final class DiskModel implements SectionModel {

    /** Shown in place of the rows when the system lists no local file system. */
    static final String NO_FILE_SYSTEMS = "No local file system is reported.";

    /**
     * Shown while there is no total. A snapshot does not say why, so this names the reason that is
     * permanent on a system, and no more than is known.
     */
    static final String NO_TOTAL = "No total: the system may not say which disks are physical.";

    private final ValueFormatter formatter;
    private final SystemReadouts readouts;
    private final MetricReadout rates = new MetricReadout();
    private final ObservableList<FileSystemRow> fileSystems = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper fileSystemsStatus =
            new ReadOnlyStringWrapper(MetricState.WAITING.placeholder());
    private final ReadOnlyStringWrapper totalNote = new ReadOnlyStringWrapper("");

    DiskModel(ValueFormatter formatter, SystemReadouts readouts) {
        this.formatter = formatter;
        this.readouts = readouts;
    }

    /** Read and write rate summed over the physical disks. */
    MetricReadout rates() {
        return rates;
    }

    /** One row per local file system, in the order of the system. */
    ObservableList<FileSystemRow> fileSystems() {
        return fileSystems;
    }

    /** What to show in place of the rows while there are none. */
    ReadOnlyStringProperty fileSystemsStatusProperty() {
        return fileSystemsStatus.getReadOnlyProperty();
    }

    /** Why there is no total, as far as that is known; empty while there is one or it is awaited. */
    ReadOnlyStringProperty totalNoteProperty() {
        return totalNote.getReadOnlyProperty();
    }

    @Override
    public void update(SystemSnapshot snapshot) {
        readouts.showDisk(rates, snapshot);
        totalNote.set(rates.stateProperty().get() == MetricState.UNAVAILABLE ? NO_TOTAL : "");
        Optional<List<FileSystemUsage>> listed = snapshot.fileSystems();
        // A reading that is missing takes the rows with it: capacities left in place would look
        // current.
        RowSync.sync(
                fileSystems,
                listed.orElse(List.of()),
                FileSystemRow::key,
                FileSystemRow::keyOf,
                FileSystemRow::new,
                (row, fileSystem) -> row.update(fileSystem, formatter));
        fileSystemsStatus.set(
                listed.isPresent()
                        ? NO_FILE_SYSTEMS
                        : MetricState.of(false, SystemReadouts.cycles(snapshot)).placeholder());
    }

    @Override
    public void showNothing() {
        SystemReadouts.showNothing(rates);
        fileSystems.clear();
        fileSystemsStatus.set(MetricState.UNAVAILABLE.placeholder());
        // The page says at its top that monitoring is not running; that is the reason then.
        totalNote.set("");
    }
}
