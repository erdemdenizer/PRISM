package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Owner decision after fix round H: the navigation names its destinations at every width. A window
 * below the wide one has the rail at its left, each icon with its name under it; a narrow window has the
 * same rail, no drawer and no button for one. Read from real scenes of the whole shell; the sizes are the
 * scenes of real Windows windows at 100 % and, through {@link ScaledScenes}, at 125 %.
 *
 * <p>The shortcut events have both Control and Meta down, so that {@code isShortcutDown()} is true on
 * every system. No window has focus here, so the focus is read as the focus owner of the scene.
 */
@Tag("ui-snapshot")
class ShellNarrowNavigationSceneTest {

    /**
     * Every name is whole, under its icon and inside its entry, the selected one in SemiBold too, and
     * the last entry stands inside the rail: at the smallest window, low windows and medium ones.
     */
    @ParameterizedTest(name = "{0} x {1}")
    @CsvSource({"706, 435", "746, 515", "786, 555", "886, 1155", "1086, 655", "1352, 723", "1426, 855"})
    void everyNameIsWholeUnderItsIconInTheRail(int width, int height) {
        List<String> faults = inShell(width, height, ShellNarrowNavigationSceneTest::railFaults);

        assertThat(faults).isEmpty();
    }

    /** The same at 125 %: the scenes of the real windows, laid out to device pixels. */
    @ParameterizedTest(name = "{0} x {1} at 125 %")
    @CsvSource({"705.6, 442.4", "745.6, 522.4", "1086.4, 662.4", "1352, 730.4"})
    void everyNameIsWholeUnderItsIconInTheRailAt125(double width, double height) {
        List<String> faults = ScaledScenes.inspect(
                Theme.DARK,
                width,
                height,
                ScaledScenes.SCALE_125,
                KeyboardRoutesSceneTest::populated,
                root -> railFaults((Shell) root));

        assertThat(faults).isEmpty();
    }

    /** A narrow window has the rail at its left, of the width of the rail, and no button for a drawer. */
    @Test
    void aNarrowWindowHasTheRailAndNoMenuButton() {
        List<Object> seen = inShell(
                706,
                435,
                shell -> List.<Object>of(
                        shell.getLeft() == shell.navigation(),
                        shell.navigation().mode(),
                        shell.navigation().getWidth(),
                        shell.lookupAll(".nav-toggle").isEmpty(),
                        shell.navigationStart() == shell.navigation().selectedEntry()));

        assertThat(seen).containsExactly(true, Sidebar.Mode.RAIL, Breakpoints.RAIL_WIDTH, true, true);
    }

    @Test
    void controlThreeChoosesPerformanceAndControlOneOverviewInANarrowWindow() {
        List<Object> seen = inShell(706, 435, shell -> {
            List<Object> facts = new ArrayList<>();
            shell.navigation().selectedEntry().requestFocus();
            shortcut(focus(shell), KeyCode.DIGIT3);
            settle(shell);
            facts.add(shell.navigation().selected());
            facts.add(shell.pageArea().getContent().getStyleClass().contains("performance"));
            shortcut(focus(shell), KeyCode.NUMPAD1);
            settle(shell);
            facts.add(shell.navigation().selected());
            return facts;
        });

        assertThat(seen).containsExactly(Destination.PERFORMANCE, true, Destination.OVERVIEW);
    }

    /** With Alt down the digits are not a shortcut of the shell; Ctrl+B is no key of the shell any more. */
    @Test
    void controlAltThreeAndControlBChooseNothing() {
        List<Object> seen = inShell(706, 435, shell -> {
            shell.navigation().selectedEntry().requestFocus();
            Event.fireEvent(
                    focus(shell), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DIGIT3, false, true, true, true));
            settle(shell);
            Destination afterAlt = shell.navigation().selected();
            shortcut(focus(shell), KeyCode.B);
            settle(shell);
            return List.<Object>of(afterAlt, shell.navigation().selected(), shell.getLeft() == shell.navigation());
        });

        assertThat(seen).containsExactly(Destination.OVERVIEW, Destination.OVERVIEW, true);
    }

    /** What is wrong with the names of the rail of this shell, for each destination selected in turn. */
    private static List<String> railFaults(Shell shell) {
        List<String> found = new ArrayList<>();
        Sidebar list = shell.navigation();
        if (shell.getLeft() != list || list.mode() != Sidebar.Mode.RAIL) {
            found.add("no rail at the left: " + list.mode());
            return found;
        }
        for (Destination destination : Destination.values()) {
            list.select(destination);
            settle(shell);
            ToggleButton entry = list.entry(destination);
            Text text = entry.getChildrenUnmodifiable().stream()
                    .filter(Text.class::isInstance)
                    .map(Text.class::cast)
                    .findFirst()
                    .orElse(null);
            if (text == null || !destination.title().equals(text.getText())) {
                found.add(destination + ": name cut to " + (text == null ? "nothing" : text.getText()));
                continue;
            }
            Bounds name = entry.sceneToLocal(text.localToScene(text.getLayoutBounds()));
            Bounds icon = entry.sceneToLocal(
                    entry.getGraphic().localToScene(entry.getGraphic().getLayoutBounds()));
            if (name.getMinX() < -0.5 || name.getMaxX() > entry.getWidth() + 0.5) {
                found.add(destination + ": the name goes out of the entry, " + name);
            }
            if (name.getMinY() < icon.getMaxY() - 0.5) {
                found.add(destination + ": the name is not under the icon");
            }
            if (name.getMaxY() > entry.getHeight() + 0.5) {
                found.add(destination + ": the name goes out of the entry at its foot");
            }
            Bounds inRail = list.sceneToLocal(entry.localToScene(entry.getLayoutBounds()));
            if (inRail.getMaxY() > list.getHeight() - list.snappedBottomInset() + 0.5) {
                found.add(destination + ": the entry goes out of the rail at " + inRail.getMaxY() + " of "
                        + list.getHeight());
            }
        }
        list.select(Destination.OVERVIEW);
        return found;
    }

    private static <T> T inShell(int width, int height, Function<Shell, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT, width, height, KeyboardRoutesSceneTest::populated, root -> scenario.apply((Shell) root));
    }

    private static Node focus(Shell shell) {
        Node owner = shell.getScene().getFocusOwner();
        return owner == null ? shell : owner;
    }

    private static void settle(Shell shell) {
        shell.applyCss();
        shell.layout();
    }

    /** The shortcut key with a key: Control and Meta both down, so that it is the shortcut on every system. */
    private static void shortcut(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, true));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, true, false, true));
    }
}
