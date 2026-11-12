package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * K1: the navigation takes the form the width of the window gives it, read from real scenes of the
 * whole shell. The sizes are scenes (K8): a real Windows window at 100 % has a scene about 14 px
 * narrower and 45 px lower than its outer size, so 1366 x 768 outer is 1352 x 723 here.
 */
@Tag("ui-snapshot")
class ShellNavigationLayoutSceneTest {

    /** The form and the width of the navigation on both sides of every line and at the real sizes. */
    @ParameterizedTest(name = "{0} x {1}: {2}")
    @CsvSource({
        "706, 435, RAIL",
        "746, 515, RAIL",
        "819, 600, RAIL",
        "820, 600, RAIL",
        "1086, 655, RAIL",
        "1163, 739, RAIL",
        "1352, 723, RAIL",
        "1426, 855, RAIL",
        "1459, 800, RAIL",
        "1460, 800, SIDEBAR",
        "1586, 455, SIDEBAR",
        "1906, 987, SIDEBAR"
    })
    void theNavigationTakesTheFormOfTheWidth(int width, int height, String form) {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(width, height, shell -> {
            Sidebar list = shell.navigation();
            if (shell.getLeft() != list) {
                return List.<Object>of("NONE", 0.0);
            }
            return List.<Object>of(list.mode().name(), list.getWidth());
        });

        double expected = switch (form) {
            case "RAIL" -> Breakpoints.RAIL_WIDTH;
            case "SIDEBAR" -> Breakpoints.SIDEBAR_WIDTH;
            default -> 0.0;
        };
        assertThat(seen).containsExactly(form, expected);
    }

    /**
     * Every name is whole in its row, the selected one in SemiBold too, and the shortcut at the right
     * end leaves it room: the widths of Breakpoints.SIDEBAR_WIDTH were measured from these names.
     */
    @Test
    void everyNameIsWholeInItsRowAndTheShortcutLeavesItRoom() {
        List<String> faults = KeyboardRoutesSceneTest.inShell(1586, 455, shell -> {
            List<String> found = new ArrayList<>();
            Sidebar list = shell.navigation();
            for (Destination destination : Destination.values()) {
                list.select(destination);
                shell.applyCss();
                shell.layout();
                ToggleButton entry = list.entry(destination);
                // The entry's own text, a child of the entry; the shortcut has a text of its own in the graphic.
                Text text = entry.getChildrenUnmodifiable().stream()
                        .filter(Text.class::isInstance)
                        .map(Text.class::cast)
                        .findFirst()
                        .orElse(null);
                if (text == null || !destination.title().equals(text.getText())) {
                    found.add(destination + ": name cut to " + (text == null ? "nothing" : text.getText()));
                    continue;
                }
                Node hint = ((Parent) entry.getGraphic()).lookup(".nav-shortcut");
                Bounds name = entry.sceneToLocal(text.localToScene(text.getLayoutBounds()));
                Bounds keys = entry.sceneToLocal(hint.localToScene(hint.getLayoutBounds()));
                if (name.getMaxX() + 8 > keys.getMinX()) {
                    found.add(destination + ": the shortcut at " + keys.getMinX() + " is on the name to "
                            + name.getMaxX());
                }
                if (keys.getMaxX() > entry.getWidth() - entry.snappedRightInset() + 0.5) {
                    found.add(destination + ": the shortcut goes out of the row");
                }
            }
            return found;
        });

        assertThat(faults).isEmpty();
    }

    /**
     * Crossing the line builds nothing: the same entries in the other form, and back to the same number
     * of nodes.
     */
    @Test
    void crossingTheLineOfTheNamesBuildsNothing() {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(1459, 800, shell -> {
            List<Object> facts = new ArrayList<>();
            Sidebar list = shell.navigation();
            ToggleButton processes = list.entry(Destination.PROCESSES);
            int nodes = count(shell);
            facts.add(list.mode());
            resize(shell, 1460, 800);
            facts.add(list.mode());
            facts.add(list.entry(Destination.PROCESSES) == processes);
            // At most the text of each entry, which its skin shows only with a name.
            facts.add(count(shell) - nodes <= Destination.values().length);
            resize(shell, 1459, 800);
            facts.add(list.mode());
            facts.add(count(shell) == nodes);
            return facts;
        });

        assertThat(seen).containsExactly(Sidebar.Mode.RAIL, Sidebar.Mode.SIDEBAR, true, true, Sidebar.Mode.RAIL, true);
    }

    /**
     * SH-04: the dot of the badge sits on the upper right corner of the icon, in the rail and with names,
     * not at the corner of the entry; at Emergency it takes the error color, as the status bar does.
     */
    @ParameterizedTest(name = "{0} px")
    @CsvSource({"1086", "1586"})
    void theBadgeSitsOnTheCornerOfTheIconAndTakesTheToneOfTheState(int width) {
        List<Object> seen = KeyboardRoutesSceneTest.inShell(width, 655, shell -> {
            List<Object> facts = new ArrayList<>();
            OomSettings monitoring = OomFixtures.settings(true, true, false);
            shell.showOom(OomFixtures.view(
                    monitoring, OomState.WARNING, Optional.of(OomFixtures.reading(12)), List.of(), List.of()));
            shell.applyCss();
            shell.layout();
            ToggleButton entry = shell.navigation().entry(Destination.OOM_GUARD);
            Parent graphic = (Parent) entry.getGraphic();
            Node icon = graphic.getChildrenUnmodifiable().getFirst();
            Region dot = (Region) graphic.lookup(".nav-badge");
            Bounds ink = icon.localToScene(icon.getLayoutBounds());
            Bounds mark = dot.localToScene(dot.getLayoutBounds());
            facts.add(mark.getMinX() >= ink.getCenterX() && mark.getMaxX() <= ink.getMaxX() + 3);
            facts.add(mark.getMinY() >= ink.getMinY() - 3 && mark.getMaxY() <= ink.getCenterY());
            facts.add(fill(dot, 1).equals(color(shell, "-srm-warning")));
            shell.showOom(OomFixtures.view(
                    monitoring, OomState.EMERGENCY, Optional.of(OomFixtures.reading(3)), List.of(), List.of()));
            shell.applyCss();
            facts.add(fill(dot, 1).equals(color(shell, "-srm-error")));
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true);
    }

    /**
     * SH-05: the selected entry is told by its 3 px bar at 3:1 at least against the surface of the list
     * (WCAG 1.4.11), in both themes and on both surfaces (the chrome of the rail and the sidebar, and
     * the raised surface, which the drawer had before fix round H); the surface under the pointer and the selected one differ from the
     * list's surface.
     */
    @ParameterizedTest
    @EnumSource(Theme.class)
    void theSelectionIsToldAtThreeToOneAndTheHoverCanBeSeen(Theme theme) {
        List<Double> seen = SnapshotHarness.inspect(theme, 1586, 655, KeyboardRoutesSceneTest::populated, root -> {
            Shell shell = (Shell) root;
            List<Double> ratios = new ArrayList<>();
            Sidebar list = shell.navigation();
            ToggleButton selected = list.selectedEntry();
            ToggleButton other = list.entry(Destination.LOGS);
            other.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
            shell.applyCss();
            Color surface = fill(list, 0);
            ratios.add(contrast(fill(selected, 0), surface));
            ratios.add(contrast(fill(selected, 1), surface));
            ratios.add(contrast(fill(other, 0), surface));
            Color raised = color(shell, "-srm-surface-raised");
            ratios.add(contrast(fill(selected, 0), raised));
            return ratios;
        });

        assertThat(seen.get(0)).as("bar on the chrome").isGreaterThanOrEqualTo(3.0);
        assertThat(seen.get(1)).as("selected surface on the chrome").isGreaterThan(1.1);
        assertThat(seen.get(2)).as("hover on the chrome").isGreaterThan(1.1);
        assertThat(seen.get(3)).as("bar on the raised surface").isGreaterThanOrEqualTo(3.0);
    }

    private static Color fill(Region region, int layer) {
        return (Color) region.getBackground().getFills().get(layer).getFill();
    }

    /** A token as the stylesheet resolves it, read from a region that has it as its background. */
    private static Color color(Shell shell, String token) {
        Region probe = new Region();
        probe.setStyle("-fx-background-color: " + token + ";");
        shell.getChildren().add(probe);
        probe.applyCss();
        Color resolved = fill(probe, 0);
        shell.getChildren().remove(probe);
        return resolved;
    }

    /** The contrast ratio of WCAG 2 between two opaque colors. */
    static double contrast(Color a, Color b) {
        double first = luminance(a);
        double second = luminance(b);
        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * channel(color.getRed())
                + 0.7152 * channel(color.getGreen())
                + 0.0722 * channel(color.getBlue());
    }

    private static double channel(double value) {
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** Puts the shell in a scene of another size, as a window that is made wider or narrower. */
    private static void resize(Shell shell, int width, int height) {
        Scene before = shell.getScene();
        before.setRoot(new Group());
        Scene after = new Scene(shell, width, height);
        after.getPreferences().setColorScheme(before.getPreferences().getColorScheme());
        after.getPreferences().setReducedMotion(true);
        after.getStylesheets().setAll(Stylesheets.app());
        for (int pass = 0; pass < 3; pass++) {
            shell.applyCss();
            shell.layout();
        }
    }

    private static int count(Node node) {
        int total = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                total += count(child);
            }
        }
        return total;
    }
}
