package prism.srm.ui.graph;

import java.util.Objects;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import prism.srm.domain.MetricHistory;

/**
 * Draws one or two bounded histories as lines on a single Canvas.
 *
 * <p>The scene holds one node for the whole graph, however many samples it shows. It is redrawn
 * only when its data, its size or one of its colors changes, never on a timer, and nothing moves
 * between two samples.
 *
 * <p>The colors come from the stylesheet through the properties {@code -srm-graph-line}, {@code
 * -srm-graph-secondary}, {@code -srm-graph-grid}, {@code -srm-graph-cursor} and {@code
 * -srm-graph-surface}, so the graph follows the theme like any other control. Background and
 * frame are the Region's own and are styled as usual.
 *
 * <p>A past value can be read (owner decision K5): under the pointer, or moved by the keys of
 * whoever holds the focus for the graph ({@link #moveCursor}), a cursor marks one sample with a
 * vertical line and a dot on each series. The cursor is drawn on an overlay of its own, so moving
 * it never draws the graph again. It stands for an age, a number of samples before the newest, not
 * for one sample: as the samples move left with each cycle, the cursor stays where it is and reads
 * the newer sample that has come under it, as the pointer that holds it stays where it is.
 */
public final class HistoryGraph extends CanvasGraph {

    /** The smallest height at which a history is still readable (docs/UI_GUIDE.md, Graphs). */
    private static final double MIN_HEIGHT = 64;

    private static final double MIN_WIDTH = 64;
    private static final double PREF_WIDTH = 160;
    private static final int GRID_DIVISIONS = 4;

    /** The width of the line of the primary series. */
    private static final double PRIMARY_LINE_WIDTH = 1.5;

    /**
     * The width of the dashed line of the secondary series: a little more than a pixel, so that a
     * dashed line reads as a line beside the solid one, which it is drawn over.
     */
    private static final double SECONDARY_LINE_WIDTH = 1.25;

    /** The dashes of the line of the secondary series, in pixels: 5 drawn, 3 left out. */
    private static final double[] SECONDARY_DASHES = {5, 3};

    /** The age of the cursor while there is none. */
    public static final int NO_CURSOR = -1;

    /** How many samples a step of the cursor with Shift held goes. */
    private static final int LONG_STEP = 10;

    /** The radius of the dot that marks the sample of a series under the cursor, and of its ring. */
    private static final double DOT_RADIUS = 2.5;

    private static final double RING_RADIUS = 4;

    private final int capacity;

    private final ReadOnlyIntegerWrapper cursorAge = new ReadOnlyIntegerWrapper(this, "cursorAge", NO_CURSOR);

    private GraphData data = GraphData.EMPTY;

    /**
     * @param capacity the number of samples the width of the graph spans; a shorter history is
     *     drawn against the right edge
     */
    public HistoryGraph(int capacity) {
        super("history-graph", true);
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        // The pointer reads the sample under it; leaving the graph takes the cursor away.
        addEventHandler(MouseEvent.MOUSE_MOVED, event -> pointAt(event.getX()));
        addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> pointAt(event.getX()));
        addEventHandler(MouseEvent.MOUSE_EXITED, event -> clearCursor());
        cursorAge.addListener(observable -> redrawOverlay());
    }

    /** Shows the data, replacing what was shown. Call on the JavaFX Application Thread. */
    public void show(GraphData newData) {
        Objects.requireNonNull(newData, "newData");
        if (!newData.equals(data)) {
            data = newData;
            redraw();
            if (!hasSamples()) {
                clearCursor();
            } else if (cursorAge.get() != NO_CURSOR) {
                // The cursor keeps its age; its dots follow the samples that came under it.
                redrawOverlay();
            }
        }
    }

    /** The number of samples the width of the graph spans. */
    public int capacity() {
        return capacity;
    }

    /**
     * The age of the sample the cursor is on, in samples before the newest (0 is the newest), or
     * {@link #NO_CURSOR}.
     */
    public ReadOnlyIntegerProperty cursorAgeProperty() {
        return cursorAge.getReadOnlyProperty();
    }

    /** Takes the cursor away. Call on the JavaFX Application Thread. */
    public void clearCursor() {
        cursorAge.set(NO_CURSOR);
    }

    /**
     * Moves the cursor by a key, for the node that holds the focus for the graph: Left one sample
     * older, Right one newer, ten with Shift; Home to the oldest sample, End to the newest; Escape
     * takes the cursor away. Without a cursor, Left and Right put it on the newest sample. The
     * keyboard stays within the samples there are.
     *
     * @return whether the key moved or took away the cursor; a key that did nothing is left to others
     */
    public boolean moveCursor(KeyCode code, boolean shift) {
        if (code == KeyCode.ESCAPE) {
            boolean had = cursorAge.get() != NO_CURSOR;
            clearCursor();
            return had;
        }
        if (!hasSamples()) {
            return false;
        }
        int oldest = Math.min(data.primary().size(), capacity) - 1;
        int age = cursorAge.get();
        int step = shift ? LONG_STEP : 1;
        int next;
        switch (code) {
            case LEFT, KP_LEFT -> next = age == NO_CURSOR ? 0 : age + step;
            case RIGHT, KP_RIGHT -> next = age == NO_CURSOR ? 0 : age - step;
            case HOME -> next = oldest;
            case END -> next = 0;
            default -> {
                return false;
            }
        }
        cursorAge.set(Math.clamp(next, 0, oldest));
        return true;
    }

    /**
     * Puts the cursor on the sample nearest to a horizontal position, in the coordinates of the
     * graph. The pointer may stand where the history has not reached yet: the cursor goes there,
     * and whoever reads it says that there is no sample yet.
     */
    void pointAt(double x) {
        if (!hasSamples() || plotWidth() < 2) {
            return;
        }
        PlotGeometry geometry = new PlotGeometry(plotWidth() - 1, 1, capacity, 1);
        // The lines are drawn half a pixel in.
        cursorAge.set(geometry.ageAt(x - snappedLeftInset() - 0.5));
    }

    private boolean hasSamples() {
        return data.primary().size() > 0;
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
            Color line = lineColor();
            graphics.setLineWidth(PRIMARY_LINE_WIDTH);
            SeriesPainter.drawSeries(
                    graphics,
                    geometry,
                    data.primary().toArray(),
                    line,
                    line.deriveColor(0, 1, 1, SeriesPainter.FILL_OPACITY));
            // The second series is dashed as well as colored: it is never told by color alone. It
            // is drawn last, so that where the two are equal its dashes still show on the solid line.
            graphics.setLineWidth(SECONDARY_LINE_WIDTH);
            graphics.setLineDashes(SECONDARY_DASHES);
            SeriesPainter.drawSeries(graphics, geometry, data.secondary().toArray(), secondaryColor(), null);
            graphics.setLineDashes((double[]) null);
            graphics.setLineWidth(1);
        }
    }

    /**
     * The cursor: a 1 px line across the plot at the sample, and on each series that has a value
     * there a dot in its color, ringed with the surface so that it stands out from its line.
     */
    @Override
    void drawOverlay(GraphicsContext graphics, double width, double height) {
        int age = cursorAge.get();
        if (age == NO_CURSOR) {
            return;
        }
        PlotGeometry geometry = new PlotGeometry(width - 1, height - 1, capacity, data.maximum());
        double x = geometry.xOfAge(age);
        graphics.setStroke(cursorColor());
        double line = Math.floor(x) + 0.5;
        graphics.strokeLine(line, 0, line, height);
        if (data.maximum() > 0) {
            dot(graphics, geometry, x, data.secondary(), secondaryColor(), age);
            dot(graphics, geometry, x, data.primary(), lineColor(), age);
        }
    }

    private void dot(
            GraphicsContext graphics, PlotGeometry geometry, double x, MetricHistory series, Color color, int age) {
        int index = PlotGeometry.indexOfAge(age, series.size());
        if (index < 0 || series.sample(index).isEmpty()) {
            return;
        }
        double y = geometry.y(series.sample(index).getAsDouble()) + 0.5;
        double centerX = x + 0.5;
        graphics.setFill(surfaceColor());
        graphics.fillOval(centerX - RING_RADIUS, y - RING_RADIUS, 2 * RING_RADIUS, 2 * RING_RADIUS);
        graphics.setFill(color);
        graphics.fillOval(centerX - DOT_RADIUS, y - DOT_RADIUS, 2 * DOT_RADIUS, 2 * DOT_RADIUS);
    }

    /**
     * The scale in quarters, with its two ends, and the time window in quarters. The lines at
     * the ends bound the plot: the graph has no frame of its own.
     */
    private void drawGrid(GraphicsContext graphics, double width, double height) {
        graphics.setStroke(gridColor());
        for (int division = 0; division <= GRID_DIVISIONS; division++) {
            double y = Math.min(Math.floor(height * division / GRID_DIVISIONS), height - 1) + 0.5;
            graphics.strokeLine(0, y, width, y);
        }
        for (int division = 1; division < GRID_DIVISIONS; division++) {
            double x = Math.floor(width * division / GRID_DIVISIONS) + 0.5;
            graphics.strokeLine(x, 0, x, height);
        }
    }
}
