package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.processes.ProcessOrder.SortKey;

/**
 * The process table: one row per process, the nine columns of the product specification (UI-2).
 *
 * <p>Layout and binding only. The rows and their order belong to the view model. The table shows
 * the view model's list as it is and never sorts or filters it itself: a click on a column header,
 * or a choice in the context menu ({@link SortMenu}, which the keyboard opens with Shift+F10 or
 * the Menu key), tells the view model which columns to sort by, and the view model puts its rows
 * in order. The header shows the order with the stock sort arrow.
 *
 * <p><b>Cells.</b> Every cell of a row observes the one snapshot of that row and shows its part
 * of it. The table creates cells for the rows it has room for and keeps them; an update sets
 * their texts and creates nothing. Texts are made for the cells on screen only.
 *
 * <p><b>Selection.</b> The selected row is the view model's. While the view model changes the
 * rows, what the table does to its own selection is not passed on, and afterwards the table is
 * given the view model's selection again. So a row that stays selected stays selected wherever
 * it moves, and a row that is taken out leaves nothing selected instead of its neighbor.
 *
 * <p><b>Width.</b> Every column but the name has a fixed width, wide enough for its largest
 * value, so the digits do not move as values change. The name column takes the room that is
 * left. In a window too narrow for all nine columns the table scrolls sideways, also with the
 * left and right arrow keys; scrolled as far as it goes, it begins with a whole column.
 */
final class ProcessTable extends TableView<ProcessRow> {

    // The table metrics of docs/UI_GUIDE.md, as table.css sets them.
    private static final double ROW_HEIGHT = 26;
    private static final double HEADER_HEIGHT = 28;
    private static final double FRAME = 2;

    /** The width of the vertical scroll bar, as app.css sets it. */
    private static final double SCROLL_BAR = 12;

    /** The rows the table shows at least; below that it could not be read. */
    private static final int FEWEST_ROWS = 3;

    private static final double NAME_MIN_WIDTH = 160;
    private static final double STATE_WIDTH = 80;

    private final ProcessesViewModel model;
    private final TableColumn<ProcessRow, ProcessSnapshot> name;
    private final TableColumn<ProcessRow, ProcessSnapshot> state;

    /** True while the view model changes the rows; the table's own selection changes do not count then. */
    private boolean rowsChanging;

    ProcessTable(ProcessesViewModel model, ProcessText text) {
        super(model.rows());
        this.model = model;
        getStyleClass().add("process-table");
        setAccessibleText("Processes");
        // Not a constrained policy: those cut columns off where this one scrolls.
        setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        setMinHeight(FRAME + HEADER_HEIGHT + FEWEST_ROWS * ROW_HEIGHT);
        setPrefHeight(FRAME + HEADER_HEIGHT + FEWEST_ROWS * ROW_HEIGHT);

        name = column(ProcessColumn.NAME, NAME_MIN_WIDTH);
        name.setCellFactory(ignored -> new TextCell(text::name));
        name.setMaxWidth(Double.MAX_VALUE);
        addNumberColumn(
                ProcessColumn.PID,
                72,
                process -> Long.toString(process.identity().pid()));
        addQuantityColumn(ProcessColumn.CPU, 72, "percent", text::cpu);
        addQuantityColumn(ProcessColumn.MEMORY, 96, "size", text::memory);
        addQuantityColumn(ProcessColumn.IO, 104, "rate", text::io);
        column(ProcessColumn.USER, 120).setCellFactory(ignored -> new TextCell(text::user));
        addNumberColumn(ProcessColumn.THREADS, 76, text::threads);
        addNumberColumn(ProcessColumn.PRIORITY, 72, text::priority);
        state = column(ProcessColumn.STATE, STATE_WIDTH);
        state.setCellFactory(ignored -> new TextCell(text::state));
        state.setMaxWidth(Double.MAX_VALUE);
        widthProperty().addListener(observable -> fitColumns());

        // The view model sorts. Its list is sorted in place, as the stock policy would sort it,
        // so the table carries its selection along with the rows.
        setSortPolicy(table -> {
            rowsChanging = true;
            try {
                model.sortBy(sortKeys());
            } finally {
                rowsChanging = false;
            }
            return true;
        });
        // The order the table opens with: by name, which changes least from one listing to the next.
        getSortOrder().add(name);
        // The way to choose the order without a mouse; a right click opens it too.
        setContextMenu(new SortMenu(this));

        getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            if (!rowsChanging) {
                model.select(after);
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, this::scrollSideways);

        Label whenEmpty = new Label();
        whenEmpty.textProperty().bind(model.whenEmptyProperty());
        whenEmpty.getStyleClass().add("text-muted");
        setPlaceholder(whenEmpty);
    }

    /**
     * Runs a change of the view model's rows and then gives the table the view model's selection
     * again. Every change of the rows goes through here, except a sort the table itself asks for.
     */
    void changeRows(Runnable change) {
        rowsChanging = true;
        try {
            change.run();
        } finally {
            rowsChanging = false;
        }
        ProcessRow wanted = model.selected();
        TableViewSelectionModel<ProcessRow> selection = getSelectionModel();
        int index = selection.getSelectedIndex();
        boolean inStep = wanted == null
                ? index < 0
                : index >= 0
                        && index < getItems().size()
                        && getItems().get(index) == wanted
                        && selection.getSelectedItem() == wanted;
        if (inStep) {
            return;
        }
        if (wanted == null) {
            selection.clearSelection();
        } else {
            selection.clearAndSelect(getItems().indexOf(wanted));
        }
    }

    private List<SortKey> sortKeys() {
        List<SortKey> keys = new ArrayList<>(getSortOrder().size());
        for (TableColumn<ProcessRow, ?> column : getSortOrder()) {
            keys.add(new SortKey(
                    (ProcessColumn) column.getUserData(), column.getSortType() == TableColumn.SortType.DESCENDING));
        }
        return keys;
    }

    /**
     * The name column takes what the other columns, the frame and the scroll bar leave.
     *
     * <p>Where that is less than the name needs, the table scrolls sideways, and the last column
     * is widened by just as much as it takes for a column to begin at the left edge when the
     * table is scrolled as far as it goes. Otherwise that position would cut into a column of
     * numbers, and what is left of a number that lost its first digits looks like a number.
     */
    private void fitColumns() {
        double room = getWidth() - FRAME - SCROLL_BAR;
        if (room <= 0) {
            return;
        }
        double others = 0;
        for (TableColumn<ProcessRow, ?> column : getColumns()) {
            if (column != name && column != state) {
                others += column.getPrefWidth();
            }
        }
        double nameWidth = Math.max(NAME_MIN_WIDTH, room - others - STATE_WIDTH);
        double hidden = nameWidth + others + STATE_WIDTH - room;
        double stateWidth = STATE_WIDTH;
        if (hidden > 0) {
            // The first place a column begins at or after what is scrolled out of view.
            double begins = 0;
            for (TableColumn<ProcessRow, ?> column : getColumns()) {
                if (begins >= hidden) {
                    break;
                }
                begins += column == name ? nameWidth : column == state ? STATE_WIDTH : column.getPrefWidth();
            }
            stateWidth += begins - hidden;
        }
        name.setPrefWidth(nameWidth);
        state.setPrefWidth(stateWidth);
    }

    /**
     * In a window too narrow for all columns, the right arrow key brings the last columns into
     * view and the left arrow key the first ones. Where all columns fit, neither does anything.
     */
    private void scrollSideways(KeyEvent event) {
        if (event.getCode() == KeyCode.RIGHT) {
            scrollToColumnIndex(getColumns().size() - 1);
            event.consume();
        } else if (event.getCode() == KeyCode.LEFT) {
            scrollToColumnIndex(0);
            event.consume();
        }
    }

    private void addNumberColumn(ProcessColumn which, double width, Function<ProcessSnapshot, String> text) {
        TableColumn<ProcessRow, ProcessSnapshot> column = column(which, width);
        column.getStyleClass().add("numeric");
        column.setCellFactory(ignored -> new NumberCell(text));
    }

    /**
     * Adds a column for a number with its unit. The numbers end at one edge and the units begin at
     * the next, so the digits of all rows line up whatever their units are.
     *
     * @param kind the style class that gives the units of this column their room
     */
    private void addQuantityColumn(
            ProcessColumn which, double width, String kind, Function<ProcessSnapshot, Quantity> quantity) {
        TableColumn<ProcessRow, ProcessSnapshot> column = column(which, width);
        column.getStyleClass().addAll("numeric", kind);
        column.setCellFactory(ignored -> new QuantityCell(quantity));
    }

    /** Adds a column of a fixed width whose cells observe the snapshot of their row. */
    private TableColumn<ProcessRow, ProcessSnapshot> column(ProcessColumn which, double width) {
        TableColumn<ProcessRow, ProcessSnapshot> column = new TableColumn<>(which.title());
        column.setUserData(which);
        column.setCellValueFactory(cell -> cell.getValue().snapshotProperty());
        column.setMinWidth(width);
        column.setPrefWidth(width);
        column.setMaxWidth(width);
        column.setResizable(false);
        column.setReorderable(false);
        getColumns().add(column);
        return column;
    }

    /** A cell of text. It is cut with an ellipsis when it does not fit, and then has a tooltip. */
    private static final class TextCell extends TableCell<ProcessRow, ProcessSnapshot> {

        private final Function<ProcessSnapshot, String> text;

        TextCell(Function<ProcessSnapshot, String> text) {
            this.text = text;
            CutTextTooltip.install(this);
        }

        @Override
        protected void updateItem(ProcessSnapshot item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : text.apply(item));
        }
    }

    /** A cell of a number without a unit, set against the right edge by its column. */
    private static final class NumberCell extends TableCell<ProcessRow, ProcessSnapshot> {

        private final Function<ProcessSnapshot, String> text;

        NumberCell(Function<ProcessSnapshot, String> text) {
            this.text = text;
        }

        @Override
        protected void updateItem(ProcessSnapshot item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : text.apply(item));
        }
    }

    /**
     * A cell that sets the number against the right edge of its part and the unit, set back, in a
     * part of a fixed width beside it. Its two labels are created once, with the cell; an update
     * only sets their texts.
     */
    private static final class QuantityCell extends TableCell<ProcessRow, ProcessSnapshot> {

        private final Function<ProcessSnapshot, Quantity> quantity;
        private final Label number = new Label();
        private final Label unit = new Label();
        private final HBox content = new HBox(number, unit);

        QuantityCell(Function<ProcessSnapshot, Quantity> quantity) {
            this.quantity = quantity;
            content.getStyleClass().add("quantity");
            unit.getStyleClass().addAll("quantity-unit", "text-muted");
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(ProcessSnapshot item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setAccessibleText(null);
            } else {
                Quantity value = quantity.apply(item);
                number.setText(value.number());
                unit.setText(value.unit());
                setGraphic(content);
                // The cell has no text of its own; a screen reader is given number and unit as one.
                setAccessibleText(value.text());
            }
        }
    }
}
