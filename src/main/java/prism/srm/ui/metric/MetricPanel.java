package prism.srm.ui.metric;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import prism.srm.ui.graph.HistoryGraph;

/**
 * One metric of a page: its name, its current value or values, and its 60-second graph.
 *
 * <p>Layout and binding only. Every node is created once, here; an update changes texts and the
 * data of the graph, never the number of nodes. The values keep their place while there are none
 * to show, so the graph below does not move when a value arrives or goes missing, and the graphs
 * of two panels side by side stay level.
 */
public final class MetricPanel extends VBox {

    /** Why a value is unavailable, as far as a snapshot can tell. */
    public static final String UNAVAILABLE_REASON =
            "No value was read from the system. If reading it failed, the log file says why.";

    private final String title;
    private final MetricReadout readout;
    private final String unavailableReason;

    private MetricPanel(
            String title,
            String axisCaption,
            MetricReadout readout,
            Node values,
            int historyCapacity,
            String unavailableReason) {
        this.title = title;
        this.readout = readout;
        this.unavailableReason = unavailableReason;
        getStyleClass().add("metric-panel");

        HistoryGraph graph = new HistoryGraph(historyCapacity);
        graph.setAccessibleText(title + ", " + axisCaption);
        graph.show(readout.graphProperty().get());
        readout.graphProperty().addListener((observable, before, after) -> graph.show(after));
        VBox.setVgrow(graph, Priority.ALWAYS);

        getChildren().setAll(header(values), axis(axisCaption), graph);
    }

    /** A panel with one current value, set large, and an optional figure beside it. */
    public static MetricPanel single(String title, String axisCaption, MetricReadout readout, int historyCapacity) {
        Label detail = bound(readout.detailProperty(), "text-muted");
        Label value = bound(readout.valueProperty(), "text-key-readout");
        Label unit = bound(readout.unitProperty(), "text-muted");
        HBox valueWithUnit = new HBox(value, unit);
        valueWithUnit.getStyleClass().add("metric-value");
        HBox values = new HBox(detail, valueWithUnit);
        values.getStyleClass().add("metric-values");
        return new MetricPanel(title, axisCaption, readout, values, historyCapacity, UNAVAILABLE_REASON);
    }

    /** A panel with two series, each named and marked with the color of its line. */
    public static MetricPanel dual(
            String title,
            String axisCaption,
            String firstSeries,
            String secondSeries,
            MetricReadout readout,
            int historyCapacity) {
        return dual(title, axisCaption, firstSeries, secondSeries, readout, historyCapacity, UNAVAILABLE_REASON);
    }

    /**
     * A panel with two series, for a value that can be unavailable for a reason of its own.
     *
     * @param unavailableReason the tooltip of "Unavailable"
     */
    public static MetricPanel dual(
            String title,
            String axisCaption,
            String firstSeries,
            String secondSeries,
            MetricReadout readout,
            int historyCapacity,
            String unavailableReason) {
        HBox values = new HBox(
                series(firstSeries, "series-primary", readout.valueProperty(), readout.unitProperty()),
                series(secondSeries, "series-secondary", readout.secondValueProperty(), readout.secondUnitProperty()));
        values.getStyleClass().add("metric-series");
        return new MetricPanel(title, axisCaption, readout, values, historyCapacity, unavailableReason);
    }

    private HBox header(Node values) {
        Label name = new Label(title);
        name.getStyleClass().add("text-section-title");
        name.setMinWidth(Region.USE_PREF_SIZE);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        // Shown in place of the values while there are none: "Reading..." or "Unavailable".
        Label status = bound(readout.statusProperty(), "text-muted");
        Tooltip reason = new Tooltip(unavailableReason);
        BooleanBinding live = readout.stateProperty().isEqualTo(MetricState.LIVE);
        status.visibleProperty().bind(live.not());
        // Listening to the property itself: a binding nothing else holds could be collected.
        readout.stateProperty()
                .addListener((observable, before, after) ->
                        status.setTooltip(after == MetricState.UNAVAILABLE ? reason : null));
        status.setTooltip(readout.stateProperty().get() == MetricState.UNAVAILABLE ? reason : null);
        // Hidden, not removed from the layout: the header is as high with values as without.
        values.visibleProperty().bind(live);

        StackPane readoutOrStatus = new StackPane(values, status);
        readoutOrStatus.getStyleClass().add("metric-readout");

        HBox header = new HBox(name, gap, readoutOrStatus);
        header.getStyleClass().add("metric-header");
        return header;
    }

    private HBox axis(String axisCaption) {
        Label caption = new Label(axisCaption);
        caption.getStyleClass().addAll("text-caption", "text-muted");

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        // The value at the top edge of the graph, above its right corner.
        Label scale = bound(readout.scaleProperty(), "text-caption", "text-muted");
        scale.setMinWidth(Region.USE_PREF_SIZE);

        HBox axis = new HBox(caption, gap, scale);
        axis.getStyleClass().add("metric-axis");
        return axis;
    }

    private static VBox series(
            String name, String swatchClass, ObservableValue<String> number, ObservableValue<String> unit) {
        // The swatch is the graphic of the name, so the pair has the baseline of its text.
        Region swatch = new Region();
        swatch.getStyleClass().addAll("series-swatch", swatchClass);
        Label legend = new Label(name, swatch);
        legend.getStyleClass().addAll("series-legend", "text-caption", "text-muted");

        HBox value = new HBox(bound(number), bound(unit, "text-muted"));
        value.getStyleClass().add("series-value");

        VBox series = new VBox(legend, value);
        series.getStyleClass().add("series");
        return series;
    }

    private static Label bound(ObservableValue<String> text, String... styleClasses) {
        Label label = new Label();
        label.textProperty().bind(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }
}
