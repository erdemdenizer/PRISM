package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.performance.PerformanceSnapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Renders the shell with the Performance page: each of its four sections in each of its states,
 * in both themes, at the default scene size and at the minimum window size, for inspection
 * against docs/UI_GUIDE.md.
 *
 * <p>The PNGs are for the eye. What a test can assert is asserted: each render produces a file,
 * the scene does not grow while snapshots arrive, and ordinary content fits the minimum window.
 */
@Tag("ui-snapshot")
class PerformanceSnapshotTest {

    /** A scene size, named for the file. */
    private record Size(int width, int height) {}

    private static final Size DEFAULT = new Size(1100, 700);
    private static final Size MINIMUM = new Size(720, 480);
    /** What is left of the minimum window for the scene once Windows 11 has drawn its frame. */
    private static final Size MINIMUM_INSIDE_FRAME = new Size(704, 441);

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static final String CPU = "CPU";
    private static final String MEMORY = "Memory";
    private static final String DISK = "Disk";
    private static final String NETWORK = "Network";

    private static Stream<Arguments> statesThemesAndSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (Size size : new Size[] {DEFAULT, MINIMUM}) {
                for (String section : new String[] {CPU, MEMORY, DISK, NETWORK}) {
                    String name = "performance-" + section.toLowerCase(Locale.ROOT);
                    cases.add(Arguments.of(name + "-loading", theme, size, section, (Consumer<Shell>) shell -> {}));
                    cases.add(Arguments.of(
                            name + "-first-cycle", theme, size, section, showing(PerformanceSnapshots.firstCycle())));
                    cases.add(Arguments.of(name + "-live", theme, size, section, showing(PerformanceSnapshots.live())));
                    cases.add(Arguments.of(
                            name + "-unavailable", theme, size, section, showing(Snapshots.nothingAvailable())));
                    cases.add(Arguments.of(
                            name + "-extreme", theme, size, section, showing(PerformanceSnapshots.extreme())));
                }
                // The milestone names these: 4 and 64 logical processors (64 is the extreme above),
                // no swap, no network interface.
                cases.add(Arguments.of(
                        "performance-cpu-4-processors", theme, size, CPU, showing(PerformanceSnapshots.live(4))));
                // More than any desktop has: labels of three digits, and many rows of cells.
                cases.add(Arguments.of(
                        "performance-cpu-128-processors", theme, size, CPU, showing(PerformanceSnapshots.live(128))));
                cases.add(Arguments.of(
                        "performance-memory-no-swap", theme, size, MEMORY, showing(PerformanceSnapshots.noSwap())));
                cases.add(Arguments.of(
                        "performance-network-no-interface",
                        theme,
                        size,
                        NETWORK,
                        showing(PerformanceSnapshots.noDevices())));
                cases.add(Arguments.of(
                        "performance-disk-no-file-system",
                        theme,
                        size,
                        DISK,
                        showing(PerformanceSnapshots.noDevices())));
                // A system that does not say which devices are physical: no totals, rates per interface.
                cases.add(Arguments.of(
                        "performance-network-unknown-layers",
                        theme,
                        size,
                        NETWORK,
                        showing(PerformanceSnapshots.unknownLayers())));
                cases.add(Arguments.of(
                        "performance-disk-unknown-layers",
                        theme,
                        size,
                        DISK,
                        showing(PerformanceSnapshots.unknownLayers())));
                cases.add(Arguments.of("performance-cpu-error", theme, size, CPU, (Consumer<Shell>)
                        shell -> shell.showFailures(Set.of(MonitoringFailure.STARTUP))));
                cases.add(Arguments.of(
                        "performance-network-system-stopped", theme, size, NETWORK, (Consumer<Shell>) shell -> {
                            shell.showSnapshot(PerformanceSnapshots.live());
                            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
                        }));
            }
            // Where there is the least room.
            for (String section : new String[] {CPU, MEMORY, DISK, NETWORK}) {
                String name = "performance-" + section.toLowerCase(Locale.ROOT);
                cases.add(Arguments.of(
                        name + "-live", theme, MINIMUM_INSIDE_FRAME, section, showing(PerformanceSnapshots.live())));
                cases.add(Arguments.of(
                        name + "-extreme",
                        theme,
                        MINIMUM_INSIDE_FRAME,
                        section,
                        showing(PerformanceSnapshots.extreme())));
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("statesThemesAndSizes")
    void rendersEverySectionAndStateInBothThemesAtBothSizes(
            String name, Theme theme, Size size, String section, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name, theme, size.width(), size.height(), () -> performance(section, state));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** Number notation of another locale, where it is longest: the extreme values at the minimum. */
    @ParameterizedTest
    @ValueSource(strings = {CPU, MEMORY, DISK, NETWORK})
    void rendersTurkishNumberNotation(String section) {
        Path png = SnapshotHarness.render(
                "performance-" + section.toLowerCase(Locale.ROOT) + "-extreme-tr",
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.forLanguageTag("tr-TR")),
                            ZoneOffset.UTC,
                            Snapshots.CAPACITY,
                            LOG_FOLDER,
                            identity -> {});
                    shell.showSnapshot(PerformanceSnapshots.extreme());
                    open(shell, section);
                    return shell;
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** Extra space goes to the graphs and the tables; nothing else may stretch or drift apart. */
    @ParameterizedTest
    @ValueSource(strings = {CPU, MEMORY, DISK, NETWORK})
    void rendersALargeWindow(String section) {
        Path png = SnapshotHarness.render(
                "performance-" + section.toLowerCase(Locale.ROOT) + "-live",
                Theme.LIGHT,
                1920,
                1080,
                () -> performance(section, showing(PerformanceSnapshots.live())));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /**
     * The ring a keyboard user sees on the choice of the section and on a table with its focused
     * row; the harness has no real focus to give.
     */
    @ParameterizedTest
    @MethodSource("themes")
    void rendersTheKeyboardFocus(Theme theme) {
        PseudoClass focusVisible = PseudoClass.getPseudoClass("focus-visible");
        Path tabs = SnapshotHarness.render("performance-focus-tabs", theme, MINIMUM.width(), MINIMUM.height(), () -> {
            Shell shell = performance(MEMORY, showing(PerformanceSnapshots.live()));
            tab(shell, MEMORY).pseudoClassStateChanged(focusVisible, true);
            return shell;
        });
        Path table = SnapshotHarness.render("performance-focus-table", theme, MINIMUM.width(), MINIMUM.height(), () -> {
            Shell shell = performance(NETWORK, showing(PerformanceSnapshots.live()));
            Node interfaces = page(shell).lookup(".table-view");
            interfaces.pseudoClassStateChanged(focusVisible, true);
            interfaces.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
            return shell;
        });

        assertThat(tabs).isRegularFile().isNotEmptyFile();
        assertThat(table).isRegularFile().isNotEmptyFile();
    }

    /**
     * At the minimum size, the sections with ordinary content need no scrolling. The CPU section
     * of a machine with 16 logical processors is the tallest of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {CPU, MEMORY, DISK, NETWORK})
    void ordinaryContentFitsTheMinimumWindowWithoutScrolling(String section) {
        double[] seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> performance(section, showing(PerformanceSnapshots.live())),
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

    /**
     * The columns of a table share its width: at the smallest scene none is cut off and the table
     * does not scroll sideways, with ordinary content and with the longest.
     */
    @ParameterizedTest
    @ValueSource(strings = {DISK, NETWORK})
    void theColumnsOfATableFitItsWidthInTheMinimumWindow(String section) {
        for (SystemSnapshot snapshot : List.of(PerformanceSnapshots.live(), PerformanceSnapshots.extreme())) {
            double[] seen = SnapshotHarness.inspect(
                    Theme.LIGHT,
                    MINIMUM_INSIDE_FRAME.width(),
                    MINIMUM_INSIDE_FRAME.height(),
                    () -> performance(section, showing(snapshot)),
                    root -> {
                        TableView<?> table = (TableView<?>) page((Shell) root).lookup(".table-view");
                        double columns = 0;
                        for (TableColumn<?, ?> column : table.getColumns()) {
                            columns += column.getWidth();
                        }
                        return new double[] {columns, table.getWidth()};
                    });

            assertThat(seen[0]).as("sum of the column widths").isLessThanOrEqualTo(seen[1]);
        }
    }

    /**
     * The heights a table is given in Java are those the stylesheet gives its header and its rows.
     * If the two disagreed, a table would be higher or lower than its rows.
     */
    @Test
    void aTableIsExactlyAsHighAsItsHeaderAndItsRows() {
        double[] seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                DEFAULT.width(),
                DEFAULT.height(),
                () -> performance(DISK, showing(PerformanceSnapshots.live())),
                root -> {
                    Node table = page((Shell) root).lookup(".table-view");
                    Node header = table.lookup(".column-header-background");
                    Node row = table.lookup(".table-row-cell");
                    return new double[] {
                        header.getLayoutBounds().getHeight(),
                        row.getLayoutBounds().getHeight(),
                        table.getLayoutBounds().getHeight()
                    };
                });

        assertThat(seen[0]).as("height of the header").isEqualTo(28);
        assertThat(seen[1]).as("height of a row").isEqualTo(26);
        // Three file systems: the frame, the header and three rows, and no room below them.
        assertThat(seen[2]).as("height of the table").isEqualTo(2 + 28 + 3 * 26);
    }

    /**
     * 64 logical processors do not fit the minimum window. The page then scrolls as a whole: the
     * status bar stays in the window, and no cell is squeezed below its smallest size.
     */
    @Test
    void sixtyFourProcessorsScrollInTheMinimumWindowInsteadOfBeingSqueezed() {
        double[] seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> performance(CPU, showing(PerformanceSnapshots.extreme())),
                root -> {
                    Shell shell = (Shell) root;
                    ScrollPane area = (ScrollPane) shell.getCenter();
                    Node grid = area.getContent().lookup(".history-grid");
                    return new double[] {
                        shell.getBottom().getBoundsInParent().getMaxY(),
                        area.getContent().getLayoutBounds().getHeight(),
                        area.getViewportBounds().getHeight(),
                        grid.getLayoutBounds().getHeight(),
                        grid.minHeight(grid.getLayoutBounds().getWidth())
                    };
                });

        assertThat(seen[0]).as("bottom edge of the status bar").isLessThanOrEqualTo(MINIMUM_INSIDE_FRAME.height());
        assertThat(seen[1]).as("height of the page").isGreaterThan(seen[2]);
        assertThat(seen[3]).as("height of the processor grid").isGreaterThanOrEqualTo(seen[4]);
    }

    /**
     * Two hundred updates with changing values, states and devices must leave each section with
     * exactly the nodes it had: an update changes texts, redraws Canvases and sets the texts of
     * table rows, nothing else.
     */
    @ParameterizedTest
    @ValueSource(strings = {CPU, MEMORY, DISK, NETWORK})
    void theSceneDoesNotGrowWhileSnapshotsArrive(String section) {
        SystemSnapshot[] rotation = {
            PerformanceSnapshots.firstCycle(),
            PerformanceSnapshots.live(),
            Snapshots.nothingAvailable(),
            PerformanceSnapshots.extreme(),
            PerformanceSnapshots.noDevices(),
            PerformanceSnapshots.live(4)
        };
        int[] counts = SnapshotHarness.inspect(
                Theme.LIGHT, DEFAULT.width(), DEFAULT.height(), () -> performance(section, shell -> {}), root -> {
                    Shell shell = (Shell) root;
                    // Once through every state first: a table creates the cells for the most rows
                    // it has shown and keeps them.
                    for (SystemSnapshot snapshot : rotation) {
                        shell.showSnapshot(snapshot);
                        settle(shell);
                    }
                    shell.showSnapshot(PerformanceSnapshots.live());
                    settle(shell);
                    int before = countNodes(shell);
                    for (int update = 0; update < 200; update++) {
                        shell.showSnapshot(rotation[update % rotation.length]);
                        settle(shell);
                    }
                    shell.showSnapshot(PerformanceSnapshots.live());
                    settle(shell);
                    return new int[] {before, countNodes(shell)};
                });

        assertThat(counts[0]).isGreaterThan(20);
        assertThat(counts[1]).isEqualTo(counts[0]);
    }

    /** One Canvas draws every logical processor: 64 of them need no more nodes than 4. */
    @Test
    void theCpuSectionHasAsManyNodesForSixtyFourProcessorsAsForFour() {
        int[] counts = SnapshotHarness.inspect(
                Theme.LIGHT, DEFAULT.width(), DEFAULT.height(), () -> performance(CPU, shell -> {}), root -> {
                    Shell shell = (Shell) root;
                    shell.showSnapshot(PerformanceSnapshots.live(4));
                    settle(shell);
                    int four = countNodes(shell);
                    shell.showSnapshot(PerformanceSnapshots.extreme());
                    settle(shell);
                    return new int[] {four, countNodes(shell)};
                });

        assertThat(counts[1]).isEqualTo(counts[0]);
    }

    private static Stream<Theme> themes() {
        return Stream.of(Theme.values());
    }

    /** A shell in the given state, with the given section of the Performance page on screen. */
    private static Shell performance(String section, Consumer<Shell> state) {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
        state.accept(shell);
        open(shell, section);
        return shell;
    }

    private static void open(Shell shell, String section) {
        ((Sidebar) shell.getLeft()).entry(Destination.PERFORMANCE).fire();
        tab(shell, section).fire();
    }

    private static Node page(Shell shell) {
        return ((ScrollPane) shell.getCenter()).getContent();
    }

    private static ToggleButton tab(Shell shell, String section) {
        return page(shell).lookupAll(".section-tab").stream()
                .map(ToggleButton.class::cast)
                .filter(entry -> entry.getText().equals(section))
                .findFirst()
                .orElseThrow();
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
