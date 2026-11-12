package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.PopupWindow.AnchorLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SH-01 and SH-03: the name of an entry of the compact rail, read in a real scene of the whole shell at
 * the scene of a real 1100 x 700 window (1086 x 655). The harness has no window, so the popup itself
 * does not open; what is checked is where it would stand, that nothing in it can make its window larger
 * than its text, that the stock tooltip behavior has no hold on it, and when it shows and goes.
 */
@Tag("ui-snapshot")
class RailNameSceneTest {

    private static final int WIDTH = 1086;
    private static final int HEIGHT = 655;

    /**
     * The blink: a popup that covered the pointer took the hover from the entry. Wherever the name stands,
     * for every entry and for any size the name may have, it is right of the rail by the gap: it shares
     * no point with the entry under the pointer, nor with any other part of the rail.
     */
    @Test
    void theNameNeverCoversTheEntryUnderThePointerNorTheRail() {
        List<String> faults = KeyboardRoutesSceneTest.inShell(WIDTH, HEIGHT, shell -> {
            List<String> found = new ArrayList<>();
            Sidebar rail = shell.navigation();
            Bounds edge = rail.localToScene(rail.getLayoutBounds());
            for (Destination destination : Destination.values()) {
                ToggleButton entry = rail.entry(destination);
                Bounds hover = entry.localToScene(entry.getLayoutBounds());
                for (double width : new double[] {1, 40, 120, 260, 600}) {
                    for (double height : new double[] {1, 22, 26, 40, 80}) {
                        Rectangle2D name = RailName.placement(hover, edge.getMaxX(), width, height);
                        if (name.getMinX() < edge.getMaxX() + RailName.GAP - 0.5) {
                            found.add(destination + " " + width + "x" + height + ": left of the gap");
                        }
                        if (name.intersects(hover.getMinX(), hover.getMinY(), hover.getWidth(), hover.getHeight())
                                || name.contains(hover.getCenterX(), hover.getCenterY())
                                || name.intersects(edge.getMinX(), edge.getMinY(), edge.getWidth(), edge.getHeight())) {
                            found.add(destination + " " + width + "x" + height + ": over the rail");
                        }
                        double middle = name.getMinY() + name.getHeight() / 2;
                        if (Math.abs(middle - hover.getCenterY()) > 1) {
                            found.add(destination + " " + width + "x" + height + ": not centred on the entry");
                        }
                    }
                }
            }
            return found;
        });

        assertThat(faults).isEmpty();
    }

    /**
     * Nothing makes the popup larger than its text, and nothing moves it onto the rail: no shadow (the
     * stylesheet of every tooltip, and of the name in particular), no fitting to the screen, its anchor at
     * the top left of its content. No entry has a tooltip installed, so the stock behavior, which shows a
     * tooltip at the pointer, never runs for the rail.
     */
    @Test
    void theNameHasNoShadowIsPlacedByItsContentAndNoEntryHasATooltipOfItsOwn() {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(WIDTH, HEIGHT, shell -> {
            List<Object> facts = new ArrayList<>();
            Sidebar rail = shell.navigation();
            var tip = rail.railName().tooltip();
            facts.add(tip.getStyleClass().contains("nav-name"));
            facts.add(tip.isAutoFix());
            facts.add(tip.isAutoHide());
            facts.add(tip.getAnchorLocation());
            facts.add(rail.lookupAll(".nav-item").stream()
                    .map(ToggleButton.class::cast)
                    .allMatch(entry -> entry.getTooltip() == null));
            // The tooltip's look, on stand-ins of its root in the same scene: the shared rule and the
            // name's own.
            for (String[] classes : new String[][] {{"tooltip"}, {"tooltip", "nav-name"}}) {
                Label standIn = new Label("Processes");
                standIn.getStyleClass().setAll(classes);
                shell.getChildren().add(standIn);
                standIn.applyCss();
                facts.add(standIn.getEffect());
                shell.getChildren().remove(standIn);
            }
            return facts;
        });

        assertThat(seen).containsExactly(true, false, false, AnchorLocation.CONTENT_TOP_LEFT, true, null, null);
    }

    /**
     * The pointer: the name waits for the pointer to rest, shows, and goes as the pointer leaves; from one
     * entry to the next right after, it shows at once; a press of the mouse takes it away.
     */
    @Test
    void thePointerShowsTheNameAfterItsDelayAndTakesItAwayAsItLeaves() {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(WIDTH, HEIGHT, shell -> {
            List<Object> facts = new ArrayList<>();
            Sidebar rail = shell.navigation();
            Node processes = rail.entry(Destination.PROCESSES);
            Node limits = rail.entry(Destination.LIMITS);
            Event.fireEvent(processes, mouse(MouseEvent.MOUSE_ENTERED));
            facts.add(rail.railName().waiting());
            facts.add(rail.nameShownForPointer());
            rail.railName().elapseDelay();
            facts.add(rail.nameShownForPointer());
            Event.fireEvent(processes, mouse(MouseEvent.MOUSE_EXITED));
            facts.add(rail.nameShownForPointer());
            Event.fireEvent(limits, mouse(MouseEvent.MOUSE_ENTERED));
            facts.add(rail.nameShownForPointer());
            Event.fireEvent(limits, mouse(MouseEvent.MOUSE_PRESSED));
            facts.add(rail.nameShownForPointer());
            facts.add(rail.railName().waiting());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        true,
                        Optional.empty(),
                        Optional.of(Destination.PROCESSES),
                        Optional.empty(),
                        Optional.of(Destination.LIMITS),
                        Optional.empty(),
                        false);
    }

    /**
     * SH-03: Ctrl+2 from the page puts the focus on the entry of the page it shows, and no name over the
     * page; an arrow key from there shows the name of the entry it reaches. The mouse and the keyboard
     * share the one name: the pointer resting on another entry takes it there, and there is never a
     * second.
     */
    @Test
    void aShortcutShowsNoNameAnArrowKeyDoesAndThereIsOnlyEverOne() {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(WIDTH, HEIGHT, shell -> {
            List<Object> facts = new ArrayList<>();
            Sidebar rail = shell.navigation();
            rail.select(Destination.LOGS);
            shell.applyCss();
            shell.layout();
            Node filter = shell.getCenter().lookup(".logs-filter");
            filter.requestFocus();
            shortcut(filter, KeyCode.DIGIT2);
            facts.add(rail.selected());
            facts.add(shell.getScene().getFocusOwner() == rail.entry(Destination.PROCESSES));
            facts.add(rail.nameShownForKeyboard());
            press(shell.getScene().getFocusOwner(), KeyCode.DOWN);
            facts.add(rail.nameShownForKeyboard());
            Node logs = rail.entry(Destination.LOGS);
            Event.fireEvent(logs, mouse(MouseEvent.MOUSE_ENTERED));
            rail.railName().elapseDelay();
            facts.add(rail.nameShownForKeyboard());
            facts.add(rail.nameShownForPointer());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        Destination.PROCESSES,
                        true,
                        Optional.empty(),
                        Optional.of(Destination.PERFORMANCE),
                        Optional.empty(),
                        Optional.of(Destination.LOGS));
    }

    /** With names in the list (a wide window, the scene of 1600 x 500) the pointer brings no name beside it. */
    @Test
    void theSidebarWithNamesShowsNoNameBesideIt() {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(1586, 455, shell -> {
            Sidebar list = shell.navigation();
            Node processes = list.entry(Destination.PROCESSES);
            Event.fireEvent(processes, mouse(MouseEvent.MOUSE_ENTERED));
            List<Object> facts = new ArrayList<>();
            facts.add(list.mode());
            facts.add(list.railName().waiting());
            press(list.selectedEntry(), KeyCode.DOWN);
            facts.add(list.nameShownForKeyboard());
            return facts;
        });

        assertThat(seen).containsExactly(Sidebar.Mode.SIDEBAR, false, Optional.empty());
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        boolean pressed = type == MouseEvent.MOUSE_PRESSED;
        return new MouseEvent(
                type,
                20,
                20,
                20,
                20,
                pressed ? MouseButton.PRIMARY : MouseButton.NONE,
                pressed ? 1 : 0,
                false,
                false,
                false,
                false,
                pressed,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private static void press(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
    }

    /** Control and Meta both down, so that it is the shortcut key on every system. */
    private static void shortcut(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, true));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, true, false, true));
    }
}
