package prism.srm.ui.metric;

import java.util.Locale;
import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import prism.srm.ui.Motion;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.graph.GraphData;
import prism.srm.ui.graph.HistoryGraph;

/**
 * One metric of a page: its name, its current value or values, and its 60-second graph.
 *
 * <p>Layout and binding only. Every node is created once, here; an update changes texts and the
 * data of the graph, never the number of nodes. The values keep their place while there are none
 * to show, so the graph below does not move when a value arrives or goes missing, and the graphs
 * of two panels side by side stay level. A change of state ("Reading…" to the values, the values
 * to "Unavailable") comes in with a short fade; a change of value never does.
 *
 * <p>A past value can be read (owner decision K5): with the pointer over the graph, or with the
 * arrow keys while the graph has the keyboard focus (the panel has it where it is a link to a page),
 * the axis line between the header and the graph says how long ago the sample under the cursor was
 * taken and what it was, in place of its caption. The current values in the header stay as they are,
 * so a past value is never taken for the current one, and the reading covers nothing of the graph.
 * The keys are those of {@link HistoryGraph#moveCursor}; an assistive technology reads the same
 * words through the accessible text.
 */
public final class MetricPanel extends VBox {

    /** Why a value is unavailable, as far as a snapshot can tell: the tooltip of the short reason. */
    public static final String UNAVAILABLE_REASON =
            "No value was read from the system. If reading it failed, the log file says why.";

    /** The reason on the page, in the axis line, where the sentence above would be cut. */
    static final String UNAVAILABLE_CAPTION = "No value read from the system";

    /** Why there is no value while monitoring is not running; the top of the page has the cause. */
    static final String NOT_RUNNING_CAPTION = "Monitoring is not running";

    static final String NOT_RUNNING_REASON = "Monitoring is not running. The message at the top of the page says why.";

    /**
     * The least height of a graph while monitoring is not running: half the 64 px a history needs to be
     * read, enough for the bands of its grid. There is no history to read.
     */
    static final double NOT_RUNNING_GRAPH_LEAST = 32;

    /** The window the graphs show, in seconds (MON-7); the axis captions say "last 60 s". */
    private static final double HISTORY_SECONDS = 60;

    /**
     * Below this width of the window a panel with two series puts their values on a line of their
     * own under its title where they do not fit beside it (UI lab v2: two long rates beside the
     * title were cut). Where they fit, they stay beside it, so the page keeps its height (UI-8).
     * Panels with one value keep it beside the title.
     */
    static final double STACK_BELOW_WINDOW_WIDTH = 900;

    private static final PseudoClass STACKED = PseudoClass.getPseudoClass("stacked");

    private static final PseudoClass READING = PseudoClass.getPseudoClass("reading");

    private static final String HELP = "Left and Right arrow keys read past values; Escape stops reading.";

    private final String title;
    private final String axisCaption;
    private final MetricReadout readout;
    private final String unavailableCaption;
    private final String unavailableReason;
    private final String firstSeries;
    private final String secondSeries;
    private final HistoryGraph graph;
    private final Label name = new Label();
    private final Region gap = new Region();
    private final HBox header = new HBox();
    private final HBox valuesLine = new HBox();
    private final HBox axis = new HBox();
    private final Label caption = new Label();
    private final Tooltip captionReason = new Tooltip();
    private final HBox reading = new HBox();
    private final Label readingAge = new Label();
    private final Label readingFirst = new Label();
    private final Label readingSecond = new Label();
    private StackPane readoutOrStatus;
    private boolean stacked;

    /** What a click, Enter or Space on the panel opens; {@code null} while it opens nothing. */
    private Runnable open;

    /** The page it opens, for the accessible text: "Performance, CPU". */
    private String openTarget;

    private MetricPanel(
            String title,
            String axisCaption,
            MetricReadout readout,
            Node values,
            int historyCapacity,
            String unavailableCaption,
            String unavailableReason,
            String firstSeries,
            String secondSeries) {
        this.title = title;
        this.axisCaption = axisCaption;
        this.readout = readout;
        this.unavailableCaption = unavailableCaption;
        this.unavailableReason = unavailableReason;
        this.firstSeries = firstSeries;
        this.secondSeries = secondSeries;
        getStyleClass().addAll("metric-panel", "panel");

        graph = new HistoryGraph(historyCapacity);
        graph.show(readout.graphProperty().get());
        readout.graphProperty().addListener((observable, before, after) -> {
            graph.show(after);
            if (graph.cursorAgeProperty().get() != HistoryGraph.NO_CURSOR) {
                showReading();
            }
        });
        VBox.setVgrow(graph, Priority.ALWAYS);
        // A graph that will be given no history while monitoring is not running needs no height to
        // read one by: it keeps its grid, and the page its notice and its facts in the smallest
        // window (UI audit OP-08). It takes all the room the page has to give, as before.
        graph.minHeightProperty()
                .bind(Bindings.when(readout.notRunningProperty())
                        .then(NOT_RUNNING_GRAPH_LEAST)
                        .otherwise(Region.USE_COMPUTED_SIZE));
        // Where the panel opens nothing, the graph itself is the stop of the keyboard that reads it.
        graph.setFocusTraversable(true);
        graph.getStyleClass().add("focusable");
        graph.setAccessibleHelp(HELP);
        graph.cursorAgeProperty().addListener(observable -> showReading());
        // The cursor of the keyboard goes with the focus, and with the page when it leaves the window.
        ChangeListener<Node> focusMoved = (observable, before, after) -> {
            if (before == focusTarget()) {
                graph.clearCursor();
            }
        };
        sceneProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.focusOwnerProperty().removeListener(focusMoved);
            }
            if (after != null) {
                after.focusOwnerProperty().addListener(focusMoved);
            } else {
                graph.clearCursor();
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (graph.moveCursor(event.getCode(), event.isShiftDown())) {
                event.consume();
            }
        });

        buildHeader(values);
        valuesLine.getStyleClass().add("metric-values-line");
        valuesLine.managedProperty().bind(valuesLine.visibleProperty());
        valuesLine.setVisible(false);
        getChildren().setAll(header, valuesLine, buildAxis(), graph);
        InvalidationListener spoken = observable -> describe();
        for (ObservableValue<?> property : new ObservableValue<?>[] {
            readout.stateProperty(),
            readout.statusProperty(),
            readout.valueProperty(),
            readout.unitProperty(),
            readout.detailProperty(),
            readout.secondValueProperty(),
            readout.secondUnitProperty(),
            readout.notRunningProperty()
        }) {
            property.addListener(spoken);
        }
        showCaption();
        describe();
        if (secondSeries != null) {
            followWindowWidth();
        }
    }

    /** Whether the values are on a line of their own under the title. */
    boolean isStacked() {
        return stacked;
    }

    /** The graph of the panel; for tests that read it with the pointer or the keyboard. */
    HistoryGraph graph() {
        return graph;
    }

    /**
     * Makes the panel lead to the page that shows the metric in full (UI-1): the whole panel is a
     * button. Its graph is then no stop of its own; the arrow keys read it while the panel has the
     * focus. Call once, on the JavaFX Application Thread.
     *
     * @param target the page it opens, as an assistive technology says it: "Performance, CPU"
     * @param onOpen opens that page
     */
    public void openWith(String target, Runnable onOpen) {
        if (open != null) {
            throw new IllegalStateException("The panel opens a page already: " + openTarget);
        }
        openTarget = Objects.requireNonNull(target, "target");
        open = Objects.requireNonNull(onOpen, "onOpen");
        graph.setFocusTraversable(false);
        graph.getStyleClass().remove("focusable");
        graph.setAccessibleHelp(null);
        OpenablePart.install(this, onOpen);
        setAccessibleHelp(HELP);
        describe();
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE && open != null) {
            open.run();
        } else {
            super.executeAccessibleAction(action, parameters);
        }
    }

    /** The node that holds the keyboard focus for the graph: the panel where it is a button. */
    private Node focusTarget() {
        return open != null ? this : graph;
    }

    private void followWindowWidth() {
        ChangeListener<Number> width = (observable, before, after) -> updateStacked();
        sceneProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.widthProperty().removeListener(width);
            }
            if (after != null) {
                after.widthProperty().addListener(width);
            }
            updateStacked();
        });
        widthProperty().addListener(width);
    }

    /**
     * The values are fixed in width (metric.css), so whether they fit beside the title does not
     * change while they change: the decision follows the width of the window and of the panel only.
     */
    private void updateStacked() {
        Scene scene = getScene();
        double window = scene == null ? 0 : scene.getWidth();
        double room = getWidth() - snappedLeftInset() - snappedRightInset();
        double needed = name.prefWidth(-1) + 2 * header.getSpacing() + readoutOrStatus.prefWidth(-1);
        setStacked(window > 0 && window < STACK_BELOW_WINDOW_WIDTH && room > 0 && needed > room);
    }

    private void setStacked(boolean value) {
        if (value == stacked) {
            return;
        }
        stacked = value;
        pseudoClassStateChanged(STACKED, value);
        if (value) {
            header.getChildren().setAll(name);
            valuesLine.getChildren().setAll(readoutOrStatus);
        } else {
            valuesLine.getChildren().clear();
            header.getChildren().setAll(name, gap, readoutOrStatus);
        }
        valuesLine.setVisible(value);
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
        return new MetricPanel(
                title,
                axisCaption,
                readout,
                values,
                historyCapacity,
                UNAVAILABLE_CAPTION,
                UNAVAILABLE_REASON,
                null,
                null);
    }

    /** A panel with two series, each named and marked with the color of its line. */
    public static MetricPanel dual(
            String title,
            String axisCaption,
            String firstSeries,
            String secondSeries,
            MetricReadout readout,
            int historyCapacity) {
        return dual(
                title,
                axisCaption,
                firstSeries,
                secondSeries,
                readout,
                historyCapacity,
                UNAVAILABLE_CAPTION,
                UNAVAILABLE_REASON);
    }

    /**
     * A panel with two series, for a value that can be unavailable for a reason of its own.
     *
     * @param unavailableCaption the reason on the page, in the axis line: a few words, never cut
     * @param unavailableReason the reason in full: the tooltip of the short one and of "Unavailable"
     */
    public static MetricPanel dual(
            String title,
            String axisCaption,
            String firstSeries,
            String secondSeries,
            MetricReadout readout,
            int historyCapacity,
            String unavailableCaption,
            String unavailableReason) {
        HBox values = new HBox(
                series(firstSeries, "series-primary", readout.valueProperty(), readout.unitProperty()),
                series(secondSeries, "series-secondary", readout.secondValueProperty(), readout.secondUnitProperty()));
        values.getStyleClass().add("metric-series");
        return new MetricPanel(
                title,
                axisCaption,
                readout,
                values,
                historyCapacity,
                unavailableCaption,
                unavailableReason,
                firstSeries,
                secondSeries);
    }

    private void buildHeader(Node values) {
        name.setText(title);
        name.getStyleClass().add("text-section-title");
        name.setMinWidth(Region.USE_PREF_SIZE);

        HBox.setHgrow(gap, Priority.ALWAYS);

        // Shown in place of the values while there are none: "Reading..." or "Unavailable".
        Label status = bound(readout.statusProperty(), "text-muted");
        BooleanBinding live = readout.stateProperty().isEqualTo(MetricState.LIVE);
        status.visibleProperty().bind(live.not());
        // Listening to the properties themselves: a binding nothing else holds could be collected.
        InvalidationListener why = observable -> {
            status.setTooltip(readout.stateProperty().get() == MetricState.UNAVAILABLE ? captionReason : null);
            showCaption();
        };
        readout.stateProperty().addListener(why);
        readout.notRunningProperty().addListener(why);
        status.setTooltip(readout.stateProperty().get() == MetricState.UNAVAILABLE ? captionReason : null);
        // Hidden, not removed from the layout: the header is as high with values as without.
        values.visibleProperty().bind(live);

        readoutOrStatus = new StackPane(values, status);
        readoutOrStatus.getStyleClass().add("metric-readout");
        // One fade, kept for the panel: a state changes rarely, a value every second.
        FadeTransition crossfade = new FadeTransition(Duration.ZERO, readoutOrStatus);
        crossfade.setFromValue(0);
        crossfade.setToValue(1);
        readout.stateProperty().addListener((observable, before, after) -> {
            Duration duration = Motion.of(readoutOrStatus, Motion.CROSSFADE_MS);
            if (!duration.equals(Duration.ZERO)) {
                // The first frame by hand: a transition applies it only with the next pulse.
                readoutOrStatus.setOpacity(0);
                crossfade.setDuration(duration);
                crossfade.playFromStart();
            }
        });

        header.getChildren().setAll(name, gap, readoutOrStatus);
        header.getStyleClass().add("metric-header");
    }

    /**
     * The line between the header and the graph: what the graph shows at the left, the value at
     * its top edge at the right. While the value is unavailable the left says why, in the place
     * of the caption, so the reason is on the page and not only in a tooltip; while a cursor is on
     * the graph the left reads the sample under it.
     */
    private HBox buildAxis() {
        caption.getStyleClass().addAll("text-caption", "text-muted", "metric-axis-caption");
        // A caption that does not fit is cut, and the tooltip has the reason in full.
        caption.setMinWidth(0);
        caption.managedProperty().bind(caption.visibleProperty());

        readingAge.getStyleClass().addAll("text-caption", "text-muted", "metric-reading-age");
        readingAge.setMinWidth(Region.USE_PREF_SIZE);
        readingFirst.getStyleClass().addAll("text-caption", "metric-reading-value");
        readingFirst.setMinWidth(0);
        if (secondSeries == null) {
            reading.getChildren().setAll(readingAge, readingFirst);
        } else {
            // Each value after the mark of its series, as in the legend above: never told by color alone.
            readingFirst.setGraphic(swatch("series-primary"));
            readingSecond.setGraphic(swatch("series-secondary"));
            readingSecond.getStyleClass().addAll("text-caption", "metric-reading-value");
            readingSecond.setMinWidth(0);
            readingSecond.managedProperty().bind(readingSecond.visibleProperty());
            reading.getChildren().setAll(readingAge, readingFirst, readingSecond);
        }
        reading.getStyleClass().add("metric-reading");
        reading.setMinWidth(0);
        reading.managedProperty().bind(reading.visibleProperty());
        reading.setVisible(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // The value at the top edge of the graph, above its right corner.
        Label scale = bound(readout.scaleProperty(), "text-caption", "text-muted");
        scale.setMinWidth(Region.USE_PREF_SIZE);

        axis.getChildren().setAll(caption, reading, spacer, scale);
        axis.getStyleClass().add("metric-axis");
        return axis;
    }

    private static Region swatch(String seriesClass) {
        Region swatch = new Region();
        swatch.getStyleClass().addAll("series-swatch", seriesClass);
        return swatch;
    }

    /** The caption, or the reason in its place; hidden while a cursor reads the graph. */
    private void showCaption() {
        boolean unavailable = readout.stateProperty().get() == MetricState.UNAVAILABLE;
        boolean notRunning = readout.notRunningProperty().get();
        caption.setText(unavailable ? (notRunning ? NOT_RUNNING_CAPTION : unavailableCaption) : axisCaption);
        captionReason.setText(notRunning ? NOT_RUNNING_REASON : unavailableReason);
        caption.setTooltip(unavailable ? captionReason : null);
    }

    /** Puts the reading of the cursor in the axis line, or the caption back once there is none. */
    private void showReading() {
        int age = graph.cursorAgeProperty().get();
        boolean active = age != HistoryGraph.NO_CURSOR;
        caption.setVisible(!active);
        reading.setVisible(active);
        axis.pseudoClassStateChanged(READING, active);
        if (active) {
            GraphData.Reading sample = readout.graphProperty().get().readingAt(age);
            readingAge.setText(age == 0 ? "Now" : seconds(age) + " s ago");
            boolean inHistory = sample.inHistory();
            readingFirst.setText(
                    inHistory ? readout.formatSample(sample.primary()).text() : "No sample yet");
            if (secondSeries != null) {
                // Where there is no sample, there is nothing to mark: the words say so.
                readingFirst.setContentDisplay(inHistory ? ContentDisplay.LEFT : ContentDisplay.TEXT_ONLY);
                readingSecond.setVisible(inHistory);
                readingSecond.setText(readout.formatSample(sample.secondary()).text());
            }
        }
        describe();
    }

    /** The age of a sample in whole seconds, from the samples the graph spans in its window. */
    private long seconds(int age) {
        return Math.round(age * HISTORY_SECONDS / graph.capacity());
    }

    /**
     * The words an assistive technology reads for the panel: its name, its current values, the
     * sample under the cursor, and the page it opens. Set on the panel where it is a button, on
     * the graph otherwise.
     */
    private void describe() {
        StringBuilder words = new StringBuilder(title).append(": ").append(currentWords());
        String cursor = readingWords();
        if (!cursor.isEmpty()) {
            words.append(". ").append(cursor);
        }
        if (open != null) {
            setAccessibleText(words.append(". Open ").append(openTarget).toString());
        } else {
            graph.setAccessibleText(words.append(". ").append(axisCaption).toString());
        }
    }

    private String currentWords() {
        MetricState state = readout.stateProperty().get();
        if (state == MetricState.WAITING) {
            return readout.statusProperty().get();
        }
        if (state == MetricState.UNAVAILABLE) {
            return readout.statusProperty().get() + ", " + caption.getText();
        }
        String first =
                quantity(readout.valueProperty().get(), readout.unitProperty().get());
        if (secondSeries == null) {
            String detail = readout.detailProperty().get();
            return detail.isEmpty() ? first : first + ", " + detail;
        }
        return firstSeries + " " + first + ", " + secondSeries + " "
                + quantity(
                        readout.secondValueProperty().get(),
                        readout.secondUnitProperty().get());
    }

    private String readingWords() {
        int age = graph.cursorAgeProperty().get();
        if (age == HistoryGraph.NO_CURSOR) {
            return "";
        }
        String when = age == 0 ? "Now" : String.format(Locale.ROOT, "%d seconds ago", seconds(age));
        GraphData.Reading sample = readout.graphProperty().get().readingAt(age);
        if (!sample.inHistory()) {
            return when + ": no sample yet";
        }
        String first = spoken(readout.formatSample(sample.primary()));
        if (secondSeries == null) {
            return when + ": " + first;
        }
        return when + ": " + firstSeries + " " + first + ", " + secondSeries + " "
                + spoken(readout.formatSample(sample.secondary()));
    }

    private static String spoken(Quantity quantity) {
        return quantity.equals(Quantity.MISSING) ? "not read" : quantity.text();
    }

    private static String quantity(String number, String unit) {
        return unit.isEmpty() ? number : number + " " + unit;
    }

    private static VBox series(
            String name, String swatchClass, ObservableValue<String> number, ObservableValue<String> unit) {
        // The swatch is the graphic of the name, so the pair has the baseline of its text.
        Label legend = new Label(name, swatch(swatchClass));
        legend.getStyleClass().addAll("series-legend", "text-caption", "text-muted");

        // Neither part is cut: the value is as wide as the longest rate (metric.css) and never narrower.
        Label shownNumber = bound(number);
        shownNumber.setMinWidth(Region.USE_PREF_SIZE);
        Label shownUnit = bound(unit, "text-muted");
        shownUnit.setMinWidth(Region.USE_PREF_SIZE);
        HBox value = new HBox(shownNumber, shownUnit);
        value.getStyleClass().add("series-value");

        VBox series = new Series(legend, value);
        series.getStyleClass().add("series");
        return series;
    }

    /**
     * A named series of a header: its legend above its value. Its baseline is that of the value, so
     * the name of the panel stands on the same line as the values, as it does beside the single
     * readout of a panel with one series, and the names of panels side by side line up.
     */
    private static final class Series extends VBox {

        private final Region legend;
        private final Region value;

        Series(Region legend, Region value) {
            super(legend, value);
            this.legend = legend;
            this.value = value;
        }

        @Override
        public double getBaselineOffset() {
            return snappedTopInset() + legend.prefHeight(-1) + getSpacing() + value.getBaselineOffset();
        }
    }

    private static Label bound(ObservableValue<String> text, String... styleClasses) {
        Label label = new Label();
        label.textProperty().bind(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }
}
