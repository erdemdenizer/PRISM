package prism.srm.ui.format;

import java.util.Objects;

/**
 * A formatted value in two parts, so that a view can set the number and its unit in different
 * styles.
 *
 * @param number the digits, in the notation of the formatter's locale
 * @param unit the unit symbol, the same in every locale; empty when the value has none
 */
public record Quantity(String number, String unit) {

    /**
     * Stands in for a value that is missing where there is no room to say so in words, as in a
     * table cell: an en dash. A value that is missing is not zero.
     */
    public static final Quantity MISSING = new Quantity("\u2013", "");

    public Quantity {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(unit, "unit");
    }

    /** Returns the number and the unit as one text, separated by a space. */
    public String text() {
        return unit.isEmpty() ? number : number + " " + unit;
    }
}
