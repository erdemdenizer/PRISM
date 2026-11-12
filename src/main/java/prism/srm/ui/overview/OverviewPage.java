package prism.srm.ui.overview;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.InvalidationListener;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import prism.srm.domain.OomView;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.format.OomWords;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactView;
import prism.srm.ui.metric.FactsPanel;
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
 *
 * <p>Each part leads to its page (UI-1): a metric to its section of the Performance page, the
 * number of processes and those an earlier session left suspended to the Processes page, the
 * managed processes to the Limits page, the state of OOM Guard to its page. A part is a button:
 * a stop in the Tab order, in the order of the page, opened with a click, Enter or Space. Where
 * it goes is for whoever shows the pages to decide ({@link #setOnOpen}); the facts of the system
 * itself (its name and its uptime) are on no other page and lead nowhere.
 */
public final class OverviewPage extends VBox {

    /** A part of the page that leads to another page. */
    public enum Part {
        CPU,
        MEMORY,
        DISK,
        NETWORK,
        PROCESSES,
        LEFT_SUSPENDED,
        MANAGED,
        OOM_GUARD
    }

    /**
     * On the page while it has no more room than it needs with the usual gaps (UI audit OP-08): the
     * gaps of the page and between the rows of metrics, and the space under the toolbar, are 8 px
     * instead of 12 (overview.css, and shell.css for every compact page), as the OOM Guard page does
     * in the smallest window.
     */
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");

    /** The gaps of the page and the space at its top when it is not compact (overview.css, shell.css). */
    private static final double USUAL_GAP = 12;

    private final OverviewViewModel model;

    /** The four metrics. */
    private final GridPane metrics;

    /** The facts under the metrics: the system, and what Prism does on it, side by side. */
    private final GridPane lowerRow;

    /** Told the part the user opened; nothing until whoever shows the pages says what it does. */
    private Consumer<Part> onOpen = part -> {};

    /**
     * @param formatter formats the values in the notation of the user's locale
     * @param historyCapacity the number of samples the histories hold when they are full
     */
    public OverviewPage(ValueFormatter formatter, int historyCapacity) {
        model = new OverviewViewModel(formatter);
        getStyleClass().add("overview");
        getStylesheets().addAll(Stylesheets.metric(), Stylesheets.overview());

        lowerRow = lowerRow();
        metrics = metrics(historyCapacity);
        VBox.setVgrow(metrics, Priority.ALWAYS);
        // A group of four capped tiles stands in the middle of a very wide page, the lower row under it.
        setAlignment(Pos.TOP_CENTER);
        getChildren().setAll(new PageHeader("Overview"), new PageNotice(model.status()), metrics, lowerRow);
    }

    /**
     * Says what opening a part does: the page of the part is shown. Call on the JavaFX Application
     * Thread.
     */
    public void setOnOpen(Consumer<Part> newOnOpen) {
        onOpen = Objects.requireNonNull(newOnOpen, "newOnOpen");
    }

    /** Shows how many processes are managed. Call on the JavaFX Application Thread. */
    public void showManaged(int count) {
        model.showManaged(count);
    }

    /** Shows the state of OOM Guard (UI-1). Call on the JavaFX Application Thread. */
    public void showOom(OomView view) {
        model.showOom(view);
    }

    /** Shows how many processes an earlier session may have left suspended. Call on the JavaFX Application Thread. */
    public void showLeftSuspended(int count) {
        model.showLeftSuspended(count);
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

    /**
     * Decides whether the page is compact before it lays out its parts: from what the page needs with
     * the usual gaps, whatever it has now, so the choice cannot feed back into itself. The page area
     * gives the page the height of the window, or more where the page needs more and then scrolls.
     */
    @Override
    protected void layoutChildren() {
        pseudoClassStateChanged(COMPACT, getHeight() <= heightWithUsualGaps() + 0.5);
        super.layoutChildren();
    }

    /** The least height of the page with the usual gaps, whichever it has now. */
    private double heightWithUsualGaps() {
        int gaps = Math.max(0, getManagedChildren().size() - 1);
        int rowGaps = Math.max(0, metrics.getRowConstraints().size() - 1);
        return minHeight(getWidth())
                + gaps * (USUAL_GAP - getSpacing())
                + rowGaps * (USUAL_GAP - metrics.getVgap())
                + (USUAL_GAP - getPadding().getTop());
    }

    /** Opens the page of a part, as a click on it does. */
    private void open(Part part) {
        onOpen.accept(part);
    }

    /**
     * The four metrics in the arrangement that keeps each graph wider than it is tall (UI lab v2,
     * spec section 6): one row where the page is wide and low, two by two in the usual window, one
     * column where the page is narrow or taller than it is wide. The panels are built once; a change
     * of the size only moves them between the arrangements.
     */
    private GridPane metrics(int historyCapacity) {
        GridPane metrics = new GridPane();
        metrics.getStyleClass().add("metric-grid");
        MetricPanel cpu = MetricPanel.single("CPU", "Utilization, last 60 s", model.cpu(), historyCapacity);
        cpu.getStyleClass().add("metric-cpu");
        cpu.openWith("Performance, CPU", () -> open(Part.CPU));
        MetricPanel memory = MetricPanel.single("Memory", "In use, last 60 s", model.memory(), historyCapacity);
        memory.getStyleClass().add("metric-memory");
        memory.openWith("Performance, Memory", () -> open(Part.MEMORY));
        MetricPanel disk =
                MetricPanel.dual("Disk", "Transfer rate, last 60 s", "Read", "Write", model.disk(), historyCapacity);
        disk.getStyleClass().add("metric-disk");
        disk.openWith("Performance, Disk", () -> open(Part.DISK));
        MetricPanel network = MetricPanel.dual(
                "Network", "Transfer rate, last 60 s", "Download", "Upload", model.network(), historyCapacity);
        network.getStyleClass().add("metric-network");
        network.openWith("Performance, Network", () -> open(Part.NETWORK));
        metrics.getChildren().setAll(cpu, memory, disk, network);
        arrange(metrics, 2);
        // The edges of the lower row follow the places of the metrics, whenever these change.
        InvalidationListener placed = observable -> alignLowerRow(metrics);
        for (Node panel : metrics.getChildren()) {
            panel.layoutXProperty().addListener(placed);
        }
        metrics.hgapProperty().addListener(placed);
        alignLowerRow(metrics);
        InvalidationListener resized = observable -> {
            // The height the graphs share: the page less everything else on it, which does not
            // depend on the arrangement, so a new arrangement cannot feed back into the choice.
            double others = snappedTopInset()
                    + snappedBottomInset()
                    + getSpacing() * (getChildren().size() - 1);
            for (Node child : getChildren()) {
                others += child == metrics ? 0 : child.getLayoutBounds().getHeight();
            }
            // The width of the page, not of the grid: a group of four capped tiles is narrower than the
            // page, and the choice must not depend on the arrangement it makes.
            double width = getWidth() - snappedLeftInset() - snappedRightInset();
            int columns = columns(width, getHeight() - others);
            if (columns != metrics.getColumnConstraints().size()) {
                arrange(metrics, columns);
                alignLowerRow(metrics);
            }
            // Also for the first arrangement, once the stylesheet has given the grid its gap.
            double group = groupWidth(columns, metrics.getHgap());
            if (metrics.getMaxWidth() != group) {
                metrics.setMaxWidth(group);
                lowerRow.setMaxWidth(group);
            }
        };
        widthProperty().addListener(resized);
        metrics.widthProperty().addListener(resized);
        heightProperty().addListener(resized);
        // A notice that comes or goes changes the height the graphs share, not the size of the page.
        metrics.heightProperty().addListener(resized);
        return metrics;
    }

    /**
     * The width of the metrics below which they stand in one column. The smallest window (720 px, a
     * 706 px scene) leaves the metrics 602 px beside the rail with names (Breakpoints.RAIL_WIDTH) and
     * its 12 px margins; two by two they still fit its height there (OP-08), one under another they do
     * not.
     */
    static final double ONE_COLUMN_BELOW = 560;

    /** The width of the metrics from which they may stand in one row. */
    static final double FOUR_ACROSS_FROM = 1100;

    /** The widest a metric tile is in a row of four; the group of four stands in the middle of the page. */
    static final double TILE_MAX = 520;

    /**
     * The widest a metric tile is two by two. An ultrawide window gives the graphs more width than
     * sixty samples can use (OP-11): the group stops there and stands in the middle of the page.
     */
    static final double TWO_ACROSS_TILE_MAX = 960;

    /** The widest the group of metrics, and the lower row under it, is in an arrangement. */
    static double groupWidth(int columns, double gap) {
        return switch (columns) {
            case 4 -> 4 * TILE_MAX + 3 * gap;
            case 2 -> 2 * TWO_ACROSS_TILE_MAX + gap;
            default -> Double.MAX_VALUE;
        };
    }

    /**
     * The number of columns of the metrics for the space they have: four in a row where a tile, a
     * quarter of the width but at most {@link #TILE_MAX}, is at least four fifths of the height, one
     * where the width is below {@link #ONE_COLUMN_BELOW} or the page is taller than wide (then a
     * quarter of the height is at least 160 px), two otherwise.
     *
     * <p>The lab spec has four in a row from 1100 px at any height. That stretches every graph to
     * more than twice as tall as it is wide in the usual window, or leaves the page empty under the
     * row (docs/PRODUCT_SPEC.md, section 11: no wasted space, no decorative charts): four in a row
     * stays where the graphs keep their shape.
     */
    static int columns(double width, double height) {
        if (width < ONE_COLUMN_BELOW) {
            return 1;
        }
        if (width >= FOUR_ACROSS_FROM && Math.min(width / 4, TILE_MAX) >= height * 0.8) {
            return 4;
        }
        if (width < height) {
            return 1;
        }
        return 2;
    }

    /** Places the four panels in a grid of the given number of columns, each cell the same size. */
    private static void arrange(GridPane metrics, int columns) {
        int rows = 4 / columns;
        metrics.getColumnConstraints().clear();
        metrics.getRowConstraints().clear();
        for (int index = 0; index < columns; index++) {
            ColumnConstraints column = new ColumnConstraints();
            if (columns == 4) {
                // Equal shares of the width up to the cap: the grid is no wider than four capped tiles.
                column.setHgrow(Priority.ALWAYS);
                column.setMinWidth(0);
                column.setPrefWidth(0);
                column.setMaxWidth(TILE_MAX);
            } else {
                column.setPercentWidth(100.0 / columns);
            }
            metrics.getColumnConstraints().add(column);
        }
        for (int index = 0; index < rows; index++) {
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(100.0 / rows);
            metrics.getRowConstraints().add(row);
        }
        for (int index = 0; index < metrics.getChildren().size(); index++) {
            Node panel = metrics.getChildren().get(index);
            GridPane.setColumnIndex(panel, index % columns);
            GridPane.setRowIndex(panel, index / columns);
        }
    }

    /**
     * The lower row: the facts of the system, and beside them, as wide, what Prism does on it (UI lab
     * v2, spec section 6: a balanced lower row). The same facts as before, in two panels.
     *
     * <p>Its edges are those of the metrics above in every arrangement, whatever the facts need
     * (OP-05; before, two panels of equal share but unequal minimum stood 18 px off the columns).
     */
    private GridPane lowerRow() {
        GridPane row = new GridPane();
        row.getStyleClass().add("overview-facts");
        List<FactsPanel> panels = facts();
        for (int index = 0; index < panels.size(); index++) {
            FactsPanel panel = panels.get(index);
            panel.setMaxWidth(Double.MAX_VALUE);
            panel.setMaxHeight(Double.MAX_VALUE);
            row.add(panel, index, 0);
        }
        return row;
    }

    /**
     * Puts the inner edges of the lower row under those of the metrics: in a row of four the facts of
     * the system are as wide as the first two metrics with the gap between them, two by two as the
     * first metric; in one column the two panels share the width. The width is taken from where the
     * grid of the metrics placed its panels, so both round their edges alike.
     */
    private void alignLowerRow(GridPane metrics) {
        int columns = metrics.getColumnConstraints().size();
        ColumnConstraints system = new ColumnConstraints();
        ColumnConstraints prism = new ColumnConstraints();
        if (columns == 1) {
            system.setPercentWidth(50);
            prism.setPercentWidth(50);
        } else {
            Node first = metrics.getChildren().getFirst();
            Node inner = metrics.getChildren().get(columns == 4 ? 2 : 1);
            double width = Math.max(0, inner.getLayoutX() - first.getLayoutX() - metrics.getHgap());
            system.setMinWidth(width);
            system.setPrefWidth(width);
            system.setMaxWidth(width);
            prism.setHgrow(Priority.ALWAYS);
            prism.setMinWidth(0);
        }
        List<ColumnConstraints> before = lowerRow.getColumnConstraints();
        if (before.size() != 2 || !same(before.get(0), system) || !same(before.get(1), prism)) {
            lowerRow.getColumnConstraints().setAll(system, prism);
        }
    }

    private static boolean same(ColumnConstraints a, ColumnConstraints b) {
        return a.getPercentWidth() == b.getPercentWidth()
                && a.getMinWidth() == b.getMinWidth()
                && a.getPrefWidth() == b.getPrefWidth()
                && a.getMaxWidth() == b.getMaxWidth()
                && a.getHgrow() == b.getHgrow();
    }

    /** The system facts, on a surface of their own; the captions say what they are. */
    private List<FactsPanel> facts() {
        // A long system name is cut with an ellipsis; the tooltip has it in full.
        FactView operatingSystem = FactView.truncating("Operating system", model.operatingSystem());
        // The only fact that gives way when the window is narrow.
        operatingSystem.setMinWidth(0);

        FactView uptime = new FactView("Uptime", model.uptime(), null);
        uptime.setMinWidth(Region.USE_PREF_SIZE);
        FactView processes = new FactView("Processes", model.processCount(), null);
        processes.setMinWidth(Region.USE_PREF_SIZE);
        processes.openWith("Processes", () -> open(Part.PROCESSES));

        FactView managed = new FactView("Managed", model.managedCount(), null);
        managed.setMinWidth(Region.USE_PREF_SIZE);
        managed.openWith("Limits", () -> open(Part.MANAGED));
        FactView oom = new FactView("OOM Guard", model.oom(), null);
        oom.setMinWidth(Region.USE_PREF_SIZE);
        oom.openWith("OOM Guard", () -> open(Part.OOM_GUARD));
        model.oomToneProperty().addListener((observable, before, after) -> {
            oom.pseudoClassStateChanged(PseudoClass.getPseudoClass("warning"), after == OomWords.Tone.WARNING);
            oom.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), after == OomWords.Tone.ERROR);
        });
        // Only while there is any: the Processes page lists them, with what can be done about each.
        FactView leftSuspended = new FactView("Left suspended", model.leftSuspendedCount(), null);
        leftSuspended.setMinWidth(Region.USE_PREF_SIZE);
        leftSuspended.visibleProperty().bind(model.anyLeftSuspendedProperty());
        leftSuspended.managedProperty().bind(leftSuspended.visibleProperty());
        leftSuspended.openWith("Processes", () -> open(Part.LEFT_SUSPENDED));

        FactsPanel system = new FactsPanel(operatingSystem, uptime, processes);
        system.getStyleClass().add("overview-system");
        FactsPanel prism = new FactsPanel(managed, oom, leftSuspended);
        prism.getStyleClass().add("overview-prism");
        return List.of(system, prism);
    }
}
