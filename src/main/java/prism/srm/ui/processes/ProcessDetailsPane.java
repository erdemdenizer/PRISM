package prism.srm.ui.processes;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import prism.srm.ui.Motion;
import prism.srm.ui.control.CutTextTooltip;
import prism.srm.ui.control.InspectorFrame;

/**
 * The inspector of the selected process: its name with its PID, the actions on it, the values of the
 * process, and its executable path and command line.
 *
 * <p>Fix round G (owner decision K2): the inspector is an {@link InspectorFrame}. Its pinned part, which
 * never scrolls away, holds the name and the PID and the action bar: "Control", "Limits &amp;
 * protection", "End process", and the line about the actions (the action on its way, the last result,
 * or why none is possible: CAP-2). Its details, which scroll inside the panel where they are taller than
 * the room, are "Now" and "Executable". One scroll region, inside the panel.
 *
 * <p>One design in two arrangements of the same nodes. <b>Narrow</b>, beside the table, in the sheet over
 * it, or under it in a tall narrow window: the action groups under one another, then Now and Executable
 * under one another. <b>Columns</b>, under the table: the three action groups side by side, then Now and
 * Executable side by side ({@link ProcessActionBar}).
 *
 * <p>Fix round H (the resize cost): the two arrangements are two layouts of the same children, which
 * never change their parent: the heading has its inline mark and the mark line under it, each shown in
 * its arrangement; Now and Executable stand in one pane that lays them side by side or under one
 * another; the facts of Now keep their grid and only take other cells.
 *
 * <p>Layout and binding only. Every node is created once, here, and the pane has the same lines
 * whether a process is selected or not, so selecting one moves nothing in the window. A text
 * that stands in for a value is set back. Path and command line never wrap: they are cut with an
 * ellipsis and then have a tooltip. "Now" stands in two columns only where both have room for their
 * values whole (PR-12); narrower, in one.
 *
 * <p>The pane comes in with a short fade when a process is shown after none was; a change of the
 * process shown, and the values changing with every listing, move nothing.
 */
final class ProcessDetailsPane extends VBox {

    private static final PseudoClass PLACEHOLDER = PseudoClass.getPseudoClass("placeholder");

    private static final PseudoClass NARROW = PseudoClass.getPseudoClass("narrow");

    /** How far up the pane moves while it comes in. */
    private static final double RISE = 4;

    /**
     * Under the table: the least width of the column of "Now", where it shows its values whole in two
     * pairs (measured: 646.3 MiB, 741.5 KiB/s and 2026-10-06 06:04:28 beside their captions).
     */
    private static final double FACTS_COLUMN_MIN = 320;

    /**
     * The least width of "Now" for its values in two columns: at 55 % and 45 % of it the right column
     * holds "Virtual size" with "999.0 GiB" whole (measured in the real 1366 x 768 window, where a
     * 320 px inspector cut "170,5 MiB" to "170,5 ...": PR-12). Narrower, one column.
     */
    static final double NOW_TWO_COLUMNS_FROM = 400;

    private final HBox heading;
    private final Label title;
    private final Label pid;
    private final Label suspended;
    /** The same mark, inline after the PID, under the table (in columns). */
    private final Label suspendedInline;

    private final HBox marks = new HBox();
    private final GridPane now = new GridPane();
    /** The facts of "Now", a caption and its value on one line, in pairs. */
    private final HBox[] nowFacts;
    /** The start time, on a line of its own under them: it is the widest value. */
    private final HBox startedFact;

    private final GridPane lines = new GridPane();
    private final Label pathCaption = lineCaption("Path");
    private final Label commandCaption = lineCaption("Command line");
    private final Label pathValue;
    private final Label commandValue;
    private final Label nowCaption = sectionCaption("Now");
    private final Label executableCaption = sectionCaption("Executable");
    private final Region executableSeparator = sectionSeparator();

    /** What never scrolls away: the name, the PID and the action bar. */
    private final VBox pinned = new VBox();

    /** What scrolls under it: Now and Executable, under one another or side by side. */
    private final VBox facts = new VBox();

    /** "Now" and "Executable": side by side under the table, under one another elsewhere. */
    private final FactsColumns columns;

    private final VBox nowColumn = new VBox();
    private final VBox executableColumn = new VBox();
    private final InspectorFrame frame;

    private final Node actions;

    private boolean narrow;
    private boolean twoColumns = true;
    private final javafx.beans.property.BooleanProperty narrowShown =
            new javafx.beans.property.SimpleBooleanProperty(false);

    /**
     * @param actions the action bar, with "Limits &amp; protection" in it; it shows the managed mark of the
     *     process, so the pane does not
     */
    ProcessDetailsPane(ProcessDetailsModel model, Node actions) {
        this.actions = actions;
        getStyleClass().add("process-details");
        setAccessibleText("Details of the selected process");

        title = new Label();
        title.textProperty().bind(model.titleProperty());
        title.getStyleClass().add("process-details-title");
        showPlaceholder(title, !model.processShownProperty().get());
        model.processShownProperty().addListener((observable, before, after) -> showPlaceholder(title, !after));
        // A long name gives way; the PID beside it does not.
        title.setMinWidth(0);
        CutTextTooltip.install(title);
        pid = new Label();
        pid.textProperty().bind(model.pidProperty());
        pid.getStyleClass().addAll("text-small", "text-muted");
        pid.setMinWidth(Region.USE_PREF_SIZE);
        // The one state that bears on the actions below, inline after the PID: muted, no badge.
        suspended = new Label();
        suspended.textProperty().bind(model.suspendedByPrismProperty());
        suspended.getStyleClass().addAll("text-small", "text-muted", "process-details-state");
        suspended.setMinWidth(Region.USE_PREF_SIZE);
        suspended.visibleProperty().bind(model.suspendedByPrismProperty().isNotEmpty());
        suspended.managedProperty().bind(suspended.visibleProperty());
        suspendedInline = new Label();
        suspendedInline.textProperty().bind(model.suspendedByPrismProperty());
        suspendedInline.getStyleClass().addAll("text-small", "text-muted", "process-details-state");
        suspendedInline.setMinWidth(Region.USE_PREF_SIZE);
        suspendedInline
                .visibleProperty()
                .bind(model.suspendedByPrismProperty().isNotEmpty().and(narrowShown.not()));
        suspendedInline.managedProperty().bind(suspendedInline.visibleProperty());
        heading = new HBox();
        heading.getStyleClass().add("process-details-heading");
        marks.getStyleClass().add("process-details-marks");
        // The line of the marks is there only while it has one, and only in the narrow arrangement.
        marks.visibleProperty().bind(suspended.visibleProperty().and(narrowShown));
        marks.managedProperty().bind(marks.visibleProperty());

        // The values the row has too: beside the table they are a glance away from the row, not under it.
        nowFacts = new HBox[] {
            inlineFact("CPU", model.cpu()),
            inlineFact("Memory", model.memory()),
            inlineFact("I/O", model.io()),
            inlineFact("Threads", model.threads()),
            inlineFact("Priority", model.priority()),
            inlineFact("State", model.state()),
            inlineFact("User", model.user()),
            inlineFact("Parent PID", model.parentPid()),
            inlineFact("Private memory", model.privateMemory()),
            inlineFact("Virtual size", model.virtualSize()),
            inlineFact("I/O read", model.ioRead()),
            inlineFact("I/O write", model.ioWrite())
        };
        startedFact = inlineFact("Started", model.started());
        now.getStyleClass().add("process-details-now");

        lines.getStyleClass().add("process-details-lines");
        pathValue = value(model.path());
        // Cut in its middle, so that the file name stays readable; processes.css says so.
        pathValue.getStyleClass().add("detail-path");
        cut(pathValue);
        commandValue = cut(value(model.commandLine()));

        pinned.getStyleClass().add("process-details-pinned");
        facts.getStyleClass().add("process-details-facts");
        columns = new FactsColumns(nowColumn, executableColumn, executableSeparator);
        columns.getStyleClass().add("process-details-columns");
        nowColumn.getStyleClass().add("inspector-column");
        executableColumn.getStyleClass().add("inspector-column");
        nowColumn.setMinWidth(FACTS_COLUMN_MIN);
        executableColumn.setMinWidth(0);
        // Now and Executable share the room evenly, whatever their values: a value that changes moves nothing.
        nowColumn.setPrefWidth(FACTS_COLUMN_MIN);
        executableColumn.setPrefWidth(FACTS_COLUMN_MIN);
        facts.widthProperty().addListener((observable, before, after) -> fitNow());
        // Every node in its place once (fix round H): the arrangements only lay them out differently.
        marks.getChildren().setAll(suspended);
        heading.getChildren().setAll(title, pid, suspendedInline);
        pinned.getChildren().setAll(heading, marks, actions);
        nowColumn.getChildren().setAll(nowCaption, now);
        executableColumn.getChildren().setAll(executableSeparator, executableCaption, lines);
        facts.getChildren().setAll(columns);
        for (HBox fact : nowFacts) {
            now.getChildren().add(fact);
        }
        now.getChildren().add(startedFact);
        placeLines();

        frame = new InspectorFrame(pinned, facts);
        if (actions instanceof ProcessActionBar bar) {
            // Fix round H (PR-03): in a frame too low for the actions whole, they go compact, still pinned.
            frame.setCompactable(bar::setCompact);
        }
        VBox.setVgrow(frame, Priority.ALWAYS);
        getChildren().setAll(frame);
        arrange();
        installReveal(model);
    }

    /**
     * Narrow, the pane is the inspector beside the table, over it, or under it in a tall narrow window,
     * its sections under one another; otherwise it is the inspector under the table, in columns.
     */
    void setNarrow(boolean value) {
        if (value == narrow) {
            return;
        }
        narrow = value;
        pseudoClassStateChanged(NARROW, value);
        arrange();
    }

    boolean isNarrow() {
        return narrow;
    }

    /** The frame with its pinned part and its scrolling details; for the page and for tests. */
    InspectorFrame frame() {
        return frame;
    }

    /**
     * The height that shows all of it without scrolling, at a width: its own preferred height may be the
     * one the page set to fit it under the table.
     */
    double wholeHeight(double width) {
        double inner = Math.max(0, width - snappedLeftInset() - snappedRightInset());
        return snappedTopInset() + frame.prefHeight(inner) + snappedBottomInset();
    }

    private void arrange() {
        narrowShown.set(narrow);
        columns.setSideBySide(!narrow);
        twoColumns = !narrow;
        placeNow();
        fitNow();
        if (actions instanceof ProcessActionBar bar) {
            bar.setStacked(narrow);
        }
    }

    /** "Now" in two columns where it has the room for them, else in one (PR-12). */
    private void fitNow() {
        double width = facts.getWidth() - facts.snappedLeftInset() - facts.snappedRightInset();
        boolean two = !narrow || width >= NOW_TWO_COLUMNS_FROM;
        if (two != twoColumns) {
            twoColumns = two;
            placeNow();
        }
    }

    /** For tests: whether "Now" stands in two columns. */
    boolean nowInTwoColumns() {
        return twoColumns;
    }

    /** The facts of "Now" in two columns, the start time on a line of its own under them; or all in one. */
    private void placeNow() {
        now.getColumnConstraints().clear();
        if (twoColumns) {
            // Fixed shares, so that the values changing every second move nothing; the left column
            // holds the longer captions (Private memory, I/O read).
            ColumnConstraints left = new ColumnConstraints();
            left.setPercentWidth(55);
            ColumnConstraints right = new ColumnConstraints();
            right.setPercentWidth(45);
            now.getColumnConstraints().addAll(left, right);
            for (int index = 0; index < nowFacts.length; index++) {
                GridPane.setConstraints(nowFacts[index], index % 2, index / 2, 1, 1);
            }
            GridPane.setConstraints(startedFact, 0, nowFacts.length / 2, 2, 1);
        } else {
            ColumnConstraints whole = new ColumnConstraints();
            whole.setPercentWidth(100);
            now.getColumnConstraints().add(whole);
            for (int index = 0; index < nowFacts.length; index++) {
                GridPane.setConstraints(nowFacts[index], 0, index, 1, 1);
            }
            GridPane.setConstraints(startedFact, 0, nowFacts.length, 1, 1);
        }
    }

    /** Path and command line, each caption above its value: no arrangement has room for a caption column. */
    private void placeLines() {
        ColumnConstraints whole = new ColumnConstraints();
        whole.setHgrow(Priority.ALWAYS);
        whole.setMinWidth(0);
        lines.getColumnConstraints().add(whole);
        lines.add(pathCaption, 0, 0);
        lines.add(pathValue, 0, 1);
        lines.add(commandCaption, 0, 2);
        lines.add(commandValue, 0, 3);
    }

    /**
     * "Now" and "Executable" (fix round H): side by side under the table, sharing the width as two boxes
     * that grow alike and 24 px apart (the separator before Executable hidden); under one another
     * elsewhere, the separator between them. The same two children either way.
     */
    private static final class FactsColumns extends Region {

        private static final double GAP = 24;

        private final Region first;
        private final Region second;
        private final Region separator;
        private boolean sideBySide = true;

        FactsColumns(Region first, Region second, Region separator) {
            this.first = first;
            this.second = second;
            this.separator = separator;
            getChildren().setAll(first, second);
            showSeparator();
        }

        void setSideBySide(boolean value) {
            if (value != sideBySide) {
                sideBySide = value;
                showSeparator();
                requestLayout();
            }
        }

        private void showSeparator() {
            separator.setVisible(!sideBySide);
            separator.setManaged(!sideBySide);
        }

        @Override
        public javafx.geometry.Orientation getContentBias() {
            return javafx.geometry.Orientation.HORIZONTAL;
        }

        @Override
        protected double computeMinWidth(double height) {
            double inner = sideBySide
                    ? first.minWidth(-1) + GAP + second.minWidth(-1)
                    : Math.max(first.minWidth(-1), second.minWidth(-1));
            return snappedLeftInset() + inner + snappedRightInset();
        }

        @Override
        protected double computePrefWidth(double height) {
            double inner = sideBySide
                    ? first.prefWidth(-1) + GAP + second.prefWidth(-1)
                    : Math.max(first.prefWidth(-1), second.prefWidth(-1));
            return snappedLeftInset() + inner + snappedRightInset();
        }

        @Override
        protected double computePrefHeight(double width) {
            double inner = width < 0 ? -1 : Math.max(0, width - snappedLeftInset() - snappedRightInset());
            double height;
            if (sideBySide) {
                double[] widths = inner < 0 ? new double[] {-1, -1} : widths(inner);
                height = Math.max(first.prefHeight(widths[0]), second.prefHeight(widths[1]));
            } else {
                height = first.prefHeight(inner) + second.prefHeight(inner);
            }
            return snappedTopInset() + height + snappedBottomInset();
        }

        @Override
        protected double computeMinHeight(double width) {
            return computePrefHeight(width);
        }

        @Override
        protected void layoutChildren() {
            double left = snappedLeftInset();
            double top = snappedTopInset();
            double inner = getWidth() - left - snappedRightInset();
            if (sideBySide) {
                double[] widths = widths(inner);
                double height = getHeight() - top - snappedBottomInset();
                first.resizeRelocate(left, top, snapSizeX(widths[0]), height);
                second.resizeRelocate(snapPositionX(left + widths[0] + GAP), top, snapSizeX(widths[1]), height);
            } else {
                double firstHeight = first.prefHeight(inner);
                first.resizeRelocate(left, top, inner, firstHeight);
                second.resizeRelocate(left, top + firstHeight, inner, second.prefHeight(inner));
            }
        }

        /** As a row of two boxes that both grow: each its preferred width and half of the rest, or less down to its least. */
        private double[] widths(double inner) {
            double room = inner - GAP;
            double a = first.prefWidth(-1);
            double b = second.prefWidth(-1);
            double rest = room - a - b;
            if (rest >= 0) {
                return new double[] {a + rest / 2, b + rest / 2};
            }
            double leastA = first.minWidth(-1);
            double leastB = second.minWidth(-1);
            double nextA = Math.max(leastA, a + rest / 2);
            double nextB = Math.max(leastB, room - nextA);
            nextA = Math.max(leastA, room - nextB);
            return new double[] {nextA, nextB};
        }
    }

    /** One finite motion, kept for the pane, played when a process is shown after none was. */
    private void installReveal(ProcessDetailsModel model) {
        FadeTransition fade = new FadeTransition(Duration.ZERO, this);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.ZERO, this);
        rise.setFromY(RISE);
        rise.setToY(0);
        ParallelTransition reveal = new ParallelTransition(fade, rise);
        model.processShownProperty().addListener((observable, before, after) -> {
            if (!after || before) {
                return;
            }
            Duration duration = Motion.of(this, Motion.REVEAL_MS);
            if (duration.equals(Duration.ZERO)) {
                return;
            }
            // The first frame by hand: a transition applies it only with the next pulse.
            setOpacity(0);
            setTranslateY(RISE);
            fade.setDuration(duration);
            rise.setDuration(duration);
            reveal.playFromStart();
        });
    }

    private static Label lineCaption(String text) {
        Label caption = new Label(text);
        caption.getStyleClass().addAll("detail-line-caption", "text-small", "text-muted");
        return caption;
    }

    /** The caption of a section of the narrow inspector. */
    private static Label sectionCaption(String text) {
        Label caption = new Label(text);
        caption.getStyleClass().add("inspector-section-caption");
        return caption;
    }

    private static Region sectionSeparator() {
        Region line = new Region();
        line.getStyleClass().add("inspector-section-separator");
        return line;
    }

    private static Label value(DetailValue value) {
        Label label = new Label();
        label.textProperty().bind(value.textProperty());
        label.getStyleClass().add("detail-value");
        showPlaceholder(label, value.placeholderProperty().get());
        value.placeholderProperty().addListener((observable, before, after) -> showPlaceholder(label, after));
        return label;
    }

    /** A value that may be narrower than its text: it is cut, and then its tooltip has it. */
    private static Label cut(Label value) {
        value.setMinWidth(0);
        value.setMaxWidth(Double.MAX_VALUE);
        CutTextTooltip.install(value);
        return value;
    }

    /**
     * A fact of the narrow inspector: its caption at the left, its value at the right of the same
     * line, cut with a tooltip where the line is too short for it.
     */
    private static HBox inlineFact(String name, DetailValue value) {
        Label caption = new Label(name);
        caption.getStyleClass().addAll("inspector-fact-caption", "text-small", "text-muted");
        caption.setMinWidth(Region.USE_PREF_SIZE);
        Label shown = value(value);
        shown.getStyleClass().add("inspector-fact-value");
        shown.setMinWidth(0);
        CutTextTooltip.install(shown);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox fact = new HBox(caption, gap, shown);
        fact.getStyleClass().add("inspector-fact");
        fact.setMinWidth(0);
        return fact;
    }

    private static void showPlaceholder(Label label, boolean placeholder) {
        label.pseudoClassStateChanged(PLACEHOLDER, placeholder);
    }
}
