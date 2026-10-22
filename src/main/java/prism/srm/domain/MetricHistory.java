package prism.srm.domain;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * The recent samples of one metric, oldest first, taken at the monitoring cadence. A skipped
 * sample keeps its place in the sequence, so position always corresponds to time.
 *
 * <p>A class instead of a record so the samples can be held as a primitive array, copied on the
 * way in and out, which a graph can draw from without boxing.
 */
public final class MetricHistory {

    private static final MetricHistory EMPTY = new MetricHistory(new double[0]);

    private final double[] samples;

    private MetricHistory(double[] samples) {
        this.samples = samples;
    }

    public static MetricHistory empty() {
        return EMPTY;
    }

    /**
     * Creates a history from samples ordered oldest first, where {@code NaN} marks a skipped
     * sample.
     */
    public static MetricHistory of(double... samples) {
        double[] copy = samples.clone();
        for (double sample : copy) {
            if (Double.isInfinite(sample)) {
                throw new IllegalArgumentException("Samples must be finite or NaN: " + sample);
            }
        }
        return copy.length == 0 ? EMPTY : new MetricHistory(copy);
    }

    public int size() {
        return samples.length;
    }

    /** Returns the sample at {@code index} (0 is the oldest), or empty if it was skipped. */
    public OptionalDouble sample(int index) {
        double sample = samples[index];
        return Double.isNaN(sample) ? OptionalDouble.empty() : OptionalDouble.of(sample);
    }

    /** Returns a copy of the samples, oldest first, with {@code NaN} for each skipped sample. */
    public double[] toArray() {
        return samples.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MetricHistory history && Arrays.equals(samples, history.samples);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(samples);
    }

    @Override
    public String toString() {
        return "MetricHistory" + Arrays.toString(samples);
    }
}
