package prism.srm.ui.processes;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.PageNotice;

/**
 * The Processes page: the filter, the table of all processes, and the details of the selected
 * one. The page is read-only: it shows processes and acts on none.
 *
 * <p>Its structure is on screen from the start. Until the first listing arrives the table says
 * that the processes are being read; a filter that leaves nothing says so in the table; a failed
 * start, and process monitoring that ended while the application ran, are explained at the top of
 * the page, and then no process is shown.
 *
 * <p>Every method is called on the JavaFX Application Thread and reads nothing from the system:
 * the listings are handed in, and the details of a process are asked for through the function
 * given at construction, which returns at once.
 */
public final class ProcessesPage extends VBox {

    private final ProcessesViewModel model;
    private final ProcessTable table;

    /**
     * @param formatter formats the values in the notation of the user's locale
     * @param zone the time zone start times are shown in
     * @param logFolder where the log file is, as text for the user; named when something fails
     * @param detailsRequest asks for the path and the command line of a process and returns at
     *     once; the answer is handed to {@link #showDetails}
     */
    public ProcessesPage(
            ValueFormatter formatter, ZoneId zone, String logFolder, Consumer<ProcessIdentity> detailsRequest) {
        model = new ProcessesViewModel(formatter, zone, logFolder, detailsRequest);
        getStyleClass().add("processes");
        getStylesheets().addAll(Stylesheets.metric(), Stylesheets.table(), Stylesheets.processes());

        table = new ProcessTable(model, model.text());
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().setAll(new PageNotice(model.status()), toolbar(), table, new ProcessDetailsPane(model.details()));
    }

    /** Shows a listing of all processes. Call on the JavaFX Application Thread. */
    public void show(List<ProcessSnapshot> processes) {
        table.changeRows(() -> model.update(processes));
    }

    /**
     * Takes the result of a read of process details. It is shown only if it is for the process
     * that is selected now. Call on the JavaFX Application Thread.
     */
    public void showDetails(ProcessDetailsResult result) {
        model.showDetails(result);
    }

    /** Shows that monitoring could not start. Call on the JavaFX Application Thread. */
    public void showStartupFailure() {
        table.changeRows(model::startupFailed);
    }

    /**
     * Shows that process monitoring has ended and takes the processes off the page: they would
     * otherwise stand there as if they were current. Call on the JavaFX Application Thread.
     */
    public void showMonitoringStopped() {
        table.changeRows(model::monitoringStopped);
    }

    /**
     * Shows that system monitoring ended before any process was listed. Process monitoring starts
     * with a system snapshot, so no listing may ever come; one that does is shown as usual. Call
     * on the JavaFX Application Thread.
     */
    public void showProcessesNotListed() {
        table.changeRows(model::processesNotListed);
    }

    /** The filter at the left, the number of processes at the right. */
    private HBox toolbar() {
        TextField filter = new TextField();
        filter.getStyleClass().add("process-filter");
        filter.setPromptText("Filter by name, PID or user");
        // The field has no label on screen: its prompt says what it is. A screen reader takes the
        // name of a text field from its label, so it has one that takes no room and is not drawn.
        Label name = new Label("Filter processes by name, PID or user");
        name.setLabelFor(filter);
        name.setManaged(false);
        name.setVisible(false);
        // Typing filters the rows the table holds; nothing is read from the system for it.
        filter.textProperty().addListener((observable, before, after) -> table.changeRows(() -> model.filter(after)));
        filter.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && !filter.getText().isEmpty()) {
                filter.clear();
                event.consume();
            }
        });

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label count = new Label();
        count.textProperty().bind(model.countProperty());
        count.getStyleClass().addAll("text-small", "text-muted");
        count.setMinWidth(Region.USE_PREF_SIZE);

        HBox toolbar = new HBox(name, filter, gap, count);
        toolbar.getStyleClass().add("process-toolbar");
        return toolbar;
    }
}
