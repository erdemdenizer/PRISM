package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.shell.Shell;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * How the columns share the width of the table, read from real scenes of the whole shell at the window
 * sizes of docs/UI_GUIDE.md and docs/ui-lab/LAB_DESIGN_SPEC.md (section 7): the name column takes the room
 * that is left (UI lab v2: with no ceiling), the user column follows the names on screen between a floor
 * and a ceiling, and the rest keep the widths the font gives them.
 *
 * <p>UI lab v2: the page is measured in the real shell, not alone at a computed width. The rail
 * (Breakpoints.RAIL_WIDTH, its hairline included) stands at the left of every window below the wide one,
 * the sidebar from Breakpoints.LABELLED_NAV_FROM; the page
 * padding is 12, 16 or 24 px a side by the width; and where the inspector stands (beside the table from
 * 1320, under it from 1100 where the window is at least 560 px high, a sheet elsewhere: Breakpoints)
 * depends on the size of the window, not of the page.
 * The claim at every size is the same: the columns that must fit do so without scrolling sideways,
 * neither of the table nor of the page area.
 */
@Tag("ui-snapshot")
class ProcessTableWidthTest {

    private static final String LOG_FOLDER = "C:\\logs";

    /** The frame of the table and the room of its vertical scroll bar (ProcessTable). */
    private static final double FRAME_AND_SCROLL_BAR = 14;

    @Test
    void inTheDefaultWindowEveryColumnFitsAndTheNameColumnTakesTheRoomThatIsLeft() {
        List<Object> seen = inWindow(
                1100,
                700,
                ProcessListings.windows(394, 0),
                (table, page) -> List.of(
                        scrollBar(table, Orientation.HORIZONTAL).isVisible(),
                        width(table, "Name") > ColumnWidths.NAME_MIN,
                        columnsWidth(table) + FRAME_AND_SCROLL_BAR == table.getWidth(),
                        pageScrollsSideways(page)));

        assertThat(seen).containsExactly(false, true, true, false);
    }

    /**
     * UI lab v2: in a wide window, with the inspector beside the table, the name column takes the
     * room left up to its ceiling and State takes the rest, so no empty band stays after the last
     * column and the numbers stay near the names (the name took all the room before; critique M5).
     */
    @Test
    void inAWideWindowTheNameColumnTakesTheRoomLeftUpToItsCeilingAndNoBandStaysEmptyAfterTheLastColumn() {
        for (int[] window : new int[][] {{1600, 900}, {1920, 1080}, {3440, 1440}}) {
            List<Object> seen = inWindow(
                    window[0],
                    window[1],
                    ProcessListings.windows(394, 0),
                    (table, page) -> List.of(
                            width(table, "Name") > ColumnWidths.NAME_MIN,
                            width(table, "Name") <= ColumnWidths.NAME_MAX,
                            width(table, "User") <= ColumnWidths.USER_MAX,
                            columnsWidth(table) + FRAME_AND_SCROLL_BAR == table.getWidth(),
                            scrollBar(table, Orientation.HORIZONTAL).isVisible(),
                            pageScrollsSideways(page)));

            assertThat(seen).as("window %d", window[0]).containsExactly(true, true, true, true, false, false);
        }
        // In the widest window the name is at its ceiling and State has the rest.
        List<Object> widest = inWindow(
                3440,
                1440,
                ProcessListings.windows(394, 0),
                (table, page) -> List.of(width(table, "Name") == ColumnWidths.NAME_MAX, width(table, "State") > 100));
        assertThat(widest).containsExactly(true, true);
    }

    /**
     * The common laptop size, 1366 x 768: the inspector beside the table and every column whole, with
     * no scrolling sideways, neither of the table nor of the page.
     */
    @Test
    void at1366EveryColumnFitsBesideTheInspectorWithoutScrollingSideways() {
        assertEveryColumnFitsBesideTheInspector(1366, 768);
    }

    /**
     * 1280 x 800. The table keeps the width its columns need (866 px) and the inspector its least (320
     * px), so beside each other, with the 12 px divider and 2 x 24 px padding, the page would need 1246
     * px of the 1220 the page area has. UI lab v2: the inspector stands beside the table only from 1320
     * px (Breakpoints, measured from this), so at 1280 it is under the table, and every column fits
     * without scrolling sideways.
     */
    @Test
    void at1280EveryColumnFitsWithTheInspectorUnderTheTable() {
        List<Object> seen = inWindow(
                1280,
                800,
                ProcessListings.windows(394, 0),
                (table, page) -> List.of(
                        page.mode(),
                        scrollBar(table, Orientation.HORIZONTAL).isVisible(),
                        columnsWidth(table) + FRAME_AND_SCROLL_BAR <= table.getWidth(),
                        pageScrollsSideways(page)));

        assertThat(seen).containsExactly(Breakpoints.Inspector.BOTTOM, false, true, false);
    }

    /**
     * The narrowest window with the inspector beside the table, the rail with names beside the page
     * (Breakpoints.SIDE_FROM and RAIL_WIDTH, 1340 x 800): every column whole.
     */
    @Test
    void atTheNarrowestSideInspectorEveryColumnFitsWithoutScrollingSideways() {
        assertEveryColumnFitsBesideTheInspector((int) (Breakpoints.SIDE_FROM + Breakpoints.RAIL_WIDTH), 800);
    }

    /**
     * In the smallest window (704 x 441 inside the frame: no rail, 12 px padding, the inspector a sheet)
     * the first four columns are whole and the table, not the page, scrolls sideways.
     */
    @Test
    void inTheSmallestWindowTheFirstFourColumnsFitAndTheTableScrollsSideways() {
        List<Object> seen = inWindow(704, 441, ProcessListings.windows(394, 0), (table, page) -> {
            double firstFour = 0;
            for (TableColumn<ProcessRow, ?> column : table.getColumns().subList(0, 4)) {
                firstFour += column.getWidth();
            }
            return List.of(
                    scrollBar(table, Orientation.HORIZONTAL).isVisible(),
                    firstFour <= table.getWidth() - FRAME_AND_SCROLL_BAR,
                    width(table, "Name") == ColumnWidths.NAME_MIN,
                    pageScrollsSideways(page));
        });

        assertThat(seen).containsExactly(true, true, true, false);
    }

    /**
     * Course release, finding L-1 of the UI lab's Linux test: with the long user names of a Linux desktop the
     * user column is at its ceiling. Beside the inspector the page must still be able to become narrower than
     * Breakpoints.SIDE_FROM, so that a narrower window takes the inspector from beside the table, and the area
     * of the page never scrolls sideways (L-2): the table scrolls itself where its columns do not fit.
     */
    @Test
    void withLongUserNamesThePageBesideTheInspectorCanStillBecomeNarrowerThanTheSideWidth() {
        List<ProcessSnapshot> listing = new java.util.ArrayList<>();
        String[] users = {"cups-browsed", "systemd-timesync", "NT AUTHORITY\\NETWORK SERVICE", "messagebus", "prism"};
        for (int index = 0; index < 120; index++) {
            listing.add(ProcessListings.process(4L * (index + 1), "process-" + index)
                    .user(users[index % users.length])
                    .build());
        }
        for (int[] size : new int[][] {{1366, 768}, {1600, 900}}) {
            List<Object> seen = inWindow(
                    size[0],
                    size[1],
                    listing,
                    (table, page) -> List.of(
                            page.mode(),
                            width(table, "User") == ColumnWidths.USER_MAX,
                            page.minWidth(-1) < Breakpoints.SIDE_FROM,
                            inspectorWidth(page) >= ProcessesPage.INSPECTOR_MIN,
                            pageScrollsSideways(page)));

            assertThat(seen)
                    .as("window %d x %d", size[0], size[1])
                    .containsExactly(Breakpoints.Inspector.SIDE, true, true, true, false);
        }
    }

    private static void assertEveryColumnFitsBesideTheInspector(int windowWidth, int windowHeight) {
        List<Object> seen = inWindow(
                windowWidth,
                windowHeight,
                ProcessListings.windows(394, 0),
                (table, page) -> List.of(
                        page.mode(),
                        inspectorWidth(page) >= ProcessesPage.INSPECTOR_MIN,
                        scrollBar(table, Orientation.HORIZONTAL).isVisible(),
                        columnsWidth(table) + FRAME_AND_SCROLL_BAR <= table.getWidth(),
                        pageScrollsSideways(page)));

        assertThat(seen)
                .as("window %d x %d", windowWidth, windowHeight)
                .containsExactly(Breakpoints.Inspector.SIDE, true, false, true, false);
    }

    /** The user column is as wide as the widest user on screen needs, within its bounds. */
    @Test
    void theUserColumnFollowsTheWidestUserNameOnScreenBetweenItsFloorAndItsCeiling() {
        List<Double> seen = new ArrayList<>();
        for (String user : new String[] {"ED", "Administrator", "NT AUTHORITY\\SYSTEM"}) {
            List<ProcessSnapshot> listing = List.of(
                    ProcessListings.process(100, "a.exe").user(user).build(),
                    ProcessListings.process(101, "b.exe").user("ED").build());
            seen.add(inWindow(1100, 700, listing, (table, page) -> width(table, "User")));
        }

        // "ED" alone takes the floor; "Administrator" more; a long name stops at the ceiling.
        assertThat(seen.get(0)).isGreaterThanOrEqualTo(64).isLessThan(seen.get(1));
        assertThat(seen.get(1)).isLessThan(seen.get(2));
        assertThat(seen.get(2)).isEqualTo(ColumnWidths.USER_MAX);
    }

    /**
     * On one page, listing after listing: rows that come and go with the same users move no
     * column; a wider user name widens the user column and narrows only the name column, down to its
     * floor, beyond which the table scrolls sideways; without it the column is back at its floor; a
     * listing of unknown users is at the floor too.
     */
    @Test
    void listingsMoveTheUserColumnOnlyWhenTheWidestUserNameChanges() {
        List<Object> seen = inWindow(1100, 700, ProcessListings.windows(394, 0), (table, page) -> {
            List<Object> facts = new ArrayList<>();
            List<Double> first = widths(table);
            show(table, ProcessListings.windows(120, 3));
            facts.add(widths(table).equals(first));
            show(
                    table,
                    List.of(
                            ProcessListings.process(100, "a.exe")
                                    .user("Administrator")
                                    .build(),
                            ProcessListings.process(101, "b.exe").user("SYSTEM").build()));
            List<Double> wider = widths(table);
            facts.add(width(table, "User") > first.get(5));
            facts.add(width(table, "Name") < first.get(0));
            // The fixed columns stay and the user column takes its room from the name, down to the
            // name's floor. Threads is wider since critique M9 ("123,456" fits), so at this width
            // the name reaches its floor and the table scrolls sideways by the rest; State is then
            // only ever widened, so that a column begins at the left edge scrolled to the end.
            facts.add(wider.subList(1, 5).equals(first.subList(1, 5))
                    && wider.subList(6, 8).equals(first.subList(6, 8))
                    && wider.get(0) == Math.max(ColumnWidths.NAME_MIN, first.get(0) - (wider.get(5) - first.get(5)))
                    && wider.get(8) >= first.get(8));
            show(table, ProcessListings.windows(50, 1));
            facts.add(widths(table).equals(first));
            show(
                    table,
                    List.of(ProcessListings.process(100, "a.exe").nothingElse().build()));
            facts.add(width(table, "User") == first.get(5));
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true, true, true);
    }

    /** The widths were measured with the font the cells draw with, so no value of a fixed column is cut. */
    @Test
    void theCellsDrawWithTheFontTheWidthsWereMeasuredWithAndNoValueOfAFixedColumnIsCut() {
        List<Object> seen = inWindow(1100, 700, ProcessListings.extreme(), (table, page) -> {
            List<Object> facts = new ArrayList<>();
            javafx.scene.control.TableCell<?, ?> cell =
                    (javafx.scene.control.TableCell<?, ?>) table.lookup(".table-cell");
            facts.add(cell.getFont().getFamily() + " " + cell.getFont().getSize());
            javafx.scene.control.Label header = (javafx.scene.control.Label) table.lookup(".column-header .label");
            facts.add(header.getFont().getFamily() + " " + header.getFont().getSize());
            int cut = 0;
            for (Node node : table.lookupAll(".table-cell")) {
                javafx.scene.control.TableCell<?, ?> each = (javafx.scene.control.TableCell<?, ?>) node;
                TableColumn<?, ?> column = each.getTableColumn();
                if (column == null
                        || column.getText().equals("Name")
                        || column.getText().equals("User")) {
                    continue;
                }
                if (each.prefWidth(-1) > column.getWidth() + 0.5) {
                    cut++;
                }
            }
            facts.add(cut);
            // A unit has a part of a fixed width; the label itself is cut when the part is too narrow.
            int unitsCut = 0;
            for (Node node : table.lookupAll(".quantity-unit")) {
                javafx.scene.control.Label unit = (javafx.scene.control.Label) node;
                javafx.scene.text.Text drawn = (javafx.scene.text.Text) unit.lookup(".text");
                if (drawn != null && !drawn.getText().equals(unit.getText())) {
                    unitsCut++;
                }
            }
            facts.add(unitsCut);
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        prism.srm.ui.Typefaces.FAMILY + " 13.0", prism.srm.ui.Typefaces.MEDIUM_FAMILY + " 12.0", 0, 0);
    }

    private static List<Double> widths(TableView<ProcessRow> table) {
        List<Double> widths = new ArrayList<>();
        for (TableColumn<ProcessRow, ?> column : table.getColumns()) {
            widths.add(column.getWidth());
        }
        return widths;
    }

    private static void show(TableView<ProcessRow> table, List<ProcessSnapshot> listing) {
        javafx.scene.Parent at = table.getParent();
        while (!(at instanceof ProcessesPage)) {
            at = at.getParent();
        }
        ProcessesPage page = (ProcessesPage) at;
        page.show(listing);
        page.applyCss();
        page.layout();
    }

    private static double width(TableView<ProcessRow> table, String title) {
        return table.getColumns().stream()
                .filter(column -> column.getText().equals(title))
                .findFirst()
                .orElseThrow()
                .getWidth();
    }

    private static double columnsWidth(TableView<ProcessRow> table) {
        return table.getColumns().stream().mapToDouble(TableColumn::getWidth).sum();
    }

    private static ScrollBar scrollBar(TableView<ProcessRow> table, Orientation orientation) {
        for (Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == orientation) {
                return bar;
            }
        }
        throw new IllegalStateException("no " + orientation + " scroll bar");
    }

    /** Whether the page is wider than the area of the shell that shows it, which then scrolls sideways. */
    private static boolean pageScrollsSideways(ProcessesPage page) {
        Node at = page.getParent();
        while (!(at instanceof ScrollPane)) {
            at = at.getParent();
        }
        return page.getWidth() > ((ScrollPane) at).getViewportBounds().getWidth();
    }

    private static double inspectorWidth(ProcessesPage page) {
        Node inspector = page.lookup(".process-inspector");
        return inspector instanceof Region region && region.getScene() != null ? region.getWidth() : 0;
    }

    /**
     * The whole shell in a window of this size, the Processes page on screen with the listing and its
     * first row selected, as the page is used.
     */
    @SuppressWarnings("unchecked")
    private static <T> T inWindow(
            int width,
            int height,
            List<ProcessSnapshot> listing,
            BiFunction<TableView<ProcessRow>, ProcessesPage, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            LOG_FOLDER,
                            identity -> {});
                    shell.lookupAll(".nav-item").stream()
                            .map(ToggleButton.class::cast)
                            .filter(entry -> entry.getText().equals("Processes"))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    shell.showProcesses(listing);
                    return shell;
                },
                root -> {
                    Shell shell = (Shell) root;
                    // UI lab v2: the page area is under the page toolbar in the centre of the shell.
                    ProcessesPage page =
                            (ProcessesPage) ((ScrollPane) shell.getCenter().lookup(".page-area")).getContent();
                    TableView<ProcessRow> table = (TableView<ProcessRow>) page.lookup(".process-table");
                    table.getSelectionModel().select(0);
                    for (int pass = 0; pass < 3; pass++) {
                        shell.applyCss();
                        shell.layout();
                    }
                    return scenario.apply(table, page);
                });
    }
}
