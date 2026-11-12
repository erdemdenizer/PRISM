package prism.srm.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import prism.srm.domain.ThemeMode;
import prism.srm.platform.ChromeColors;
import prism.srm.platform.WindowChrome;

/**
 * Gives the frame the system draws around each window of the application the colors of the theme (UI
 * lab v2): the caption bar in the chrome token, so that caption, navigation rail, page header and status
 * line read as one frame, the caption text in the text token and the border in the hairline token.
 *
 * <p>Every stage that is shown gets them, the main window and each dialog, and all of them get them again
 * when the theme changes, also when it follows the system and the system changes. A popup (a tooltip, a
 * menu) has no caption and is left alone, and so is a stage without a title, which cannot be found by it.
 *
 * <p>A window that is not the active one (another application is, or one of its own dialogs) shows its
 * caption text in the subtle text token, as Windows dims the text of an inactive caption: the system dims
 * the caption buttons of an inactive window by itself but leaves a text color set by the application as
 * it is (measured, lab-evidence/B-titlebar/dwm-dark-inactive.png). Caption and border stay the same. The
 * text is changed when the stage's {@code focused} property changes, and only when the frame does not
 * show the wanted colors already: a focus change that changes nothing makes no native call.
 *
 * <p>JavaFX gives the native window its title a moment after the window is shown, so the first lookup
 * often finds nothing; it is tried again, first on the next turns of the JavaFX Application Thread and
 * then every {@value #LATER_RETRY_MILLIS} ms, at most {@value #ATTEMPTS} times in all (about a second),
 * and then left (measured: the second attempt finds it). A change while a lookup runs is taken by its
 * next attempt, which reads the wanted colors again. A window left without colors is tried again when
 * the theme changes, not at each focus change. Where the frame cannot be colored the windows keep the
 * system's frame and nothing else changes.
 *
 * <p>A high-contrast theme of the system wins: while it is on, every frame gets the system's own colors
 * back ({@link WindowChrome#reset}), so the user's accessibility colors are never replaced.
 *
 * <p>Used on the JavaFX Application Thread only, which owns the windows the lookup searches; the calls
 * are short and do not wait. A window that ends without colors is logged once, from another thread.
 */
final class TitleBarTint {

    /** How many times one window is looked for, the first time included. */
    static final int ATTEMPTS = 20;

    /** The attempts before this many are made on the next turn; the later ones wait. */
    static final int QUICK_ATTEMPTS = 5;

    static final int LATER_RETRY_MILLIS = 50;

    /** The JavaFX platform preference that is true while Windows uses a high-contrast theme. */
    static final String HIGH_CONTRAST = "Windows.SPI.HighContrast";

    /** The tokens of app.css: {@code -srm-chrome}, {@code -srm-text}, {@code -srm-hairline}. */
    static final ChromeColors DARK = new ChromeColors(true, 0x16181D, 0xECEEF2, 0x2A2E36);

    static final ChromeColors LIGHT = new ChromeColors(false, 0xEEF0F3, 0x15181E, 0xD5D9E0);

    /**
     * A window that is not the active one: {@code -srm-chrome}, {@code -srm-text-subtle}, {@code
     * -srm-hairline}. The text keeps at least 4.5:1 on the caption (6.1:1 dark, 5.2:1 light) and is
     * clearly a step below the active text (15:1).
     */
    static final ChromeColors DARK_INACTIVE = new ChromeColors(true, 0x16181D, 0x8F98A8, 0x2A2E36);

    static final ChromeColors LIGHT_INACTIVE = new ChromeColors(false, 0xEEF0F3, 0x5C6474, 0xD5D9E0);

    /** What a frame is to show: these colors, or the system's own ({@link #SYSTEM}, colors null). */
    record Look(ChromeColors colors) {

        static final Look SYSTEM = new Look(null);
    }

    /** The frame of one window as this class knows it. On the JavaFX Application Thread only. */
    static final class Frame {

        private final Supplier<String> title;
        private final BooleanSupplier active;
        /** What the frame was last given; null before it was given anything, or after a theme change. */
        private Look shown;
        /** Whether a lookup runs; a change meanwhile is taken by its next attempt. */
        private boolean looking;
        /** Whether the last lookup ended without colors; it is tried again on a theme change only. */
        private boolean lost;

        /**
         * @param title the title the window is found by, read at each attempt; {@code null} when the
         *     window is gone or has none
         * @param active whether the window is the active one, read at each attempt
         */
        Frame(Supplier<String> title, BooleanSupplier active) {
            this.title = Objects.requireNonNull(title, "title");
            this.active = Objects.requireNonNull(active, "active");
        }
    }

    /** A followed stage: its frame and the listener on its focus. */
    private record Followed(Frame frame, ChangeListener<Boolean> focus) {}

    private final WindowChrome chrome;
    private final Consumer<String> reporter;
    private final ObservableValue<ThemeMode> mode;
    private final Map<Stage, Followed> followed = new HashMap<>();
    private final ChangeListener<Object> themeChanged = (observable, before, after) -> refreshAll();
    private final MapChangeListener<String, Object> contrastChanged = change -> {
        if (HIGH_CONTRAST.equals(change.getKey())) {
            refreshAll();
        }
    };
    private final ListChangeListener<Window> windowsChanged = change -> {
        while (change.next()) {
            if (change.wasRemoved()) {
                for (Window window : change.getRemoved()) {
                    forget(window);
                }
            }
            if (change.wasAdded()) {
                for (Window window : change.getAddedSubList()) {
                    follow(window);
                }
            }
        }
    };
    private boolean reported;

    /**
     * @param log the application's logger; it is used from a thread of its own
     */
    TitleBarTint(WindowChrome chrome, ObservableValue<ThemeMode> mode, Logger log) {
        this(chrome, mode, offThisThread(log));
    }

    /**
     * @param reporter takes the one report of a run, on the JavaFX Application Thread, and must return at
     *     once
     */
    TitleBarTint(WindowChrome chrome, ObservableValue<ThemeMode> mode, Consumer<String> reporter) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.chrome = Objects.requireNonNull(chrome, "chrome");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (!chrome.available()) {
            report("every window keeps the system's frame: " + chrome.detail());
            return;
        }
        mode.addListener(themeChanged);
        Platform.getPreferences().colorSchemeProperty().addListener(themeChanged);
        Platform.getPreferences().addListener(contrastChanged);
        Window.getWindows().addListener(windowsChanged);
        for (Window window : List.copyOf(Window.getWindows())) {
            follow(window);
        }
    }

    /** Stops following the windows and the theme. The frames keep the colors they have. */
    void close() {
        mode.removeListener(themeChanged);
        Platform.getPreferences().colorSchemeProperty().removeListener(themeChanged);
        Platform.getPreferences().removeListener(contrastChanged);
        Window.getWindows().removeListener(windowsChanged);
        followed.forEach((stage, tracked) -> stage.focusedProperty().removeListener(tracked.focus()));
        followed.clear();
    }

    /**
     * The colors of a mode; {@link ThemeMode#SYSTEM} takes the scheme the system prefers now.
     *
     * @param active whether the window is the active one; an inactive one has the dimmer caption text
     */
    static ChromeColors colors(ThemeMode mode, ColorScheme system, boolean active) {
        boolean dark = switch (Objects.requireNonNull(mode, "mode")) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> system == ColorScheme.DARK;
        };
        if (dark) {
            return active ? DARK : DARK_INACTIVE;
        }
        return active ? LIGHT : LIGHT_INACTIVE;
    }

    /** What a frame is to show: the system's own colors under a high-contrast theme, else those of the mode. */
    static Look look(ThemeMode mode, ColorScheme system, boolean highContrast, boolean active) {
        return highContrast ? Look.SYSTEM : new Look(colors(mode, system, active));
    }

    /** Whether the system's high-contrast theme is on, by the platform preferences. */
    static boolean highContrast(Map<String, Object> preferences) {
        return Boolean.TRUE.equals(preferences.get(HIGH_CONTRAST));
    }

    /** Whether a lookup that found nothing is tried again; {@code attempt} counts from 1. */
    static boolean retries(WindowChrome.Result result, int attempt) {
        return result == WindowChrome.Result.NOT_FOUND && attempt < ATTEMPTS;
    }

    /**
     * Whether a frame is to be given {@code wanted} now: not while a lookup for it runs (that lookup takes
     * the change), not when its last lookup was lost (until the theme changes), and not when it shows it
     * already.
     *
     * @param shown what the frame was last given, null if nothing
     */
    static boolean due(Look shown, Look wanted, boolean looking, boolean lost) {
        return !looking && !lost && !wanted.equals(shown);
    }

    /** One attempt on the frame of one window: the system's own colors back, or the colors of the look. */
    static WindowChrome.Result tint(WindowChrome chrome, String title, Look look) {
        return look.colors() == null ? chrome.reset(title) : chrome.apply(title, look.colors());
    }

    /**
     * Gives a frame the colors it should show, unless it shows them already or a lookup for it runs.
     *
     * @param themeChanged true when the theme, the high-contrast theme or the window itself is new: what
     *     the frame shows is then not trusted, and a frame left without colors is tried again
     */
    void refresh(Frame frame, boolean themeChanged) {
        if (themeChanged) {
            frame.shown = null;
            frame.lost = false;
        }
        if (!due(frame.shown, wanted(frame), frame.looking, frame.lost)) {
            return;
        }
        frame.looking = true;
        lookUp(frame, 1);
    }

    private void follow(Window window) {
        if (!(window instanceof Stage stage) || followed.containsKey(stage)) {
            return;
        }
        Frame frame = new Frame(() -> titleOf(stage), stage::isFocused);
        ChangeListener<Boolean> focus = (observable, before, after) -> refresh(frame, false);
        stage.focusedProperty().addListener(focus);
        followed.put(stage, new Followed(frame, focus));
        refresh(frame, true);
    }

    /** A stage that is hidden: shown again it has a new native window, which is followed afresh. */
    private void forget(Window window) {
        if (window instanceof Stage stage) {
            Followed gone = followed.remove(stage);
            if (gone != null) {
                stage.focusedProperty().removeListener(gone.focus());
            }
        }
    }

    private void refreshAll() {
        for (Followed tracked : List.copyOf(followed.values())) {
            refresh(tracked.frame(), true);
        }
    }

    private Look wanted(Frame frame) {
        return look(
                mode.getValue(),
                Platform.getPreferences().getColorScheme(),
                highContrast(Platform.getPreferences()),
                frame.active.getAsBoolean());
    }

    /** The title a stage is found by; {@code null} for a stage not showing or one without a title. */
    private static String titleOf(Stage stage) {
        if (!stage.isShowing()) {
            return null;
        }
        String title = stage.getTitle();
        return title == null || title.isEmpty() ? null : title;
    }

    /**
     * Tints the window, and while it is not found tries again: on the next turns of this thread first,
     * then every {@value #LATER_RETRY_MILLIS} ms, {@value #ATTEMPTS} attempts in all; a window left
     * without colors is reported. The title and the wanted colors are read again at each attempt; a
     * {@code null} title (the window is gone or has no title) ends the lookup.
     *
     * @param attempt the number of this attempt, from 1
     */
    private void lookUp(Frame frame, int attempt) {
        String title = frame.title.get();
        if (title == null) {
            frame.looking = false;
            return;
        }
        Look wanted = wanted(frame);
        WindowChrome.Result result = tint(chrome, title, wanted);
        if (retries(result, attempt)) {
            Runnable again = () -> lookUp(frame, attempt + 1);
            if (attempt < QUICK_ATTEMPTS) {
                Platform.runLater(again);
            } else {
                PauseTransition wait = new PauseTransition(Duration.millis(LATER_RETRY_MILLIS));
                wait.setOnFinished(event -> again.run());
                wait.play();
            }
            return;
        }
        frame.looking = false;
        if (result == WindowChrome.Result.APPLIED) {
            frame.shown = wanted;
            return;
        }
        frame.shown = null;
        frame.lost = true;
        report("the window \"" + title + "\" keeps the system's frame after " + attempt + " attempts (" + result + "): "
                + chrome.detail());
    }

    /** Hands over the first window left without colors; the later ones are not reported. */
    private void report(String message) {
        if (reported) {
            return;
        }
        reported = true;
        reporter.accept(message);
    }

    /**
     * Logs from a thread of its own: a log line is a file write (logback.xml has no asynchronous appender)
     * and the report comes on the JavaFX Application Thread. It happens at most once per run, and the first
     * report can come from the constructor, before the one executor that could take it (the journal's
     * thread of the monitoring runtime) exists.
     */
    private static Consumer<String> offThisThread(Logger log) {
        Objects.requireNonNull(log, "log");
        return message -> Thread.ofPlatform()
                .name("srm-title-bar-log")
                .daemon(true)
                .start(() -> log.info("Title bar: {}.", message));
    }
}
