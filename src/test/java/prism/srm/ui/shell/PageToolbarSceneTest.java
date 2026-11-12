package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI lab v2: the title line of a page stands in the page toolbar, out of the page. The rules of the
 * page still reach it (the gaps and the alignment of the Processes tools), centred on the band of the
 * header, in a narrow window too.
 */
@Tag("ui-snapshot")
class PageToolbarSceneTest {

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"720, 480, 40", "800, 600, 52"})
    void theToolsOfTheTitleLineKeepThePageRulesAndAreCentredOnTheBand(int width, int height, double band) {
        List<Object> seen = SnapshotHarness.inspect(
                Theme.DARK,
                width,
                height,
                () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, "logs", identity -> {});
                    shell.showSnapshot(Snapshots.live());
                    shell.navigation().select(Destination.PROCESSES);
                    return shell;
                },
                root -> {
                    Shell shell = (Shell) root;
                    HBox tools = (HBox) shell.toolbar().lookup(".process-toolbar");
                    Node details = shell.toolbar().lookup(".process-details-open");
                    return List.of(
                            tools.getSpacing(), centreY(details) == band / 2, root.lookup(".nav-toggle") == null);
                });

        assertThat(seen).containsExactly(8.0, true, true);
    }

    private static double centreY(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        return (bounds.getMinY() + bounds.getMaxY()) / 2;
    }
}
