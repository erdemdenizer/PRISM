package prism.srm.ui.graph;

import java.util.List;
import java.util.Objects;
import prism.srm.domain.MetricHistory;

/**
 * What a history grid draws: one small history per cell, all against one scale.
 *
 * @param cells the cells, in the order they are laid out, row by row
 * @param maximum the value at the top of the scale of every cell; no history is drawn while it
 *     is not positive
 * @param widestValue the widest text the value of a cell can be, such as "100 %". Every cell
 *     keeps room for it beside its label, so the arrangement of the cells does not change with
 *     their values
 */
public record GridData(List<Cell> cells, double maximum, String widestValue) {

    /** Draws nothing. */
    public static final GridData EMPTY = new GridData(List.of(), 0, "");

    /**
     * One cell.
     *
     * @param label what the cell shows, written at its top left
     * @param value the current value as text, written at its top right
     * @param history the history drawn below them
     */
    public record Cell(String label, String value, MetricHistory history) {

        public Cell {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(history, "history");
        }
    }

    public GridData {
        cells = List.copyOf(cells);
        Objects.requireNonNull(widestValue, "widestValue");
        if (Double.isNaN(maximum) || Double.isInfinite(maximum)) {
            throw new IllegalArgumentException("maximum must be finite: " + maximum);
        }
    }
}
