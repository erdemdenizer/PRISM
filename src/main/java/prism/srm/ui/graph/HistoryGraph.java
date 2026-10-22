package prism.srm.ui.graph;

import java.util.Objects;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Draws one or two bounded histories as lines on a single Canvas.
 *
 * <p>The scene holds one node for the whole graph, however many samples it shows. It is redrawn
 * only when its data, its size or one of its colors changes, never on a timer, and nothing moves
 * between two samples.
 *
 * <p>The colors come from the stylesheet through the properties {@code -srm-graph-line}, {@code
 * -srm-graph-secondary} and {@code -srm-graph-grid}, so the graph follows the theme like any
 * other control. Background and frame are the Region's own and are styled as usual.
 */
public final class HistoryGraph extends CanvasGraph {

    /** The smallest height at which a history is still readable (docs/UI_GUIDE.md, Graphs). */
    private static final double MIN_HEIGHT = 64;

    private static final double MIN_WIDTH = 64;
    private static final double PREF_WIDTH = 160;
    private static final int GRID_DIVISIONS = 4;

    private final int capacity;

    private GraphData data = GraphData.EMPTY;

    /**
     * @param capacity the number of samples the width of the graph spans; a shorter history is
     *     drawn against the right edge
     */
    public HistoryGraph(int capacity) {
        super("history-graph");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Shows the data, replacing what was shown. Call on the JavaFX Application Thread. */
    public void show(GraphData newData) {
        Objects.requireNonNull(newData, "newData");
        if (!newData.equals(data)) {
            data = newData;
            redraw();
        }
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + MIN_WIDTH + snappedRightInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return snappedTopInset() + MIN_HEIGHT + snappedBottomInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + PREF_WIDTH + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + MIN_HEIGHT + snappedBottomInset();
    }

    @Override
    void draw(GraphicsContext graphics, double width, double height) {
        drawGrid(graphics, width, height);
        if (data.maximum() > 0) {
            // One pixel less in each direction, and half a pixel in: a 1 px line at either edge
            // of the scale then lies fully inside the Canvas.
            PlotGeometry geometry = new PlotGeometry(width - 1, height - 1, capacity, data.maximum());
            SeriesPainter.drawSeries(graphics, geometry, data.secondary().toArray(), secondaryColor(), null);
            Color line = lineColor();
            SeriesPainter.drawSeries(
                    graphics,
                    geometry,
                    data.primary().toArray(),
                    line,
                    line.deriveColor(0, 1, 1, SeriesPainter.FILL_OPACITY));
        }
    }

    /** Quarters of the scale and quarters of the time window. */
    private void drawGrid(GraphicsContext graphics, double width, double height) {
        graphics.setStroke(gridColor());
        for (int division = 1; division < GRID_DIVISIONS; division++) {
            double y = Math.floor(height * division / GRID_DIVISIONS) + 0.5;
            graphics.strokeLine(0, y, width, y);
            double x = Math.floor(width * division / GRID_DIVISIONS) + 0.5;
            graphics.strokeLine(x, 0, x, height);
        }
    }
}
