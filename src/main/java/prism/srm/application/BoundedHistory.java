package prism.srm.application;

import prism.srm.domain.MetricHistory;

/**
 * A fixed-capacity ring of the most recent samples of one metric. Once full, each new sample
 * replaces the oldest. Used by one thread at a time.
 */
final class BoundedHistory {

    private final double[] ring;
    private int next;
    private int size;

    BoundedHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        ring = new double[capacity];
    }

    void add(double sample) {
        ring[next] = sample;
        next = (next + 1) % ring.length;
        size = Math.min(size + 1, ring.length);
    }

    /** Records that this cycle has no value, keeping the samples aligned in time. */
    void addSkipped() {
        add(Double.NaN);
    }

    /** Returns an immutable copy of the samples, oldest first. */
    MetricHistory snapshot() {
        double[] ordered = new double[size];
        int oldest = (next - size + ring.length) % ring.length;
        for (int i = 0; i < size; i++) {
            ordered[i] = ring[(oldest + i) % ring.length];
        }
        return MetricHistory.of(ordered);
    }
}
