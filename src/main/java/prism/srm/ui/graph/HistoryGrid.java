package prism.srm.ui.graph;

import java.util.Objects;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * Draws many small histories, one per cell, on a single Canvas: the utilization of each logical
 * processor, for example.
 *
 * <p>The scene holds one node for the whole grid, whether it has 4 cells or 256. A cell is a
 * framed box with a line of text at its top, the label at the left and the current value at the
 * right, and the history below it. The cells are arranged by {@link GridLayout}: the number of
 * columns follows the width, so the height the grid needs depends on its width. A cell is never
 * narrower than its label and the widest value need side by side.
 *
 * <p>The colors and the size of the text come from the stylesheet, see {@link CanvasGraph}.
 */
public final class HistoryGrid extends CanvasGraph {

    private static final double PREF_WIDTH = 160;

    /** The room of the line of text at the top of a cell. */
    private static final int TEXT_ROOM = 16;

    /** Between the frame of a cell and its text. */
    private static final int TEXT_INSET = 4;

    /** The least room between the label and the value of a cell. */
    private static final int TEXT_GAP = 8;

    private final int capacity;

    private GridData data = GridData.EMPTY;
    private Font font = Font.font(0);

    /** The smallest width of a cell for the labels and the widest value shown; found when asked for. */
    private double minCellWidth = -1;

    /**
     * @param capacity the number of samples the width of a cell spans; a shorter history is drawn
     *     against the right edge
     */
    public HistoryGrid(int capacity) {
        super("history-grid");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Shows the data, replacing what was shown. Call on the JavaFX Application Thread. */
    public void show(GridData newData) {
        Objects.requireNonNull(newData, "newData");
        if (newData.equals(data)) {
            return;
        }
        // The labels and the widest value decide how the cells are arranged; the values and the
        // histories, which change with every sample, do not.
        boolean otherArrangement = newData.cells().size() != data.cells().size()
                || !newData.widestValue().equals(data.widestValue())
                || !longestLabel(newData).equals(longestLabel(data));
        data = newData;
        if (otherArrangement) {
            minCellWidth = -1;
            requestLayout();
        }
        redraw();
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + minCellWidth() + snappedRightInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + Math.max(PREF_WIDTH, minCellWidth()) + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        // Without a width to go by, the widest arrangement is assumed: one row.
        double inside = width < 0 ? Double.MAX_VALUE : width - snappedLeftInset() - snappedRightInset();
        return snappedTopInset()
                + GridLayout.of(data.cells().size(), inside, minCellWidth()).minHeight()
                + snappedBottomInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        return computeMinHeight(width);
    }

    @Override
    void labelSizeChanged() {
        font = Font.font(labelSize());
        minCellWidth = -1;
        requestLayout();
    }

    @Override
    void draw(GraphicsContext graphics, double width, double height) {
        int count = data.cells().size();
        if (count == 0) {
            return;
        }
        GridLayout layout = GridLayout.of(count, width, height, minCellWidth());
        Color line = lineColor();
        Color fill = line.deriveColor(0, 1, 1, SeriesPainter.FILL_OPACITY);
        graphics.setFont(font);
        // The text lies on the opaque surface of its cell, which is what subpixel rendering needs.
        graphics.setFontSmoothingType(FontSmoothingType.LCD);
        graphics.setTextBaseline(VPos.CENTER);
        for (int index = 0; index < count; index++) {
            drawCell(graphics, layout.cell(index, width, height), data.cells().get(index), line, fill);
        }
    }

    private void drawCell(
            GraphicsContext graphics, GridLayout.Cell bounds, GridData.Cell cell, Color line, Color fill) {
        int x = bounds.x();
        int y = bounds.y();
        int width = bounds.width();
        int height = bounds.height();
        if (width < 2 * TEXT_INSET || height <= TEXT_ROOM + 2) {
            return;
        }
        graphics.setFill(surfaceColor());
        graphics.fillRect(x, y, width, height);
        graphics.setStroke(gridColor());
        graphics.strokeRect(x + 0.5, y + 0.5, width - 1, height - 1);

        // The label set back and the value in the color of text, so the two read as two things.
        double textY = y + 1 + TEXT_ROOM / 2.0;
        double textWidth = width - 2.0 * TEXT_INSET;
        graphics.setFill(labelColor());
        graphics.setTextAlign(TextAlignment.LEFT);
        graphics.fillText(cell.label(), x + TEXT_INSET, textY, textWidth);
        graphics.setFill(valueColor());
        graphics.setTextAlign(TextAlignment.RIGHT);
        graphics.fillText(cell.value(), x + width - TEXT_INSET, textY, textWidth);

        // The plot: inside the frame, below the text.
        int plotX = x + 1;
        int plotY = y + TEXT_ROOM;
        int plotWidth = width - 2;
        int plotHeight = height - TEXT_ROOM - 1;
        double half = plotY + Math.floor(plotHeight / 2.0) + 0.5;
        graphics.strokeLine(plotX, half, plotX + plotWidth, half);
        if (data.maximum() > 0) {
            graphics.save();
            graphics.translate(plotX, plotY);
            SeriesPainter.drawSeries(
                    graphics,
                    new PlotGeometry(plotWidth - 1, plotHeight - 1, capacity, data.maximum()),
                    cell.history().toArray(),
                    line,
                    fill);
            graphics.restore();
        }
    }

    /**
     * The width a cell needs for its text: the longest label and the widest value side by side.
     * Measured once for an arrangement, not for every sample.
     */
    private double minCellWidth() {
        if (minCellWidth < 0) {
            minCellWidth =
                    Math.ceil(2 * TEXT_INSET + widthOf(longestLabel(data)) + TEXT_GAP + widthOf(data.widestValue()));
        }
        return minCellWidth;
    }

    private double widthOf(String text) {
        Text measured = new Text(text);
        measured.setFont(font);
        return measured.getLayoutBounds().getWidth();
    }

    /** The label with the most characters; with digits, that is the widest one. */
    private static String longestLabel(GridData data) {
        String longest = "";
        for (GridData.Cell cell : data.cells()) {
            if (cell.label().length() > longest.length()) {
                longest = cell.label();
            }
        }
        return longest;
    }
}
