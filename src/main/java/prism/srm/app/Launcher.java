package prism.srm.app;

/**
 * Entry point for classpath launches, as used by the packaged application.
 *
 * <p>The JavaFX launcher refuses to start when the main class itself extends {@code Application}
 * and JavaFX is on the classpath rather than the module path, so the real application class is
 * started from this plain class instead.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        App.launch(App.class, args);
    }
}
