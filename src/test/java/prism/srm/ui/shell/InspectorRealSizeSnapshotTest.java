package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;
import static prism.srm.ui.LimitsFixtures.managed;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.application.FakeProcessController;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.processes.ProcessListings;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Fix round G (PR-05, K8): the Processes and the Limits page at the sizes a real Windows window gives
 * its scene. A window of 1100 x 700 at 100 % has a scene of about 1086 x 655: the file names carry the
 * scene size and the marker "-real", next to the outer size, so the renders are compared with what a
 * user sees. The states are those the audit found cut or lost: a selected managed process with a long
 * path, the result of an action, every action disabled, and a managed process without a CPU limit.
 */
@Tag("ui-snapshot")
class InspectorRealSizeSnapshotTest {

    /** An outer window size and the scene Windows 11 leaves of it at 100 % (fix brief, K8). */
    private record RealSize(int outerWidth, int outerHeight, int width, int height) {
        String label() {
            return "real-" + outerWidth + "x" + outerHeight;
        }
    }

    private static final List<RealSize> SIZES = List.of(
            new RealSize(720, 480, 706, 435),
            new RealSize(760, 560, 746, 515),
            new RealSize(1100, 700, 1086, 655),
            new RealSize(1177, 784, 1163, 739),
            new RealSize(1366, 768, 1352, 723),
            new RealSize(1440, 900, 1426, 855),
            new RealSize(1600, 500, 1586, 455),
            new RealSize(1920, 1032, 1906, 987));

    private static final String LONG_PATH =
            "C:\\Program Files\\A vendor with a long name\\A product with a long name\\bin\\x64\\the-tool.exe";

    private static final String LONG_COMMAND_LINE = "\"" + LONG_PATH + "\" --profile=development --verbose"
            + " --data-dir=C:\\Users\\alice\\AppData\\Local\\A vendor\\cache";

    private static Stream<Arguments> processes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (RealSize size : SIZES) {
                cases.add(Arguments.of("processes-managed", theme, size, false, (Consumer<ProcessesState>)
                        ProcessesState::selectedAndManaged));
                cases.add(Arguments.of("processes-result", theme, size, false, (Consumer<ProcessesState>) state -> {
                    state.selectedAndManaged();
                    state.result(
                            ControlAction.END_TASK,
                            OperationResult.SUCCESS,
                            "Close request sent to audiodg.exe (PID 268).",
                            "The application may ignore it.");
                }));
                cases.add(Arguments.of("processes-disabled", theme, size, false, (Consumer<ProcessesState>) state -> {
                    state.selectedAndManaged();
                    state.capabilities(FakeProcessController.noneAvailable());
                }));
                cases.add(Arguments.of("processes-filtered", theme, size, false, (Consumer<ProcessesState>) state -> {
                    state.selectedAndManaged();
                    state.filter("audiodg");
                }));
                cases.add(Arguments.of("processes-sheet", theme, size, true, (Consumer<ProcessesState>) state -> {
                    state.selectedAndManaged();
                    state.result(
                            ControlAction.FORCE_KILL,
                            OperationResult.ACCESS_DENIED,
                            "Access denied: audiodg.exe (PID 268).",
                            "The system does not let Prism act on this process.");
                }));
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("processes")
    void rendersProcessesAtRealSceneSizes(
            String name, Theme theme, RealSize size, boolean openSheet, Consumer<ProcessesState> state) {
        Path png = SnapshotHarness.render(
                name + "-" + size.label(),
                theme,
                size.width(),
                size.height(),
                () -> {
                    ProcessesState shown = new ProcessesState();
                    state.accept(shown);
                    return shown.shell;
                },
                root -> {
                    if (openSheet) {
                        Node details = ((Shell) root).toolbar().lookup(".process-details-open");
                        if (details != null && details.isVisible()) {
                            ((Button) details).fire();
                        }
                    }
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Stream<Arguments> limits() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (RealSize size : SIZES) {
                cases.add(Arguments.of("limits-no-cpu-limit", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(new LimitsSnapshot(
                            List.of(managed(identity(23_816), "Adobe Desktop Service")),
                            LimitsFixtures.NATIVE,
                            Optional.empty()));
                    selectLimit(shell, 0);
                }));
                cases.add(Arguments.of("limits-result", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(new LimitsSnapshot(
                            List.of(
                                    LimitsFixtures.throttling(identity(30_324), "java.exe", 30, 29.6),
                                    managed(identity(3_000), "notepad.exe")),
                            LimitsFixtures.EMULATED,
                            Optional.empty()));
                    selectLimit(shell, 0);
                    pressLimit(shell, "Disable");
                    shell.showLimitResult(new LimitResult(
                            LimitRequest.of(LimitAction.DISABLE, identity(30_324), "java.exe"),
                            ControlOutcome.success(),
                            "The limits of java.exe (PID 30324) are disabled.",
                            "Prism no longer stops it."));
                }));
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("limits")
    void rendersLimitsAtRealSceneSizes(String name, Theme theme, RealSize size, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name + "-" + size.label(), theme, size.width(), size.height(), () -> {
            Shell shell = limitsShell();
            state.accept(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    static Shell limitsShellForProbe() {
        Shell shell = limitsShell();
        shell.showLimits(new LimitsSnapshot(
                List.of(managed(identity(23_816), "Adobe Desktop Service")), LimitsFixtures.NATIVE, Optional.empty()));
        selectLimit(shell, 0);
        return shell;
    }

    private static Shell limitsShell() {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs",
                identity -> {},
                request -> {},
                request -> {},
                request -> {},
                () -> {});
        shell.navigation().entry(Destination.LIMITS).fire();
        return shell;
    }

    private static void selectLimit(Shell shell, int row) {
        TableView<?> table = (TableView<?>) shell.pageArea().getContent().lookup(".limits-table");
        table.getSelectionModel().select(row);
        table.requestFocus();
    }

    private static void pressLimit(Shell shell, String text) {
        shell.pageArea().getContent().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .fire();
    }

    /** The shell with the Processes page on screen, and what a case does to it. */
    static final class ProcessesState {

        private final Shell shell;
        private ProcessIdentity asked;

        ProcessesState() {
            shell = new Shell(
                    new ValueFormatter(Locale.US),
                    ZoneOffset.UTC,
                    Snapshots.CAPACITY,
                    "C:\\logs",
                    identity -> asked = identity);
            shell.showSnapshot(Snapshots.live());
            shell.navigation().entry(Destination.PROCESSES).fire();
        }

        Shell shell() {
            return shell;
        }

        private TableView<?> table() {
            return (TableView<?>) shell.pageArea().getContent().lookup(".table-view");
        }

        void selectedAndManaged() {
            shell.showProcesses(ProcessListings.windows(394, 7));
            table().getSelectionModel().select(3);
            table().scrollTo(3);
            capabilities(FakeProcessController.windowsLike());
            shell.showProcessDetails(ProcessDetailsResult.read(
                    new ProcessDetails(asked, Optional.of(LONG_PATH), Optional.of(LONG_COMMAND_LINE))));
            shell.showLimits(LimitsFixtures.snapshot(LimitsFixtures.throttling(asked, "audiodg.exe", 30, 29.6)));
        }

        /** A process selected that Prism does not manage: Manage is offered, the other choices say why not. */
        void selectedNotManaged() {
            shell.showProcesses(ProcessListings.windows(394, 7));
            table().getSelectionModel().select(3);
            table().scrollTo(3);
            capabilities(FakeProcessController.windowsLike());
            shell.showProcessDetails(ProcessDetailsResult.read(
                    new ProcessDetails(asked, Optional.of(LONG_PATH), Optional.of(LONG_COMMAND_LINE))));
            shell.showLimits(LimitsFixtures.snapshot());
        }

        void capabilities(Map<ControlAction, prism.srm.domain.Capability> capabilities) {
            shell.showControlProfile(new ControlProfile(capabilities, Map.of()));
        }

        void result(ControlAction action, OperationResult result, String message, String explanation) {
            ControlRequest request = ControlRequest.of(action, asked, "audiodg.exe");
            shell.showControlResult(
                    new ProcessControlResult(request, ControlOutcome.of(result, "detail"), message, explanation));
        }

        void filter(String text) {
            ((TextField) shell.toolbar().lookup(".process-filter")).setText(text);
        }

        ButtonBase action(String text) {
            return shell.pageArea().getContent().lookupAll(".process-action").stream()
                    .map(ButtonBase.class::cast)
                    .filter(control -> control.getText().equals(text))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
