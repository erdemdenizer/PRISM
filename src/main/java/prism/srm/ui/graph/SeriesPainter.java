package prism.srm.ui.graph;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * Draws the samples of one history as a 1 px line, with an optional flat fill below it. The plot
 * area starts at the origin of the graphics context; a caller that draws several plots moves the
 * origin first.
 */
final class SeriesPainter {

    /** How much of the line color the flat fill below a line has (docs/UI_GUIDE.md, Graphs). */
    static final double FILL_OPACITY = 0.12;

    private SeriesPainter() {}

    /**
     * Draws each unbroken run of samples; a skipped sample (NaN) is a gap in the line.
     *
     * @param geometry the plot area, one pixel less than its room in each direction: with the
     *     half pixel the line is moved in, a 1 px line at either edge of the scale then lies
     *     fully inside that room
     * @param fill the color below the line; {@code null} for a line only
     */
    static void drawSeries(GraphicsContext graphics, PlotGeometry geometry, double[] samples, Color line, Color fill) {
        int size = samples.length;
        int start = 0;
        while (start < size) {
            if (Double.isNaN(samples[start])) {
                start++;
                continue;
            }
            int end = start;
            while (end + 1 < size && !Double.isNaN(samples[end + 1])) {
                end++;
            }
            drawRun(graphics, geometry, samples, start, end, line, fill);
            start = end + 1;
        }
    }

    private static void drawRun(
            GraphicsContext graphics,
            PlotGeometry geometry,
            double[] samples,
            int start,
            int end,
            Color line,
            Color fill) {
        int size = samples.length;
        if (start == end) {
            // A sample with a gap on both sides has no line to belong to.
            graphics.setFill(line);
            graphics.fillRect(geometry.x(start, size) - 0.5, geometry.y(samples[start]) - 0.5, 2, 2);
            return;
        }
        if (fill != null) {
            double bottom = geometry.height() + 1;
            graphics.beginPath();
            graphics.moveTo(geometry.x(start, size) + 0.5, bottom);
            for (int index = start; index <= end; index++) {
                graphics.lineTo(geometry.x(index, size) + 0.5, geometry.y(samples[index]) + 0.5);
            }
            graphics.lineTo(geometry.x(end, size) + 0.5, bottom);
            graphics.closePath();
            graphics.setFill(fill);
            graphics.fill();
        }
        graphics.beginPath();
        graphics.moveTo(geometry.x(start, size) + 0.5, geometry.y(samples[start]) + 0.5);
        for (int index = start + 1; index <= end; index++) {
            graphics.lineTo(geometry.x(index, size) + 0.5, geometry.y(samples[index]) + 0.5);
        }
        graphics.setStroke(line);
        graphics.stroke();
    }
}
