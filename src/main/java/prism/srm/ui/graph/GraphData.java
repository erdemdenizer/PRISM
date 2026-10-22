package prism.srm.ui.graph;

import java.util.Objects;
import prism.srm.domain.MetricHistory;

/**
 * What a history graph draws: one or two series against one scale.
 *
 * @param primary the series drawn in the accent color, with the fill below it
 * @param secondary the second series, drawn as a line only; empty when there is none
 * @param maximum the value at the top of the scale; nothing is drawn while it is not positive
 */
public record GraphData(MetricHistory primary, MetricHistory secondary, double maximum) {

    /** Draws nothing but the grid. */
    public static final GraphData EMPTY = new GraphData(MetricHistory.empty(), MetricHistory.empty(), 0);

    public GraphData {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(secondary, "secondary");
        if (Double.isNaN(maximum) || Double.isInfinite(maximum)) {
            throw new IllegalArgumentException("maximum must be finite: " + maximum);
        }
    }

    /** A single series against the given scale. */
    public static GraphData single(MetricHistory series, double maximum) {
        return new GraphData(series, MetricHistory.empty(), maximum);
    }
}
