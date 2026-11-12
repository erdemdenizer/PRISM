package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.shell.Shell;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Fix round H (PR-13): at a fractional display scale the process table must not show a horizontal scroll
 * bar where its columns fit. JavaFX snaps the frame and the vertical scroll bar to the device pixel (at 125 %
 * the 1 px frame takes 0.8 px a side and the 12 px bar 12.8 px), and the rows are as wide as their columns
 * rounded up to the device pixel; columns fitted to a fixed 2 + 12 px were 0.4 to 0.7 px too wide for the
 * room and the table scrolled sideways. The scene is put in a stage that is never shown, only so that its
 * render scale is that of a 125 % (and a 150 %) display; at 100 % the exact fill of ProcessTableWidthTest
 * holds.
 */
@Tag("ui-snapshot")
class ProcessTableScaleTest {

    @ParameterizedTest(name = "{0} x {1} at {2}")
    @CsvSource({
        "1086, 662, 1.25",
        "1352, 730, 1.25",
        "1425, 862, 1.25",
        "1585, 462, 1.25",
        "1905, 1042, 1.25",
        "1163, 746, 1.25",
        "1280, 720, 1.5",
        "1366, 705, 1.5",
        "1352, 723, 1.0",
    })
    void whereTheColumnsFitNoHorizontalScrollBarShowsAtAFractionalScale(int width, int height, double scale) {
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 200, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            "C:\\logs",
                            identity -> {});
                    shell.lookupAll(".nav-item").stream()
                            .map(ToggleButton.class::cast)
                            .filter(entry -> entry.getText().equals("Processes"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    shell.showProcesses(ProcessListings.windows(394, 0));
                    Scene scene = new Scene(shell, width, height);
                    scene.getPreferences().setReducedMotion(true);
                    scene.getStylesheets().add(Stylesheets.app());
                    Stage stage = new Stage();
                    stage.setRenderScaleX(scale);
                    stage.setRenderScaleY(scale);
                    stage.setScene(scene);
                    ProcessesPage page =
                            (ProcessesPage) ((ScrollPane) shell.getCenter().lookup(".page-area")).getContent();
                    @SuppressWarnings("unchecked")
                    TableView<ProcessRow> table = (TableView<ProcessRow>) page.lookup(".process-table");
                    table.getSelectionModel().select(0);
                    for (int pass = 0; pass < 4; pass++) {
                        shell.applyCss();
                        shell.layout();
                    }
                    double columns = table.getColumns().stream()
                            .mapToDouble(TableColumn::getWidth)
                            .sum();
                    List<Object> facts = new ArrayList<>();
                    facts.add(scrollBar(table, Orientation.HORIZONTAL).isVisible());
                    facts.add(String.format(Locale.ROOT, "table %.2f columns %.2f", table.getWidth(), columns));
                    stage.setScene(null);
                    return facts;
                });

        assertThat(seen.get(0)).as(String.valueOf(seen.get(1))).isEqualTo(false);
    }

    /**
     * Fix round H (coordinator note on TextWidths): the columns are measured once, in logical pixels, and
     * drawn at the display scale, where the 10 px padding of a header and a cell snaps to the device pixel
     * (10.4 px a side at 125 %) and a text to the next device pixel. In the order the table opens with, no
     * header and no value of the widest kind is cut at any of these scales. (The header of the sorted column,
     * with its chevron, is the subject of the next test.)
     */
    @ParameterizedTest(name = "at {0}")
    @CsvSource({"1.0", "1.25", "1.5", "1.75"})
    void noHeaderAndNoWidestValueIsCutAtAFractionalScale(double scale) {
        List<String> cut =
                SnapshotHarness.inspect(Theme.LIGHT, 200, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            "C:\\logs",
                            identity -> {});
                    shell.lookupAll(".nav-item").stream()
                            .map(ToggleButton.class::cast)
                            .filter(entry -> entry.getText().equals("Processes"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    List<prism.srm.domain.ProcessSnapshot> listing = new ArrayList<>(ProcessListings.extreme());
                    listing.addAll(ProcessListings.withStates());
                    shell.showProcesses(listing);
                    Scene scene = new Scene(shell, 1905, 1042);
                    scene.getPreferences().setReducedMotion(true);
                    scene.getStylesheets().add(Stylesheets.app());
                    Stage stage = new Stage();
                    stage.setRenderScaleX(scale);
                    stage.setRenderScaleY(scale);
                    stage.setScene(scene);
                    ProcessesPage page =
                            (ProcessesPage) ((ScrollPane) shell.getCenter().lookup(".page-area")).getContent();
                    TableView<?> table = (TableView<?>) page.lookup(".process-table");
                    for (int pass = 0; pass < 3; pass++) {
                        shell.applyCss();
                        shell.layout();
                    }
                    List<String> found = new ArrayList<>();
                    int checked = 0;
                    java.util.Set<Node> labels = new java.util.LinkedHashSet<>(table.lookupAll(".label"));
                    labels.addAll(table.lookupAll(".table-cell"));
                    for (Node node : labels) {
                        if (node instanceof javafx.scene.control.Labeled labeled
                                && labeled.getText() != null
                                && !labeled.getText().isEmpty()
                                && labeled.isVisible()
                                && labeled.lookup(".text") instanceof javafx.scene.text.Text shown) {
                            checked++;
                            if (!shown.getText().equals(labeled.getText())) {
                                found.add("\"" + labeled.getText() + "\" shown as \"" + shown.getText() + "\"");
                            }
                        }
                    }
                    stage.setScene(null);
                    found.add(0, String.valueOf(checked));
                    return found;
                });

        // The nine headers and the values of the 3 + 8 rows were looked at.
        assertThat(Integer.parseInt(cut.get(0))).isGreaterThan(9 + 11 * 7);
        assertThat(cut.subList(1, cut.size())).isEmpty();
    }

    /**
     * Fix round H (PR-H1): the header of the column the rows are sorted by carries the 10 px chevron 2 px
     * from its text, and is cut by none of it at any of these scales, whichever column it is and in
     * either direction. "Threads" and "Priority" showed as "Thr…" and "Pr…" before: the measure had no
     * room for the chevron and the skin kept 3 px of its own for its stock sort mark. The header text
     * also stays where the cells have theirs: as far from the aligned edge as it is unsorted.
     */
    @ParameterizedTest(name = "at {0}")
    @CsvSource({"1.0", "1.25", "1.5", "1.75"})
    void theHeaderOfTheSortedColumnIsNotCutAtAnyScale(double scale) {
        List<String> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 200, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            "C:\\logs",
                            identity -> {});
                    shell.lookupAll(".nav-item").stream()
                            .map(ToggleButton.class::cast)
                            .filter(entry -> entry.getText().equals("Processes"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    shell.showProcesses(ProcessListings.windows(394, 0));
                    Scene scene = new Scene(shell, 1905, 1042);
                    scene.getPreferences().setReducedMotion(true);
                    scene.getStylesheets().add(Stylesheets.app());
                    Stage stage = new Stage();
                    stage.setRenderScaleX(scale);
                    stage.setRenderScaleY(scale);
                    stage.setScene(scene);
                    ProcessesPage page =
                            (ProcessesPage) ((ScrollPane) shell.getCenter().lookup(".page-area")).getContent();
                    @SuppressWarnings("unchecked")
                    TableView<ProcessRow> table = (TableView<ProcessRow>) page.lookup(".process-table");
                    List<String> found = new ArrayList<>();
                    for (TableColumn<ProcessRow, ?> column : table.getColumns()) {
                        double unsorted = textOffset(shell, table, column);
                        for (TableColumn.SortType direction : TableColumn.SortType.values()) {
                            column.setSortType(direction);
                            table.getSortOrder().setAll(List.of(column));
                            javafx.scene.control.Label header = header(shell, table, column);
                            javafx.scene.text.Text shown = (javafx.scene.text.Text) header.lookup(".text");
                            found.add(String.format(
                                    Locale.ROOT,
                                    "%s %s %s graphic=%b offset=%b",
                                    column.getText(),
                                    direction,
                                    shown.getText(),
                                    header.getGraphic() != null
                                            && header.getGraphic().isVisible(),
                                    Math.abs(textOffset(shell, table, column) - unsorted) < 0.01));
                        }
                    }
                    stage.setScene(null);
                    return found;
                });

        List<String> expected = new ArrayList<>();
        for (String title : List.of("Name", "PID", "CPU", "Memory", "I/O", "User", "Threads", "Priority", "State")) {
            for (TableColumn.SortType direction : TableColumn.SortType.values()) {
                expected.add(title + " " + direction + " " + title + " graphic=true offset=true");
            }
        }
        assertThat(seen).as("at %s", scale).containsExactlyElementsOf(expected);
    }

    /** The header label of a column, laid out as the table shows it now. */
    private static javafx.scene.control.Label header(
            javafx.scene.Parent shell, TableView<ProcessRow> table, TableColumn<ProcessRow, ?> column) {
        for (int pass = 0; pass < 3; pass++) {
            shell.applyCss();
            shell.layout();
        }
        for (Node node : table.lookupAll(".column-header")) {
            if (node instanceof javafx.scene.control.skin.TableColumnHeader header
                    && header.getTableColumn() == column) {
                return (javafx.scene.control.Label) header.lookup(".label");
            }
        }
        throw new IllegalStateException("no header for " + column.getText());
    }

    /**
     * How far the header text stands from the edge it is aligned to (the left of a column of text, the
     * right of a column of numbers), in the coordinates of the column header.
     */
    private static double textOffset(
            javafx.scene.Parent shell, TableView<ProcessRow> table, TableColumn<ProcessRow, ?> column) {
        javafx.scene.control.Label label = header(shell, table, column);
        Node text = label.lookup(".text");
        javafx.geometry.Bounds bounds = label.getParent().sceneToLocal(text.localToScene(text.getLayoutBounds()));
        double width = ((javafx.scene.layout.Region) label.getParent()).getWidth();
        return column.getStyleClass().contains("numeric") ? width - bounds.getMaxX() : bounds.getMinX();
    }

    /**
     * Fix round H (PR-14): in a list of hundreds of processes the thumb comes down to the least length
     * the skin allows, 1.5 times the 12 px width of the bar, and it shows all of its length: inset 3 px
     * across the bar only. With the inset on every side it was a 6 x 12 px dot in a low window.
     */
    @ParameterizedTest(name = "{0} x {1}")
    @CsvSource({"1352, 730", "705, 442", "1585, 462"})
    void theThumbOfALongListShowsItsWholeLength(int width, int height) {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            "C:\\logs",
                            identity -> {});
                    shell.lookupAll(".nav-item").stream()
                            .map(ToggleButton.class::cast)
                            .filter(entry -> entry.getText().equals("Processes"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    shell.showProcesses(ProcessListings.windows(394, 0));
                    return shell;
                },
                root -> {
                    @SuppressWarnings("unchecked")
                    TableView<ProcessRow> table = (TableView<ProcessRow>) root.lookup(".process-table");
                    javafx.scene.layout.Region thumb = (javafx.scene.layout.Region)
                            scrollBar(table, Orientation.VERTICAL).lookup(".thumb");
                    javafx.geometry.Insets insets =
                            thumb.getBackground().getFills().get(0).getInsets();
                    return List.of(
                            thumb.getWidth() - insets.getLeft() - insets.getRight(),
                            thumb.getHeight() - insets.getTop() - insets.getBottom() == thumb.getHeight(),
                            thumb.getHeight() >= 18);
                });

        assertThat(seen).containsExactly(6.0, true, true);
    }

    private static ScrollBar scrollBar(TableView<ProcessRow> table, Orientation orientation) {
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == orientation) {
                return bar;
            }
        }
        throw new IllegalStateException("no " + orientation + " scroll bar");
    }
}
