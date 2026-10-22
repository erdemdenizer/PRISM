package prism.srm.ui.graph;

/**
 * Arranges a number of equal cells in rows and columns inside a given width.
 *
 * <p>As many columns are used as fit at the smallest width of a cell, and then no more than the
 * rows so found need: 16 cells in a width that fits 13 are laid out as two rows of 8, not as 13
 * and 3. That is the arrangement with the fewest rows, and its height is the least the grid
 * needs. Given more height than that, rows are added as long as the cells are tall strips, so a
 * history is not squeezed into a few pixels of width while height goes unused.
 *
 * <p>The cells of a row share its width, the rows share the height, and every edge is on a whole
 * pixel, so two neighboring cells differ by one pixel at most.
 *
 * @param cells the number of cells
 * @param columns the number of columns, at least one
 * @param rows the number of rows, at least one, also when there is no cell
 */
record GridLayout(int cells, int columns, int rows) {

    /** The gap between two cells, on the spacing scale of docs/UI_GUIDE.md. */
    static final int GAP = 4;

    /** The smallest height at which a history is still readable (docs/UI_GUIDE.md, Graphs). */
    static final int MIN_CELL_HEIGHT = 64;

    /** A cell is a tall strip when it is higher than this many times its width. */
    private static final double TALLEST_SHAPE = 1.5;

    /** The edges of one cell, in whole pixels. */
    record Cell(int x, int y, int width, int height) {}

    /**
     * Returns the arrangement with the fewest rows.
     *
     * @param cells the number of cells, zero or more
     * @param width the width the cells have to fit into
     * @param minCellWidth the smallest width of a cell
     */
    static GridLayout of(int cells, double width, double minCellWidth) {
        if (cells < 0) {
            throw new IllegalArgumentException("cells must not be negative: " + cells);
        }
        if (cells == 0) {
            return new GridLayout(0, 1, 1);
        }
        int fitting = (int) Math.floor((width + GAP) / (minCellWidth + GAP));
        return withRows(cells, ceilDivide(cells, Math.clamp(fitting, 1, cells)));
    }

    /**
     * Returns the arrangement for a grid of the given size: the one with the fewest rows, with
     * rows added while its cells are tall strips and every row keeps the smallest height.
     */
    static GridLayout of(int cells, double width, double height, double minCellWidth) {
        GridLayout layout = of(cells, width, minCellWidth);
        while (layout.rows < cells && layout.cellHeight(height) > TALLEST_SHAPE * layout.cellWidth(width)) {
            GridLayout higher = withRows(cells, layout.rows + 1);
            if (higher.cellHeight(height) < MIN_CELL_HEIGHT) {
                break;
            }
            layout = higher;
        }
        return layout;
    }

    /** The least height the rows need. */
    int minHeight() {
        return rows * MIN_CELL_HEIGHT + (rows - 1) * GAP;
    }

    /**
     * Returns the edges of the cell at {@code index}, counted row by row from the top left, when
     * the grid has the given size.
     */
    Cell cell(int index, double width, double height) {
        int column = index % columns;
        int row = index / columns;
        int left = edge(column, columns, width);
        int top = edge(row, rows, height);
        return new Cell(
                left, top, edge(column + 1, columns, width) - GAP - left, edge(row + 1, rows, height) - GAP - top);
    }

    private static GridLayout withRows(int cells, int rows) {
        return new GridLayout(cells, ceilDivide(cells, rows), rows);
    }

    private double cellWidth(double width) {
        return (width - (columns - 1) * GAP) / columns;
    }

    private double cellHeight(double height) {
        return (height - (rows - 1) * GAP) / rows;
    }

    /** Where the slot at {@code index} of {@code count} begins; a slot is a cell and the gap after it. */
    private static int edge(int index, int count, double extent) {
        return (int) Math.round(index * (extent + GAP) / count);
    }

    private static int ceilDivide(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
