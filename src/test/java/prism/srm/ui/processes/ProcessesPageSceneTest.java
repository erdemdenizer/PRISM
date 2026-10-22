package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * What the Processes page does in a real scene, with the real table: what stays selected, where
 * the table stands after a listing, what the keys do, and that nothing is built again. These are
 * the things only the toolkit can show; the rules behind them are tested without it.
 */
@Tag("ui-snapshot")
class ProcessesPageSceneTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    /** The page with the requests for details it has made so far. */
    private record Scene(ProcessesPage page, List<ProcessIdentity> asked) {

        @SuppressWarnings("unchecked") // The table of the Processes page is a table of process rows.
        TableView<ProcessRow> table() {
            return (TableView<ProcessRow>) page.lookup(".table-view");
        }

        TextField filter() {
            return (TextField) page.lookup(".process-filter");
        }

        void settle() {
            page.applyCss();
            page.layout();
            page.applyCss();
            page.layout();
        }

        ProcessRow row(long pid) {
            return table().getItems().stream()
                    .filter(row -> row.identity().pid() == pid)
                    .findFirst()
                    .orElseThrow();
        }

        TableColumn<ProcessRow, ?> column(String title) {
            return table().getColumns().stream()
                    .filter(column -> column.getText().equals(title))
                    .findFirst()
                    .orElseThrow();
        }

        void sortBy(String title, TableColumn.SortType direction) {
            TableColumn<ProcessRow, ?> column = column(title);
            column.setSortType(direction);
            table().getSortOrder().setAll(List.of(column));
        }

        List<Long> pids() {
            return table().getItems().stream().map(row -> row.identity().pid()).toList();
        }

        String title() {
            return ((Label) page.lookup(".process-details-title")).getText();
        }

        /** What stands beside the title: "PID n", or the sentence that the process has exited. */
        String besideTheTitle() {
            Parent heading = (Parent) page.lookup(".process-details-heading");
            return ((Label) heading.getChildrenUnmodifiable().get(1)).getText();
        }

        VirtualFlow<?> flow() {
            return (VirtualFlow<?>) table().lookup(".virtual-flow");
        }

        int firstVisibleRow() {
            IndexedCell<?> first = flow().getFirstVisibleCell();
            return first == null ? -1 : first.getIndex();
        }

        ScrollBar scrollBar(Orientation orientation) {
            return table().lookupAll(".scroll-bar").stream()
                    .map(ScrollBar.class::cast)
                    .filter(bar -> bar.getOrientation() == orientation && bar.getParent() == flow())
                    .findFirst()
                    .orElseThrow();
        }

        /** Presses and releases a key, and lays the page out as the next pulse would. */
        void press(Node target, KeyCode code) {
            Event.fireEvent(target, key(KeyEvent.KEY_PRESSED, code));
            Event.fireEvent(target, key(KeyEvent.KEY_RELEASED, code));
            settle();
        }
    }

    // --- Selection ----------------------------------------------------------------------------

    @Test
    void theSelectedRowStaysSelectedWhenAListingMovesItToAnotherPlace() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(List.of(cpu(10, 5), cpu(20, 3), cpu(30, 1)));
            scene.sortBy("CPU", TableColumn.SortType.DESCENDING);
            scene.settle();
            ProcessRow selected = scene.row(30);
            scene.table().getSelectionModel().select(selected);
            int placeBefore = scene.table().getSelectionModel().getSelectedIndex();

            scene.page().show(List.of(cpu(10, 5), cpu(20, 3), cpu(30, 9)));
            scene.settle();

            return List.of(
                    placeBefore,
                    scene.pids(),
                    scene.table().getSelectionModel().getSelectedItem() == selected,
                    scene.table().getSelectionModel().getSelectedIndex(),
                    scene.table().getSelectionModel().getSelectedIndices().size(),
                    scene.title());
        });

        // Last of three before, first of three after, and still the one selected row.
        assertThat(seen).containsExactly(2, List.of(30L, 10L, 20L), true, 0, 1, "process-30");
    }

    @Test
    void theSelectedRowStaysSelectedWhenTheUserSortsByAnotherColumn() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(394, 3));
            scene.settle();
            ProcessRow selected = scene.table().getItems().get(17);
            scene.table().getSelectionModel().select(17);
            List<Object> facts = new ArrayList<>();

            for (String column : new String[] {"PID", "CPU", "Memory", "I/O", "User", "Threads", "Priority", "State"}) {
                scene.sortBy(column, TableColumn.SortType.DESCENDING);
                scene.settle();
                facts.add(scene.table().getSelectionModel().getSelectedItem() == selected
                        && scene.table()
                                        .getItems()
                                        .get(scene.table().getSelectionModel().getSelectedIndex())
                                == selected
                        && scene.table()
                                        .getSelectionModel()
                                        .getSelectedIndices()
                                        .size()
                                == 1);
            }
            // No column at all: the third click on a header.
            scene.table().getSortOrder().clear();
            scene.settle();
            facts.add(scene.table().getSelectionModel().getSelectedItem() == selected);
            facts.add(scene.asked().size());
            return facts;
        });

        // Selected through eight sorts and the unsorted order, and its details asked for once.
        assertThat(seen).containsExactly(true, true, true, true, true, true, true, true, true, 1);
    }

    @Test
    void whenTheSelectedProcessExitsNothingIsSelectedAndNoNeighborTakesItsPlace() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 1), cpu(30, 1)));
            scene.settle();
            scene.table().getSelectionModel().select(scene.row(20));

            scene.page().show(List.of(cpu(10, 1), cpu(30, 1)));
            scene.settle();

            return List.of(
                    scene.table().getSelectionModel().getSelectedIndex(),
                    scene.table().getSelectionModel().isEmpty(),
                    scene.title(),
                    scene.besideTheTitle(),
                    scene.asked());
        });

        assertThat(seen)
                .containsExactly(-1, true, "process-20", "PID 20 has exited", List.of(ProcessListings.identity(20)));
    }

    /** The same PID with another start time is another process: it is not selected in its place. */
    @Test
    void aProcessThatTookOverThePidOfTheSelectedOneIsNotSelected() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 1)));
            scene.settle();
            ProcessRow before = scene.row(20);
            scene.table().getSelectionModel().select(before);

            scene.page()
                    .show(List.of(
                            cpu(10, 1),
                            ProcessListings.process(20, "another")
                                    .startedSecondsAfterBoot(5000)
                                    .build()));
            scene.settle();

            return List.of(
                    scene.table().getSelectionModel().isEmpty(),
                    scene.row(20) != before,
                    scene.title(),
                    scene.besideTheTitle(),
                    scene.asked().size());
        });

        assertThat(seen).containsExactly(true, true, "process-20", "PID 20 has exited", 1);
    }

    @Test
    void theSelectedRowStaysSelectedWhileProcessesStartAndExitAroundIt() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.settle();
            ProcessRow selected = scene.table().getItems().get(200);
            scene.table().getSelectionModel().select(200);
            List<Object> facts = new ArrayList<>();
            for (int tick = 1; tick <= 20; tick++) {
                // Every listing loses some processes and gains others; the selected one stays.
                List<ProcessSnapshot> listing = new ArrayList<>(ProcessListings.windows(394, tick));
                int drop = tick;
                listing.removeIf(process -> process.identity().pid() % 40 == 4L * (drop % 10)
                        && !process.identity().equals(selected.identity()));
                for (int extra = 0; extra < tick % 5; extra++) {
                    listing.add(ProcessListings.process(100_000 + tick * 10L + extra, "new-" + extra)
                            .build());
                }
                scene.page().show(listing);
                scene.settle();
                facts.add(scene.table().getSelectionModel().getSelectedItem() == selected
                        && scene.table()
                                        .getItems()
                                        .get(scene.table().getSelectionModel().getSelectedIndex())
                                == selected);
            }
            facts.add(scene.asked().size());
            return facts;
        });

        List<Object> expected = new ArrayList<>(java.util.Collections.nCopies(20, true));
        expected.add(1);
        assertThat(seen).isEqualTo(expected);
    }

    /** What a click on a row comes to: the details are asked for, and the answer is shown. */
    @Test
    void selectingARowAsksForItsDetailsAndShowsTheAnswer() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 1)));
            scene.settle();
            scene.table().getSelectionModel().select(scene.row(20));
            String whileReading = detail(scene, 0);

            scene.page()
                    .showDetails(ProcessDetailsResult.read(new ProcessDetails(
                            ProcessListings.identity(20), Optional.of("C:\\bin\\twenty.exe"), Optional.empty())));
            scene.settle();

            return List.of(scene.asked(), whileReading, detail(scene, 0), detail(scene, 1));
        });

        assertThat(seen)
                .containsExactly(
                        List.of(ProcessListings.identity(20)),
                        "Reading\u2026",
                        "C:\\bin\\twenty.exe",
                        ProcessDetailsModel.NOT_AVAILABLE);
    }

    // --- Scroll position ------------------------------------------------------------------------

    @Test
    void theTableStaysWhereItIsWhenAListingChangesValuesAndAddsAndRemovesRows() {
        List<Integer> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.settle();
            scene.table().scrollTo(180);
            scene.settle();
            List<Integer> firstRows = new ArrayList<>();
            firstRows.add(scene.firstVisibleRow());

            // Only values change.
            scene.page().show(ProcessListings.windows(394, 1));
            scene.settle();
            firstRows.add(scene.firstVisibleRow());

            // Processes start; by name they sort to the end, below what is on screen.
            List<ProcessSnapshot> more = new ArrayList<>(ProcessListings.windows(394, 2));
            for (int extra = 0; extra < 12; extra++) {
                more.add(ProcessListings.process(200_000 + extra, "zz-new-" + extra)
                        .build());
            }
            scene.page().show(more);
            scene.settle();
            firstRows.add(scene.firstVisibleRow());

            // They exit again.
            scene.page().show(ProcessListings.windows(394, 3));
            scene.settle();
            firstRows.add(scene.firstVisibleRow());

            // Sorted by a value that changes with every listing: the rows move, the table does not.
            scene.sortBy("CPU", TableColumn.SortType.DESCENDING);
            scene.settle();
            firstRows.add(scene.firstVisibleRow());
            scene.page().show(ProcessListings.windows(394, 4));
            scene.settle();
            firstRows.add(scene.firstVisibleRow());
            return firstRows;
        });

        assertThat(seen.get(0)).isEqualTo(180);
        assertThat(seen).containsOnly(180);
    }

    // --- Keyboard -------------------------------------------------------------------------------

    @Test
    void theArrowKeysHomeAndEndMoveTheSelectionThroughTheRows() {
        List<Integer> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.settle();
            TableView<ProcessRow> table = scene.table();
            List<Integer> places = new ArrayList<>();
            table.getSelectionModel().select(0);
            scene.press(table, KeyCode.DOWN);
            scene.press(table, KeyCode.DOWN);
            places.add(table.getSelectionModel().getSelectedIndex());
            scene.press(table, KeyCode.UP);
            places.add(table.getSelectionModel().getSelectedIndex());
            scene.press(table, KeyCode.END);
            places.add(table.getSelectionModel().getSelectedIndex());
            scene.press(table, KeyCode.HOME);
            places.add(table.getSelectionModel().getSelectedIndex());
            scene.press(table, KeyCode.PAGE_DOWN);
            scene.settle();
            places.add(table.getSelectionModel().getSelectedIndex() > 5 ? 1 : 0);
            return places;
        });

        assertThat(seen).containsExactly(2, 1, 393, 0, 1);
    }

    /** The row the keys moved to is the one whose details are shown. */
    @Test
    void movingTheSelectionByKeyboardShowsTheDetailsOfTheRowTheSelectionIsOn() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 1), cpu(30, 1)));
            scene.settle();
            scene.table().getSelectionModel().select(0);
            scene.press(scene.table(), KeyCode.DOWN);
            return List.of(scene.title(), scene.asked());
        });

        assertThat(seen)
                .containsExactly("process-20", List.of(ProcessListings.identity(10), ProcessListings.identity(20)));
    }

    @Test
    void inAWindowTooNarrowForAllColumnsTheArrowKeysBringTheOtherColumnsIntoView() {
        List<Object> seen = inPage(520, 392, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.settle();
            ScrollBar sideways = scene.scrollBar(Orientation.HORIZONTAL);
            List<Object> facts = new ArrayList<>();
            facts.add(sideways.isVisible());
            facts.add(sideways.getValue() == sideways.getMin());
            scene.press(scene.table(), KeyCode.RIGHT);
            scene.settle();
            facts.add(sideways.getValue() == sideways.getMax());
            scene.press(scene.table(), KeyCode.LEFT);
            scene.settle();
            facts.add(sideways.getValue() == sideways.getMin());
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true);
    }

    /**
     * Scrolled as far as it goes, the table must not begin in the middle of a column of numbers:
     * "448.0 MiB" without its first digit reads as "8.0 MiB", which is a number too.
     */
    @Test
    void scrolledAsFarAsItGoesTheTableBeginsWithAWholeColumnAtEveryWidthTooNarrowForAllColumns() {
        for (int width = 420; width <= 880; width += 23) {
            List<Double> seen = inPage(width, 392, scene -> {
                scene.page().show(ProcessListings.windows(394, 0));
                scene.settle();
                scene.press(scene.table(), KeyCode.RIGHT);
                List<Double> facts = new ArrayList<>();
                facts.add(scene.scrollBar(Orientation.HORIZONTAL).isVisible() ? 1.0 : 0.0);
                facts.add(scene.scrollBar(Orientation.HORIZONTAL).getValue());
                double begins = 0;
                for (TableColumn<ProcessRow, ?> column : scene.table().getColumns()) {
                    facts.add(begins);
                    begins += column.getWidth();
                }
                return facts;
            });

            assertThat(seen.get(0)).as("scrolls sideways at width %d", width).isEqualTo(1.0);
            assertThat(seen.subList(2, seen.size()))
                    .as("the place a column begins, at width %d", width)
                    .contains(seen.get(1));
            assertThat(seen.get(1)).isPositive();
        }
    }

    /** The details describe the selected row, so it has to be found while the filter has the focus. */
    @Test
    void theSelectedRowHasTheSelectionColorAlsoWhileTheTableDoesNotHaveTheFocus() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(40, 0));
            scene.settle();
            scene.table().getSelectionModel().select(2);
            scene.settle();
            List<Object> fills = new ArrayList<>();
            for (Node node : scene.table().lookupAll(".table-row-cell")) {
                javafx.scene.control.TableRow<?> row = (javafx.scene.control.TableRow<?>) node;
                if (row.getIndex() == 2 || row.getIndex() == 3) {
                    fills.add(row.getIndex() + " "
                            + row.getBackground().getFills().get(0).getFill());
                }
            }
            fills.add(scene.table().isFocused());
            return fills;
        });

        // -srm-selection and -srm-surface of the light theme.
        assertThat(seen).containsExactlyInAnyOrder("2 0xd9eceeff", "3 0xffffffff", false);
    }

    @Test
    void theSentenceThatAProcessExitedIsNotCutHoweverLongItsName() {
        List<Object> seen = inPage(520, 392, scene -> {
            scene.page().show(ProcessListings.longNames());
            scene.settle();
            scene.table().getSelectionModel().select(scene.row(4120));
            scene.page().show(ProcessListings.longNames().subList(1, 5));
            scene.settle();
            Parent heading = (Parent) scene.page().lookup(".process-details-heading");
            Label title = (Label) heading.getChildrenUnmodifiable().get(0);
            Label note = (Label) heading.getChildrenUnmodifiable().get(1);
            return List.of(
                    note.getText(),
                    note.getWidth() >= note.prefWidth(-1),
                    title.getWidth() < title.prefWidth(-1),
                    title.getTooltip() != null);
        });

        // The sentence has all the room it needs; the name gave way and has its tooltip.
        assertThat(seen).containsExactly("PID 4120 has exited", true, true, true);
    }

    @Test
    void atTheDefaultSizeAllColumnsFitAndTheNameColumnTakesTheRoomThatIsLeft() {
        List<Object> seen = inPage(916, 651, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.settle();
            double columns = scene.table().getColumns().stream()
                    .mapToDouble(TableColumn::getWidth)
                    .sum();
            return List.of(
                    scene.scrollBar(Orientation.HORIZONTAL).isVisible(),
                    scene.scrollBar(Orientation.VERTICAL).isVisible(),
                    // The frame of the table and its vertical scroll bar take the rest.
                    scene.table().getWidth() - columns);
        });

        assertThat(seen).containsExactly(false, true, 14.0);
    }

    @Test
    void theEscapeKeyInTheFilterClearsItAndTheRowsComeBack() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.filter().setText("chrome");
            scene.settle();
            int filtered = scene.table().getItems().size();
            scene.press(scene.filter(), KeyCode.ESCAPE);
            scene.settle();
            return List.of(
                    filtered, scene.filter().getText(), scene.table().getItems().size());
        });

        assertThat(seen).containsExactly(22, "", 394);
    }

    // --- Cells ------------------------------------------------------------------------------------

    @Test
    void aNameThatIsCutHasATooltipWithTheWholeNameAndANameThatFitsHasNone() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.longNames());
            scene.settle();
            List<Object> facts = new ArrayList<>();
            for (TableCell<?, ?> cell : cells(scene, "Name")) {
                if ("short.exe".equals(cell.getText())) {
                    facts.add(cell.getTooltip() == null);
                } else if (cell.getText() != null && cell.getText().startsWith("a-process")) {
                    facts.add(cell.getTooltip() != null
                            && cell.getTooltip().getText().equals(cell.getText()));
                }
            }
            return facts;
        });

        assertThat(seen).containsExactly(true, true);
    }

    @Test
    void numbersAreSetAgainstTheRightEdgeAndTextsAgainstTheLeft() {
        List<Object> seen = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(40, 0));
            scene.settle();
            List<Object> alignments = new ArrayList<>();
            for (String column :
                    new String[] {"Name", "PID", "CPU", "Memory", "I/O", "User", "Threads", "Priority", "State"}) {
                alignments.add(cells(scene, column).get(0).getAlignment());
            }
            return alignments;
        });

        assertThat(seen)
                .containsExactly(
                        Pos.CENTER_LEFT,
                        Pos.CENTER_RIGHT,
                        Pos.CENTER_RIGHT,
                        Pos.CENTER_RIGHT,
                        Pos.CENTER_RIGHT,
                        Pos.CENTER_LEFT,
                        Pos.CENTER_RIGHT,
                        Pos.CENTER_RIGHT,
                        Pos.CENTER_LEFT);
    }

    // --- The scene does not grow --------------------------------------------------------------------

    @Test
    void twoHundredListingsLeaveTheSceneWithExactlyItsNodes() {
        List<Integer> counts = inPage(1100, 700, scene -> {
            scene.sortBy("CPU", TableColumn.SortType.DESCENDING);
            for (int tick = 0; tick < 5; tick++) {
                scene.page().show(ProcessListings.windows(394, tick));
                scene.settle();
            }
            scene.table().getSelectionModel().select(3);
            scene.settle();
            int before = nodes(scene.page());
            for (int tick = 5; tick < 205; tick++) {
                List<ProcessSnapshot> listing = new ArrayList<>(ProcessListings.windows(394, tick));
                // Processes come and go as well.
                listing.add(ProcessListings.process(300_000 + tick, "passing")
                        .cpu(3)
                        .build());
                scene.page().show(listing);
                scene.settle();
            }
            return List.of(before, nodes(scene.page()), scene.table().getItems().size());
        });

        assertThat(counts.get(1)).isEqualTo(counts.get(0));
        assertThat(counts.get(2)).isEqualTo(395);
    }

    @Test
    void aThousandRowsNeedNoMoreNodesThanFourHundred() {
        int few = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(394, 0));
            scene.settle();
            return nodes(scene.page());
        });
        int many = inPage(1100, 700, scene -> {
            scene.page().show(ProcessListings.windows(1000, 0));
            scene.settle();
            return nodes(scene.page());
        });

        // The table builds cells for the rows it has room for, however many rows there are.
        assertThat(many).isEqualTo(few);
    }

    private static ProcessSnapshot cpu(long pid, double percent) {
        return ProcessListings.process(pid, "process-" + pid).cpu(percent).build();
    }

    /** The text of a line of the details pane: 0 is the path, 1 the command line. */
    private static String detail(Scene scene, int line) {
        List<Node> values = new ArrayList<>(scene.page().lookupAll(".process-details-lines .detail-value"));
        return ((Label) values.get(line)).getText();
    }

    /** The cells of a column that show a row, in no particular order. */
    private static List<TableCell<?, ?>> cells(Scene scene, String title) {
        TableColumn<ProcessRow, ?> column = scene.column(title);
        List<TableCell<?, ?>> cells = new ArrayList<>();
        for (Node node : scene.table().lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell && cell.getTableColumn() == column && !cell.isEmpty()) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private static int nodes(Node node) {
        int count = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += nodes(child);
            }
        }
        return count;
    }

    private static <T> T inPage(int width, int height, Function<Scene, T> scenario) {
        List<ProcessIdentity> asked = new ArrayList<>();
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> new ProcessesPage(new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, asked::add),
                root -> scenario.apply(new Scene((ProcessesPage) root, asked)));
    }

    private static KeyEvent key(EventType<KeyEvent> type, KeyCode code) {
        return new KeyEvent(type, "", "", code, false, false, false, false);
    }
}
