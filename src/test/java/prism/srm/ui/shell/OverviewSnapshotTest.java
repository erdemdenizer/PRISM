package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Renders the shell with the Overview page in each of its states, in both themes, at the default
 * scene size and at the minimum window size, for inspection against docs/UI_GUIDE.md.
 *
 * <p>The PNGs are for the eye. What a test can assert is asserted: each render produces a file,
 * and the scene does not grow while snapshots arrive.
 */
@Tag("ui-snapshot")
class OverviewSnapshotTest {

    /** A scene size, named for the file. */
    private record Size(int width, int height) {}

    private static final Size DEFAULT = new Size(1100, 700);
    private static final Size MINIMUM = new Size(720, 480);
    /** What is left of the minimum window for the scene once Windows 11 has drawn its frame. */
    private static final Size MINIMUM_INSIDE_FRAME = new Size(704, 441);

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static Stream<Arguments> statesThemesAndSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (Size size : new Size[] {DEFAULT, MINIMUM}) {
                cases.add(Arguments.of("overview-loading", theme, size, (Consumer<Shell>) shell -> {}));
                cases.add(Arguments.of("overview-first-cycle", theme, size, showing(Snapshots.firstCycle())));
                cases.add(Arguments.of("overview-live", theme, size, showing(Snapshots.live())));
                cases.add(Arguments.of("overview-unavailable", theme, size, showing(Snapshots.nothingAvailable())));
                cases.add(Arguments.of(
                        "overview-partly-unavailable", theme, size, showing(Snapshots.partlyUnavailable())));
                cases.add(Arguments.of("overview-extreme", theme, size, showing(Snapshots.extreme())));
                cases.add(Arguments.of("overview-error", theme, size, (Consumer<Shell>)
                        shell -> shell.showFailures(Set.of(MonitoringFailure.STARTUP))));
                // Both ran for a minute; then one of the two monitors ended.
                cases.add(Arguments.of("overview-system-stopped", theme, size, (Consumer<Shell>) shell -> {
                    shell.showSnapshot(Snapshots.live());
                    shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
                }));
                cases.add(Arguments.of("overview-process-stopped", theme, size, (Consumer<Shell>) shell -> {
                    shell.showSnapshot(Snapshots.live());
                    shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                }));
            }
            cases.add(Arguments.of("overview-live", theme, MINIMUM_INSIDE_FRAME, showing(Snapshots.live())));
            // The failure states where they have the least room.
            cases.add(Arguments.of("overview-system-stopped", theme, MINIMUM_INSIDE_FRAME, (Consumer<Shell>) shell -> {
                shell.showSnapshot(Snapshots.live());
                shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
            }));
            cases.add(Arguments.of("overview-process-stopped", theme, MINIMUM_INSIDE_FRAME, (Consumer<Shell>) shell -> {
                shell.showSnapshot(Snapshots.live());
                shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
            }));
            cases.add(Arguments.of("overview-all-stopped", theme, MINIMUM, (Consumer<Shell>) shell -> {
                shell.showSnapshot(Snapshots.live());
                shell.showFailures(Set.of(
                        MonitoringFailure.SYSTEM_MONITORING_STOPPED, MonitoringFailure.PROCESS_MONITORING_STOPPED));
            }));
            cases.add(Arguments.of("overview-extreme", theme, MINIMUM_INSIDE_FRAME, showing(Snapshots.extreme())));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("statesThemesAndSizes")
    void rendersEveryStateInBothThemesAtBothSizes(String name, Theme theme, Size size, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name, theme, size.width(), size.height(), () -> shell(state));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** Number notation of another locale, where it is longest: the extreme values at the minimum. */
    @Test
    void rendersTurkishNumberNotation() {
        Path png = SnapshotHarness.render(
                "overview-extreme-tr", Theme.LIGHT, MINIMUM_INSIDE_FRAME.width(), MINIMUM_INSIDE_FRAME.height(), () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.forLanguageTag("tr-TR")),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            LOG_FOLDER,
                            identity -> {});
                    shell.showSnapshot(Snapshots.extreme());
                    return shell;
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** A folder name cannot be broken across lines by a hyphen; it has to wrap or be cut. */
    @ParameterizedTest
    @MethodSource("themes")
    void rendersAFailedStartWithAVeryLongLogFolder(Theme theme) {
        String folder = "C:\\Users\\a-user-with-a-rather-long-account-name\\AppData\\Local\\Prism"
                + "\\SystemResourceManager\\logs-of-a-redirected-profile-on-a-network-share\\logs";
        Path png = SnapshotHarness.render(
                "overview-error-long-path",
                theme,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> failedStart(folder));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /**
     * When the window is too small for the least the page needs, the status bar must stay in the
     * window and the rest of the page must stay reachable: the page area scrolls.
     */
    @Test
    void aPageThatDoesNotFitScrollsAndLeavesTheStatusBarInPlace() {
        String folder = "C:\\Users\\a-user-with-a-rather-long-account-name\\AppData\\Local\\Prism"
                + "\\SystemResourceManager\\logs-of-a-redirected-profile-on-a-network-share\\logs";
        double[] seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> failedStart(folder),
                root -> {
                    Shell shell = (Shell) root;
                    ScrollPane area = (ScrollPane) shell.getCenter();
                    Bounds status = shell.getBottom().getBoundsInParent();
                    return new double[] {
                        status.getMaxY(),
                        area.getContent().getLayoutBounds().getHeight(),
                        area.getViewportBounds().getHeight()
                    };
                });

        assertThat(seen[0]).as("bottom edge of the status bar").isLessThanOrEqualTo(MINIMUM_INSIDE_FRAME.height());
        assertThat(seen[1]).as("height of the page").isGreaterThan(seen[2]);
    }

    /** At the minimum size and with ordinary content nothing needs to scroll. */
    @ParameterizedTest
    @MethodSource("themes")
    void ordinaryContentFitsTheMinimumWindowWithoutScrolling(Theme theme) {
        double[] seen = SnapshotHarness.inspect(
                theme,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> shell(showing(Snapshots.extreme())),
                root -> {
                    ScrollPane area = (ScrollPane) ((Shell) root).getCenter();
                    return new double[] {
                        area.getContent().getLayoutBounds().getHeight(),
                        area.getViewportBounds().getHeight(),
                        area.getContent().getLayoutBounds().getWidth(),
                        area.getViewportBounds().getWidth()
                    };
                });

        assertThat(seen[0]).as("height of the page").isLessThanOrEqualTo(seen[1]);
        assertThat(seen[2]).as("width of the page").isLessThanOrEqualTo(seen[3]);
    }

    /** Extra space goes to the graphs; nothing else may stretch or drift apart. */
    @Test
    void rendersALargeWindow() {
        Path png = SnapshotHarness.render(
                "overview-live", Theme.LIGHT, 1920, 1080, () -> shell(showing(Snapshots.live())));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** The ring a keyboard user sees on the navigation; the harness has no real focus to give. */
    @ParameterizedTest
    @MethodSource("themes")
    void rendersTheKeyboardFocusOfTheNavigation(Theme theme) {
        Path png = SnapshotHarness.render("overview-focus", theme, MINIMUM.width(), MINIMUM.height(), () -> {
            Shell shell = shell(showing(Snapshots.live()));
            Sidebar sidebar = (Sidebar) shell.getLeft();
            sidebar.entry(Destination.OVERVIEW)
                    .pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Stream<Theme> themes() {
        return Stream.of(Theme.values());
    }

    /**
     * Two hundred updates with changing values and states must leave the scene with exactly the
     * nodes it started with: an update changes texts and redraws Canvases, nothing else.
     */
    @Test
    void theSceneDoesNotGrowWhileSnapshotsArrive() {
        int[] counts = SnapshotHarness.inspect(
                Theme.LIGHT, DEFAULT.width(), DEFAULT.height(), () -> shell(shell -> {}), root -> {
                    Shell shell = (Shell) root;
                    shell.showSnapshot(Snapshots.live());
                    settle(shell);
                    int before = countNodes(shell);
                    SystemSnapshot[] rotation = {
                        Snapshots.firstCycle(), Snapshots.live(), Snapshots.nothingAvailable(), Snapshots.extreme()
                    };
                    for (int update = 0; update < 200; update++) {
                        shell.showSnapshot(rotation[update % rotation.length]);
                        settle(shell);
                    }
                    shell.showSnapshot(Snapshots.live());
                    settle(shell);
                    return new int[] {before, countNodes(shell)};
                });

        assertThat(counts[0]).isGreaterThan(20);
        assertThat(counts[1]).isEqualTo(counts[0]);
    }

    private static Shell failedStart(String logFolder) {
        Shell shell =
                new Shell(new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, logFolder, identity -> {});
        shell.showFailures(Set.of(MonitoringFailure.STARTUP));
        return shell;
    }

    private static Shell shell(Consumer<Shell> state) {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
        state.accept(shell);
        return shell;
    }

    private static Consumer<Shell> showing(SystemSnapshot snapshot) {
        return shell -> shell.showSnapshot(snapshot);
    }

    private static void settle(Parent root) {
        root.applyCss();
        root.layout();
    }

    private static int countNodes(Node node) {
        int count = 1;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += countNodes(child);
            }
        }
        return count;
    }
}
