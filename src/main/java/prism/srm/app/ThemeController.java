package prism.srm.app;

import java.util.Objects;
import javafx.application.ColorScheme;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import prism.srm.domain.ThemeMode;

/**
 * Applies the appearance preference of the application to its windows.
 *
 * <p>The stylesheet holds the light tokens and, under {@code @media (prefers-color-scheme:
 * dark)}, the dark ones. Which of the two a scene uses is its color scheme, which it takes from
 * the platform unless one is set on the scene; so {@link ThemeMode#SYSTEM} sets none and the
 * window follows the operating system, also while the application runs, and {@link
 * ThemeMode#LIGHT} and {@link ThemeMode#DARK} set one. No code of the platform is involved
 * (docs/adr/0009).
 *
 * <p>A popup, as a tooltip or a context menu, has a scene of its own that does not take the color
 * scheme of the scene of its owner (measured with JavaFX 25): it would follow the platform while
 * the window is forced to the other scheme. So the mode is set on the scene of every window that
 * is opened while the application runs, and again on all of them when the mode changes.
 *
 * <p>Used on the JavaFX Application Thread only. {@link #close()} stops it listening to the
 * windows; it is called once, when the application stops.
 */
final class ThemeController {

    private final Scene scene;
    private final ObjectProperty<ThemeMode> mode = new SimpleObjectProperty<>(this, "mode", ThemeMode.SYSTEM);
    private final ListChangeListener<Window> windowsOpened = change -> {
        while (change.next()) {
            if (change.wasAdded()) {
                for (Window window : change.getAddedSubList()) {
                    apply(window);
                }
            }
        }
    };

    /**
     * @param scene the scene of the application window; it gets the mode at once, and the
     *     default mode is {@link ThemeMode#SYSTEM}
     */
    ThemeController(Scene scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
        mode.addListener((observable, before, after) -> applyToAll());
        Window.getWindows().addListener(windowsOpened);
        applyToAll();
    }

    /** The mode; setting it applies it to every window at once. Never {@code null}. */
    ObjectProperty<ThemeMode> modeProperty() {
        return mode;
    }

    ThemeMode mode() {
        return mode.get();
    }

    /** Stops following the windows that open. The scenes keep the scheme they have. */
    void close() {
        Window.getWindows().removeListener(windowsOpened);
    }

    private void applyToAll() {
        apply(scene);
        for (Window window : Window.getWindows()) {
            apply(window);
        }
    }

    /** A window that has no scene yet gets the mode when it has one. */
    private void apply(Window window) {
        Scene windowScene = window.getScene();
        if (windowScene != null) {
            apply(windowScene);
            return;
        }
        window.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(
                    javafx.beans.value.ObservableValue<? extends Scene> observable, Scene before, Scene after) {
                if (after != null) {
                    observable.removeListener(this);
                    apply(after);
                }
            }
        });
    }

    private void apply(Scene target) {
        target.getPreferences().setColorScheme(scheme(mode.get()));
    }

    /** The scheme a mode forces; {@code null} for the one the platform prefers. */
    private static ColorScheme scheme(ThemeMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case SYSTEM -> null;
            case LIGHT -> ColorScheme.LIGHT;
            case DARK -> ColorScheme.DARK;
        };
    }
}
