package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.MonitoringFailure;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The Overview fits the smallest window without scrolling (UI_GUIDE: "the Overview fits without
 * scrolling"; UI audit OP-08, fix round H): at 720 x 480 at 100 % and at 125 % (a scene of 705.6 x 442.4
 * laid out at a render scale of 1.25), live and in the failure states, with the facts under the metrics
 * in view.
 */
@Tag("ui-snapshot")
class OverviewFitSceneTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    static Map<String, Consumer<Shell>> states() {
        Map<String, Consumer<Shell>> states = new java.util.LinkedHashMap<>();
        states.put("live", shell -> shell.showSnapshot(Snapshots.live()));
        states.put("error", shell -> shell.showFailures(Set.of(MonitoringFailure.STARTUP)));
        states.put("system-stopped", shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        });
        states.put("process-stopped", shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
        });
        states.put("all-stopped", shell -> {
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(
                    Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED, MonitoringFailure.PROCESS_MONITORING_STOPPED));
        });
        return states;
    }

    static Stream<Arguments> cases() {
        List<Arguments> all = new ArrayList<>();
        // The 720 x 480 window: at 125 % (K8), at 100 % (the round G estimate) and inside the frame.
        double[][] sizes = {{705.6, 442.4, 1.25}, {706, 435, 1.0}, {704, 441, 1.0}};
        for (double[] size : sizes) {
            for (String state : states().keySet()) {
                all.add(Arguments.of(state, size[0], size[1], size[2]));
            }
        }
        return all.stream();
    }

    private record Measured(
            double least, double viewport, boolean scrolls, double factsBottom, double viewportBottom) {}

    @ParameterizedTest(name = "{0} {1}x{2} at {3}")
    @MethodSource("cases")
    void theOverviewFitsTheSmallestWindowWithItsFactsInView(String state, double width, double height, double scale) {
        Measured seen = ScaledScenes.inspect(
                Theme.DARK,
                width,
                height,
                scale,
                () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            LOG_FOLDER,
                            identity -> {});
                    states().get(state).accept(shell);
                    return shell;
                },
                OverviewFitSceneTest::measure);

        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(seen.scrolls())
                    .as("OP-08: no vertical scroll bar (least %s, viewport %s)", seen.least(), seen.viewport())
                    .isFalse();
            soft.assertThat(seen.factsBottom())
                    .as("OP-08: the facts under the metrics are in view")
                    .isLessThanOrEqualTo(seen.viewportBottom() + 0.5);
        });
    }

    /** Each state at the scene of a 720 x 480 window at 125 % (rendered at 100 %), for the eye. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stateNames")
    void rendersEachStateInTheSmallestWindowAt125(String state) {
        for (Theme theme : Theme.values()) {
            Path png = SnapshotHarness.render("overview-" + state + "-real125", theme, 706, 442, () -> {
                Shell shell = new Shell(
                        new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
                states().get(state).accept(shell);
                return shell;
            });
            assertThat(png).isRegularFile();
        }
    }

    static Stream<String> stateNames() {
        return states().keySet().stream();
    }

    private static Measured measure(Parent root) {
        ScrollPane area = ((Shell) root).pageArea();
        Region page = (Region) area.getContent();
        Node viewport = area.lookup(".viewport");
        Bounds viewportBounds = viewport.localToScene(viewport.getLayoutBounds());
        Node facts = page.lookup(".overview-facts");
        Bounds factsBounds = facts.localToScene(facts.getLayoutBounds());
        boolean scrolls = area.lookupAll(".scroll-bar").stream()
                .anyMatch(node -> node instanceof ScrollBar bar
                        && bar.getOrientation() == Orientation.VERTICAL
                        && bar.isVisible());
        return new Measured(
                page.minHeight(area.getViewportBounds().getWidth()),
                area.getViewportBounds().getHeight(),
                scrolls,
                factsBounds.getMaxY(),
                viewportBounds.getMaxY());
    }
}
