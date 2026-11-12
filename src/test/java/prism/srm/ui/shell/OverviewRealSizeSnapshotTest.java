package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.HistoryGraph;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Round G (overview area): the Overview at the scene sizes of real Windows windows (K8; the numbers in
 * the file names are scene sizes, the marker {@code -real} says so), and the states that the round
 * added, for the eye: a part under the pointer and with the keyboard focus (UI-1, K4), and the reading
 * of past values on two graphs (K5). The harness has no pointer and no focus to give: the states are
 * set as pseudo-classes, and the cursors through the keys.
 */
@Tag("ui-snapshot")
class OverviewRealSizeSnapshotTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static Stream<Arguments> themesAndRealSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (RealSceneSizes.Size size : RealSceneSizes.ALL) {
                cases.add(Arguments.of(theme, size.width(), size.height()));
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("themesAndRealSizes")
    void rendersTheLiveOverviewAtTheSceneSizesOfRealWindows(Theme theme, int width, int height) {
        Path png =
                SnapshotHarness.render("overview-live-real", theme, width, height, OverviewRealSizeSnapshotTest::shell);

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /**
     * The pointer over the CPU panel and over the Processes fact; the keyboard focus on the Network panel
     * and on the Managed fact; the reading of past values on the Memory graph (12 s ago) and on the
     * Network graph (23 s ago).
     */
    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("themesAndRealSizes")
    void rendersThePartsUnderThePointerWithTheFocusAndReadingPastValues(Theme theme, int width, int height) {
        Path png = SnapshotHarness.render(
                "overview-states-real", theme, width, height, OverviewRealSizeSnapshotTest::shell, root -> {
                    Parent page = (Parent) ((Shell) root).pageArea().getContent();
                    page.lookup(".metric-cpu").pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
                    page.lookup(".metric-network")
                            .pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
                    fact(page, "Processes").pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
                    fact(page, "Managed").pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
                    read(page, ".metric-memory", 12);
                    read(page, ".metric-network", 23);
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** The cursor on a graph, as Left pressed so often from the newest sample puts it. */
    private static void read(Parent page, String panel, int age) {
        HistoryGraph graph = (HistoryGraph) page.lookup(panel + " .history-graph");
        for (int step = 0; step <= age; step++) {
            graph.moveCursor(KeyCode.LEFT, false);
        }
    }

    private static Node fact(Parent page, String caption) {
        return page.lookupAll(".fact").stream()
                .filter(fact -> ((Label)
                                ((Parent) fact).getChildrenUnmodifiable().getFirst())
                        .getText()
                        .equals(caption))
                .findFirst()
                .orElseThrow();
    }

    private static Shell shell() {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
        // Values on every page, OOM Guard and the managed processes included.
        KeyboardRoutesSceneTest.fill(shell);
        shell.showRecovered(List.of(LimitsFixtures.userLeft(LimitsFixtures.identity(30), "java.exe")));
        return shell;
    }
}
