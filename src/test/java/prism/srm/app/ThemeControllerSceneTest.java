package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ThemeMode;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * What the theme controller does to scenes, read from real scenes and windows: the mode SYSTEM
 * leaves the scheme to the platform, a forced mode sets it whatever the platform prefers, and a
 * popup opened while the application runs gets the same scheme as the window.
 *
 * <p>Which scheme the platform prefers depends on the machine the tests run on, so the tests
 * compare with it instead of assuming it.
 */
@Tag("ui-snapshot")
class ThemeControllerSceneTest {

    @Test
    void systemModeLeavesTheSchemeToThePlatformAndAForcedModeSetsIt() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 16, 16, StackPane::new, ignored -> {
            Scene scene = new Scene(new StackPane(), 100, 100);
            ThemeController controller = new ThemeController(scene);
            List<Object> facts = new ArrayList<>();
            facts.add(controller.mode());
            facts.add(scene.getPreferences().getColorScheme()
                    == Platform.getPreferences().getColorScheme());
            controller.modeProperty().set(ThemeMode.DARK);
            facts.add(scene.getPreferences().getColorScheme());
            controller.modeProperty().set(ThemeMode.LIGHT);
            facts.add(scene.getPreferences().getColorScheme());
            controller.modeProperty().set(ThemeMode.SYSTEM);
            facts.add(scene.getPreferences().getColorScheme()
                    == Platform.getPreferences().getColorScheme());
            controller.close();
            return facts;
        });

        assertThat(seen).containsExactly(ThemeMode.SYSTEM, true, ColorScheme.DARK, ColorScheme.LIGHT, true);
    }

    /** The tokens of the stylesheet follow the scheme of the scene, not the platform. */
    @Test
    void aForcedModeResolvesTheTokensOfThatSchemeWhateverThePlatformPrefers() {
        List<Color> seen = SnapshotHarness.inspect(Theme.LIGHT, 16, 16, StackPane::new, ignored -> {
            Label probe = new Label("probe");
            probe.getStyleClass().add("text-muted");
            StackPane root = new StackPane(probe);
            Scene scene = new Scene(root, 100, 100);
            scene.getStylesheets().add(Stylesheets.app());
            ThemeController controller = new ThemeController(scene);
            controller.modeProperty().set(ThemeMode.LIGHT);
            root.applyCss();
            Color light = (Color) probe.getTextFill();
            controller.modeProperty().set(ThemeMode.DARK);
            root.applyCss();
            Color dark = (Color) probe.getTextFill();
            controller.close();
            return List.of(light, dark);
        });

        assertThat(seen.get(0)).isEqualTo(Color.web("#4a5262"));
        assertThat(seen.get(1)).isEqualTo(Color.web("#a9b1be"));
    }

    /** After close() the controller follows no window any more; what it set stays as it is. */
    @Test
    void afterCloseAWindowThatOpensIsNotTouchedAndTheWindowKeepsItsScheme() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 16, 16, StackPane::new, ignored -> {
            Scene scene = new Scene(new StackPane(), 100, 100);
            ThemeController controller = new ThemeController(scene);
            ColorScheme forced = Platform.getPreferences().getColorScheme() == ColorScheme.DARK
                    ? ColorScheme.LIGHT
                    : ColorScheme.DARK;
            controller.modeProperty().set(forced == ColorScheme.DARK ? ThemeMode.DARK : ThemeMode.LIGHT);
            controller.close();
            Stage later = new Stage();
            Scene laterScene = new Scene(new StackPane(), 100, 100);
            later.setScene(laterScene);
            later.setX(1300);
            later.setY(60);
            later.show();
            try {
                return List.of(
                        laterScene.getPreferences().getColorScheme()
                                == Platform.getPreferences().getColorScheme(),
                        scene.getPreferences().getColorScheme() == forced);
            } finally {
                later.close();
            }
        });

        assertThat(seen).containsExactly(true, true);
    }

    /** A window that opens before it has a scene gets the scheme when it has one. */
    @Test
    void aWindowThatGetsItsSceneAfterOpeningGetsTheSchemeThen() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 16, 16, StackPane::new, ignored -> {
            Scene scene = new Scene(new StackPane(), 100, 100);
            ThemeController controller = new ThemeController(scene);
            ColorScheme forced = Platform.getPreferences().getColorScheme() == ColorScheme.DARK
                    ? ColorScheme.LIGHT
                    : ColorScheme.DARK;
            controller.modeProperty().set(forced == ColorScheme.DARK ? ThemeMode.DARK : ThemeMode.LIGHT);
            Stage bare = new Stage();
            bare.setX(1300);
            bare.setY(60);
            bare.show();
            Scene lateScene = new Scene(new StackPane(), 100, 100);
            bare.setScene(lateScene);
            try {
                return List.of(lateScene.getPreferences().getColorScheme() == forced);
            } finally {
                bare.close();
                controller.close();
            }
        });

        assertThat(seen).containsExactly(true);
    }

    /** A popup has a scene of its own; it gets the scheme of the window, not the platform's. */
    @Test
    void aPopupOpenedWhileAModeIsForcedGetsThatSchemeAndAModeChangeReachesIt() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 16, 16, StackPane::new, ignored -> {
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(), 100, 100);
            stage.setScene(scene);
            stage.setX(1300);
            stage.setY(60);
            stage.show();
            ThemeController controller = new ThemeController(scene);
            // The opposite of what the platform prefers, so that following the platform would show.
            ColorScheme forced = Platform.getPreferences().getColorScheme() == ColorScheme.DARK
                    ? ColorScheme.LIGHT
                    : ColorScheme.DARK;
            controller.modeProperty().set(forced == ColorScheme.DARK ? ThemeMode.DARK : ThemeMode.LIGHT);
            Tooltip tip = new Tooltip("tip");
            tip.show(stage, 1310, 70);
            List<Object> facts = new ArrayList<>();
            try {
                facts.add(tip.getScene().getPreferences().getColorScheme() == forced);
                controller.modeProperty().set(ThemeMode.SYSTEM);
                facts.add(tip.getScene().getPreferences().getColorScheme()
                        == Platform.getPreferences().getColorScheme());
                controller.close();
            } finally {
                tip.hide();
                stage.close();
            }
            return facts;
        });

        assertThat(seen).containsExactly(true, true);
    }
}
