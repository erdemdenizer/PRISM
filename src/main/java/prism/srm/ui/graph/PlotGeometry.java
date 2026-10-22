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
