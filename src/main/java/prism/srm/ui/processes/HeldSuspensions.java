package prism.srm.ui.processes;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.application.ControlMessages;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspendedProcess;
import prism.srm.ui.control.ResultLineState;
import prism.srm.ui.control.ResultTone;

/**
 * What the Processes page offers once process monitoring has stopped: the processes Prism still
 * holds suspended, each with Resume, and what the last of those requests came to. Without it a
 * suspension the user made could not be released for the rest of the session, because the table
 * and its selection go with the monitoring.
 *
 * <p>The processes are the ones Prism's own record holds, handed over as they change; nothing is
 * read from a listing. Resume is the only action here: the others need the process as it is now.
 * One request per process at a time; a second one while the first is on its way is ignored, so
 * the button can keep the keyboard focus. Every method is called on the JavaFX Application Thread;
 * needs no running toolkit.
 */
final class HeldSuspensions {

    private final Consumer<ControlRequest> requests;
    private final ObservableList<SuspendedProcess> rows = FXCollections.observableArrayList();
    // One view, kept: a listener on a view that was let go would be let go with it.
    private final ObservableList<SuspendedProcess> rowsView = FXCollections.unmodifiableObservableList(rows);
    private final ReadOnlyBooleanWrapper shown = new ReadOnlyBooleanWrapper(false);
    private final ResultLineState line = new ResultLineState();
    private final Set<ProcessIdentity> resuming = new HashSet<>();

    private List<SuspendedProcess> held = List.of();
    private boolean monitoringStopped;

    HeldSuspensions(Consumer<ControlRequest> requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    /** The processes offered for Resume; empty while monitoring runs. */
    ObservableList<SuspendedProcess> rows() {
        return rowsView;
    }

    /** Whether a Resume of the process is on its way. */
    boolean resuming(ProcessIdentity identity) {
        return resuming.contains(identity);
    }

    /** Whether the page shows this at all: after monitoring stopped, while something is held or there is a message. */
    ReadOnlyBooleanProperty shownProperty() {
        return shown.getReadOnlyProperty();
    }

    /** The line under the processes: the Resume on its way, or what the last one came to. */
    ResultLineState line() {
        return line;
    }

    /** The whole message as one text. */
    ReadOnlyStringProperty messageProperty() {
        return line.wholeProperty();
    }

    /** The first part of the message: the result, or the Resume on its way. */
    ReadOnlyStringProperty lineHeadlineProperty() {
        return line.headlineProperty();
    }

    /** The second part of the message: what the result does not say; may be empty. */
    ReadOnlyStringProperty lineExplanationProperty() {
        return line.explanationProperty();
    }

    ReadOnlyObjectProperty<ResultTone> lineToneProperty() {
        return line.toneProperty();
    }

    /** Counts the results shown here. */
    ReadOnlyIntegerProperty newResultProperty() {
        return line.resultsProperty();
    }

    /** The processes Prism holds suspended now, from its own record. */
    void show(List<SuspendedProcess> processes) {
        held = List.copyOf(processes);
        refresh();
    }

    /** Process monitoring has stopped for good: from now on the held processes are offered here. */
    void monitoringStopped() {
        monitoringStopped = true;
        refresh();
    }

    /** Asks to resume a held process. Nothing if it is not held, or a request for it is on its way. */
    void resume(ProcessIdentity identity) {
        if (!monitoringStopped || resuming.contains(identity)) {
            return;
        }
        for (SuspendedProcess process : held) {
            if (process.identity().equals(identity)) {
                resuming.add(identity);
                ControlRequest request = ControlRequest.of(ControlAction.RESUME, identity, process.name());
                line.showText(ControlMessages.inProgress(request));
                refresh();
                requests.accept(request);
                return;
            }
        }
    }

    /** Takes the result of any action; only the answer to a Resume asked for here is shown here. */
    void accept(ProcessControlResult result) {
        if (result.request().action() != ControlAction.RESUME || !resuming.remove(result.identity())) {
            return;
        }
        line.showResult(result);
        refresh();
        line.arrived();
    }

    private void refresh() {
        List<SuspendedProcess> now = monitoringStopped ? held : List.of();
        if (!now.equals(rows)) {
            rows.setAll(now);
        }
        shown.set(monitoringStopped
                && (!held.isEmpty() || !line.wholeProperty().get().isEmpty()));
    }
}
