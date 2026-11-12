package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.ui.Breakpoints;

/**
 * F14: the Processes and the Limits page put their inspector in the same place for the same window
 * ({@link Breakpoints#inspectorFor}), on both sides of every breakpoint; where it is docked, the page
 * does not scroll sideways and neither table does. Fix round G: the place follows the room of the page,
 * not the window (the navigation beside it may be wide or narrow), so the expected place is read from
 * the size of each page on screen, and both pages have the same. Each page is read from what is on screen, not from
 * its own record of the mode: a sheet has its "Details" button in the title line, a side inspector
 * stands to the right of the table, one under the table below it.
 */
@Tag("ui-snapshot")
class BreakpointAgreementSceneTest {

    @ParameterizedTest(name = "{0} x {1}")
    @CsvSource({
        "704, 441",
        "819, 600",
        "820, 600",
        "1099, 700",
        "1100, 559",
        "1100, 560",
        "1100, 700",
        "1319, 900",
        "1320, 400",
        "1320, 800",
        "1600, 500",
        "1920, 1080",
        // K8: scenes of real Windows outer windows (1366 x 768, 1440 x 900, 1600 x 500, 1920 x 1032),
        // and both sides of the line from which the navigation has names (K1, LABELLED_NAV_FROM).
        "1352, 723",
        "1426, 855",
        "1459, 800",
        "1460, 800",
        "1586, 455",
        "1906, 987"
    })
    void bothPagesPutTheirInspectorInTheSamePlace(int width, int height) {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(width, height, shell -> {
            KeyboardRoutesSceneTest.sidebar(shell).select(Destination.PROCESSES);
            settle(shell);
            Breakpoints.Inspector processes = where(
                    shell,
                    shell.getCenter().lookup(".process-details-open"),
                    shell.pageArea().getContent().lookup(".process-details"),
                    shell.pageArea().getContent().lookup(".process-table"));
            boolean processesScrollSideways = scrollsSideways(shell, ".process-table");
            javafx.scene.layout.Region processesPage =
                    (javafx.scene.layout.Region) shell.pageArea().getContent();
            Breakpoints.Inspector expected =
                    Breakpoints.inspectorFor(processesPage.getWidth(), processesPage.getHeight());

            KeyboardRoutesSceneTest.sidebar(shell).select(Destination.LIMITS);
            settle(shell);
            Node limitsDetails = shell.getCenter().lookupAll(".limits-details").stream()
                    .findFirst()
                    .orElseThrow();
            Breakpoints.Inspector limits = where(
                    shell,
                    limitsDetails,
                    shell.pageArea().getContent().lookup(".limit-editor"),
                    shell.pageArea().getContent().lookup(".limits-table"));
            boolean limitsScrollSideways = scrollsSideways(shell, ".limits-table");
            javafx.scene.layout.Region limitsPage =
                    (javafx.scene.layout.Region) shell.pageArea().getContent();
            Breakpoints.Inspector limitsExpected =
                    Breakpoints.inspectorFor(limitsPage.getWidth(), limitsPage.getHeight());
            return List.of(processes, limits, processesScrollSideways, limitsScrollSideways, expected, limitsExpected);
        });

        Breakpoints.Inspector expected = (Breakpoints.Inspector) seen.get(4);
        assertThat(seen.get(5)).as("the same room on both pages").isEqualTo(expected);
        assertThat(seen.subList(0, 2)).as("Processes, Limits").containsExactly(expected, expected);
        if (expected != Breakpoints.Inspector.SHEET) {
            assertThat(seen.subList(2, 4))
                    .as("docked: the page area and the tables do not scroll sideways (Processes, Limits)")
                    .containsExactly(false, false);
        }
    }

    /**
     * Fix round G (LO-23, PR-07): in a tall narrow window (900 x 1200) both pages dock their inspector
     * under the table, stacked, instead of hiding it behind a sheet over 800 px of empty table; in a wide
     * window that is not high enough for the panel under the table, both keep the sheet.
     */
    @org.junit.jupiter.api.Test
    void aTallNarrowWindowDocksBothInspectorsUnderTheTable() {
        for (int[] window : new int[][] {{900, 1200, 1}, {1086, 520, 0}}) {
            List<Object> seen = KeyboardRoutesSceneTest.inShell(window[0], window[1], shell -> {
                KeyboardRoutesSceneTest.sidebar(shell).select(Destination.PROCESSES);
                settle(shell);
                Breakpoints.Inspector processes = where(
                        shell,
                        shell.getCenter().lookup(".process-details-open"),
                        shell.pageArea().getContent().lookup(".process-details"),
                        shell.pageArea().getContent().lookup(".process-table"));
                KeyboardRoutesSceneTest.sidebar(shell).select(Destination.LIMITS);
                settle(shell);
                Breakpoints.Inspector limits = where(
                        shell,
                        shell.getCenter().lookupAll(".limits-details").stream()
                                .findFirst()
                                .orElseThrow(),
                        shell.pageArea().getContent().lookup(".limit-editor"),
                        shell.pageArea().getContent().lookup(".limits-table"));
                return List.of(processes, limits);
            });
            Breakpoints.Inspector expected =
                    window[2] == 1 ? Breakpoints.Inspector.BOTTOM : Breakpoints.Inspector.SHEET;
            assertThat(seen).as("%d x %d", window[0], window[1]).containsExactly(expected, expected);
        }
    }

    /** Where the inspector of a page is, as the screen shows it. */
    private static Breakpoints.Inspector where(Shell shell, Node opener, Node inspector, Node table) {
        if (opener.isVisible() && opener.getScene() != null) {
            return Breakpoints.Inspector.SHEET;
        }
        var panel = inspector.localToScene(inspector.getLayoutBounds());
        var rows = table.localToScene(table.getLayoutBounds());
        if (panel.getMinX() >= rows.getMaxX() - 0.5) {
            return Breakpoints.Inspector.SIDE;
        }
        return panel.getMinY() >= rows.getMaxY() - 0.5 ? Breakpoints.Inspector.BOTTOM : null;
    }

    /** Whether the page area or the table of the page on screen shows a horizontal scroll bar. */
    private static boolean scrollsSideways(Shell shell, String table) {
        ScrollPane area = shell.pageArea();
        double content = area.getContent().getLayoutBounds().getWidth();
        boolean areaScrolls = content > area.getViewportBounds().getWidth() + 0.5;
        TableView<?> rows = (TableView<?>) area.getContent().lookup(table);
        boolean tableScrolls = rows.lookupAll(".scroll-bar").stream()
                .map(ScrollBar.class::cast)
                .anyMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible());
        return areaScrolls || tableScrolls;
    }

    private static void settle(Shell shell) {
        shell.applyCss();
        shell.layout();
        shell.applyCss();
        shell.layout();
    }
}
