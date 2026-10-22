package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.processes.ProcessListings;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Renders the shell with the Processes page in each of its states, in both themes, at the default
 * scene size and at the minimum window size, for inspection against docs/UI_GUIDE.md.
 *
 * <p>The PNGs are for the eye; a test asserts only that each render produced a file. What can be
 * asserted about the page in a scene is in {@link ProcessesPageSceneTest}.
 */
@Tag("ui-snapshot")
class ProcessesSnapshotTest {

    /** A scene size, named for the file. */
    private record Size(int width, int height) {}

    private static final Size DEFAULT = new Size(1100, 700);
    private static final Size MINIMUM = new Size(720, 480);
    /** What is left of the minimum window for the scene once Windows 11 has drawn its frame. */
    private static final Size MINIMUM_INSIDE_FRAME = new Size(704, 441);

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static final String LONG_COMMAND_LINE = "\"C:\\Program Files\\Eclipse Adoptium\\jdk-25\\bin\\java.exe\""
            + " -Xmx512m -Dfile.encoding=UTF-8 -classpath C:\\Users\\alice\\.m2\\repository\\org\\openjfx\\javafx-base"
            + "\\25\\javafx-base-25-win.jar;C:\\Users\\alice\\.m2\\repository\\org\\openjfx\\javafx-controls\\25"
            + "\\javafx-controls-25-win.jar prism.srm.app.Launcher --profile=development";

    private static Stream<Arguments> statesThemesAndSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (Size size : new Size[] {DEFAULT, MINIMUM, MINIMUM_INSIDE_FRAME}) {
                cases.add(Arguments.of("processes-loading", theme, size, (Consumer<Page>) page -> {}));
                cases.add(Arguments.of(
                        "processes-first-listing", theme, size, showing(ProcessListings.firstListing(40))));
                cases.add(Arguments.of("processes-live", theme, size, showing(ProcessListings.windows(394, 7))));
                cases.add(Arguments.of("processes-1000-rows", theme, size, showing(ProcessListings.windows(1000, 7))));
                cases.add(Arguments.of("processes-none-reported", theme, size, showing(List.of())));
                cases.add(Arguments.of("processes-long-names", theme, size, showing(ProcessListings.longNames())));
                cases.add(Arguments.of("processes-extreme", theme, size, showing(ProcessListings.extreme())));
                cases.add(Arguments.of(
                        "processes-unavailable", theme, size, showing(ProcessListings.nothingAvailable())));
                cases.add(Arguments.of("processes-with-states", theme, size, showing(ProcessListings.withStates())));
                cases.add(Arguments.of("processes-filter-no-match", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.filter("no-such-process");
                }));
                cases.add(Arguments.of("processes-filtered", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.filter("chrome");
                }));
                cases.add(Arguments.of("processes-selected-reading", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.focusTable();
                }));
                cases.add(Arguments.of("processes-selected-details", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.focusTable();
                    page.answer(identity -> ProcessDetailsResult.read(new ProcessDetails(
                            identity,
                            Optional.of("C:\\Program Files\\Eclipse Adoptium\\jdk-25\\bin\\java.exe"),
                            Optional.of(LONG_COMMAND_LINE))));
                }));
                cases.add(Arguments.of("processes-selected-denied", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(0);
                    page.answer(identity -> ProcessDetailsResult.read(
                            new ProcessDetails(identity, Optional.empty(), Optional.empty())));
                }));
                cases.add(Arguments.of("processes-selected-read-failed", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(2);
                    page.answer(ProcessDetailsResult::failed);
                }));
                cases.add(Arguments.of("processes-selected-exited", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.longNames());
                    page.select(0);
                    page.show(ProcessListings.longNames().subList(2, 5));
                }));
                cases.add(Arguments.of("processes-selected-long-name", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.longNames());
                    page.select(0);
                    page.answer(identity -> ProcessDetailsResult.read(new ProcessDetails(
                            identity,
                            Optional.of(
                                    "C:\\Program Files\\A Vendor With A Long Name\\A Product With A Longer Name"
                                            + "\\bin\\a-process-with-a-remarkably-long-executable-name-that-no-column-can-hold.exe"),
                            Optional.of(LONG_COMMAND_LINE))));
                }));
                cases.add(Arguments.of("processes-selected-nothing-available", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.nothingAvailable());
                    page.select(0);
                    page.answer(identity -> ProcessDetailsResult.read(
                            new ProcessDetails(identity, Optional.empty(), Optional.empty())));
                }));
                cases.add(Arguments.of("processes-selected-extreme", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.extreme());
                    page.select(0);
                    page.answer(identity -> ProcessDetailsResult.read(new ProcessDetails(
                            identity, Optional.of("C:\\everything-at-once.exe"), Optional.of("everything"))));
                }));
                cases.add(Arguments.of("processes-error-startup", theme, size, (Consumer<Page>)
                        page -> page.shell.showFailures(Set.of(MonitoringFailure.STARTUP))));
                cases.add(Arguments.of("processes-error-monitoring-stopped", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                }));
                cases.add(Arguments.of("processes-error-not-listed", theme, size, (Consumer<Page>)
                        page -> page.shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED))));
                // Sorted by a column of numbers and by the narrowest column: the mark needs room in both.
                cases.add(Arguments.of("processes-sorted-by-cpu", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.sortBy("CPU", true);
                    page.select(1);
                    page.focusTable();
                }));
                cases.add(Arguments.of("processes-sorted-by-priority", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.withStates());
                    page.sortBy("Priority", false);
                }));
                cases.add(Arguments.of("processes-filter-focused", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.focusFilter();
                }));
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("statesThemesAndSizes")
    void rendersEveryStateInBothThemesAtEverySize(String name, Theme theme, Size size, Consumer<Page> state) {
        Path png = SnapshotHarness.render(name, theme, size.width(), size.height(), () -> processes(Locale.US, state));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** Number notation of another locale, where it is longest: the extreme values at the minimum. */
    @Test
    void rendersTurkishNumberNotation() {
        Path png = SnapshotHarness.render(
                "processes-extreme-tr",
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> processes(Locale.forLanguageTag("tr-TR"), showing(ProcessListings.extreme())));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** Extra room goes to the table and, within it, to the name column. */
    @Test
    void rendersALargeWindow() {
        Path png = SnapshotHarness.render(
                "processes-live",
                Theme.LIGHT,
                1600,
                1000,
                () -> processes(Locale.US, showing(ProcessListings.windows(394, 7))));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** The last columns, which a window at its smallest shows only after scrolling sideways. */
    @Test
    void rendersTheLastColumnsOfTheSmallestWindow() {
        Path png = SnapshotHarness.render(
                "processes-live-scrolled-right",
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> processes(Locale.US, showing(ProcessListings.windows(394, 7))),
                root -> {
                    TableView<?> table = (TableView<?>) root.lookup(".table-view");
                    table.scrollToColumnIndex(table.getColumns().size() - 1);
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /**
     * UI-8: the page works at the minimum window size. Its least height and width are not more
     * than the page area has there, so the area does not scroll and the status bar stays in place.
     */
    @Test
    void atTheSmallestWindowThePageFitsItsAreaWithoutScrollingIt() {
        List<Double> sizes = SnapshotHarness.inspect(
                Theme.LIGHT,
                MINIMUM_INSIDE_FRAME.width(),
                MINIMUM_INSIDE_FRAME.height(),
                () -> processes(Locale.US, page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                }),
                root -> {
                    ScrollPane area = (ScrollPane) ((Shell) root).getCenter();
                    Region page = (Region) area.getContent();
                    return List.of(
                            page.getWidth(),
                            area.getViewportBounds().getWidth(),
                            page.getHeight(),
                            area.getViewportBounds().getHeight(),
                            page.minHeight(page.getWidth()));
                });

        assertThat(sizes.get(0)).isEqualTo(sizes.get(1));
        assertThat(sizes.get(2)).isEqualTo(sizes.get(3));
        assertThat(sizes.get(4)).isLessThanOrEqualTo(sizes.get(3));
    }

    private static Consumer<Page> showing(List<prism.srm.domain.ProcessSnapshot> listing) {
        return page -> page.show(listing);
    }

    private static Shell processes(Locale locale, Consumer<Page> state) {
        Page page = new Page(locale);
        state.accept(page);
        return page.shell;
    }

    /** The shell with the Processes page on screen, and what a test does to it. */
    private static final class Page {

        private final Shell shell;
        private ProcessIdentity asked;

        Page(Locale locale) {
            shell = new Shell(
                    new ValueFormatter(locale),
                    ZoneOffset.UTC,
                    Snapshots.CAPACITY,
                    LOG_FOLDER,
                    identity -> asked = identity);
            shell.showSnapshot(Snapshots.live());
            ((Sidebar) shell.getLeft()).entry(Destination.PROCESSES).fire();
        }

        void show(List<prism.srm.domain.ProcessSnapshot> listing) {
            shell.showProcesses(listing);
        }

        /** The page itself: the page area holds it as its content, which no lookup of the shell reaches yet. */
        private Node page() {
            return ((ScrollPane) shell.getCenter()).getContent();
        }

        TableView<?> table() {
            return (TableView<?>) page().lookup(".table-view");
        }

        void select(int row) {
            table().getSelectionModel().select(row);
        }

        @SuppressWarnings("unchecked") // Only the title and the direction of a column are used.
        void sortBy(String title, boolean descending) {
            TableView<Object> table = (TableView<Object>) table();
            TableColumn<Object, ?> column = table.getColumns().stream()
                    .filter(candidate -> candidate.getText().equals(title))
                    .findFirst()
                    .orElseThrow();
            column.setSortType(descending ? TableColumn.SortType.DESCENDING : TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll(List.of(column));
        }

        void filter(String text) {
            ((TextField) page().lookup(".process-filter")).setText(text);
        }

        /** Answers the request for details that selecting a row made. */
        void answer(java.util.function.Function<ProcessIdentity, ProcessDetailsResult> result) {
            shell.showProcessDetails(result.apply(asked));
        }

        /** The harness has no window focus, so the focus ring is rendered by forcing its pseudo-classes. */
        void focusTable() {
            forceFocus(table());
        }

        void focusFilter() {
            forceFocus(page().lookup(".process-filter"));
        }

        private static void forceFocus(Node node) {
            node.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
            node.pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
        }
    }
}
