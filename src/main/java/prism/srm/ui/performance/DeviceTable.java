package prism.srm.ui.performance;

import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import prism.srm.ui.format.Quantity;

/**
 * A table of devices: one row per file system or network interface.
 *
 * <p>Layout and binding only. The rows belong to the view model, which sets their values in
 * place, so the table keeps its cells from one snapshot to the next. The order of the rows is the
 * view model's as well: the columns do not sort. The columns share the width of the table, so it
 * never scrolls sideways; a text that does not fit is cut with an ellipsis and its tooltip has it
 * in full.
 *
 * <p>The table is never higher than its rows need, so that the room it does not use goes to the
 * graph above it, and never lower than a few rows, below which it could not be read. Between the
 * two it shares the height with its neighbors and scrolls its rows.
 *
 * @param <R> the row
 */
final class DeviceTable<R> extends TableView<R> {

    // The table metrics of docs/UI_GUIDE.md, as table.css sets them.
    static final double ROW_HEIGHT = 26;
    static final double HEADER_HEIGHT = 28;
    private static final double FRAME = 2;

    /** The rows a table shows at least when it has more; an empty table says in that room why. */
    private static final int FEWEST_ROWS = 3;

    /**
     * @param rows the rows, owned and changed by the view model
     * @param whenEmpty what to show in place of the rows while there are none
     * @param accessibleText what the table lists, for a screen reader
     */
    DeviceTable(ObservableList<R> rows, ObservableValue<String> whenEmpty, String accessibleText) {
        super(rows);
        setAccessibleText(accessibleText);
        setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // An empty table keeps the room of a few rows for the text that says why it is empty.
        DoubleBinding highest =
                Bindings.createDoubleBinding(() -> heightFor(rows.isEmpty() ? FEWEST_ROWS : rows.size()), rows);
        DoubleBinding lowest = Bindings.createDoubleBinding(
                () -> heightFor(rows.isEmpty() ? FEWEST_ROWS : Math.min(FEWEST_ROWS, rows.size())), rows);
        minHeightProperty().bind(lowest);
        prefHeightProperty().bind(lowest);
        maxHeightProperty().bind(highest);

        Label placeholder = new Label();
        placeholder.textProperty().bind(whenEmpty);
        placeholder.getStyleClass().add("text-muted");
        setPlaceholder(placeholder);
    }

    private static double heightFor(int rows) {
        return FRAME + HEADER_HEIGHT + rows * ROW_HEIGHT;
    }

    /**
     * Adds a column of text that takes its share of the width left by the columns of a fixed
     * width.
     *
     * @param prefWidth its width relative to the other columns of text
     */
    void addTextColumn(String title, Function<R, ObservableValue<String>> value, double prefWidth) {
        TableColumn<R, String> column = newColumn(title, value);
        column.setPrefWidth(prefWidth);
        column.setCellFactory(ignored -> new TextCell<>());
        getColumns().add(column);
    }

    /** Adds a column of a fixed width for a short text, such as one word out of a few. */
    void addFixedTextColumn(String title, Function<R, ObservableValue<String>> value, double width) {
        addFixedColumn(newColumn(title, value), width);
    }

    /**
     * Adds a column of a fixed width for a number with its unit. The numbers end at one edge and
     * the units begin at the next, so the digits of all rows line up whatever their units are.
     *
     * @param kind the style class that gives the units of this column their room: "size" or
     *     "rate"
     */
    void addQuantityColumn(String title, Function<R, ObservableValue<Quantity>> value, String kind, double width) {
        TableColumn<R, Quantity> column = newColumn(title, value);
        column.getStyleClass().addAll("numeric", kind);
        column.setCellFactory(ignored -> new QuantityCell<>());
        addFixedColumn(column, width);
    }

    private void addFixedColumn(TableColumn<R, ?> column, double width) {
        column.setMinWidth(width);
        column.setPrefWidth(width);
        column.setMaxWidth(width);
        column.setResizable(false);
        getColumns().add(column);
    }

    private <T> TableColumn<R, T> newColumn(String title, Function<R, ObservableValue<T>> value) {
        TableColumn<R, T> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> value.apply(cell.getValue()));
        column.setSortable(false);
        column.setReorderable(false);
        return column;
    }

    /** A cell whose tooltip has its text in full. The tooltip is created once, with the cell. */
    private static final class TextCell<R> extends TableCell<R, String> {

        private final Tooltip full = new Tooltip();

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
            } else {
                setText(item);
                full.setText(item);
                setTooltip(full);
            }
        }
    }

    /**
     * A cell that sets the number against the right edge of its part and the unit, set back, in a
     * part of a fixed width beside it. Its two labels are created once, with the cell; an update
     * only sets their texts.
     */
    private static final class QuantityCell<R> extends TableCell<R, Quantity> {

        private final Label number = new Label();
        private final Label unit = new Label();
        private final HBox content = new HBox(number, unit);

        QuantityCell() {
            content.getStyleClass().add("quantity");
            unit.getStyleClass().addAll("quantity-unit", "text-muted");
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(Quantity item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
            } else {
                number.setText(item.number());
                unit.setText(item.unit());
                setGraphic(content);
            }
        }
    }
}
