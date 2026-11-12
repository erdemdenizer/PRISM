package prism.srm.ui.processes;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ObservableList;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspendedProcess;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.MetricState;
import prism.srm.ui.metric.PageStatus;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/**
 * Turns the listings of the process monitor into what the Processes page shows. It holds no
 * JavaFX node and does no monitoring: it is given each listing on the JavaFX Application Thread
 * and brings its rows up to date.
 *
 * <p>Nothing here acts on a process itself: it asks for the path and the command line of the
 * selected one, and hands the actions the user chooses to the function it was given ({@link
 * ProcessActions}).
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
    private final ProcessActions actions;
    private final HeldSuspensions held;
    private final ManagedActions managed;
    private final RecoveredSuspensions recovered;
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
        this(formatter, zone, logFolder, detailsRequest, request -> {});
    }

    /**
     * @param controlRequests carries out an action on a process and returns at once; the result
     *     comes back through {@link #showControlResult}
     */
    ProcessesViewModel(
            ValueFormatter formatter,
            ZoneId zone,
            String logFolder,
            Consumer<ProcessIdentity> detailsRequest,
            Consumer<ControlRequest> controlRequests) {
        this(formatter, zone, logFolder, detailsRequest, controlRequests, request -> {}, request -> {});
    }

    /**
     * @param limitRequests manages a process or sets its limit and returns at once; the result comes
     *     back through {@link #showLimitResult}
     * @param recoveryRequests resumes or dismisses a process an earlier session may have left
     *     suspended and returns at once; the result comes back through {@link #showRecoveryResult}
     */
    ProcessesViewModel(
            ValueFormatter formatter,
            ZoneId zone,
            String logFolder,
            Consumer<ProcessIdentity> detailsRequest,
            Consumer<ControlRequest> controlRequests,
            Consumer<LimitRequest> limitRequests,
            Consumer<RecoveryRequest> recoveryRequests) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.logFolder = Objects.requireNonNull(logFolder, "logFolder");
        this.text = new ProcessText(formatter, zone);
        this.details = new ProcessDetailsModel(text, logFolder, detailsRequest);
        this.actions = new ProcessActions(controlRequests);
        this.held = new HeldSuspensions(controlRequests);
        Objects.requireNonNull(limitRequests, "limitRequests");
        this.managed = new ManagedActions(request -> {
            actions.limitRequested(request);
            limitRequests.accept(request);
        });
        this.recovered = new RecoveredSuspensions(recoveryRequests);
    }

    /** Manage, Set CPU limit and Unmanage for the selected process, and its mark in the inspector. */
    ManagedActions managed() {
        return managed;
    }

    /** What an earlier session may have left suspended, with Resume and Dismiss. */
    RecoveredSuspensions recovered() {
        return recovered;
    }

    void showLimits(LimitsSnapshot snapshot) {
        managed.showLimits(Objects.requireNonNull(snapshot, "snapshot"));
        // The State column marks them from the next listing on.
        text.showManaged(snapshot.processes().stream()
                .map(prism.srm.domain.ManagedProcessView::identity)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    void showLimitResult(LimitResult result) {
        managed.accept(Objects.requireNonNull(result, "result"));
        actions.acceptLimit(result);
    }

    void showRecovered(List<RecoveredSuspension> entries) {
        recovered.show(Objects.requireNonNull(entries, "entries"));
    }

    void showRecoveryCheck(prism.srm.domain.RecoveryCheck check) {
        recovered.showCheck(Objects.requireNonNull(check, "check"));
    }

    void showRecoveryResult(RecoveryResult result) {
        recovered.accept(Objects.requireNonNull(result, "result"));
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

    /** What the actions on the selected process may do, and what the last one came to. */
    ProcessActions actions() {
        return actions;
    }

    /** The PID of Prism itself: no action of the page is offered on it (CAP-2). */
    void showOwnProcess(long pid) {
        actions.showOwnProcess(pid);
        managed.showOwnProcess(pid);
    }

    void showControlProfile(ControlProfile profile) {
        actions.showProfile(profile);
    }

    void showControlResult(ProcessControlResult result) {
        actions.accept(Objects.requireNonNull(result, "result"));
        held.accept(result);
    }

    /** What Prism holds suspended, and Resume for it once monitoring has stopped. */
    HeldSuspensions held() {
        return held;
    }

    /** The processes Prism holds suspended, from its own record. */
    void showHeld(List<SuspendedProcess> processes) {
        held.show(Objects.requireNonNull(processes, "processes"));
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
        actions.listingArrived();
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
        actions.select(now);
        managed.select(now);
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
        actions.monitoringEnded();
        managed.monitoringEnded();
        status.startupFailed(logFolder);
        showNoProcesses();
    }

    /**
     * Shows that process monitoring has ended while the application ran. The rows are taken away:
     * left in place, they would look current.
     */
    void monitoringStopped() {
        actions.monitoringEnded();
        managed.monitoringEnded();
        held.monitoringStopped();
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
            managed.select(null);
            if (table.isRunning(before)) {
                actions.select(null);
                details.showNothing();
            } else {
                actions.selectedExited();
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
