package prism.srm.ui.control;

import java.util.Collection;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import prism.srm.ui.Typefaces;

/**
 * The width a table column of fixed content needs, measured from the font the table draws with:
 * the widest of its values and its header, with the padding of a cell on each side, on the 4 px
 * grid. Measured, not set, so a column still fits when another typeface stands in for the bundled
 * one (an unknown family resolves to the system font). Every table of the application measures
 * with these fonts and this rounding.
 */
public final class TextWidths {

    /** The padding of a table cell on each side, as the table stylesheet sets it. */
    public static final double CELL_PADDING = 10;

    /** The sizes the table stylesheet sets for its cells and its header. */
    private static final double CELL_FONT_SIZE = 13;

    private static final double HEADER_FONT_SIZE = 12;

    private static final double GRID = 4;

    private TextWidths() {}

    /** The font of the cells of a table. */
    public static Font tableBody() {
        return Font.font(Typefaces.FAMILY, CELL_FONT_SIZE);
    }

    /** The font of the headers of a table. */
    public static Font tableHeader() {
        return Font.font(Typefaces.MEDIUM_FAMILY, HEADER_FONT_SIZE);
    }

    /** The width of a text in a font. */
    public static double width(Font font, String text) {
        Text measured = new Text(text);
        measured.setFont(font);
        return measured.getLayoutBounds().getWidth();
    }

    /** A content width with the padding of a cell on each side, on the 4 px grid. */
    public static double cell(double content) {
        return Math.ceil((content + 2 * CELL_PADDING) / GRID) * GRID;
    }

    /** The width of a column that shows these values under this header. */
    public static double column(Font body, Font header, String title, Collection<String> values) {
        double widest = width(header, title);
        for (String value : values) {
            widest = Math.max(widest, width(body, value));
        }
        return cell(widest);
    }
}
