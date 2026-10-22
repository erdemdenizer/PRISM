package prism.srm.ui.processes;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ObservableList;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.PageStatus;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/**
 * Turns the listings of the process monitor into what the Processes page shows. It holds no
 * JavaFX node and does no monitoring: it is given each listing on the JavaFX Application Thread
 * and brings its rows up to date.
 *
 * <p>The page is read-only. Nothing here acts on a process; the only thing it ever asks for is
 * the path and the command line of the selected one.
 */
final class ProcessesViewModel {

    /** Shown in place of the rows until the first listing arrives. */
    static final String READING = "Reading processes…";

    static final String NO_MATCH = "No process matches the filter.";
    static final String NO_PROCESSES = "No process is reported.";

    private final ValueFormatter formatter;
    private final ProcessText text;
    private final String logFolder;
    private final PageStatus status = new PageStatus();
    private final ProcessTableModel table = new ProcessTableModel();
    private final ProcessDetailsModel details;
    private final ReadOnlyStringWrapper whenEmpty = new ReadOnlyStringWrapper(READING);
    private final ReadOnlyStringWrapper count = new ReadOnlyStringWrapper("");

    private ProcessFilter filter = ProcessFilter.NONE;

    /** Whether the rows are those of a listing; false before the first and once monitoring ended. */
    private boolean listed;

    /**
     * @param formatter formats numbers in the notation of the user's locale
     * @param zone the time zone start times are shown in
     * @param logFolder where the log file is, as text for the user; named when something fails
     * @param detailsRequest asks for the path and the command line of a process and returns at
     *     once; the answer comes back through {@link #showDetails}
     */
    ProcessesViewModel(
            ValueFormatter formatter, ZoneId zone, String logFolder, Consumer<ProcessIdentity> detailsRequest) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.logFolder = Objects.requireNonNull(logFolder, "logFolder");
        this.text = new ProcessText(formatter, zone);
        this.details = new ProcessDetailsModel(text, logFolder, detailsRequest);
    }

    /** The texts of the values of a process, for the cells that show them. */
    ProcessText text() {
        return text;
    }

    /** Whether listings arrive, and the sentence that says why not. */
    PageStatus status() {
        return status;
    }

    /** The rows of the table: the processes the filter lets through, in order. */
    ObservableList<ProcessRow> rows() {
        return table.rows();
    }

    /** The selected row; {@code null} while nothing is selected. */
    ProcessRow selected() {
        return table.selected();
    }

    ProcessDetailsModel details() {
        return details;
    }

    /** What to show in place of the rows while there are none. */
    ReadOnlyStringProperty whenEmptyProperty() {
        return whenEmpty.getReadOnlyProperty();
    }

    /** How many processes there are, and how many of them the filter shows; empty before a listing. */
    ReadOnlyStringProperty countProperty() {
        return count.getReadOnlyProperty();
    }

    /** Shows a listing. */
    void update(List<ProcessSnapshot> processes) {
        Objects.requireNonNull(processes, "processes");
        status.live();
        listed = true;
        changeRows(() -> table.update(processes));
    }

    /** Filters the rows by a text, as they are; nothing is read. */
    void filter(String typed) {
        filter = ProcessFilter.of(typed);
        changeRows(() -> table.filter(filter));
    }

    /** @param keys the columns to sort by, the most important first */
    void sortBy(List<SortKey> keys) {
        table.sortBy(keys);
    }

    /**
     * Selects a row, or nothing, and shows its details.
     *
     * @param row {@code null} for no selection
     */
    void select(ProcessRow row) {
        ProcessRow before = table.selected();
        table.select(row);
        ProcessRow now = table.selected();
        if (now == before) {
            // Also when nothing was selected and nothing is: the pane keeps what it says.
            return;
        }
        if (now == null) {
            details.showNothing();
        } else {
            details.show(now);
        }
    }

    /** Takes the result of a read of process details; it is shown only if it is still wanted. */
    void showDetails(ProcessDetailsResult result) {
        details.accept(Objects.requireNonNull(result, "result"));
    }

    /** Shows that monitoring could not start. */
    void startupFailed() {
        status.startupFailed(logFolder);
        showNoProcesses();
    }

    /**
     * Shows that process monitoring has ended while the application ran. The rows are taken away:
     * left in place, they would look current.
     */
    void monitoringStopped() {
        status.processMonitoringStopped(logFolder);
        showNoProcesses();
    }

    /** Shows that system monitoring ended before any process was listed. */
    void processesNotListed() {
        status.processesNotListed(logFolder);
        showNoProcesses();
    }

    private void showNoProcesses() {
        listed = false;
        table.clear();
        details.showNothing();
        whenEmpty.set(MetricState.UNAVAILABLE.placeholder());
        count.set("");
    }

    /**
     * Runs a change of the rows. If it takes the selected row out of the table, the details pane
     * says why: the process has exited, or the filter no longer shows it.
     */
    private void changeRows(Runnable change) {
        ProcessRow before = table.selected();
        change.run();
        if (before != null && table.selected() == null) {
            if (table.isRunning(before)) {
                details.showNothing();
            } else {
                details.showExited(before);
            }
        }
        if (listed) {
            showCount();
        }
    }

    private void showCount() {
        int total = table.total();
        String processes = formatter.count(total) + (total == 1 ? " process" : " processes");
        count.set(filter.isActive() ? formatter.count(table.rows().size()) + " of " + processes : processes);
        whenEmpty.set(total == 0 ? NO_PROCESSES : NO_MATCH);
    }
}
