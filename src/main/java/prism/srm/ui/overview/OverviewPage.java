package prism.srm.ui.overview;

import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactView;
import prism.srm.ui.metric.MetricPanel;
import prism.srm.ui.metric.PageNotice;

/**
 * The Overview page: CPU, memory, disk and network with their 60-second histories, and the system
 * facts below them.
 *
 * <p>Its structure is on screen from the start. Until the first snapshot arrives every part says
 * that it is being read; a part the system has no value for says so in its own place; a failed
 * start, and monitoring that ended while the application ran, are explained at the top of the
 * page, and then no value is shown.
 */
public final class OverviewPage extends VBox {

    private final OverviewViewModel model;

    /**
     * @param formatter formats the values in the notation of the user's locale
     * @param historyCapacity the number of samples the histories hold when they are full
     */
    public OverviewPage(ValueFormatter formatter, int historyCapacity) {
        model = new OverviewViewModel(formatter);
        getStyleClass().add("overview");
        getStylesheets().addAll(Stylesheets.metric(), Stylesheets.overview());

        GridPane metrics = metrics(historyCapacity);
        VBox.setVgrow(metrics, Priority.ALWAYS);
        getChildren().setAll(new PageNotice(model.status()), metrics, facts());
    }

    /** Shows a snapshot. Call on the JavaFX Application Thread. */
    public void show(SystemSnapshot snapshot) {
        model.update(snapshot);
    }

    /**
     * Shows that monitoring could not start. Call on the JavaFX Application Thread.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void showStartupFailure(String logFolder) {
        model.startupFailed(logFolder);
    }

    /**
     * Shows that system monitoring has ended and takes the values off the page: they would
     * otherwise stand there as if they were current. Call on the JavaFX Application Thread.
     *
     * @param logFolder where the log file with the cause is, as text for the user
     */
    public void showMonitoringStopped(String logFolder) {
        model.monitoringStopped(logFolder);
    }

    private GridPane metrics(int historyCapacity) {
        GridPane metrics = new GridPane();
        metrics.getStyleClass().add("metric-grid");
        for (int index = 0; index < 2; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            metrics.getColumnConstraints().add(column);
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(50);
            metrics.getRowConstraints().add(row);
        }
        metrics.add(MetricPanel.single("CPU", "Utilization, last 60 s", model.cpu(), historyCapacity), 0, 0);
        metrics.add(MetricPanel.single("Memory", "In use, last 60 s", model.memory(), historyCapacity), 1, 0);
        metrics.add(
                MetricPanel.dual("Disk", "Transfer rate, last 60 s", "Read", "Write", model.disk(), historyCapacity),
                0,
                1);
        metrics.add(
                MetricPanel.dual(
                        "Network", "Transfer rate, last 60 s", "Download", "Upload", model.network(), historyCapacity),
                1,
                1);
        return metrics;
    }

    private VBox facts() {
        Label title = new Label("System");
        title.getStyleClass().add("text-section-title");

        // A long system name is cut with an ellipsis; the tooltip has it in full.
        FactView operatingSystem = FactView.truncating("Operating system", model.operatingSystem());
        // The only fact that gives way when the window is narrow.
        operatingSystem.setMinWidth(0);

        FactView uptime = new FactView("Uptime", model.uptime(), null);
        uptime.setMinWidth(Region.USE_PREF_SIZE);
        FactView processes = new FactView("Processes", model.processCount(), null);
        processes.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(operatingSystem, uptime, processes);
        row.getStyleClass().add("fact-row");

        VBox facts = new VBox(title, row);
        facts.getStyleClass().add("facts");
        return facts;
    }
}
