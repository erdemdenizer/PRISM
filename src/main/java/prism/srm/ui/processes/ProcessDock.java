package prism.srm.ui.processes;

import java.util.Objects;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import prism.srm.ui.Breakpoints;

/**
 * The table of the Processes page and its inspector, docked (fix round H, the resize cost): beside the
 * table at the right with a divider the user drags, under the table, or not here at all while the page
 * shows the inspector in its sheet. The table, the divider and the inspector are its children in every
 * mode: a change of mode changes how they are laid out and what is shown, never their parent, so the
 * table keeps its cells and its styles and nothing is styled again (before, the table and the inspector
 * moved between a split pane and the page at every crossing of a breakpoint, which styled them anew).
 * The inspector leaves only into the sheet, when the sheet opens, and comes back when the page docks it.
 *
 * <p>Beside the table: the inspector as wide as the user left it, between {@link ProcessesPage#INSPECTOR_MIN}
 * and {@link ProcessesPage#INSPECTOR_MAX}; at first {@link ProcessesPage#INSPECTOR_WIDTH} (the widest in an
 * ultrawide window), narrower where the table needs the room for its columns. A wider window widens the
 * table; the inspector keeps its width.
 *
 * <p>Under the table (owner decision K2, PR-04): the inspector as high as its content, up to what the table
 * leaves when it keeps {@link ProcessesPage#TABLE_ROWS_KEPT} rows (or the rows it has); never lower than
 * what keeps its actions pinned while the table can still show {@link ProcessesPage#TABLE_ROWS_LEAST}.
 */
final class ProcessDock extends Region {

    /** Between the table and the inspector under it. */
    static final double GAP = 12;

    /** The divider beside the table: the 12 px gutter the user drags. */
    static final double DIVIDER = 12;

    private final ProcessTable table;
    private final ProcessDetailsPane details;
    private final Region divider = new Region();
    private Breakpoints.Inspector mode = Breakpoints.Inspector.BOTTOM;
    /** The width of the inspector beside the table; NaN until it is placed. */
    private double inspectorWidth = Double.NaN;

    private double dragFrom;
    private double dragWidth;

    ProcessDock(ProcessTable table, ProcessDetailsPane details) {
        this.table = Objects.requireNonNull(table, "table");
        this.details = Objects.requireNonNull(details, "details");
        getStyleClass().add("process-dock");
        divider.getStyleClass().add("process-divider");
        divider.setCursor(Cursor.H_RESIZE);
        divider.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            dragFrom = event.getScreenX();
            dragWidth = details.getWidth();
        });
        divider.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            inspectorWidth = clampWidth(dragWidth - (event.getScreenX() - dragFrom), innerWidth());
            requestLayout();
        });
        getChildren().setAll(table, divider, details);
        setMode(Breakpoints.Inspector.BOTTOM);
    }

    /**
     * Where the inspector is. Beside the table it is placed anew, as wide as usual; in the sheet it is not
     * shown here. The inspector comes back from the sheet, where it was, when it is docked again.
     */
    void setMode(Breakpoints.Inspector next) {
        mode = Objects.requireNonNull(next, "next");
        if (next != Breakpoints.Inspector.SHEET && details.getParent() != this) {
            // From the sheet, where it went when the sheet opened.
            if (details.getParent() instanceof javafx.scene.layout.Pane holder) {
                holder.getChildren().remove(details);
            }
            getChildren().add(details);
        }
        if (next == Breakpoints.Inspector.SIDE) {
            inspectorWidth = Double.NaN;
        }
        divider.setVisible(next == Breakpoints.Inspector.SIDE);
        details.setVisible(next != Breakpoints.Inspector.SHEET || details.getParent() != this);
        // Beside the table, the table keeps the width its columns need without scrolling.
        table.setMinWidth(
                next == Breakpoints.Inspector.SIDE ? table.widthWithoutScrolling() : Region.USE_COMPUTED_SIZE);
        requestLayout();
    }

    Breakpoints.Inspector mode() {
        return mode;
    }

    /** Whether the inspector is in the dock now (not in the sheet). */
    boolean holdsInspector() {
        return details.getParent() == this;
    }

    /** The inspector is going into the sheet: it is shown there. */
    void releaseInspector() {
        details.setVisible(true);
    }

    /** The width of the inspector beside the table now; for tests. */
    double inspectorWidth() {
        return mode == Breakpoints.Inspector.SIDE ? details.getWidth() : 0;
    }

    private double innerWidth() {
        return getWidth() - snappedLeftInset() - snappedRightInset();
    }

    /** Between the least and the most of an inspector, and never so wide that the table must scroll sideways. */
    private double clampWidth(double wanted, double inner) {
        double room = inner - DIVIDER - table.widthWithoutScrolling();
        return Math.max(ProcessesPage.INSPECTOR_MIN, Math.min(Math.min(wanted, ProcessesPage.INSPECTOR_MAX), room));
    }

    /**
     * Beside the inspector, the least width of the table is the width its columns need without scrolling,
     * but never so much that the page could not become narrower than {@link Breakpoints#SIDE_FROM}.
     *
     * <p>Course release, finding L-1 of the UI lab's Linux test (2026-11-12): long user names (system
     * accounts such as {@code cups-browsed}) widen the user column up to {@link ColumnWidths#USER_MAX}, and
     * the table's need with them. When that need held the page at or above {@link Breakpoints#SIDE_FROM},
     * a narrower window could not make the page narrower: the page kept the inspector beside the table and
     * the area of the page scrolled sideways, with the inspector cut or out of sight. Capped, the page can
     * always become narrower than that width, so the inspector goes under the table or into its sheet; a
     * table with such long names scrolls sideways itself, which it does at every narrow width anyway.
     */
    @Override
    protected double computeMinWidth(double height) {
        double least = table.minWidth(-1);
        if (mode == Breakpoints.Inspector.SIDE) {
            least = Math.min(least, mostTableBesideTheInspector()) + DIVIDER + ProcessesPage.INSPECTOR_MIN;
        }
        return snappedLeftInset() + least + snappedRightInset();
    }

    /** The widest least width of the table beside the inspector that leaves the page under {@link Breakpoints#SIDE_FROM}. */
    private double mostTableBesideTheInspector() {
        double around = snappedLeftInset() + snappedRightInset();
        if (getParent() instanceof Region page) {
            around += page.snappedLeftInset() + page.snappedRightInset();
        }
        return Math.max(0, Breakpoints.SIDE_FROM - 1 - around - DIVIDER - ProcessesPage.INSPECTOR_MIN);
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + table.prefWidth(-1) + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return snappedTopInset() + table.minHeight(-1) + snappedBottomInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        double inner = table.prefHeight(-1);
        if (mode == Breakpoints.Inspector.BOTTOM) {
            inner += GAP + details.prefHeight(width);
        }
        return snappedTopInset() + inner + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double x = snappedLeftInset();
        double y = snappedTopInset();
        double width = innerWidth();
        double height = getHeight() - y - snappedBottomInset();
        if (width <= 0 || height <= 0) {
            return;
        }
        switch (mode) {
            case SIDE -> {
                if (Double.isNaN(inspectorWidth)) {
                    // An ultrawide window gives the inspector its widest at first.
                    double wanted = getScene() != null && getScene().getWidth() >= Breakpoints.ULTRAWIDE_FROM
                            ? ProcessesPage.INSPECTOR_MAX
                            : ProcessesPage.INSPECTOR_WIDTH;
                    inspectorWidth = clampWidth(wanted, width);
                }
                double inspector = snapSizeX(Math.max(ProcessesPage.INSPECTOR_MIN, Math.min(inspectorWidth, width)));
                double tableWidth = Math.max(0, width - DIVIDER - inspector);
                table.resizeRelocate(x, y, tableWidth, height);
                divider.resizeRelocate(x + tableWidth, y, DIVIDER, height);
                details.resizeRelocate(x + tableWidth + DIVIDER, y, inspector, height);
            }
            case BOTTOM -> {
                double inspector = heightUnderTheTable(width, height);
                double tableHeight = Math.max(0, height - GAP - inspector);
                table.resizeRelocate(x, y, width, tableHeight);
                details.resizeRelocate(x, y + tableHeight + GAP, width, inspector);
            }
            case SHEET -> table.resizeRelocate(x, y, width, height);
        }
    }

    /**
     * Under the table the inspector is as high as its content, up to what the table leaves when it keeps
     * {@link ProcessesPage#TABLE_ROWS_KEPT} rows (or the rows it has, so a filter that leaves one gives the
     * inspector the room); and never lower than its pinned part with the least of its details while the
     * table can still show {@link ProcessesPage#TABLE_ROWS_LEAST} (owner decision K2, PR-04).
     */
    private double heightUnderTheTable(double width, double height) {
        double room = height - GAP;
        int rows = Math.max(1, table.getItems().size());
        double kept =
                table.heightForRows(Math.clamp(rows, ProcessesPage.TABLE_ROWS_LEAST, ProcessesPage.TABLE_ROWS_KEPT));
        double least = table.heightForRows(ProcessesPage.TABLE_ROWS_LEAST);
        double wanted = Math.ceil(details.wholeHeight(width));
        double pinned =
                details.frame().leastPinnedHeight(width - details.snappedLeftInset() - details.snappedRightInset())
                        + details.snappedTopInset()
                        + details.snappedBottomInset();
        double inspector = Math.min(wanted, Math.max(room - kept, Math.min(pinned, room - least)));
        return Math.max(0, Math.floor(inspector));
    }

    /** Whether a node is the inspector's divider; for the page's tests. */
    Node divider() {
        return divider;
    }
}
