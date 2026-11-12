package prism.srm.ui.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;
import static prism.srm.ui.LimitsFixtures.managed;
import static prism.srm.ui.LimitsFixtures.snapshot;
import static prism.srm.ui.LimitsFixtures.throttling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The panel of the Limits page (UI lab v2): beside the table from {@link Breakpoints#SIDE_FROM}, under it
 * with its groups in columns from 1100 px where the window is high enough, a sheet over the page
 * elsewhere (the rule of the Processes page, {@link Breakpoints#inspectorFor}). The sheet opens by
 * Enter, a double click or its button, keeps the keyboard while it is open and gives it back to what
 * opened it. The harness renders with reduced motion, so the sheet opens and closes at once.
 */
@Tag("ui-snapshot")
class LimitsPanelSceneTest {

    @Test
    void inANarrowWindowTheSheetOpensOnEnterTabGoesRoundItAndEscapeClosesIt() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 720, 480, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            Node panel = page.lookup(".limit-editor");
            TableView<?> table = table(page);
            List<Object> facts = new ArrayList<>();
            facts.add(page.mode());
            facts.add(page.sheet().isOpen());
            facts.add(button(page, "Details and actions").isVisible());
            boolean panelShownBefore = panel != null && panel.isVisible();

            table.getSelectionModel().select(0);
            table.requestFocus();
            press(table, KeyCode.ENTER, false);
            settle(page);
            facts.add(page.sheet().isOpen());
            // The panel goes into the sheet when the sheet opens (round G, the resize measurement): before,
            // it is hidden in the page's layout (fix round H: it stays a child there, so a change of mode
            // moves no node), and the sheet holds it once open.
            facts.add(panel != null && !panelShownBefore && page.sheet().holds(page.lookup(".limit-editor")));
            facts.add(page.sheet().lookup(".sheet-close").isVisible());
            facts.add(page.sheet().holds(page.getScene().getFocusOwner()));

            // F8: Tab visits every enabled control of the sheet in order and wraps; Shift+Tab the reverse
            // order. A memory limit is not offered with an emulated CPU limit here: its button is disabled.
            List<Node> forward = walk(page.getScene(), false);
            List<Node> backward = walk(page.getScene(), true);
            facts.add(names(forward));
            facts.add(backward.equals(reversedAfterFirst(forward)));

            press(page.getScene().getFocusOwner(), KeyCode.ESCAPE, false);
            facts.add(page.sheet().isOpen());
            facts.add(page.getScene().getFocusOwner() == table);
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        Breakpoints.Inspector.SHEET,
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        List.of("Close details", "Set CPU limit…", "Remove CPU limit", "Disable", "Unmanage"),
                        true,
                        false,
                        true);
    }

    /**
     * Details opens the sheet without a selection: the actions are there, disabled, and their reasons
     * stand in a line under them, not only in a tooltip (CAP-2, A-M4). × gives the focus back to Details.
     */
    @Test
    void theButtonOfTheHeaderOpensTheSheetAndTheCloseButtonGivesTheFocusBackToIt() {
        List<Object> seen = SnapshotHarness.inspect(Theme.DARK, 800, 600, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            Button details = button(page, "Details and actions");
            List<Object> facts = new ArrayList<>();
            details.requestFocus();
            details.fire();
            settle(page);
            facts.add(page.sheet().isOpen());
            Button edit = button(page, "Set CPU limit…");
            facts.add(edit.isDisabled());
            Labeled reasons = (Labeled) page.lookup(".limit-editor-reasons");
            facts.add(reasons.isVisible() && !reasons.getText().isBlank());
            facts.add(edit.getAccessibleHelp() != null && reasons.getText().contains(edit.getAccessibleHelp()));
            ((Button) page.sheet().lookup(".sheet-close")).fire();
            settle(page);
            facts.add(page.sheet().isOpen());
            facts.add(page.getScene().getFocusOwner() == details);
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true, false, true);
    }

    /**
     * From 1100 px and 560 px high the panel is under the table, its three groups in columns;
     * every column has its least width and the table does not scroll sideways. UI lab v2 change: at
     * 1100 x 700 the panel was beside the table, which left the table too narrow for its columns. Fix
     * round G (LO-01, LO-02, K2): the columns are the three groups only, pinned at the top of the panel;
     * the facts stand under them in the part that scrolls, as wide as the panel.
     */
    @Test
    void underTheTableEveryColumnOfThePanelAndOfTheTableIsInView() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            Region panel = (Region) page.lookup(".limit-editor");
            TableView<?> table = table(page);
            table.getSelectionModel().select(0);
            settle(page);
            Region columns = (Region) panel.lookup(".limit-editor-actions");
            boolean everyColumnFits = columns.getChildrenUnmodifiable().stream()
                    .filter(Node::isManaged)
                    .allMatch(column -> column.getLayoutBounds().getWidth() + 0.5 >= column.minWidth(-1)
                            && column.getBoundsInParent().getMaxX() <= columns.getWidth() + 0.5);
            List<Object> facts = new ArrayList<>();
            facts.add(page.mode());
            facts.add(panel.isVisible());
            facts.add(button(page, "Details and actions").isVisible());
            facts.add(columns.getChildrenUnmodifiable().size());
            facts.add(everyColumnFits);
            facts.add(horizontalScrollBarShown(table));
            facts.add(below(panel, table));
            table.requestFocus();
            press(table, KeyCode.ENTER, false);
            facts.add(page.sheet().isOpen());
            return facts;
        });

        assertThat(seen).containsExactly(Breakpoints.Inspector.BOTTOM, true, false, 3, true, false, true, false);
    }

    /** From 1320 px the panel is beside the table, at any height: also in a wide and low window. */
    @Test
    void fromTheSideBreakpointThePanelIsBesideTheTableAtAnyHeight() {
        for (int[] size : new int[][] {{1320, 768}, {1600, 500}, {1920, 1080}}) {
            List<Object> seen =
                    SnapshotHarness.inspect(Theme.LIGHT, size[0], size[1], LimitsPanelSceneTest::page, root -> {
                        LimitsPage page = (LimitsPage) root;
                        Node panel = page.lookup(".limit-editor");
                        TableView<?> table = table(page);
                        return List.of(
                                page.mode(),
                                panel.isVisible(),
                                button(page, "Details and actions").isVisible(),
                                panel.getBoundsInParent().getMinX()
                                        >= table.getBoundsInParent().getMaxX(),
                                horizontalScrollBarShown(table));
                    });

            assertThat(seen)
                    .as(size[0] + "x" + size[1])
                    .containsExactly(Breakpoints.Inspector.SIDE, true, false, true, false);
        }
    }

    /** F4, docked to sheet: the focus on a control of the docked panel goes to the table. */
    @Test
    void dockedToSheetTheFocusGoesToTheTable() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 1600, 900, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            table(page).getSelectionModel().select(0);
            settle(page);
            Button disable = button(page, "Disable");
            disable.requestFocus();
            boolean before = page.getScene().getFocusOwner() == disable;
            moveTo(page, 720, 480);
            return List.of(
                    before, page.mode(), page.sheet().isOpen(), page.getScene().getFocusOwner() == table(page));
        });

        assertThat(seen).containsExactly(true, Breakpoints.Inspector.SHEET, false, true);
    }

    /** F4, sheet to docked: the focus on a control in the open sheet stays on it, docked now. */
    @Test
    void sheetToDockedTheFocusStaysOnTheSameControl() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 720, 480, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            table(page).getSelectionModel().select(0);
            table(page).requestFocus();
            press(table(page), KeyCode.ENTER, false);
            settle(page);
            Button disable = button(page, "Disable");
            disable.requestFocus();
            boolean inSheet = page.sheet().holds(page.getScene().getFocusOwner());
            moveTo(page, 1600, 900);
            return List.of(
                    inSheet,
                    page.mode(),
                    page.sheet().isOpen(),
                    page.getScene().getFocusOwner() == disable,
                    page.sheet().holds(disable));
        });

        assertThat(seen).containsExactly(true, Breakpoints.Inspector.SIDE, false, true, false);
    }

    /**
     * F5: the selected process leaves the snapshot while the sheet is open. The sheet stays open, its
     * actions disabled with their reasons on screen; the focus, on an action disabled now, goes to the
     * close button; Esc still closes the sheet, and the focus goes back to the table, which opened it.
     */
    @Test
    void theSelectedProcessLeavesWhileTheSheetIsOpen() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 720, 480, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            List<Object> facts = new ArrayList<>();
            table(page).getSelectionModel().select(0);
            table(page).requestFocus();
            press(table(page), KeyCode.ENTER, false);
            settle(page);
            button(page, "Disable").requestFocus();
            page.show(snapshot(managed(identity(3_000), "notepad.exe")));
            settle(page);
            facts.add(page.sheet().isOpen());
            facts.add(button(page, "Disable").isDisabled());
            facts.add(page.lookup(".limit-editor-reasons").isVisible());
            facts.add(page.getScene().getFocusOwner() == page.sheet().lookup(".sheet-close"));
            press(page.getScene().getFocusOwner(), KeyCode.ESCAPE, false);
            facts.add(page.sheet().isOpen());
            facts.add(page.getScene().getFocusOwner() == table(page));
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true, false, true);
    }

    /** CAP-2 (A-M4): docked and without a selection, the reasons of the disabled actions are on screen. */
    @Test
    void theReasonsOfTheDisabledActionsAreOnScreenWhenDocked() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 1600, 900, LimitsPanelSceneTest::page, root -> {
            LimitsPage page = (LimitsPage) root;
            Labeled reasons = (Labeled) page.lookup(".limit-editor-reasons");
            Button edit = button(page, "Set CPU limit…");
            return List.of(
                    edit.isDisabled(),
                    reasons.isVisible(),
                    reasons.getHeight() > 0,
                    reasons.getText().contains(edit.getAccessibleHelp()),
                    edit.getParent().getStyleClass().contains("action-holder"));
        });

        assertThat(seen).containsExactly(true, true, true, true, true);
    }

    private static Button button(LimitsPage page, String text) {
        return page.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static TableView<?> table(LimitsPage page) {
        return (TableView<?>) page.lookup(".limits-table");
    }

    private static boolean horizontalScrollBarShown(TableView<?> table) {
        return table.lookupAll(".scroll-bar").stream()
                .map(ScrollBar.class::cast)
                .anyMatch(bar -> bar.getOrientation() == Orientation.HORIZONTAL && bar.isVisible());
    }

    private static boolean below(Node lower, Node upper) {
        Bounds low = lower.localToScene(lower.getLayoutBounds());
        Bounds up = upper.localToScene(upper.getLayoutBounds());
        return low.getMinY() >= up.getMaxY() - 0.5;
    }

    private static void press(Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    private static void settle(Parent page) {
        page.applyCss();
        page.layout();
        page.applyCss();
        page.layout();
    }

    /** Moves the page into a new scene of another size, as a window that is resized past a breakpoint. */
    private static void moveTo(Parent root, int width, int height) {
        Scene before = root.getScene();
        before.setRoot(new Region());
        Scene after = new Scene(root, width, height);
        after.getPreferences().setReducedMotion(true);
        after.getPreferences().setColorScheme(before.getPreferences().getColorScheme());
        after.getStylesheets().setAll(before.getStylesheets());
        settle(root);
    }

    /** Tab (or Shift+Tab) from the focus owner until it comes back to it: the controls visited, in order. */
    private static List<Node> walk(Scene scene, boolean backwards) {
        Node start = scene.getFocusOwner();
        List<Node> visited = new ArrayList<>(List.of(start));
        for (int step = 0; step < 40; step++) {
            press(scene.getFocusOwner(), KeyCode.TAB, backwards);
            Node next = scene.getFocusOwner();
            if (next == start) {
                return visited;
            }
            if (visited.contains(next)) {
                throw new AssertionError("Tab went round without coming back to " + start + ": " + visited);
            }
            visited.add(next);
        }
        throw new AssertionError("Tab never came back: " + visited);
    }

    private static List<Node> reversedAfterFirst(List<Node> forward) {
        List<Node> reversed = new ArrayList<>(forward.subList(1, forward.size()));
        Collections.reverse(reversed);
        reversed.add(0, forward.get(0));
        return reversed;
    }

    private static List<String> names(List<Node> nodes) {
        return nodes.stream()
                .map(node -> node.getAccessibleText() != null
                        ? node.getAccessibleText()
                        : node instanceof Labeled labeled ? labeled.getText() : String.valueOf(node))
                .toList();
    }

    private static LimitsPage page() {
        LimitsPage page = new LimitsPage(new ValueFormatter(Locale.US), request -> {});
        page.show(
                snapshot(throttling(identity(30_324), "java.exe", 30, 29.6), managed(identity(3_000), "notepad.exe")));
        return page;
    }
}
