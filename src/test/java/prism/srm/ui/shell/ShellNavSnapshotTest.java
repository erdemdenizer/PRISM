package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * SH-07: images of the navigation in its states, for the eye, at the scenes of real Windows windows
 * (K8; the "-real" in the name: these sizes are scenes, the outer window is about 14 px wider and 45 px
 * higher). The harness has no pointer and no focused window, so the states are given as pseudo-classes:
 * {@code :hover} on one entry, {@code :focus-visible} on another. The name beside the rail is a popup,
 * which a snapshot of a scene cannot hold: a stand-in with the classes of the popup's root is drawn where
 * {@link RailName#placement} puts the popup.
 */
@Tag("ui-snapshot")
class ShellNavSnapshotTest {

    private static final PseudoClass HOVER = PseudoClass.getPseudoClass("hover");
    private static final PseudoClass FOCUS_VISIBLE = PseudoClass.getPseudoClass("focus-visible");

    /**
     * "Night sky" of Windows 11 as HighContrastTest gives it, and a light theme with the colors of
     * "Desert" as its settings page shows them; neither was read from a real high-contrast session.
     */
    private static final String NIGHT_SKY = "-hc-window: #000000; -hc-windowtext: #ffffff; -hc-highlight: #d193ff;"
            + " -hc-highlighttext: #240042; -hc-graytext: #a6a6a6; -hc-btnface: #101010; -hc-hotlight: #8080ff;";

    private static final String DESERT = "-hc-window: #fffaef; -hc-windowtext: #3d3d3d; -hc-highlight: #903909;"
            + " -hc-highlighttext: #fff5e3; -hc-graytext: #6e6e6e; -hc-btnface: #fffaef; -hc-hotlight: #1c5e75;";

    private static Stream<Arguments> railAndSidebar() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size :
                    new int[][] {{706, 435}, {746, 515}, {1086, 655}, {1352, 723}, {1426, 855}, {1586, 455}, {1906, 987}
                    }) {
                cases.add(Arguments.of(theme, size[0], size[1]));
            }
        }
        return cases.build();
    }

    /**
     * The rail with names (to 1459 px, narrow windows too) or the sidebar (from 1460): Overview selected, the pointer on Limits, the
     * keyboard focus on Processes, Warning on OOM Guard; in the rail, the name of the focused entry.
     */
    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("railAndSidebar")
    void rendersTheNavigationWithItsStates(Theme theme, int width, int height) {
        Path png = SnapshotHarness.render(
                "shell-nav-real", theme, width, height, () -> pressured(OomState.WARNING), root -> {
                    Shell shell = (Shell) root;
                    Sidebar list = shell.navigation();
                    list.entry(Destination.LIMITS).pseudoClassStateChanged(HOVER, true);
                    list.entry(Destination.PROCESSES).pseudoClassStateChanged(FOCUS_VISIBLE, true);
                    if (list.mode() == Sidebar.Mode.RAIL) {
                        standInName(shell, list.entry(Destination.PROCESSES), Destination.PROCESSES);
                    }
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Stream<Arguments> highContrast() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (String[] scheme : new String[][] {{"night-sky", NIGHT_SKY}, {"desert", DESERT}}) {
            for (int[] size : new int[][] {{1086, 655}, {1586, 455}}) {
                cases.add(Arguments.of(scheme[0], scheme[1], size[0], size[1]));
            }
        }
        return cases.build();
    }

    /**
     * SH-08: a Windows high-contrast theme, as ui.HighContrast applies it to the root (its stylesheet, its
     * class and the system colors): the selection, the pointer and the keyboard focus in the rail and the
     * sidebar.
     */
    @ParameterizedTest(name = "{0} {2}x{3}")
    @MethodSource("highContrast")
    void rendersTheNavigationInAHighContrastTheme(String scheme, String colors, int width, int height) {
        Path png = SnapshotHarness.render(
                "shell-hc-" + scheme + "-real",
                Theme.DARK,
                width,
                height,
                () -> {
                    Shell shell = pressured(OomState.WARNING);
                    shell.getStylesheets().add(Stylesheets.highContrast());
                    shell.getStyleClass().add("high-contrast");
                    shell.setStyle(colors);
                    return shell;
                },
                root -> {
                    Shell shell = (Shell) root;
                    Sidebar list = shell.navigation();
                    list.entry(Destination.LIMITS).pseudoClassStateChanged(HOVER, true);
                    list.entry(Destination.PROCESSES).pseudoClassStateChanged(FOCUS_VISIBLE, true);
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** A populated shell with memory pressure in a state, so OOM Guard has its badge. */
    private static Shell pressured(OomState state) {
        Shell shell = KeyboardRoutesSceneTest.populated();
        OomSettings monitoring = OomFixtures.settings(true, true, false);
        double available = state == OomState.EMERGENCY ? 3 : 12;
        shell.showOom(
                OomFixtures.view(monitoring, state, Optional.of(OomFixtures.reading(available)), List.of(), List.of()));
        return shell;
    }

    /** Draws a stand-in of the name beside the rail where the popup would stand, in the shell's coordinates. */
    private static void standInName(Shell shell, ToggleButton entry, Destination destination) {
        Label name = new Label(destination.title());
        name.getStyleClass().setAll("tooltip", "nav-name");
        Label keys = new Label(Shortcuts.text(destination));
        keys.getStyleClass().add("nav-name-keys");
        name.setGraphic(keys);
        name.setContentDisplay(ContentDisplay.RIGHT);
        name.setGraphicTextGap(12);
        name.setManaged(false);
        shell.getChildren().add(name);
        name.applyCss();
        double width = name.prefWidth(-1);
        double height = name.prefHeight(width);
        Sidebar rail = shell.navigation();
        Bounds at = shell.sceneToLocal(entry.localToScene(entry.getLayoutBounds()));
        Bounds edge = shell.sceneToLocal(rail.localToScene(rail.getLayoutBounds()));
        Rectangle2D place = RailName.placement(at, edge.getMaxX(), width, height);
        name.resizeRelocate(place.getMinX(), place.getMinY(), width, height);
    }
}
