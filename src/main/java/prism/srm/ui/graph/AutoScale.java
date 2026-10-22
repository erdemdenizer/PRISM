package prism.srm.ui.graph;

import prism.srm.domain.MetricHistory;

/**
 * Chooses the top of the scale for a rate that has no natural maximum.
 *
 * <p>The top is the smallest step of a fixed ladder that holds the largest sample on display:
 * 100, 200 and 500 KiB/s, then 1, 2, 5, 10, 20, 50, 100, 200 and 500 of each larger binary unit.
 * It therefore changes only when the largest sample crosses a step, and it never goes below
 * 100 KiB/s, so the noise of an idle system does not fill the graph.
 */
public final class AutoScale {

    private static final double KIBI = 1024;
    private static final double FLOOR = 100 * KIBI;
    private static final double[] STEPS = {1, 2, 5, 10, 20, 50, 100, 200, 500};

    private AutoScale() {}

    /** Returns the top of the scale, in bytes per second, for the largest sample on display. */
    public static double maximumFor(double largestSample) {
        if (!(largestSample > FLOOR)) {
            return FLOOR;
        }
        for (double unit = KIBI; ; unit *= KIBI) {
            for (double step : STEPS) {
                double candidate = step * unit;
                if (Double.isInfinite(candidate)) {
                    // Beyond the ladder, which no real rate reaches: the sample is its own scale.
                    return largestSample;
                }
                if (candidate >= largestSample) {
                    return candidate;
                }
            }
        }
    }

    /** Returns whether any of the histories holds a sample that was not skipped. */
    public static boolean hasSample(MetricHistory... histories) {
        for (MetricHistory history : histories) {
            for (double sample : history.toArray()) {
                if (!Double.isNaN(sample)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the largest sample of the histories, skipped samples aside; zero if there is none. */
    public static double largestSample(MetricHistory... histories) {
        double largest = 0;
        for (MetricHistory history : histories) {
            for (double sample : history.toArray()) {
                // A skipped sample is NaN, and every comparison with NaN is false.
                if (sample > largest) {
                    largest = sample;
                }
            }
        }
        return largest;
    }
}
