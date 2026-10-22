package prism.srm.ui.performance;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.domain.FileSystemUsage;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.format.ValueFormatter;

/**
 * One file system in the table of the Disk section. Its texts are set in place from each
 * snapshot, on the JavaFX Application Thread only.
 */
final class FileSystemRow {

    private final String key;
    private final ReadOnlyStringWrapper name = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper mountPoint = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<Quantity> capacity = new ReadOnlyObjectWrapper<>(Quantity.MISSING);
    private final ReadOnlyObjectWrapper<Quantity> available = new ReadOnlyObjectWrapper<>(Quantity.MISSING);

    FileSystemRow(FileSystemUsage fileSystem) {
        this.key = keyOf(fileSystem);
    }

    /** What identifies a file system from one snapshot to the next: where it is mounted, and its name. */
    static String keyOf(FileSystemUsage fileSystem) {
        return fileSystem.mountPoint() + '\n' + fileSystem.name();
    }

    String key() {
        return key;
    }

    /** The label the system gives the file system. */
    ReadOnlyStringProperty nameProperty() {
        return name.getReadOnlyProperty();
    }

    ReadOnlyStringProperty mountPointProperty() {
        return mountPoint.getReadOnlyProperty();
    }

    /** The total size. */
    ReadOnlyObjectProperty<Quantity> capacityProperty() {
        return capacity.getReadOnlyProperty();
    }

    /** The space available to the current user. */
    ReadOnlyObjectProperty<Quantity> availableProperty() {
        return available.getReadOnlyProperty();
    }

    void update(FileSystemUsage fileSystem, ValueFormatter formatter) {
        name.set(fileSystem.name());
        mountPoint.set(fileSystem.mountPoint());
        set(capacity, formatter.bytes(fileSystem.totalBytes()));
        set(available, formatter.bytes(fileSystem.usableBytes()));
    }

    /** A value that did not change is not set again, so its cell is left alone. */
    private static void set(ReadOnlyObjectWrapper<Quantity> property, Quantity value) {
        if (!value.equals(property.get())) {
            property.set(value);
        }
    }
}
