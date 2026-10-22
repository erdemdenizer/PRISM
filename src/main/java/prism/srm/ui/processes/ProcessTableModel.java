package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/**
 * The rows of the process table, kept in step with the listings of the process monitor.
 *
 * <p><b>One row per process identity.</b> A listing is compared with the rows by identity, PID
 * together with start time. A process that is still there keeps its row and gets new values; a
 * new process gets a new row; the row of a process that is gone is dropped, and nothing else of
 * it is kept. A PID that another process took over is another identity: the old row goes and a
 * new one comes, and neither the values nor the selection pass from one to the other.
 *
 * <p><b>The list is changed, never replaced.</b> The table shows {@link #rows()}: the rows the
 * filter lets through, in the order chosen. After a listing that list gets at most three changes:
 * the rows that left are removed, the rows that came are added, and if the order is no longer
 * right the rows are put in order by one permutation. A listing in which nothing came, went or
 * changed its place changes the list not at all. That is what lets the table keep its cells, its
 * selection and its scroll position.
 *
 * <p><b>The selection is a row, so it is a process identity.</b> It stays through every listing
 * and every sort as long as its row is in the table. When the process exits, or the filter takes
 * its row out, nothing is selected: the selection never moves to a neighbor on its own.
 *
 * <p>Used on the JavaFX Application Thread only. Nothing here reads from the system.
 */
final class ProcessTableModel {

    private final Map<ProcessIdentity, ProcessRow> live = new HashMap<>();
    private final ObservableList<ProcessRow> rows = FXCollections.observableArrayList();

    /** The selected row; {@code null} while nothing is selected. */
    private ProcessRow selected;

    private ProcessFilter filter = ProcessFilter.NONE;
    private Comparator<ProcessRow> order = ProcessOrder.comparator(List.of());
    private long listing;

    /** The rows the filter lets through, in order. Changed by this model only. */
    ObservableList<ProcessRow> rows() {
        return rows;
    }

    /** The number of processes of the newest listing, whatever the filter shows of them. */
    int total() {
        return live.size();
    }

    /** The selected row; {@code null} while nothing is selected. */
    ProcessRow selected() {
        return selected;
    }

    /** Whether the process of a row was in the newest listing. */
    boolean isRunning(ProcessRow row) {
        return live.get(row.identity()) == row;
    }

    /**
     * Brings the rows up to date with a listing.
     *
     * @param processes every process of the listing. Should it name one identity twice, the first
     *     is the one shown.
     */
    void update(List<ProcessSnapshot> processes) {
        listing++;
        for (ProcessSnapshot process : processes) {
            ProcessRow row = live.get(process.identity());
            if (row == null) {
                row = new ProcessRow(process);
                live.put(process.identity(), row);
            } else if (row.listing != listing) {
                row.update(process);
            }
            row.listing = listing;
        }
        // Nothing is kept of a process that the listing no longer has.
        Iterator<ProcessRow> rowsOfProcesses = live.values().iterator();
        while (rowsOfProcesses.hasNext()) {
            if (rowsOfProcesses.next().listing != listing) {
                rowsOfProcesses.remove();
            }
        }
        bringRowsInStep();
    }

    /** Drops every row: no process is shown, and nothing is selected. */
    void clear() {
        live.clear();
        bringRowsInStep();
    }

    /** Sets which processes are shown. The rows are filtered as they are; nothing is read. */
    void filter(ProcessFilter newFilter) {
        Objects.requireNonNull(newFilter, "newFilter");
        if (newFilter.equals(filter)) {
            return;
        }
        filter = newFilter;
        bringRowsInStep();
    }

    /** @param keys the columns to sort by, the most important first; none sorts by PID */
    void sortBy(List<SortKey> keys) {
        order = ProcessOrder.comparator(keys);
        putInOrder();
    }

    /**
     * Selects a row of the table, or nothing.
     *
     * @param row {@code null} for no selection. A row that is not in the table is not selected.
     */
    void select(ProcessRow row) {
        selected = row != null && row.shown && isRunning(row) ? row : null;
    }

    /**
     * Makes {@link #rows} the rows that belong in the table: those of running processes that the
     * filter lets through, in order.
     */
    private void bringRowsInStep() {
        Set<ProcessRow> leaving = null;
        for (ProcessRow row : rows) {
            if (!isRunning(row) || !filter.matches(row)) {
                if (leaving == null) {
                    leaving = new HashSet<>();
                }
                leaving.add(row);
                row.shown = false;
            }
        }
        List<ProcessRow> arriving = null;
        for (ProcessRow row : live.values()) {
            if (!row.shown && filter.matches(row)) {
                if (arriving == null) {
                    arriving = new ArrayList<>();
                }
                arriving.add(row);
                row.shown = true;
            }
        }

        if (selected != null && !selected.shown) {
            selected = null;
        }
        // One change for all rows that leave and one for all that arrive; a row that stays is
        // not touched by either.
        if (leaving != null) {
            rows.removeAll(leaving);
        }
        if (arriving != null) {
            rows.addAll(arriving);
        }
        putInOrder();
    }

    /** Sorts the rows with one permutation, and only if they are not in order already. */
    private void putInOrder() {
        for (int index = 1; index < rows.size(); index++) {
            if (order.compare(rows.get(index - 1), rows.get(index)) > 0) {
                FXCollections.sort(rows, order);
                return;
            }
        }
    }
}
