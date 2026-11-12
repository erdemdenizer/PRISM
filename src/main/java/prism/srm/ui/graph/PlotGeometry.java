package prism.srm.ui.graph;

/**
 * Maps the samples of a history onto a plot area. The area always spans {@code capacity} samples,
 * so a history that is not full yet sits against the right edge and grows to the left, and a
 * sample keeps its horizontal step from one cycle to the next.
 *
 * @param width width of the plot area
 * @param height height of the plot area
 * @param capacity the number of samples the area spans, at least one
 * @param maximum the value drawn at the top edge; the bottom edge is zero
 */
record PlotGeometry(double width, double height, int capacity, double maximum) {

    PlotGeometry {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
    }

    /**
     * Returns the horizontal position of the sample at {@code index} in a history of {@code size}
     * samples, oldest first: the newest sample is at the right edge.
     */
    double x(int index, int size) {
        if (capacity == 1) {
            return width;
        }
        int slot = capacity - size + index;
        return width * slot / (capacity - 1);
    }

    /**
     * Returns the horizontal position of the sample that is {@code age} samples older than the
     * newest: the newest (age 0) is at the right edge, whatever the size of the history.
     */
    double xOfAge(int age) {
        if (capacity == 1) {
            return width;
        }
        return width * (capacity - 1 - age) / (capacity - 1);
    }

    /**
     * Returns the age of the sample whose place is nearest to a horizontal position: 0 for the
     * newest, at the right edge, up to {@code capacity - 1} at the left edge. A position outside
     * the area counts as its nearer edge. The inverse of {@link #xOfAge}.
     */
    int ageAt(double x) {
        if (capacity == 1 || !(width > 0)) {
            return 0;
        }
        long slot = Math.round(Math.clamp(x, 0, width) / width * (capacity - 1));
        return (int) (capacity - 1 - slot);
    }

    /**
     * Returns the index, oldest first, of the sample {@code age} samples older than the newest in
     * a history of {@code size} samples; -1 when the history does not reach back that far.
     */
    static int indexOfAge(int age, int size) {
        if (age < 0) {
            return -1;
        }
        int index = size - 1 - age;
        return index >= 0 ? index : -1;
    }

    /**
     * Returns the vertical position of a value, measured from the top edge. A value outside the
     * scale is drawn at the edge it exceeds; with no scale, everything is at the bottom.
     */
    double y(double value) {
        if (!(maximum > 0)) {
            return height;
        }
        double share = Math.clamp(value / maximum, 0, 1);
        return height - height * share;
    }
}
