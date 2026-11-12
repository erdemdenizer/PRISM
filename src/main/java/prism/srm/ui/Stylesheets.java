package prism.srm.ui;

import java.net.URL;

/**
 * Locations of the stylesheets bundled with the application, in the external form that {@code
 * getStylesheets()} of a scene or a parent takes.
 *
 * <p>The application stylesheet holds the design tokens and goes on the scene. A component
 * stylesheet is added by the component itself, to its own root, and uses the tokens only.
 *
 * <p>Every method throws {@link IllegalStateException} if its stylesheet is missing from the
 * build, so a broken resource path fails loudly instead of silently rendering the default look.
 */
public final class Stylesheets {

    private Stylesheets() {}

    /** The application stylesheet: design tokens and base control styling. */
    public static String app() {
        return location("app.css");
    }

    /**
     * The stylesheet that puts every type role on the system font, for a scene whose bundled
     * typeface could not be loaded. It goes on the scene after {@link #app()}.
     */
    public static String fontsFallback() {
        return location("fonts-fallback.css");
    }

    /** The stylesheet of the shell: navigation and status bar. */
    public static String shell() {
        return location("shell.css");
    }

    /** The stylesheet of the Overview page. */
    public static String overview() {
        return location("overview.css");
    }

    /** The stylesheet of the Performance page. */
    public static String performance() {
        return location("performance.css");
    }

    /** The stylesheet of the Processes page. */
    public static String processes() {
        return location("processes.css");
    }

    /** The stylesheet of the Logs page. */
    public static String logs() {
        return location("logs.css");
    }

    /** The stylesheet the pages of system metrics share: metric panel, facts, page notice. */
    public static String metric() {
        return location("metric.css");
    }

    /** The stylesheet of the controls pages share: the line about an action. */
    public static String control() {
        return location("control.css");
    }

    /** The stylesheet of the Settings page. */
    public static String settings() {
        return location("settings.css");
    }

    /** The stylesheet of the Limits page. */
    public static String limits() {
        return location("limits.css");
    }

    /** The stylesheet of the OOM Guard page and its dialogs. */
    public static String oom() {
        return location("oom.css");
    }

    /** The stylesheet of tables. */
    public static String table() {
        return location("table.css");
    }

    /** The stylesheet of the history graph. */
    public static String graph() {
        return location("graph.css");
    }

    /** The token mapping of a Windows high-contrast theme ({@link HighContrast}). */
    public static String highContrast() {
        return location("hc.css");
    }

    private static String location(String resource) {
        URL url = Stylesheets.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing stylesheet resource: " + resource);
        }
        return url.toExternalForm();
    }
}
