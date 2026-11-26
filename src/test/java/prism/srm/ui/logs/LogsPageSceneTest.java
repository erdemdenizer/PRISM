package prism.srm.ui.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Font;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.ui.Typefaces;
import prism.srm.ui.control.TextWidths;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The Logs page in a scene: column widths from the font, and Ctrl+C on a cut message. */
@Tag("ui-snapshot")
class LogsPageSceneTest {

    private static final Instant T = Instant.parse("2026-10-08T07:00:00Z");

    private static final List<ActivityEntry> ENTRIES = List.of(
            new ActivityEntry(T, ActivitySeverity.INFO, "Started."),
            new ActivityEntry(
                    T.plusSeconds(5),
                    ActivitySeverity.WARNING,
                    "Change priority: Access denied: java (PID 4242). The system does not let Prism act on this"
                            + " process. [OpenProcess failed: 5; code 5]"));

    @SuppressWarnings("unchecked")
    private static TableView<ActivityEntry> table(LogsPage page) {
        return (TableView<ActivityEntry>) page.lookup(".logs-table");
    }

    /** Time and Severity are as wide as their widest value or header in the table's own font, on the 4 px grid. */
    @Test
    void theFixedColumnsAreMeasuredFromTheFont() {
        List<Double> widths = SnapshotHarness.inspect(
                Theme.LIGHT, 1100, 700, () -> new LogsPage(ZoneOffset.UTC, 1000, "logs", () -> {}), root -> {
                    TableView<ActivityEntry> table = table((LogsPage) root);
                    List<Double> facts = new ArrayList<>();
                    for (TableColumn<ActivityEntry, ?> column :
                            table.getColumns().subList(0, 2)) {
                        facts.add(column.getWidth());
                    }
                    return facts;
                });

        Font body = Font.font(Typefaces.FAMILY, 13);
        Font header = Font.font(Typefaces.MEDIUM_FAMILY, 12);
        assertThat(widths)
                .containsExactly(
                        TextWidths.column(body, header, "Time", List.of("00:00:00", "88:88:88")),
                        TextWidths.column(body, header, "Severity", List.of("Info", "Warning", "Error")));
        assertThat(widths).allSatisfy(width -> assertThat(width % 4).isZero());
    }

    /**
     * As in the real window: the page joins the scene after a first layout, and its entries come after
     * that. The Message column then takes the rest of the table, not 9.2E18 px (it showed no column).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theMessageColumnTakesTheRestWhenEntriesComeAfterThePageIsShown() {
        List<Double> seen = SnapshotHarness.inspect(Theme.DARK, 1300, 700, javafx.scene.layout.StackPane::new, root -> {
            javafx.scene.layout.StackPane stack = (javafx.scene.layout.StackPane) root;
            LogsPage page = new LogsPage(ZoneOffset.UTC, 1000, "logs", () -> {});
            stack.getChildren().add(page);
            stack.applyCss();
            stack.layout();
            page.show(ENTRIES);
            stack.layout();
            TableView<ActivityEntry> table = table(page);
            double fixed = table.getColumns().get(0).getWidth()
                    + table.getColumns().get(1).getWidth();
            return List.of(table.getColumns().get(2).getWidth(), table.getWidth() - fixed);
        });

        assertThat(seen.get(0)).isGreaterThan(400).isLessThanOrEqualTo(seen.get(1));
    }

    /** A message cut in its column is not lost to the keyboard: Ctrl+C copies the selected entry whole. */
    @Test
    void ctrlCCopiesTheSelectedEntryWhole() {
        List<String> copied = new ArrayList<>();
        String seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                720,
                480,
                () -> {
                    LogsPage page = new LogsPage(ZoneOffset.UTC, 1000, "logs", () -> {});
                    page.copyTo(copied::add);
                    page.show(ENTRIES);
                    return page;
                },
                root -> {
                    TableView<ActivityEntry> table = table((LogsPage) root);
                    table.getSelectionModel().select(0);
                    Event.fireEvent(
                            table, new KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, true, false, true));
                    Event.fireEvent(
                            table, new KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, false, false, false));
                    return String.join("|", copied);
                });

        assertThat(seen)
                .as("newest first; once, for the key with Ctrl only")
                .isEqualTo(
                        "2026-10-08 07:00:05 Warning Change priority: Access denied: java (PID 4242). The system does not let"
                                + " Prism act on this process. [OpenProcess failed: 5; code 5]");
    }

    private static final String OOM_SETTINGS = "OOM Guard settings changed: monitoring on, warning 15 %, critical 8 %,"
            + " emergency 5 % or under 256 MiB; automatic priority reduction off, automatic suspend off, emergency"
            + " termination off.";

    private static final String SETTINGS_PATH =
            "Settings read from C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\settings"
                    + "\\prism.settings";

    /** Real-like entries: the OOM Guard settings line and a path, both longer than a 720 px window. */
    private static final List<ActivityEntry> LONG = List.of(
            new ActivityEntry(T, ActivitySeverity.INFO, SETTINGS_PATH),
            new ActivityEntry(T.plusSeconds(1), ActivitySeverity.INFO, "Monitoring started: system metrics every 1 s."),
            new ActivityEntry(T.plusSeconds(9), ActivitySeverity.INFO, OOM_SETTINGS));

    private static LogsPage page(List<ActivityEntry> entries, List<String> copied) {
        LogsPage page = new LogsPage(ZoneOffset.UTC, 1000, "logs", () -> {});
        page.copyTo(copied::add);
        page.show(entries);
        return page;
    }

    /** K3, LS-01, LS-12: the selected entry is shown whole under the table, with its date, by mouse and arrows. */
    @Test
    void theDetailPaneShowsTheSelectedEntryWhole() {
        List<String> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 720, 480, () -> page(LONG, new ArrayList<>()), root -> {
                    TableView<ActivityEntry> table = table((LogsPage) root);
                    TextArea message = (TextArea) root.lookup(".logs-detail-message");
                    Label severity = (Label) root.lookup(".logs-detail-severity");
                    Label time = (Label) root.lookup(".logs-detail-time");
                    List<String> facts = new ArrayList<>();
                    table.requestFocus();
                    table.getSelectionModel().select(0);
                    facts.add(message.getText());
                    facts.add(severity.getText());
                    facts.add(time.getText());
                    facts.add(String.valueOf(message.isEditable()));
                    facts.add(String.valueOf(message.isWrapText()));
                    Event.fireEvent(
                            table,
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                    facts.add(message.getText());
                    facts.add(time.getText());
                    return facts;
                });

        assertThat(seen)
                .containsExactly(
                        OOM_SETTINGS,
                        "Info",
                        "2026-10-08 07:00:09",
                        "false",
                        "true",
                        "Monitoring started: system metrics every 1 s.",
                        "2026-10-08 07:00:01");
    }

    /** With nothing selected the pane says how to fill it, and Copy is disabled but there (CAP-2). */
    @Test
    void withoutASelectionThePaneSaysHowToFillIt() {
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 720, 480, () -> page(LONG, new ArrayList<>()), root -> {
                    Label hint = (Label) root.lookup(".logs-detail-hint");
                    Button copy = (Button) root.lookup(".logs-copy");
                    return List.of(
                            hint.getText(),
                            hint.isVisible(),
                            root.lookup(".logs-detail-message").isVisible(),
                            copy.isVisible(),
                            copy.isDisabled());
                });

        assertThat(seen).containsExactly(LogsViewModel.NO_SELECTION, true, false, true, true);
    }

    /** The Copy button and the context menu put on the clipboard exactly what Ctrl+C puts there. */
    @Test
    void copyAndTheContextMenuCopyWhatCtrlCCopies() {
        List<String> copied = new ArrayList<>();
        SnapshotHarness.inspect(Theme.LIGHT, 720, 480, () -> page(LONG, copied), root -> {
            TableView<ActivityEntry> table = table((LogsPage) root);
            table.getSelectionModel().select(0);
            Event.fireEvent(table, new KeyEvent(KeyEvent.KEY_PRESSED, "c", "c", KeyCode.C, false, true, false, true));
            ((Button) root.lookup(".logs-copy")).fire();
            MenuItem item = table.getContextMenu().getItems().get(0);
            item.fire();
            return null;
        });

        String expected = "2026-10-08 07:00:09 Info " + OOM_SETTINGS;
        assertThat(copied).containsExactly(expected, expected, expected);
    }

    /** LS-09: a new entry keeps the selection and the detail pane on the entry being read. */
    @Test
    void aNewEntryKeepsTheSelectedEntry() {
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 720, 480, () -> page(LONG, new ArrayList<>()), root -> {
                    LogsPage logs = (LogsPage) root;
                    TableView<ActivityEntry> table = table(logs);
                    TextArea message = (TextArea) root.lookup(".logs-detail-message");
                    table.getSelectionModel().select(1);
                    List<ActivityEntry> more = new ArrayList<>(LONG);
                    more.add(new ActivityEntry(T.plusSeconds(30), ActivitySeverity.WARNING, "A new entry."));
                    logs.show(more);
                    return List.of(
                            table.getSelectionModel().getSelectedIndex(),
                            table.getSelectionModel().getSelectedItem().message(),
                            message.getText());
                });

        assertThat(seen)
                .containsExactly(
                        2,
                        "Monitoring started: system metrics every 1 s.",
                        "Monitoring started: system metrics every 1 s.");
    }

    /**
     * LS-09: in a table scrolled away from its top, a new entry at the head of the log does not push
     * the row being read down; the first row drawn stays the same entry.
     */
    @Test
    void aNewEntryDoesNotMoveARowBeingReadInAScrolledTable() {
        List<ActivityEntry> many = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            many.add(new ActivityEntry(T.plusSeconds(index), ActivitySeverity.INFO, "Entry " + index + "."));
        }
        List<String> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 720, 480, () -> page(many, new ArrayList<>()), root -> {
                    LogsPage logs = (LogsPage) root;
                    TableView<ActivityEntry> table = table(logs);
                    table.scrollTo(40);
                    root.applyCss();
                    root.layout();
                    javafx.scene.control.skin.VirtualFlow<?> flow =
                            (javafx.scene.control.skin.VirtualFlow<?>) table.lookup(".virtual-flow");
                    String before = table.getItems()
                            .get(flow.getFirstVisibleCell().getIndex())
                            .message();
                    List<ActivityEntry> more = new ArrayList<>(many);
                    more.add(new ActivityEntry(T.plusSeconds(500), ActivitySeverity.INFO, "A new entry."));
                    logs.show(more);
                    root.applyCss();
                    root.layout();
                    String after = table.getItems()
                            .get(flow.getFirstVisibleCell().getIndex())
                            .message();
                    return List.of(before, after);
                });

        assertThat(seen.get(1)).isEqualTo(seen.get(0)).isEqualTo("Entry 59.");
    }

    /** No cell drawn cut with an ellipsis is without the tooltip that holds its text whole. */
    @Test
    void noCutCellIsWithoutItsTooltip() {
        List<String> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 706, 435, () -> page(LONG, new ArrayList<>()), root -> {
                    List<String> facts = new ArrayList<>();
                    int cut = 0;
                    for (javafx.scene.Node node : root.lookupAll(".table-cell")) {
                        javafx.scene.control.TableCell<?, ?> cell = (javafx.scene.control.TableCell<?, ?>) node;
                        // Cut is what is drawn: the text node of the cell holds less than the cell's text.
                        javafx.scene.text.Text drawn = (javafx.scene.text.Text) cell.lookup(".text");
                        if (cell.getText() != null
                                && drawn != null
                                && !cell.getText().equals(drawn.getText())) {
                            cut++;
                            if (cell.getTooltip() == null
                                    || !cell.getText().equals(cell.getTooltip().getText())) {
                                facts.add(cell.getText());
                            }
                        }
                    }
                    facts.add("cut " + cut);
                    return facts;
                });

        assertThat(seen)
                .as("the two long messages are cut at 706 px, and each has its tooltip")
                .containsExactly("cut 2");
    }

    /** Every Time and Severity cell drawn, and their headers, show their text whole. */
    @Test
    void theTimeAndSeverityCellsAreNotCut() {
        List<String> cut = SnapshotHarness.inspect(
                Theme.LIGHT,
                720,
                480,
                () -> {
                    LogsPage page = new LogsPage(ZoneOffset.UTC, 1000, "logs", () -> {});
                    page.show(ENTRIES);
                    return page;
                },
                root -> {
                    List<String> facts = new ArrayList<>();
                    int[] checked = {0};
                    for (javafx.scene.Node node : root.lookupAll(".table-cell")) {
                        javafx.scene.control.TableCell<?, ?> cell = (javafx.scene.control.TableCell<?, ?>) node;
                        boolean fixed = cell.getTableColumn() != null
                                && List.of("Time", "Severity")
                                        .contains(cell.getTableColumn().getText());
                        // What is drawn: a table cell reports its column's width as its preferred width.
                        javafx.scene.text.Text drawn = (javafx.scene.text.Text) cell.lookup(".text");
                        if (fixed && cell.getText() != null) {
                            checked[0]++;
                            if (drawn == null || !cell.getText().equals(drawn.getText())) {
                                facts.add(cell.getText());
                            }
                        }
                    }
                    facts.add("checked " + (checked[0] > 0));
                    return facts;
                });

        assertThat(cut).as("cells were checked, none is cut").containsExactly("checked true");
    }

    @Test
    void aWiderFontGivesAWiderColumnAndEveryWidthIsOnTheGrid() {
        List<Double> widths =
                SnapshotHarness.inspect(Theme.LIGHT, 200, 100, () -> new javafx.scene.layout.Pane(), root -> {
                    Font small = Font.font(Typefaces.FAMILY, 13);
                    Font large = Font.font(Typefaces.FAMILY, 20);
                    return List.of(
                            TextWidths.column(small, small, "T", List.of("Warning")),
                            TextWidths.column(large, large, "T", List.of("Warning")),
                            TextWidths.column(small, small, "A much longer header", List.of("Info")));
                });

        assertThat(widths.get(1)).isGreaterThan(widths.get(0));
        assertThat(widths.get(2)).isGreaterThan(widths.get(0));
        assertThat(widths).allSatisfy(width -> assertThat(width % 4).isZero());
    }
}
