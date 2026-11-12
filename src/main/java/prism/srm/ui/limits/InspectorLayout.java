package prism.srm.ui.limits;

import java.util.Objects;
import javafx.geometry.Insets;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.control.InspectorFrame;

/**
 * A list with a panel about its selected entry, docked (UI lab v2, LAB_DESIGN_SPEC sections 2 and 7):
 * beside the list at the right, or under it as wide as it is, as the page has room for
 * ({@link Breakpoints#inspectorFor}). Where it has room for neither, the page shows the panel in a
 * sheet over the page instead, and here the list is alone.
 *
 * <p>Under the list (fix round G, owner decision K2): the panel is as high as its content, up to what the
 * list leaves when it keeps {@link #ROWS_KEPT} rows, or the rows it has; never lower than its pinned part
 * with the least of its details while the list can still show {@link #ROWS_LEAST}. The list takes the rest.
 *
 * <p>Layout only: what the list and the panel show is theirs, and the page decides where the panel is.
 */
final class InspectorLayout extends Region {

    /**
     * The width of the panel beside the list. Fix round H (LO-06): 400, within the 320 to 440 px of an
     * inspector (LAB_DESIGN_SPEC section 2), so that each group of the compact panel of a low window holds
     * its buttons on one line beside its title; the table still keeps its columns from Breakpoints.SIDE_FROM
     * (1260 - 48 - 400 - 12 = 800 px of table for its 752).
     */
    static final double PANEL_WIDTH = 400;

    /** Between the list and the docked panel. */
    static final double GAP = 12;

    /** Under the list: the rows it keeps before the panel grows past its pinned part. */
    static final int ROWS_KEPT = 5;

    /** Under the list: the fewest rows it shows, whatever the panel needs. */
    static final int ROWS_LEAST = 2;

    /** The header and the frame of a table, as table.css draws them. */
    private static final double TABLE_HEADER = 32;

    private static final double TABLE_FRAME = 2;

    private static final double ROW = 30;

    private final TableView<?> list;
    private final Region panel;
    private final InspectorFrame frame;
    private Breakpoints.Inspector mode = Breakpoints.Inspector.SIDE;

    /** @param frame the frame of the panel, whose pinned part the panel keeps whole where it can */
    InspectorLayout(TableView<?> list, Region panel, InspectorFrame frame) {
        this.list = Objects.requireNonNull(list, "list");
        this.panel = Objects.requireNonNull(panel, "panel");
        this.frame = Objects.requireNonNull(frame, "frame");
        getStyleClass().add("inspector-layout");
        panel.getStyleClass().add("inspector-panel");
        getChildren().setAll(list, panel);
    }

    /**
     * Beside the list, under it, or not here (the page shows the panel in its sheet). Fix round H (the
     * resize cost): the list and the panel stay children in every mode; in the sheet's mode the panel is
     * hidden until the sheet opens and takes it, and it comes back here when the page docks it again.
     */
    void setMode(Breakpoints.Inspector next) {
        mode = Objects.requireNonNull(next, "next");
        if (next != Breakpoints.Inspector.SHEET && panel.getParent() != this) {
            if (panel.getParent() instanceof javafx.scene.layout.Pane holder) {
                holder.getChildren().remove(panel);
            }
            getChildren().add(panel);
        }
        panel.setVisible(next != Breakpoints.Inspector.SHEET || panel.getParent() != this);
        requestLayout();
    }

    /** The panel is going into the sheet: it is shown there. */
    void releasePanel() {
        panel.setVisible(true);
    }

    Breakpoints.Inspector mode() {
        return mode;
    }

    @Override
    protected void layoutChildren() {
        Insets in = getInsets();
        double x = in.getLeft();
        double y = in.getTop();
        double w = getWidth() - in.getLeft() - in.getRight();
        double h = getHeight() - in.getTop() - in.getBottom();
        switch (mode) {
            case SIDE -> {
                double listWidth = Math.max(0, w - PANEL_WIDTH - GAP);
                list.resizeRelocate(x, y, listWidth, h);
                panel.resizeRelocate(x + listWidth + GAP, y, PANEL_WIDTH, h);
            }
            case BOTTOM -> {
                double panelHeight = bottomPanelHeight(w, h);
                double listHeight = Math.max(0, h - panelHeight - GAP);
                list.resizeRelocate(x, y, w, listHeight);
                panel.resizeRelocate(x, y + listHeight + GAP, w, panelHeight);
            }
            case SHEET -> list.resizeRelocate(x, y, w, h);
        }
    }

    private double rowsHeight(int rows) {
        double row = list.getFixedCellSize() > 0 ? list.getFixedCellSize() : ROW;
        return TABLE_FRAME + TABLE_HEADER + rows * row;
    }

    private double bottomPanelHeight(double width, double height) {
        double room = height - GAP;
        int rows = Math.max(1, list.getItems().size());
        double kept = rowsHeight(Math.clamp(rows, ROWS_LEAST, ROWS_KEPT));
        double least = rowsHeight(ROWS_LEAST);
        double pinned = frame.leastPinnedHeight(width - panel.snappedLeftInset() - panel.snappedRightInset())
                + panel.snappedTopInset()
                + panel.snappedBottomInset();
        double wanted = panel.prefHeight(width);
        double panelHeight = Math.min(wanted, Math.max(room - kept, Math.min(pinned, room - least)));
        return Math.max(0, Math.floor(panelHeight));
    }

    @Override
    protected double computeMinWidth(double height) {
        return list.minWidth(height) + getInsets().getLeft() + getInsets().getRight();
    }

    @Override
    protected double computeMinHeight(double width) {
        // The panel scrolls inside itself wherever it is: the least height is the list's.
        return list.minHeight(width) + getInsets().getTop() + getInsets().getBottom();
    }

    @Override
    protected double computePrefHeight(double width) {
        double inner = list.prefHeight(width);
        if (mode == Breakpoints.Inspector.BOTTOM) {
            inner += GAP + panel.prefHeight(width);
        }
        return inner + getInsets().getTop() + getInsets().getBottom();
    }
}
