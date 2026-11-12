package prism.srm.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StylesheetsTest {

    /** A hex color: # followed by 3, 4, 6 or 8 hex digits and nothing that continues a word. */
    private static final Pattern HEX_COLOR = Pattern.compile("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\\b");

    @Test
    void appStylesheetIsBundledAndReadable() throws IOException {
        String location = Stylesheets.app();

        assertThat(location).endsWith("/prism/srm/ui/app.css");
        assertThat(read(location)).contains(".root").contains("-srm-accent");
    }

    @Test
    void everyComponentStylesheetIsBundled() throws IOException {
        assertThat(Stylesheets.shell()).endsWith("/prism/srm/ui/shell.css");
        assertThat(read(Stylesheets.shell())).contains(".sidebar").contains(".status-bar");
        assertThat(Stylesheets.overview()).endsWith("/prism/srm/ui/overview.css");
        assertThat(read(Stylesheets.overview())).contains(".overview").contains(".metric-grid");
        assertThat(Stylesheets.performance()).endsWith("/prism/srm/ui/performance.css");
        assertThat(read(Stylesheets.performance())).contains(".performance").contains(".section-heading");
        assertThat(Stylesheets.processes()).endsWith("/prism/srm/ui/processes.css");
        assertThat(read(Stylesheets.processes())).contains(".processes").contains(".process-details");
        assertThat(Stylesheets.metric()).endsWith("/prism/srm/ui/metric.css");
        assertThat(read(Stylesheets.metric())).contains(".metric-panel").contains(".page-error");
        assertThat(Stylesheets.table()).endsWith("/prism/srm/ui/table.css");
        assertThat(read(Stylesheets.table())).contains(".table-view").contains(".table-row-cell");
        assertThat(Stylesheets.fontsFallback()).endsWith("/prism/srm/ui/fonts-fallback.css");
        assertThat(read(Stylesheets.fontsFallback())).contains("System").contains(".text-page-title");
        assertThat(Stylesheets.control()).endsWith("/prism/srm/ui/control.css");
        assertThat(read(Stylesheets.control()))
                .contains(".action-result-headline")
                .contains(".cpu-limit-field");
        assertThat(Stylesheets.limits()).endsWith("/prism/srm/ui/limits.css");
        assertThat(read(Stylesheets.limits())).contains(".limits").contains(".limits-notice");
        assertThat(Stylesheets.settings()).endsWith("/prism/srm/ui/settings.css");
        assertThat(read(Stylesheets.settings())).contains(".settings").contains(".settings-problem");
        assertThat(Stylesheets.graph()).endsWith("/prism/srm/ui/graph.css");
        assertThat(read(Stylesheets.graph()))
                .contains(".history-graph")
                .contains(".history-grid")
                .contains("-srm-graph-line");
    }

    /** docs/UI_GUIDE.md: colors come from tokens; no hex values in component CSS. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "shell.css",
                "overview.css",
                "performance.css",
                "processes.css",
                "metric.css",
                "table.css",
                "graph.css",
                "control.css",
                "limits.css",
                "settings.css",
                "fonts-fallback.css"
            })
    void aComponentStylesheetNamesNoColorOfItsOwn(String stylesheet) throws IOException {
        String css = read(StylesheetsTest.class.getResource(stylesheet).toExternalForm());

        assertThat(HEX_COLOR.matcher(css).find())
                .as("%s contains a hex color", stylesheet)
                .isFalse();
        assertThat(css).doesNotContain("rgb(").doesNotContain("rgba(").doesNotContain("hsb(");
    }

    /**
     * docs/UI_GUIDE.md: the accent is never used as text. On the window background it falls short
     * of the contrast body text needs, so no stylesheet may set it as a text fill.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "app.css",
                "fonts-fallback.css",
                "shell.css",
                "overview.css",
                "performance.css",
                "processes.css",
                "metric.css",
                "table.css",
                "graph.css",
                "control.css",
                "limits.css"
            })
    void noStylesheetUsesTheAccentAsText(String stylesheet) throws IOException {
        String css = read(StylesheetsTest.class.getResource(stylesheet).toExternalForm());

        assertThat(css.replace(" ", ""))
                .as("%s sets the accent as a text fill", stylesheet)
                .doesNotContain("-fx-text-fill:-srm-accent;");
    }

    @Test
    void theHexColorCheckRecognizesAHexColor() {
        assertThat(HEX_COLOR.matcher("-fx-background-color: #0e7c86;").find()).isTrue();
        assertThat(HEX_COLOR.matcher("-fx-background-color: #fff;").find()).isTrue();
        assertThat(HEX_COLOR.matcher("-fx-background-color: -srm-accent;").find())
                .isFalse();
    }

    private static String read(String location) throws IOException {
        try (InputStream in = URI.create(location).toURL().openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
