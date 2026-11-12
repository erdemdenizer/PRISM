package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.css.PseudoClass;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.HistoryGraph;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.performance.PerformancePage;
import prism.srm.ui.performance.PerformanceSnapshots;
import prism.srm.ui.performance.Section;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Round G (overview area): the Performance page at the scene sizes of real Windows windows (K8; the
 * numbers in the file names are scene sizes, the marker {@code -real} says so), every section with the
 * processor grid and the tables filled (OP-09), and the states the round changed, for the eye: the
 * reading of a past value with the keyboard focus on the graph (K5), 128 processors (OP-10, OP-17),
 * and the reasons for missing values (OP-07).
 */
@Tag("ui-snapshot")
class PerformanceRealSizeSnapshotTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static Stream<Arguments> sectionsThemesAndRealSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Section section : Section.values()) {
            for (Theme theme : Theme.values()) {
                for (RealSceneSizes.Size size : RealSceneSizes.ALL) {
                    cases.add(Arguments.of(section, theme, size.width(), size.height()));
                }
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}x{3}")
    @MethodSource("sectionsThemesAndRealSizes")
    void rendersEverySectionLiveAtTheSceneSizesOfRealWindows(Section section, Theme theme, int width, int height) {
        Path png = SnapshotHarness.render(
                "performance-" + section.name().toLowerCase(Locale.ROOT) + "-live-real",
                theme,
                width,
                height,
                () -> performance(section, shell -> shell.showSnapshot(PerformanceSnapshots.live())));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Stream<Arguments> themesAndSomeRealSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : new int[][] {{706, 435}, {1086, 655}, {1426, 855}}) {
                cases.add(Arguments.of(theme, size[0], size[1]));
            }
        }
        return cases.build();
    }

    /** 128 logical processors: rows of a regular length (OP-10), and the total keeps its room (OP-17). */
    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("themesAndSomeRealSizes")
    void rendersOneHundredTwentyEightProcessors(Theme theme, int width, int height) {
        Path png = SnapshotHarness.render(
                "performance-cpu-128-processors-real",
                theme,
                width,
                height,
                () -> performance(Section.CPU, shell -> shell.showSnapshot(PerformanceSnapshots.live(128))));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** 128 logical processors at the scenes of real windows at 125 % (fix round H, OP-17), rendered at 100 %. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenesAt125")
    void rendersOneHundredTwentyEightProcessorsAt125(ScaledScenes.Size size) {
        int[] whole = size.whole();
        Path png = SnapshotHarness.render(
                "performance-cpu-128-processors-real125",
                Theme.DARK,
                whole[0],
                whole[1],
                () -> performance(Section.CPU, shell -> shell.showSnapshot(PerformanceSnapshots.live(128))));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    static Stream<ScaledScenes.Size> scenesAt125() {
        return ScaledScenes.AT_125.stream();
    }

    /** The keyboard focus on the graph of the network total, and the reading of the sample 23 s ago. */
    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("themesAndSomeRealSizes")
    void rendersTheReadingOfAPastValueWithTheKeyboardFocusOnTheGraph(Theme theme, int width, int height) {
        Path png = SnapshotHarness.render(
                "performance-network-reading-real",
                theme,
                width,
                height,
                () -> performance(Section.NETWORK, shell -> shell.showSnapshot(PerformanceSnapshots.live())),
                root -> {
                    HistoryGraph graph = (HistoryGraph)
                            ((Shell) root).pageArea().getContent().lookup(".history-graph");
                    graph.pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
                    for (int step = 0; step <= 23; step++) {
                        graph.moveCursor(KeyCode.LEFT, false);
                    }
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /**
     * OP-07: why values are missing. A system that does not say which devices are physical (never a
     * total), a system that gives no value per processor, and monitoring that stopped.
     */
    @ParameterizedTest(name = "{0} {1}x{2}")
    @MethodSource("themesAndSomeRealSizes")
    void rendersTheReasonsForMissingValues(Theme theme, int width, int height) {
        Path layers = SnapshotHarness.render(
                "performance-network-unknown-layers-real",
                theme,
                width,
                height,
                () -> performance(Section.NETWORK, showing(PerformanceSnapshots.unknownLayers())));
        Path processors = SnapshotHarness.render(
                "performance-cpu-unavailable-real",
                theme,
                width,
                height,
                () -> performance(Section.CPU, showing(Snapshots.nothingAvailable())));
        Path stopped = SnapshotHarness.render(
                "performance-disk-system-stopped-real",
                theme,
                width,
                height,
                () -> performance(Section.DISK, shell -> {
                    shell.showSnapshot(PerformanceSnapshots.live());
                    shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
                }));
        Path overview = SnapshotHarness.render("overview-system-stopped-real", theme, width, height, () -> {
            Shell shell =
                    new Shell(new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, id -> {});
            shell.showSnapshot(Snapshots.live());
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            return shell;
        });

        for (Path png : new Path[] {layers, processors, stopped, overview}) {
            assertThat(png).isRegularFile().isNotEmptyFile();
        }
    }

    private static Consumer<Shell> showing(SystemSnapshot snapshot) {
        return shell -> shell.showSnapshot(snapshot);
    }

    /** A shell in the given state, with the given section of the Performance page on screen. */
    private static Shell performance(Section section, Consumer<Shell> state) {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
        state.accept(shell);
        shell.navigation().entry(Destination.PERFORMANCE).fire();
        ((PerformancePage) shell.pageArea().getContent()).select(section);
        return shell;
    }
}
