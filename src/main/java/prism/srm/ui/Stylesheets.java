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

    /** The stylesheet the pages of system metrics share: metric panel, facts, page notice. */
    public static String metric() {
        return location("metric.css");
    }

    /** The stylesheet of tables. */
    public static String table() {
        return location("table.css");
    }

    /** The stylesheet of the history graph. */
    public static String graph() {
        return location("graph.css");
    }

    private static String location(String resource) {
        URL url = Stylesheets.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing stylesheet resource: " + resource);
        }
        return url.toExternalForm();
    }
}
