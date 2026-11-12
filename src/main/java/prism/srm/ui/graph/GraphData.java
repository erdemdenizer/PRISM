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

    /**
     * Returns what the graph shows {@code age} samples before its newest: the sample of each
     * series there, as a cursor reads it.
     *
     * @param age 0 for the newest sample
     */
    public Reading readingAt(int age) {
        int primaryIndex = PlotGeometry.indexOfAge(age, primary.size());
        int secondaryIndex = PlotGeometry.indexOfAge(age, secondary.size());
        return new Reading(primaryIndex >= 0, sampleAt(primary, primaryIndex), sampleAt(secondary, secondaryIndex));
    }

    private static double sampleAt(MetricHistory series, int index) {
        return index < 0 ? Double.NaN : series.sample(index).orElse(Double.NaN);
    }

    /**
     * The samples of one moment of a graph.
     *
     * @param inHistory whether the history reaches back to that moment; if not, there are no samples
     * @param primary the sample of the primary series; {@code NaN} if it was skipped or is not there
     * @param secondary the sample of the second series; {@code NaN} if it was skipped or is not there
     */
    public record Reading(boolean inHistory, double primary, double secondary) {}
}
