package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.control.CutTextTooltip;
import prism.srm.ui.control.TextWidths;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;
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

    // The table metrics as table.css sets them (UI lab v2: 30 px rows, 32 px header; 28 in a low window).
    private static final double ROW_HEIGHT = 30;
    private static final double HEADER_HEIGHT = 32;
    private static final double FRAME = 2;

    /** The size of the chevron that marks the sorted column (ColumnWidths keeps room for it). */
    private static final double SORT_MARK_SIZE = ColumnWidths.SORT_MARK;

    private static final PseudoClass MISSING = PseudoClass.getPseudoClass("missing");

    /** The most distinct user names whose widths are kept; beyond it the widths are measured again. */
    private static final int USER_WIDTH_CACHE = 256;

    /** The width of the vertical scroll bar, as app.css sets it. */
    private static final double SCROLL_BAR = 12;

    /** The vertical scroll bar of the skin, found once (it is the same for the life of the table). */
    private ScrollBar verticalBar;

    /**
     * The rows the table shows at least (UI_GUIDE, window behavior). Two since the inspector carries
     * the actions on the selected process (milestone 7): with three, the page would be taller than
     * the area of the smallest window and the whole page would scroll, against UI-8. A larger window
     * gives the table every further row.
     */
    private static final int FEWEST_ROWS = 2;

    private final ProcessesViewModel model;
    private final ProcessText text;
    private final ColumnWidths widths;
    private final TableColumn<ProcessRow, ProcessSnapshot> name;
    private final TableColumn<ProcessRow, ProcessSnapshot> user;
    private final TableColumn<ProcessRow, ProcessSnapshot> state;
    private final Map<TableColumn<ProcessRow, ?>, IconView> sortMarks = new HashMap<>();
    private final Map<String, Double> userWidths = new HashMap<>();

    /** True while the view model changes the rows; the table's own selection changes do not count then. */
    private boolean rowsChanging;

    ProcessTable(ProcessesViewModel model, ProcessText text) {
        super(model.rows());
        this.model = model;
        this.text = text;
        // Measured from the font the table draws with: the bundled typeface, or the system font
        // where that did not load (an unknown family resolves to the system font).
        widths = ColumnWidths.measure(TextWidths.tableBody(), TextWidths.tableHeader(), ProcessColumn.stateNames());
        getStyleClass().add("process-table");
        setAccessibleText("Processes");
        // Not a constrained policy: those cut columns off where this one scrolls.
        setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        setMinHeight(FRAME + HEADER_HEIGHT + FEWEST_ROWS * ROW_HEIGHT);
        setPrefHeight(FRAME + HEADER_HEIGHT + FEWEST_ROWS * ROW_HEIGHT);
        // UI lab v2: a low window sets denser rows in the stylesheet (shell.css); the least height
        // follows the row height the table actually has.
        fixedCellSizeProperty().addListener((observable, before, after) -> {
            double row = after.doubleValue() > 0 ? after.doubleValue() : ROW_HEIGHT;
            setMinHeight(FRAME + HEADER_HEIGHT + FEWEST_ROWS * row);
            setPrefHeight(FRAME + HEADER_HEIGHT + FEWEST_ROWS * row);
        });

        name = column(ProcessColumn.NAME, ColumnWidths.NAME_MIN);
        name.setCellFactory(ignored -> new TextCell(text::name));
        // UI lab v2: the name takes the room left up to a ceiling; State takes the rest (fitColumns).
        name.setMaxWidth(Double.MAX_VALUE);
        addNumberColumn(
                ProcessColumn.PID,
                widths.of(ProcessColumn.PID),
                process -> Long.toString(process.identity().pid()));
        addQuantityColumn(ProcessColumn.CPU, widths.of(ProcessColumn.CPU), "percent", text::cpu);
        addQuantityColumn(ProcessColumn.MEMORY, widths.of(ProcessColumn.MEMORY), "size", text::memory);
        addQuantityColumn(ProcessColumn.IO, widths.of(ProcessColumn.IO), "rate", text::io);
        user = column(ProcessColumn.USER, widths.userMin());
        user.setCellFactory(ignored -> new TextCell(text::user));
        user.setMaxWidth(ColumnWidths.USER_MAX);
        addNumberColumn(ProcessColumn.THREADS, widths.of(ProcessColumn.THREADS), text::threads);
        addNumberColumn(ProcessColumn.PRIORITY, widths.of(ProcessColumn.PRIORITY), text::priority);
        state = column(ProcessColumn.STATE, widths.of(ProcessColumn.STATE));
        state.setCellFactory(ignored -> new TextCell(text::state));
        state.setMaxWidth(Double.MAX_VALUE);
        widthProperty().addListener(observable -> fitColumns());
        for (TableColumn<ProcessRow, ?> column : getColumns()) {
            sortMarks.put(column, new IconView(Icon.CHEVRON_UP, SORT_MARK_SIZE));
            // Fix round H (PR-H1): the skin's own sort region is a grid with 3 px of padding that it
            // takes from the header label of the sorted column even where its arrow has no size. An
            // empty node in its place takes nothing; the chevron beside the text is the mark.
            column.setSortNode(new Region());
            column.sortTypeProperty().addListener(observable -> markSortedColumns());
        }
        getSortOrder().addListener((ListChangeListener<TableColumn<ProcessRow, ?>>) change -> markSortedColumns());

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
        markSortedColumns();
        // The way to choose the order without a mouse; a right click opens it too.
        setContextMenu(new SortMenu(this));
        // On the headers the menu is about the order alone (PR-20); the keyboard opens it on the rows.
        addEventFilter(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                event -> menu().openOnHeaders(!event.isKeyboardTrigger()
                        && onHeaders(event.getPickResult().getIntersectedNode())));

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

    /** Whether a node is part of the row of column headers of this table. */
    private boolean onHeaders(Node node) {
        for (Node at = node; at != null && at != this; at = at.getParent()) {
            if (at.getStyleClass().contains("column-header-background")) {
                return true;
            }
        }
        return false;
    }

    /** The context menu, so that the page can put its actions into it. */
    SortMenu menu() {
        return (SortMenu) getContextMenu();
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
        fitUserColumn();
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

    /** The height that shows its header and this many rows, at the row height the table has now. */
    double heightForRows(int rows) {
        double row = getFixedCellSize() > 0 ? getFixedCellSize() : ROW_HEIGHT;
        return FRAME + HEADER_HEIGHT + rows * row;
    }

    /**
     * The width the table needs to show every column without scrolling sideways: the name column at
     * its least, the others at their widths, the frame and the room of the vertical scroll bar.
     */
    double widthWithoutScrolling() {
        double width = frameAndScrollBar() + ColumnWidths.NAME_MIN + widths.of(ProcessColumn.STATE);
        for (TableColumn<ProcessRow, ?> column : getColumns()) {
            if (column != name && column != state) {
                width += column.getPrefWidth();
            }
        }
        return width;
    }

    /**
     * The name column takes what the other columns, the frame and the scroll bar leave, up to
     * {@link ColumnWidths#NAME_MAX}; beyond it the last column, State, takes the rest, so a wide
     * table has no empty band after its last column and its numbers stay near the names.
     *
     * <p>Where what is left is less than the name needs, the table scrolls sideways, and the last
     * column is widened by just as much as it takes for a column to begin at the left edge when
     * the table is scrolled as far as it goes. Otherwise that position would cut into a column of
     * numbers, and what is left of a number that lost its first digits looks like a number.
     */
    private void fitColumns() {
        double room = getWidth() - frameAndScrollBar();
        if (room <= 0) {
            return;
        }
        double stateMin = widths.of(ProcessColumn.STATE);
        double others = 0;
        for (TableColumn<ProcessRow, ?> column : getColumns()) {
            if (column != name && column != state) {
                others += column.getPrefWidth();
            }
        }
        double left = room - others - stateMin;
        double nameWidth = Math.min(ColumnWidths.NAME_MAX, Math.max(ColumnWidths.NAME_MIN, left));
        double hidden = nameWidth + others + stateMin - room;
        // Exactly the least width of State unless the name reached its ceiling.
        double stateWidth = stateMin + Math.max(0, left - nameWidth);
        if (hidden > 0) {
            // The first place a column begins at or after what is scrolled out of view.
            double begins = 0;
            for (TableColumn<ProcessRow, ?> column : getColumns()) {
                if (begins >= hidden) {
                    break;
                }
                begins += column == name ? nameWidth : column == state ? stateMin : column.getPrefWidth();
            }
            stateWidth += begins - hidden;
        } else if (nameWidth < ColumnWidths.NAME_MAX) {
            nameWidth = floorToDevicePixel(nameWidth);
        } else {
            stateWidth = floorToDevicePixel(stateWidth);
        }
        name.setPrefWidth(nameWidth);
        state.setPrefWidth(stateWidth);
    }

    /**
     * A width rounded down to the device pixel (fix round H, navigation with names): the column that
     * takes what is left, where the columns fit. The rows are as wide as their columns rounded up to
     * the device pixel; a name column of 324.80000000000007 px, the room less the other columns in
     * floating point, made the rows of a table that fits one device pixel too wide at 125 %, and it
     * scrolled sideways. Within a millionth of a device pixel a width counts as on it.
     */
    private double floorToDevicePixel(double width) {
        double scale = getScene() == null || getScene().getWindow() == null
                ? 1
                : getScene().getWindow().getRenderScaleX();
        return Math.floor(width * scale + 1e-6) / scale;
    }

    /**
     * The frame and the vertical scroll bar as the table lays them out (fix round H, PR-13). At a
     * fractional display scale both are snapped to the device pixel: at 125 % the 1 px frame takes
     * 0.8 px a side and the 12 px scroll bar 12.8 px, at 150 % the frame takes 1.33 px a side. The
     * rows are as wide as their columns, rounded up to the device pixel, and the table scrolls
     * sideways as soon as that is more than the room between the frame and the scroll bar; the
     * fixed 2 + 12 px of a 100 % display left the columns 0.4 to 0.7 px too wide there.
     */
    private double frameAndScrollBar() {
        return snappedLeftInset() + snappedRightInset() + scrollBarWidth();
    }

    /** The width of the vertical scroll bar once the skin has made it, else its width in app.css. */
    private double scrollBarWidth() {
        if (verticalBar == null) {
            for (Node node : lookupAll(".scroll-bar")) {
                if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                    verticalBar = bar;
                    break;
                }
            }
        }
        return snapSizeX(verticalBar == null ? SCROLL_BAR : verticalBar.prefWidth(-1));
    }

    /**
     * The user column is as wide as the widest user name on screen needs, between its floor and
     * its ceiling: narrow when every process runs as "ED", wide enough for "Administrator", and
     * cut with a tooltip beyond the ceiling. It changes only when the set of names changes, which
     * is rare, so the columns do not shift with the listings.
     */
    private void fitUserColumn() {
        String widest = "";
        double widestWidth = -1;
        for (ProcessRow row : getItems()) {
            String userName = text.user(row.snapshot());
            if (ProcessText.MISSING.equals(userName)) {
                continue;
            }
            double width = userWidth(userName);
            if (width > widestWidth) {
                widestWidth = width;
                widest = userName;
            }
        }
        double wanted = widths.userWidth(widest);
        if (wanted != user.getPrefWidth()) {
            user.setPrefWidth(wanted);
            fitColumns();
        }
    }

    private double userWidth(String userName) {
        Double known = userWidths.get(userName);
        if (known != null) {
            return known;
        }
        if (userWidths.size() >= USER_WIDTH_CACHE) {
            userWidths.clear();
        }
        double width = widths.textWidth(userName);
        userWidths.put(userName, width);
        return width;
    }

    /**
     * Marks the columns the rows are sorted by: the chevron beside the header text, up for the
     * smallest first and down for the largest first, and the header in the color of text. The
     * stock mark of the skin is hidden by table.css. Called when the sort order or a direction
     * changes, from the header, from the context menu, or from code.
     */
    private void markSortedColumns() {
        for (TableColumn<ProcessRow, ?> column : getColumns()) {
            boolean sorted = getSortOrder().contains(column);
            IconView mark = sortMarks.get(column);
            if (sorted) {
                mark.setRotate(column.getSortType() == TableColumn.SortType.DESCENDING ? 180 : 0);
                if (column.getGraphic() != mark) {
                    column.setGraphic(mark);
                }
                if (!column.getStyleClass().contains("sorted")) {
                    column.getStyleClass().add("sorted");
                }
            } else {
                if (column.getGraphic() != null) {
                    column.setGraphic(null);
                }
                column.getStyleClass().remove("sorted");
            }
        }
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
            String value = empty || item == null ? null : text.apply(item);
            setText(value);
            pseudoClassStateChanged(MISSING, ProcessText.MISSING.equals(value));
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
            String value = empty || item == null ? null : text.apply(item);
            setText(value);
            pseudoClassStateChanged(MISSING, ProcessText.MISSING.equals(value));
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
                pseudoClassStateChanged(MISSING, false);
            } else {
                Quantity value = quantity.apply(item);
                number.setText(value.number());
                unit.setText(value.unit());
                pseudoClassStateChanged(MISSING, ProcessText.MISSING.equals(value.number()));
                setGraphic(content);
                // The cell has no text of its own; a screen reader is given number and unit as one.
                setAccessibleText(value.text());
            }
        }
    }
}
