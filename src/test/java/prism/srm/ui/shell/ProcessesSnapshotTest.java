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
import prism.srm.application.FakeProcessController;
import prism.srm.application.SuspendedByPrism;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessControlResult;
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
                // Process monitoring stopped while Prism holds a suspension: Resume is still offered.
                cases.add(Arguments.of("processes-error-stopped-held", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showHeldSuspensions(List.of(
                            new prism.srm.domain.SuspendedProcess(ProcessListings.identity(268), "audiodg.exe"),
                            new prism.srm.domain.SuspendedProcess(ProcessListings.identity(4242), "java")));
                    page.shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                }));
                // The answer to a Resume there, and a name too long for its place.
                cases.add(Arguments.of("processes-error-stopped-held-result", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.capabilities(FakeProcessController.windowsLike());
                    ProcessIdentity java = ProcessListings.identity(4242);
                    page.shell.showHeldSuspensions(List.of(
                            new prism.srm.domain.SuspendedProcess(
                                    ProcessListings.identity(268),
                                    "a-process-with-a-name-far-too-long-for-the-room-it-is-given-here.exe"),
                            new prism.srm.domain.SuspendedProcess(java, "java")));
                    page.shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                    page.page().applyCss();
                    ((javafx.scene.control.Button) page.page()
                                    .lookupAll(".held-suspension-resume")
                                    .toArray()[1])
                            .fire();
                    // As the runtime hands them over: the held list without java, then the result.
                    page.shell.showHeldSuspensions(List.of(new prism.srm.domain.SuspendedProcess(
                            ProcessListings.identity(268),
                            "a-process-with-a-name-far-too-long-for-the-room-it-is-given-here.exe")));
                    page.shell.showControlResult(new ProcessControlResult(
                            ControlRequest.of(ControlAction.RESUME, java, "java"),
                            ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "exited"),
                            "java (PID 4242) has exited.",
                            "Prism no longer holds it suspended."));
                }));
                // A Resume on its way.
                cases.add(Arguments.of("processes-error-stopped-held-resuming", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showHeldSuspensions(
                            List.of(new prism.srm.domain.SuspendedProcess(ProcessListings.identity(4242), "java")));
                    page.shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                    page.page().applyCss();
                    ((javafx.scene.control.Button) page.page().lookup(".held-suspension-resume")).fire();
                }));
                // The last held process was resumed: only the result is left.
                cases.add(Arguments.of("processes-error-stopped-held-empty-result", theme, size, (Consumer<Page>)
                        page -> {
                            ProcessIdentity java = ProcessListings.identity(4242);
                            page.show(ProcessListings.windows(394, 7));
                            page.capabilities(FakeProcessController.windowsLike());
                            page.shell.showHeldSuspensions(
                                    List.of(new prism.srm.domain.SuspendedProcess(java, "java")));
                            page.shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                            page.page().applyCss();
                            ((javafx.scene.control.Button) page.page().lookup(".held-suspension-resume")).fire();
                            page.shell.showHeldSuspensions(List.of());
                            page.shell.showControlResult(new ProcessControlResult(
                                    ControlRequest.of(ControlAction.RESUME, java, "java"),
                                    ControlOutcome.success("27 thread(s) released"),
                                    "java (PID 4242) was resumed."));
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
                // The row the inspector describes must be found while the table has no focus.
                cases.add(Arguments.of("processes-selected-filter-focused", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.focusFilter();
                }));
                // Milestone 7: the actions on the selected process, and what they come to.
                cases.add(Arguments.of("processes-actions-enabled", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                }));
                // The keyboard focus on an action: a halo, never the border alone of a default button.
                cases.add(Arguments.of("processes-action-focused", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.focusAction("End task…");
                }));
                cases.add(Arguments.of("processes-action-result", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.result(
                            ControlAction.END_TASK,
                            OperationResult.SUCCESS,
                            "Close request sent to audiodg.exe (PID 268).",
                            "The application may ignore it.");
                }));
                cases.add(Arguments.of("processes-action-failure", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.result(
                            ControlAction.FORCE_KILL,
                            OperationResult.ACCESS_DENIED,
                            "Access denied: audiodg.exe (PID 268).",
                            "The system does not let Prism act on this process.");
                }));
                cases.add(Arguments.of("processes-suspended-by-prism", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.result(
                            ControlAction.SUSPEND,
                            OperationResult.SUCCESS,
                            "audiodg.exe (PID 268) was suspended.",
                            "Its threads were stopped one by one.");
                    page.show(SuspendedByPrism.overlay(ProcessListings.windows(394, 7), Set.of(page.asked)));
                }));
                // Milestone 7.5: an action on its way, a failure of the system, a long name.
                cases.add(Arguments.of("processes-action-in-flight", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.press("Suspend");
                }));
                cases.add(Arguments.of("processes-action-error", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.result(
                            ControlAction.SUSPEND,
                            OperationResult.NATIVE_ERROR,
                            "The system did not suspend audiodg.exe (PID 268).",
                            "The Logs page has the details.");
                }));
                // Milestone 8: the actions where signals and the nice value carry them out (Linux, macOS).
                cases.add(Arguments.of("processes-actions-signals", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.unixLike());
                    page.result(
                            ControlAction.SUSPEND,
                            OperationResult.SUCCESS,
                            "audiodg.exe (PID 268) was suspended.",
                            "The system stopped the whole process.");
                }));
                cases.add(Arguments.of("processes-action-priority-denied", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.unixLike());
                    page.result(
                            ControlAction.CHANGE_PRIORITY,
                            OperationResult.ACCESS_DENIED,
                            "Access denied: audiodg.exe (PID 268).",
                            "Raising a priority, or changing another user's process, needs privileges Prism does not"
                                    + " have.");
                }));
                // Linux without pidfd signalling: four actions unavailable, priority offered.
                cases.add(Arguments.of("processes-actions-no-pidfd", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    java.util.Map<ControlAction, prism.srm.domain.Capability> table =
                            new java.util.EnumMap<>(FakeProcessController.unixLike());
                    for (ControlAction action : List.of(
                            ControlAction.END_TASK,
                            ControlAction.FORCE_KILL,
                            ControlAction.SUSPEND,
                            ControlAction.RESUME)) {
                        table.put(action, prism.srm.domain.Capability.UNAVAILABLE);
                    }
                    page.capabilities(table);
                }));
                cases.add(Arguments.of("processes-action-result-long-name", theme, size, (Consumer<Page>) page -> {
                    page.show(List.of(ProcessListings.process(
                                    4242, "a-process-with-a-name-far-too-long-for-the-room-the-inspector-gives-it.exe")
                            .build()));
                    page.select(0);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.result(
                            ControlAction.SUSPEND,
                            OperationResult.SUCCESS,
                            "a-process-with-a-name-far-too-long-for-the-room-the-inspector-gives-it.exe (PID 4242)"
                                    + " was suspended.",
                            "Its threads were stopped one by one.");
                }));
                cases.add(Arguments.of("processes-actions-long-name", theme, size, (Consumer<Page>) page -> {
                    page.show(List.of(ProcessListings.process(
                                    4242, "a-process-with-a-name-far-too-long-for-the-room-the-inspector-gives-it.exe")
                            .build()));
                    page.select(0);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.result(
                            ControlAction.END_TASK,
                            OperationResult.SUCCESS,
                            "Close request sent to a-process-with-a-name-far-too-long-for-the-room-the-inspector-gives"
                                    + "-it.exe (PID 4242).",
                            "The application may ignore it.");
                }));
                // CAP-2: one action unavailable, its reason on the page while the others stay enabled.
                cases.add(Arguments.of("processes-actions-partly-unavailable", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    java.util.Map<ControlAction, prism.srm.domain.Capability> partly =
                            new java.util.EnumMap<>(FakeProcessController.windowsLike());
                    partly.put(ControlAction.END_TASK, prism.srm.domain.Capability.UNAVAILABLE);
                    page.capabilities(partly);
                }));
                cases.add(Arguments.of("processes-actions-unavailable", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.noneAvailable());
                }));
                // Milestone 9: a managed process with its CPU limit, and the result of setting it.
                cases.add(Arguments.of("processes-managed-limited", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showLimits(prism.srm.ui.LimitsFixtures.snapshot(
                            prism.srm.ui.LimitsFixtures.throttling(page.asked, "audiodg.exe", 30, 29.6)));
                    page.shell.showLimitResult(new prism.srm.domain.LimitResult(
                            prism.srm.domain.LimitRequest.cpuLimit(page.asked, "audiodg.exe", 30),
                            ControlOutcome.success(),
                            "audiodg.exe (PID 268) is limited to 30 % of the CPU.",
                            "Approximate: Prism stops and continues the process in short cycles."));
                }));
                // Milestone 9: what an earlier session may have left suspended, above the table.
                cases.add(Arguments.of("processes-recovered", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showRecovered(recoveredEntries());
                }));
                // Milestone 9: a journal of an earlier session not read yet, and one Prism stopped looking for.
                cases.add(Arguments.of("processes-recovery-pending", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showRecoveryCheck(prism.srm.domain.RecoveryCheck.PENDING);
                }));
                cases.add(Arguments.of("processes-recovered-pending", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showRecovered(recoveredEntries());
                    page.shell.showRecoveryCheck(prism.srm.domain.RecoveryCheck.PENDING);
                }));
                cases.add(Arguments.of("processes-recovery-exhausted", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showRecoveryCheck(prism.srm.domain.RecoveryCheck.EXHAUSTED);
                }));
                cases.add(Arguments.of("processes-recovered-incomplete", theme, size, (Consumer<Page>) page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                    page.capabilities(FakeProcessController.windowsLike());
                    page.shell.showRecovered(recoveredEntries());
                    page.shell.showRecoveryCheck(prism.srm.domain.RecoveryCheck.EXHAUSTED);
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

    /**
     * UI lab v2: the window sizes of the layout matrix (lab-docs/LAB_DESIGN_SPEC.md, section 7):
     * the inspector beside the table from 1320 px, under it from 1100 px where the window is at least
     * 560 px high, and a sheet over the page elsewhere ({@link prism.srm.ui.Breakpoints}), rendered
     * closed and open.
     */
    private static final Size[] MATRIX = {
        new Size(720, 480),
        new Size(800, 600),
        new Size(1100, 700),
        new Size(1366, 768),
        new Size(1600, 500),
        new Size(900, 1200),
        new Size(1920, 1080),
        new Size(3440, 1440)
    };

    private static Stream<Arguments> matrix() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (Size size : MATRIX) {
                cases.add(Arguments.of("matrix-selected", theme, size, false));
                if (prism.srm.ui.Breakpoints.inspectorFor(size.width(), size.height())
                        == prism.srm.ui.Breakpoints.Inspector.SHEET) {
                    cases.add(Arguments.of("matrix-sheet", theme, size, true));
                }
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("matrix")
    void rendersTheLayoutMatrix(String name, Theme theme, Size size, boolean sheet) {
        Path png = SnapshotHarness.render(
                name,
                theme,
                size.width(),
                size.height(),
                () -> processes(Locale.US, Page::selectedAndManaged),
                root -> {
                    if (sheet) {
                        // Details, where the inspector is a sheet over the page: as the user would open it.
                        // It is in the title line, which the shell shows in the page toolbar.
                        Node toolbar = ((Shell) root).toolbar();
                        ((javafx.scene.control.Button) toolbar.lookup(".process-details-open")).fire();
                    }
                });

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
                    ScrollPane area = ((Shell) root).pageArea();
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

    /**
     * The line of the actions is under them, and the page keeps its usual 12 px. UI lab v2: the
     * inspector is a sheet over the page in a narrow or low window and takes no height in it; under
     * the table its sections stand in columns and the line under them all. The compact state of the
     * page (8 px apart when the line under the actions cost a line of height) was reached by no window
     * any more and is gone; this checks that no size needs it: the smallest window, a narrow and tall
     * one, and under the table at its lowest and at the default size.
     */
    @Test
    void theGroupsKeepTheirSpacingNowThatTheInspectorOfANarrowWindowIsASheet() {
        List<Object> narrowAndTall = compactness(724, 700);
        List<Object> smallest = compactness(MINIMUM_INSIDE_FRAME.width(), MINIMUM_INSIDE_FRAME.height());
        List<Object> underTheTableLow = compactness(1100, 560);
        List<Object> underTheTable = compactness(1100, 700);

        assertThat(narrowAndTall).as("line under the actions, usual spacing").containsExactly(true, false, 12.0);
        assertThat(smallest).as("line under the actions, usual spacing").containsExactly(true, false, 12.0);
        assertThat(underTheTableLow).as("under the table, lowest").containsExactly(true, false, 12.0);
        assertThat(underTheTable).as("under the table").containsExactly(true, false, 12.0);
    }

    private static List<Object> compactness(int width, int height) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> processes(Locale.US, page -> {
                    page.show(ProcessListings.windows(394, 7));
                    page.select(3);
                }),
                root -> {
                    Region page = (Region) ((Shell) root).pageArea().getContent();
                    // In a narrow window the inspector is the sheet, given the inspector when Details opens it
                    // (round G, the resize measurement); the button may stand in the shell's toolbar.
                    root.lookupAll(".process-details-open").stream()
                            .filter(javafx.scene.Node::isVisible)
                            .map(javafx.scene.control.Button.class::cast)
                            .findFirst()
                            .ifPresent(button -> {
                                button.fire();
                                for (int pass = 0; pass < 2; pass++) {
                                    root.applyCss();
                                    root.layout();
                                }
                            });
                    javafx.scene.Node line = page.lookup(".process-action-message");
                    return List.of(
                            line.getPseudoClassStates().stream()
                                    .anyMatch(
                                            state -> state.getPseudoClassName().equals("below")),
                            page.getPseudoClassStates().stream()
                                    .anyMatch(
                                            state -> state.getPseudoClassName().equals("compact")),
                            ((javafx.scene.layout.VBox) page).getSpacing());
                });
    }

    /** One entry Prism offers to resume, and one only the user can handle outside Prism. */
    private static List<prism.srm.domain.RecoveredSuspension> recoveredEntries() {
        return List.of(
                prism.srm.ui.LimitsFixtures.recovered(
                        prism.srm.ui.LimitsFixtures.identity(30_324),
                        "java.exe",
                        java.util.EnumSet.of(prism.srm.domain.SuspensionReason.USER),
                        true,
                        "Whether it is still suspended is checked when you ask to resume it."),
                prism.srm.ui.LimitsFixtures.recovered(
                        prism.srm.ui.LimitsFixtures.identity(18_812),
                        "a-build-tool-with-a-long-name-from-the-earlier-session.exe",
                        java.util.EnumSet.of(prism.srm.domain.SuspensionReason.CPU_LIMITER),
                        false,
                        "Prism did not record which threads it suspended, and a suspension count does not show whose"
                                + " it is. Prism changes nothing. If the process is stuck, resume it outside Prism, for"
                                + " example in Resource Monitor."));
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
            shell.navigation().entry(Destination.PROCESSES).fire();
        }

        void show(List<prism.srm.domain.ProcessSnapshot> listing) {
            shell.showProcesses(listing);
        }

        /** The page itself: the page area holds it as its content, which no lookup of the shell reaches yet. */
        private Node page() {
            return shell.pageArea().getContent();
        }

        TableView<?> table() {
            return (TableView<?>) page().lookup(".table-view");
        }

        void select(int row) {
            table().getSelectionModel().select(row);
            // In view at every size, as a click would have it: the inspector describes this row.
            table().scrollTo(row);
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

        void capabilities(java.util.Map<ControlAction, prism.srm.domain.Capability> capabilities) {
            shell.showControlProfile(new prism.srm.domain.ControlProfile(capabilities, java.util.Map.of()));
        }

        /** The result of an action on the selected process, as the runtime would hand it over. */
        void result(ControlAction action, OperationResult result, String message) {
            result(action, result, message, "");
        }

        /** The result of an action on the selected process, with the sentence that goes beside it. */
        void result(ControlAction action, OperationResult result, String message, String explanation) {
            ControlRequest request = action == ControlAction.CHANGE_PRIORITY
                    ? ControlRequest.priority(asked, "audiodg.exe", prism.srm.domain.PriorityLevel.HIGH)
                    : ControlRequest.of(action, asked, "audiodg.exe");
            shell.showControlResult(
                    new ProcessControlResult(request, ControlOutcome.of(result, "detail"), message, explanation));
        }

        /** A listing, a selected managed process with its details read, and the actions available. */
        void selectedAndManaged() {
            show(ProcessListings.windows(394, 7));
            select(3);
            focusTable();
            capabilities(FakeProcessController.windowsLike());
            answer(identity -> ProcessDetailsResult.read(new ProcessDetails(
                    identity,
                    Optional.of("C:\\Program Files\\Eclipse Adoptium\\jdk-25\\bin\\java.exe"),
                    Optional.of(LONG_COMMAND_LINE))));
            shell.showLimits(prism.srm.ui.LimitsFixtures.snapshot(
                    prism.srm.ui.LimitsFixtures.throttling(asked, "audiodg.exe", 30, 29.6)));
        }

        /** Presses an action of the inspector, as the user would. */
        void press(String text) {
            action(text).fire();
        }

        private javafx.scene.control.ButtonBase action(String text) {
            // In a low inspector its pinned part scrolls with the details (fix round G, K2), in a scroll pane
            // whose content is a child of its skin only, and the page is in no scene yet when a state is
            // set: so the content is searched as well.
            java.util.Set<javafx.scene.Node> actions =
                    new java.util.LinkedHashSet<>(page().lookupAll(".process-action"));
            for (javafx.scene.Node scroll : page().lookupAll(".inspector-scroll")) {
                javafx.scene.Node content = ((javafx.scene.control.ScrollPane) scroll).getContent();
                if (content != null) {
                    actions.addAll(content.lookupAll(".process-action"));
                }
            }
            return actions.stream()
                    .map(javafx.scene.control.ButtonBase.class::cast)
                    .filter(control -> control.getText().equals(text))
                    .findFirst()
                    .orElseThrow();
        }

        void focusAction(String text) {
            forceFocus(action(text));
        }

        void filter(String text) {
            // The filter is in the title line, which the shell shows in the page toolbar (UI lab v2).
            ((TextField) shell.toolbar().lookup(".process-filter")).setText(text);
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
            forceFocus(shell.toolbar().lookup(".process-filter"));
        }

        private static void forceFocus(Node node) {
            node.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
            node.pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
        }
    }
}
