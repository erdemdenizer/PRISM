package prism.srm.ui.processes;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.SortType;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;

/**
 * The context menu of the process table, which is how the order is chosen without a mouse: with
 * the table focused, Shift+F10 or the Menu key opens it, the arrow keys move through it, Enter
 * chooses. "Sort by" lists the columns; below it "Ascending" and "Descending" set the direction.
 * A right click opens the same menu.
 *
 * <p>The menu changes nothing but the table's sort order and sort type, which is what a click on
 * a header changes, so both ways go through the one sort policy of the table. Each time it opens
 * it takes its state from the table, so it shows what the header shows: the first column of the
 * sort order and its direction, or no column while the rows are unsorted.
 */
final class SortMenu extends ContextMenu {

    private final TableView<ProcessRow> table;
    private final Map<ProcessColumn, RadioMenuItem> columns = new EnumMap<>(ProcessColumn.class);
    private final RadioMenuItem ascending = new RadioMenuItem("Ascending");
    private final RadioMenuItem descending = new RadioMenuItem("Descending");

    SortMenu(TableView<ProcessRow> table) {
        this.table = table;
        getStyleClass().add("sort-menu");

        Menu sortBy = new Menu("Sort by");
        ToggleGroup chosenColumn = new ToggleGroup();
        for (TableColumn<ProcessRow, ?> column : table.getColumns()) {
            ProcessColumn which = (ProcessColumn) column.getUserData();
            RadioMenuItem item = new RadioMenuItem(which.title());
            item.setToggleGroup(chosenColumn);
            // Chosen from the menu, a column is sorted smallest first, as by a first click on it.
            item.setOnAction(event -> {
                column.setSortType(SortType.ASCENDING);
                table.getSortOrder().setAll(List.of(column));
            });
            columns.put(which, item);
            sortBy.getItems().add(item);
        }
        ToggleGroup direction = new ToggleGroup();
        ascending.setToggleGroup(direction);
        descending.setToggleGroup(direction);
        ascending.setOnAction(event -> sortedColumn().setSortType(SortType.ASCENDING));
        descending.setOnAction(event -> sortedColumn().setSortType(SortType.DESCENDING));
        getItems().addAll(sortBy, new SeparatorMenuItem(), ascending, descending);

        setOnShowing(event -> showState());
        showState();
    }

    /** The column the rows are sorted by first; the direction items are disabled when there is none. */
    private TableColumn<ProcessRow, ?> sortedColumn() {
        return table.getSortOrder().get(0);
    }

    private void showState() {
        boolean sorted = !table.getSortOrder().isEmpty();
        TableColumn<ProcessRow, ?> first = sorted ? sortedColumn() : null;
        for (Map.Entry<ProcessColumn, RadioMenuItem> entry : columns.entrySet()) {
            entry.getValue().setSelected(first != null && first.getUserData() == entry.getKey());
        }
        ascending.setDisable(!sorted);
        descending.setDisable(!sorted);
        ascending.setSelected(sorted && first.getSortType() == SortType.ASCENDING);
        descending.setSelected(sorted && first.getSortType() == SortType.DESCENDING);
    }
}
