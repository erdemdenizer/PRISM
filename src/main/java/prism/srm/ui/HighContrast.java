package prism.srm.ui;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Gives the content of every window the colors of a Windows high-contrast theme while one is on.
 *
 * <p>The stylesheets draw everything in tokens ({@code -srm-*}, app.css). While the JavaFX platform
 * preference {@value #PREFERENCE} is true, hc.css maps every token to one of seven system colors,
 * which this class reads from the platform preferences ({@code Windows.SysColor.COLOR_*}) and puts
 * on the root of each scene as looked-up colors ({@code -hc-window} and so on): surfaces take the
 * window color, text and every boundary the window text color (so every edge is a 1 px line of it),
 * the accent and the selection mark the highlight colors, disabled text the gray text color; scrims
 * and shadows are removed. The selection keeps the window color under the text, with the highlight
 * bar and focus ring, because the stylesheets draw selected text in the text token. The preference
 * and the colors are followed while the application runs, and every window that opens gets them.
 *
 * <p>Off, nothing of this is applied and the lab tokens are untouched. Not tested in a real high
 * contrast session (the lab machine was not switched to one); the mapping is tested with a fake map
 * of preferences (HighContrastTest).
 *
 * <p>Used on the JavaFX Application Thread only. {@link #close()} stops it following the platform.
 */
public final class HighContrast {

    /** The JavaFX platform preference that is true while Windows uses a high-contrast theme. */
    public static final String PREFERENCE = "Windows.SPI.HighContrast";

    /** The style class the root of a scene carries while the theme is on; hc.css applies under it. */
    static final String STYLE_CLASS = "high-contrast";

    private static final String KEY_PREFIX = "Windows.SysColor.";
    private static final String APPLIED = "prism.srm.ui.HighContrast.applied";

    /** The system colors hc.css uses, each with the looked-up color it is given as. */
    enum SystemColor {
        WINDOW("COLOR_WINDOW", "-hc-window"),
        WINDOWTEXT("COLOR_WINDOWTEXT", "-hc-windowtext"),
        HIGHLIGHT("COLOR_HIGHLIGHT", "-hc-highlight"),
        HIGHLIGHTTEXT("COLOR_HIGHLIGHTTEXT", "-hc-highlighttext"),
        GRAYTEXT("COLOR_GRAYTEXT", "-hc-graytext"),
        BTNFACE("COLOR_BTNFACE", "-hc-btnface"),
        HOTLIGHT("COLOR_HOTLIGHT", "-hc-hotlight");

        private final String key;
        private final String lookedUp;

        SystemColor(String name, String lookedUp) {
            this.key = KEY_PREFIX + name;
            this.lookedUp = lookedUp;
        }

        /** The key of the color in the platform preferences. */
        String key() {
            return key;
        }

        /** The looked-up color hc.css names. */
        String lookedUp() {
            return lookedUp;
        }
    }

    private final Map<String, Object> preferences;
    private final MapChangeListener<String, Object> preferenceChanged = change -> {
        if (PREFERENCE.equals(change.getKey()) || change.getKey().startsWith(KEY_PREFIX)) {
            applyToAll();
        }
    };
    private final ListChangeListener<Window> windowsOpened = change -> {
        while (change.next()) {
            if (change.wasAdded()) {
                for (Window window : change.getAddedSubList()) {
                    apply(window);
                }
            }
        }
    };

    /** Follows the platform's preferences and applies them to every window, now and as they open. */
    public HighContrast() {
        Platform.Preferences platform = Platform.getPreferences();
        this.preferences = platform;
        platform.addListener(preferenceChanged);
        Window.getWindows().addListener(windowsOpened);
        applyToAll();
    }

    /** Stops following the platform and the windows. The scenes keep what they have. */
    public void close() {
        Platform.getPreferences().removeListener(preferenceChanged);
        Window.getWindows().removeListener(windowsOpened);
    }

    /**
     * The looked-up colors of a high-contrast theme as an inline style, or nothing when no such theme
     * is on or the platform does not give its window and window text colors. A color the platform does
     * not give is derived: button face from window, highlight from window text, highlight text from
     * window, gray text from window text, hot light from highlight.
     */
    static Optional<String> style(Map<String, ?> preferences) {
        if (!Boolean.TRUE.equals(preferences.get(PREFERENCE))) {
            return Optional.empty();
        }
        Map<SystemColor, Color> colors = new EnumMap<>(SystemColor.class);
        for (SystemColor color : SystemColor.values()) {
            if (preferences.get(color.key()) instanceof Color value) {
                colors.put(color, value);
            }
        }
        if (!colors.containsKey(SystemColor.WINDOW) || !colors.containsKey(SystemColor.WINDOWTEXT)) {
            return Optional.empty();
        }
        colors.putIfAbsent(SystemColor.BTNFACE, colors.get(SystemColor.WINDOW));
        colors.putIfAbsent(SystemColor.HIGHLIGHT, colors.get(SystemColor.WINDOWTEXT));
        colors.putIfAbsent(SystemColor.HIGHLIGHTTEXT, colors.get(SystemColor.WINDOW));
        colors.putIfAbsent(SystemColor.GRAYTEXT, colors.get(SystemColor.WINDOWTEXT));
        colors.putIfAbsent(SystemColor.HOTLIGHT, colors.get(SystemColor.HIGHLIGHT));
        StringBuilder style = new StringBuilder();
        for (SystemColor color : SystemColor.values()) {
            if (!style.isEmpty()) {
                style.append(' ');
            }
            style.append(color.lookedUp())
                    .append(": ")
                    .append(hex(colors.get(color)))
                    .append(';');
        }
        return Optional.of(style.toString());
    }

    /** An opaque color as {@code #rrggbb}; the system colors of a theme are opaque. */
    static String hex(Color color) {
        return String.format(
                Locale.ROOT,
                "#%02x%02x%02x",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }

    private void applyToAll() {
        for (Window window : Window.getWindows()) {
            apply(window);
        }
    }

    /** A window that has no scene yet gets the colors when it has one. */
    private void apply(Window window) {
        Scene scene = window.getScene();
        if (scene != null) {
            apply(scene);
            return;
        }
        window.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> observable, Scene before, Scene after) {
                if (after != null) {
                    observable.removeListener(this);
                    apply(after);
                }
            }
        });
    }

    private void apply(Scene scene) {
        Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        Optional<String> style = style(preferences);
        boolean applied = Boolean.TRUE.equals(root.getProperties().get(APPLIED));
        if (style.isPresent()) {
            // On the root itself: its own stylesheets come after the scene's and those a dialog adds
            // to its pane, and the inline colors are found from every node below it.
            if (!root.getStylesheets().contains(Stylesheets.highContrast())) {
                root.getStylesheets().add(Stylesheets.highContrast());
            }
            if (!root.getStyleClass().contains(STYLE_CLASS)) {
                root.getStyleClass().add(STYLE_CLASS);
            }
            root.setStyle(style.get());
            root.getProperties().put(APPLIED, Boolean.TRUE);
        } else if (applied) {
            root.getStylesheets().remove(Stylesheets.highContrast());
            root.getStyleClass().remove(STYLE_CLASS);
            root.setStyle("");
            root.getProperties().remove(APPLIED);
        }
    }
}
