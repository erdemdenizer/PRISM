package prism.srm.ui.processes;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javafx.scene.text.Font;
import prism.srm.ui.control.TextWidths;

/**
 * The widths of the columns of the process table, measured from the font the table draws with
 * (docs/UI_GUIDE.md, The process table). Nothing here is a number of one machine: a column is as
 * wide as its widest value and its header need in the font that is loaded, rounded up to the 4 px
 * grid, with the 8 px of padding on each side of a cell.
 *
 * <p>Measured once, when the table is built. The user column is the one whose width follows the
 * values on screen; {@link #userWidth} gives it for the widest user name shown, between a floor
 * and a ceiling, so the column is narrow for "ED" and never a second flexible column.
 */
final class ColumnWidths {

    /** The padding of a cell on either side, as table.css sets it. */
    static final double CELL_PADDING = TextWidths.CELL_PADDING;

    /** Between the number and the unit of a quantity, as table.css sets it. */
    static final double UNIT_GAP = 4;

    /** The parts the units have in a column of quantities, as table.css sets them. */
    static final double PERCENT_PART = 16;

    static final double SIZE_PART = 24;

    static final double RATE_PART = 36;

    /**
     * Fix round H (PR-H1): the chevron that marks the sorted column and its gap to the header text, as
     * table.css sets the gap. 10 px beside the 12 px header text, as the 12 px chevron of a menu button
     * stands beside its 13 px text; its strokes leave a quarter of the box clear on either side, so the
     * 2 px gap reads as about 4 px.
     */
    static final double SORT_MARK = 10;

    static final double SORT_MARK_GAP = 2;

    /**
     * Fix round H (PR-H1): the room a header needs beyond its text when the rows are sorted by its
     * column. The header of the sorted column gives the chevron the padding on the chevron's side
     * (table.css), so it needs the chevron and its gap less that padding: 2 px. The text stays where
     * it is, so the header text still begins (or ends) where the cells do. With these 2 px no column
     * of the bundled typeface is wider than without them ("Priority", the tightest, keeps 1.6 px).
     */
    static final double SORT_MARK_ROOM = SORT_MARK + SORT_MARK_GAP - CELL_PADDING;

    /** The least a name column takes; above it, it takes the room left up to {@link #NAME_MAX}. */
    static final double NAME_MIN = 160;

    /**
     * The most the fitted name column takes: longer than nearly every process name, and short enough
     * that the numbers stay a glance away from the name on a wide screen; State takes the rest.
     */
    static final double NAME_MAX = 480;

    static final double USER_MAX = 160;

    private final Font body;
    private final Map<ProcessColumn, Double> fixed = new EnumMap<>(ProcessColumn.class);
    private final double userMin;

    private ColumnWidths(Font body, double userMin) {
        this.body = body;
        this.userMin = userMin;
    }

    /**
     * Measures the fixed widths.
     *
     * @param body the font of the cells
     * @param header the font of the column headers
     * @param stateNames every text the State column can show; the longest decides its width
     */
    static ColumnWidths measure(Font body, Font header, Collection<String> stateNames) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(header, "header");
        ColumnWidths widths =
                new ColumnWidths(body, cell(Math.max(width(body, "SYSTEM"), width(header, "User") + SORT_MARK_ROOM)));
        widths.put(ProcessColumn.PID, header, width(body, "9999999"));
        widths.put(ProcessColumn.CPU, header, quantity(body, "100.0", PERCENT_PART));
        widths.put(ProcessColumn.MEMORY, header, quantity(body, "1023.9", SIZE_PART));
        widths.put(ProcessColumn.IO, header, quantity(body, "1023.9", RATE_PART));
        // Grouped as the counts are ("123,456"; "123.456" in Turkish): six digits and a separator.
        widths.put(ProcessColumn.THREADS, header, Math.max(width(body, "999,999"), width(body, "999.999")));
        widths.put(ProcessColumn.PRIORITY, header, Math.max(width(body, "-20"), width(body, "31")));
        double state = width(body, "–");
        for (String name : stateNames) {
            state = Math.max(state, width(body, name));
        }
        widths.put(ProcessColumn.STATE, header, state);
        return widths;
    }

    /** The width of a column whose width does not change: every column but the name and the user. */
    double of(ProcessColumn column) {
        Double width = fixed.get(column);
        if (width == null) {
            throw new IllegalArgumentException(column + " has no fixed width");
        }
        return width;
    }

    /** The least the user column is; wide enough for "SYSTEM" and for its header with the sort mark. */
    double userMin() {
        return userMin;
    }

    /**
     * The width of the user column for the widest user name on screen: that name with the
     * padding, never below the floor and never above {@link #USER_MAX}, where a longer name is cut.
     */
    double userWidth(String widestUser) {
        return Math.min(USER_MAX, Math.max(userMin, cell(width(body, widestUser))));
    }

    /** The width of a text in the font of the cells, for the user column to find its widest. */
    double textWidth(String text) {
        return width(body, text);
    }

    private void put(ProcessColumn column, Font header, double content) {
        fixed.put(column, cell(Math.max(content, width(header, column.title()) + SORT_MARK_ROOM)));
    }

    private static double cell(double content) {
        return TextWidths.cell(content);
    }

    private static double quantity(Font body, String number, double unitPart) {
        return width(body, number) + UNIT_GAP + unitPart;
    }

    private static double width(Font font, String text) {
        return TextWidths.width(font, text);
    }
}
