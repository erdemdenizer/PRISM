package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The mark of the sorted column, read from a real scene: a chevron beside the header text of
 * the column the rows are sorted by, up or down with the direction, and none on any other column;
 * the stock arrow of the skin takes no room. The sort itself is the subject of other tests.
 */
@Tag("ui-snapshot")
class SortIndicatorSceneTest {

    private static final String LOG_FOLDER = "C:\\logs";

    @Test
    void theTableOpensSortedByNameWithTheChevronUpOnThatHeaderOnly() {
        List<Object> seen = inTable(table -> {
            List<Object> facts = new ArrayList<>();
            for (TableColumn<ProcessRow, ?> column : table.getColumns()) {
                facts.add(column.getText() + ":" + mark(column));
            }
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        "Name:up",
                        "PID:none",
                        "CPU:none",
                        "Memory:none",
                        "I/O:none",
                        "User:none",
                        "Threads:none",
                        "Priority:none",
                        "State:none");
    }

    @Test
    void sortingByAnotherColumnMovesTheChevronAndTheDirectionTurnsIt() {
        List<Object> seen = inTable(table -> {
            TableColumn<ProcessRow, ?> cpu = column(table, "CPU");
            TableColumn<ProcessRow, ?> name = column(table, "Name");
            List<Object> facts = new ArrayList<>();
            cpu.setSortType(TableColumn.SortType.DESCENDING);
            table.getSortOrder().setAll(List.of(cpu));
            facts.add(mark(cpu));
            facts.add(mark(name));
            facts.add(cpu.getStyleClass().contains("sorted"));
            facts.add(name.getStyleClass().contains("sorted"));
            cpu.setSortType(TableColumn.SortType.ASCENDING);
            facts.add(mark(cpu));
            table.getSortOrder().clear();
            facts.add(mark(cpu));
            facts.add(cpu.getStyleClass().contains("sorted"));
            return facts;
        });

        assertThat(seen).containsExactly("down", "none", true, false, "up", "none", false);
    }

    /** A second column in the sort order (Shift+click on a header) is marked as well, in its own direction. */
    @Test
    void everyColumnInTheSortOrderIsMarkedInItsOwnDirection() {
        List<Object> seen = inTable(table -> {
            TableColumn<ProcessRow, ?> cpu = column(table, "CPU");
            TableColumn<ProcessRow, ?> name = column(table, "Name");
            cpu.setSortType(TableColumn.SortType.DESCENDING);
            name.setSortType(TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll(List.of(cpu, name));
            List<Object> facts = new ArrayList<>();
            facts.add(mark(cpu) + "," + mark(name));
            facts.add(cpu.getStyleClass().contains("sorted")
                    && name.getStyleClass().contains("sorted"));
            name.setSortType(TableColumn.SortType.DESCENDING);
            facts.add(mark(cpu) + "," + mark(name));
            table.getSortOrder().remove(cpu);
            facts.add(mark(cpu) + "," + mark(name));
            facts.add(cpu.getStyleClass().contains("sorted"));
            return facts;
        });

        assertThat(seen).containsExactly("down,up", true, "down,down", "none,down", false);
    }

    /** The chevron follows the text in a column of text and precedes it in a column of numbers. */
    @Test
    void theChevronStandsBesideTheTextOnTheSideAwayFromTheAlignedEdge() {
        List<Object> seen = inTable(table -> {
            table.getSortOrder().setAll(List.of(column(table, "Name")));
            table.applyCss();
            table.layout();
            Label nameHeader = headerLabel(table, "Name");
            table.getSortOrder().setAll(List.of(column(table, "PID")));
            table.applyCss();
            table.layout();
            Label pidHeader = headerLabel(table, "PID");
            Node stockArrow = table.lookup(".column-header .arrow");
            return List.of(
                    nameHeader.getContentDisplay(),
                    pidHeader.getContentDisplay(),
                    nameHeader.getGraphic() == null ? "none" : "chevron",
                    pidHeader.getGraphic() instanceof IconView view && view.icon() == Icon.CHEVRON_UP,
                    stockArrow == null ? 0.0 : stockArrow.getLayoutBounds().getWidth());
        });

        assertThat(seen).containsExactly(ContentDisplay.RIGHT, ContentDisplay.LEFT, "none", true, 0.0);
    }

    private static String mark(TableColumn<ProcessRow, ?> column) {
        if (!(column.getGraphic() instanceof IconView view)) {
            return "none";
        }
        return view.getRotate() == 180 ? "down" : "up";
    }

    private static TableColumn<ProcessRow, ?> column(TableView<ProcessRow> table, String title) {
        return table.getColumns().stream()
                .filter(column -> column.getText().equals(title))
                .findFirst()
                .orElseThrow();
    }

    private static Label headerLabel(TableView<ProcessRow> table, String title) {
        for (Node header : table.lookupAll(".column-header")) {
            Node label = header.lookup(".label");
            if (label instanceof Label text && title.equals(text.getText())) {
                return text;
            }
        }
        throw new IllegalStateException("no header " + title);
    }

    @SuppressWarnings("unchecked")
    private static <T> T inTable(Function<TableView<ProcessRow>, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> {
                    ProcessesPage page = new ProcessesPage(
                            new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, identity -> {});
                    page.show(ProcessListings.windows(40, 0));
                    return page;
                },
                root -> scenario.apply((TableView<ProcessRow>) root.lookup(".process-table")));
    }
}
