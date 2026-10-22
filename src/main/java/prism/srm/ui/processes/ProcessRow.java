package prism.srm.ui.processes;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;

/**
 * One process in the table. A row belongs to one process identity for as long as that process
 * runs: its values are replaced in place with each listing, and the row itself stays, so the table
 * keeps its cells, its selection and its place. A process that takes over the PID of another has
 * another identity and gets a row of its own.
 *
 * <p>The row holds the newest snapshot of its process and nothing older. Changed on the JavaFX
 * Application Thread only.
 */
final class ProcessRow {

    private final ProcessIdentity identity;
    private final String pidText;
    private final ReadOnlyObjectWrapper<ProcessSnapshot> snapshot;

    /**
     * The listing that last had this process; the table model's mark for telling who is gone. A
     * row belongs to one table model, which is the only one to read and set this and {@link
     * #shown}.
     */
    long listing;

    /** Whether the row is among the rows of the table. */
    boolean shown;

    ProcessRow(ProcessSnapshot first) {
        this.identity = first.identity();
        this.pidText = Long.toString(identity.pid());
        this.snapshot = new ReadOnlyObjectWrapper<>(first);
    }

    ProcessIdentity identity() {
        return identity;
    }

    /** The PID as the filter and the PID column read it: digits only, without grouping. */
    String pidText() {
        return pidText;
    }

    ProcessSnapshot snapshot() {
        return snapshot.get();
    }

    /** The newest snapshot of the process; every cell of the row shows a part of it. */
    ReadOnlyObjectProperty<ProcessSnapshot> snapshotProperty() {
        return snapshot.getReadOnlyProperty();
    }

    /**
     * Replaces the values of the row. A snapshot equal to the one held is not set again, so the
     * cells of a process that did nothing are left alone.
     */
    void update(ProcessSnapshot newest) {
        if (!newest.identity().equals(identity)) {
            throw new IllegalArgumentException("A row never changes its process: " + identity);
        }
        if (!newest.equals(snapshot.get())) {
            snapshot.set(newest);
        }
    }
}
