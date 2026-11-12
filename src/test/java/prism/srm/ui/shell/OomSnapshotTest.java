package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.OomFixtures.candidate;
import static prism.srm.ui.OomFixtures.oom;
import static prism.srm.ui.OomFixtures.reading;
import static prism.srm.ui.OomFixtures.settings;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.Claim;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomCandidateView;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The OOM Guard page (UI-5) in each of its states, both themes, at the default size, the smallest
 * window, what is left of it inside the Windows 11 frame, and a wide size; the Overview fact and the
 * status bar sentence of the same views (UI-1, plan O11).
 */
@Tag("ui-snapshot")
class OomSnapshotTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static List<OomCandidateView> managed(OomSettings settings, Map<Long, OomHold> holds) {
        return managed(settings, holds, true, OomSignalStatus.VALIDATED);
    }

    private static List<OomCandidateView> managed(
            OomSettings settings, Map<Long, OomHold> holds, boolean journal, OomSignalStatus signal) {
        return List.of(
                candidate(
                        30_324,
                        "java.exe",
                        new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND),
                        settings,
                        holds.getOrDefault(30_324L, OomHold.NONE),
                        Optional.of(PriorityLevel.NORMAL),
                        3_712,
                        Optional.empty(),
                        journal,
                        signal),
                candidate(
                        18_812,
                        "a-build-tool-with-a-very-long-name-that-the-table-has-to-cut-somewhere.exe",
                        oom(EmergencyAction.LOWER_PRIORITY),
                        settings,
                        holds.getOrDefault(18_812L, OomHold.NONE),
                        Optional.of(PriorityLevel.NORMAL),
                        1_204,
                        Optional.empty(),
                        journal,
                        signal),
                candidate(
                        9_120,
                        "node.exe",
                        oom(EmergencyAction.TERMINATE),
                        settings,
                        holds.getOrDefault(9_120L, OomHold.NONE),
                        Optional.of(PriorityLevel.BELOW_NORMAL),
                        812,
                        Optional.empty(),
                        journal,
                        signal),
                candidate(
                        7_004,
                        "notepad.exe",
                        OomManagement.OFF,
                        settings,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        24,
                        Optional.empty(),
                        journal,
                        signal));
    }

    private static List<OomActionRecord> actions() {
        return List.of(
                OomFixtures.record(
                        OomState.EMERGENCY,
                        30_324,
                        "java.exe",
                        OomActionRecord.Kind.SUSPEND,
                        OperationResult.SUCCESS,
                        "OOM Guard suspended java.exe (PID 30324): memory pressure."),
                OomFixtures.record(
                        OomState.EMERGENCY,
                        9_120,
                        "node.exe",
                        OomActionRecord.Kind.TERMINATE,
                        OperationResult.ACCESS_DENIED,
                        "Terminate failed for node.exe (PID 9120): access denied; it is not tried again in this session."),
                OomFixtures.record(
                        OomState.CRITICAL,
                        18_812,
                        "a-build-tool-with-a-very-long-name.exe",
                        OomActionRecord.Kind.LOWER_PRIORITY,
                        OperationResult.SUCCESS,
                        "OOM Guard lowered the priority of a-build-tool-with-a-very-long-name.exe (PID 18812) to Low (it"
                                + " was Normal): memory pressure."));
    }

    private static Stream<Arguments> states() {
        Stream.Builder<Arguments> all = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : new int[][] {
                {1100, 700},
                {720, 480},
                {704, 441},
                {1600, 1000},
                {800, 600},
                {1366, 768},
                {1600, 500},
                {900, 1200},
                {1920, 1080}
            }) {
                cases().forEach((name, state) -> all.add(Arguments.of(name, theme, size, state)));
            }
        }
        return all.build();
    }

    /**
     * The scene sizes of real Windows windows at 100 % (fix brief K8): the outer window 720x480 has a
     * 706x435 scene, and so on. Rendered with a "-real" marker; the size in the file name is the
     * scene, not the window.
     */
    static final int[][] REAL_SCENES = {
        {706, 435}, {746, 515}, {1086, 655}, {1163, 739}, {1352, 723}, {1426, 855}, {1586, 455}, {1906, 987}
    };

    private static Stream<Arguments> realStates() {
        Stream.Builder<Arguments> all = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : REAL_SCENES) {
                cases().forEach((name, state) -> all.add(Arguments.of(name, theme, size, state)));
            }
        }
        return all.build();
    }

    /** Every state at the scene sizes of real windows (K8): "-real" in the name, the scene size after it. */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("realStates")
    void rendersEveryStateAtRealSceneSizes(String name, Theme theme, int[] size, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name + "-real", theme, size[0], size[1], () -> {
            Shell shell = oomShell();
            state.accept(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** Every state of the page, by name. */
    static Map<String, Consumer<Shell>> cases() {
        OomSettings quiet = settings(false, false, false);
        OomSettings acting = settings(true, true, false);
        OomSettings off = OomSettings.DEFAULTS.withSwitches(
                OomSettings.DEFAULTS.switches().withMonitoring(false));
        Map<String, Consumer<Shell>> cases = new java.util.LinkedHashMap<>();
        cases.put("oom-reading", shell -> {});
        cases.put(
                "oom-empty",
                shell -> shell.showOom(
                        OomFixtures.view(quiet, OomState.NORMAL, Optional.of(reading(46)), List.of(), List.of())));
        cases.put(
                "oom-off",
                shell -> shell.showOom(
                        OomFixtures.view(off, OomState.NORMAL, Optional.empty(), managed(off, Map.of()), List.of())));
        cases.put(
                "oom-normal",
                shell -> shell.showOom(OomFixtures.view(
                        quiet, OomState.NORMAL, Optional.of(reading(46)), managed(quiet, Map.of()), List.of())));
        cases.put(
                "oom-warning",
                shell -> shell.showOom(OomFixtures.view(
                        quiet, OomState.WARNING, Optional.of(reading(13.4)), managed(quiet, Map.of()), List.of())));
        cases.put(
                "oom-critical-acting",
                shell -> shell.showOom(OomFixtures.view(
                        acting,
                        OomState.CRITICAL,
                        Optional.of(reading(7.2)),
                        managed(acting, Map.of(18_812L, OomFixtures.lowered(PriorityLevel.NORMAL))),
                        actions().subList(2, 3))));
        cases.put("oom-emergency-acted", shell -> {
            shell.showOom(OomFixtures.view(
                    acting,
                    OomState.EMERGENCY,
                    Optional.of(reading(4.1)),
                    managed(
                            acting,
                            Map.of(
                                    30_324L, OomFixtures.suspended(Set.of(Claim.PRESSURE)),
                                    18_812L, OomFixtures.lowered(PriorityLevel.NORMAL),
                                    9_120L,
                                            OomFixtures.failed(
                                                    "Terminate failed: access denied; not retried this session"))),
                    actions()));
            select(shell, 0);
        });
        cases.put(
                "oom-emergency-no-candidates",
                shell -> shell.showOom(OomFixtures.view(
                        quiet,
                        OomState.EMERGENCY,
                        Optional.of(reading(3.8)),
                        managed(quiet, Map.of()),
                        List.of(),
                        Optional.of(
                                "No further candidates: 3 under OOM management, 0 acted on in this incident, 3 with an action"
                                        + " not in effect."),
                        false,
                        OomSignalStatus.VALIDATED,
                        OomView.Availability.ALL)));
        cases.put(
                "oom-unavailable",
                shell -> shell.showOom(OomFixtures.view(
                        quiet,
                        OomState.WARNING,
                        Optional.of(reading(12)),
                        managed(quiet, Map.of(), true, OomSignalStatus.UNVALIDATED),
                        List.of(),
                        Optional.empty(),
                        false,
                        OomSignalStatus.UNVALIDATED,
                        OomFixtures.unvalidated())));
        cases.put(
                "oom-journal-failed",
                shell -> shell.showOom(OomFixtures.view(
                        acting,
                        OomState.EMERGENCY,
                        Optional.of(reading(4)),
                        managed(acting, Map.of(30_324L, OomFixtures.incomplete()), false, OomSignalStatus.VALIDATED),
                        actions(),
                        Optional.empty(),
                        false,
                        OomSignalStatus.VALIDATED,
                        new OomView.Availability(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("There is not enough space on the disk")))));
        cases.put("oom-not-monitoring", shell -> {
            shell.showOom(OomFixtures.view(
                    acting,
                    OomState.EMERGENCY,
                    Optional.of(reading(4)),
                    managed(acting, Map.of(30_324L, OomFixtures.suspended(Set.of(Claim.PRESSURE)))),
                    actions(),
                    Optional.empty(),
                    true,
                    OomSignalStatus.VALIDATED,
                    OomView.Availability.ALL));
            shell.showFailures(Set.of(MonitoringFailure.SYSTEM_MONITORING_STOPPED));
        });
        cases.put("oom-terminate-chosen-switch-off", shell -> {
            shell.showOom(OomFixtures.view(
                    acting, OomState.NORMAL, Optional.of(reading(40)), managed(acting, Map.of()), List.of()));
            select(shell, 2);
        });
        return cases;
    }

    static Shell oomShell() {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                LOG_FOLDER,
                identity -> {},
                request -> {},
                request -> {},
                request -> {},
                () -> {},
                OomSettings.DEFAULTS,
                settings -> {},
                identity -> {});
        shell.showControlProfile(OomFixtures.windows());
        shell.navigation().entry(Destination.OOM_GUARD).fire();
        return shell;
    }

    private static Node page(Shell shell) {
        return shell.pageArea().getContent();
    }

    private static void select(Shell shell, int row) {
        TableView<?> table = (TableView<?>) page(shell).lookup(".oom-table");
        table.getSelectionModel().select(row);
        table.requestFocus();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("states")
    void rendersEveryState(String name, Theme theme, int[] size, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name + "-" + size[0], theme, size[0], size[1], () -> {
            Shell shell = oomShell();
            state.accept(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** The Overview fact and the status bar, with a degraded monitoring line beside the pressure sentence. */
    @Test
    void theOverviewAndTheStatusBarSayThePressureInWords() {
        for (Theme theme : Theme.values()) {
            SnapshotHarness.render("overview-oom-critical", theme, 1100, 700, () -> {
                Shell shell = new Shell(
                        new ValueFormatter(Locale.US),
                        ZoneOffset.UTC,
                        Snapshots.CAPACITY,
                        LOG_FOLDER,
                        identity -> {},
                        request -> {},
                        request -> {},
                        request -> {},
                        () -> {},
                        OomSettings.DEFAULTS,
                        settings -> {},
                        identity -> {});
                shell.showSnapshot(Snapshots.live());
                shell.showOom(OomFixtures.view(
                        settings(false, false, false),
                        OomState.CRITICAL,
                        Optional.of(reading(7.2)),
                        List.of(),
                        List.of()));
                return shell;
            });
            SnapshotHarness.render("statusbar-degraded-emergency", theme, 704, 441, () -> {
                Shell shell = oomShell();
                shell.showSnapshot(Snapshots.live());
                shell.showFailures(Set.of(MonitoringFailure.PROCESS_MONITORING_STOPPED));
                shell.showOom(OomFixtures.view(
                        settings(false, false, false),
                        OomState.EMERGENCY,
                        Optional.of(reading(3.6)),
                        List.of(),
                        List.of()));
                return shell;
            });
        }
    }

    /** At the smallest window the managed table and its actions are in view; the recent actions may scroll (plan A11). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("withRows")
    void atTheSmallestWindowTheActionsOfTheManagedTableAreInView(String name) {
        List<Double> measured = actionsAtTheSmallestWindow(name);

        assertThat(measured.get(0))
                .as("the bottom of the actions within the view")
                .isLessThanOrEqualTo(measured.get(1));
        assertThat(measured.get(2))
                .as("a header, two rows and a sideways scroll bar")
                .isGreaterThanOrEqualTo(30 + 2 * 28 + 12);
    }

    /**
     * Owner decision after fix round H (the navigation with names takes 80 px at the left of the smallest
     * window too): in an emergency with no further candidates, where two notices of two lines each stand
     * above the table, the page scrolls a little at 720 x 480 to show the actions; it measured 18 px. The
     * table keeps its header and two rows, and the actions are no more than 24 px below the view.
     */
    @Test
    void anEmergencyWithNoFurtherCandidatesScrollsAtMostALittleToItsActionsAtTheSmallestWindow() {
        List<Double> measured = actionsAtTheSmallestWindow("oom-emergency-no-candidates");

        assertThat(measured.get(0) - measured.get(1))
                .as("how far the bottom of the actions is below the view")
                .isLessThanOrEqualTo(24);
        assertThat(measured.get(2))
                .as("a header, two rows and a sideways scroll bar")
                .isGreaterThanOrEqualTo(30 + 2 * 28 + 12);
    }

    /** The bottom of the actions in the page, the height of the view and the height of the managed table. */
    private static List<Double> actionsAtTheSmallestWindow(String name) {
        Consumer<Shell> state = cases().get(name);
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                704,
                441,
                () -> {
                    Shell shell = oomShell();
                    state.accept(shell);
                    return shell;
                },
                root -> {
                    ScrollPane area = ((Shell) root).pageArea();
                    Node release = area.getContent().lookupAll(".button").stream()
                            .filter(node -> node instanceof javafx.scene.control.Button button
                                    && "Release OOM Guard hold".equals(button.getText()))
                            .findFirst()
                            .orElseThrow();
                    Bounds inPage = area.getContent().sceneToLocal(release.localToScene(release.getBoundsInLocal()));
                    TableView<?> table = (TableView<?>) area.getContent().lookup(".oom-table");
                    return List.of(inPage.getMaxY(), area.getViewportBounds().getHeight(), table.getHeight());
                });
    }

    /**
     * The states with managed processes, the notices above the table included (plan A11, the fold). An
     * emergency with no further candidates has a test of its own (owner decision after fix round H).
     */
    static Stream<String> withRows() {
        return Stream.of(
                "oom-off",
                "oom-normal",
                "oom-critical-acting",
                "oom-emergency-acted",
                "oom-unavailable",
                "oom-journal-failed",
                "oom-not-monitoring",
                "oom-terminate-chosen-switch-off");
    }

    /** UI lab v2: the safe-default chip of the state band says so only while no automatic action is on. */
    @Test
    void theSafeDefaultChipShowsOnlyWhileEveryAutomaticActionIsOff() {
        List<Boolean> shown = new java.util.ArrayList<>();
        for (OomSettings each :
                List.of(settings(false, false, false), settings(true, false, false), settings(false, false, true))) {
            shown.add(SnapshotHarness.inspect(
                    Theme.LIGHT,
                    1100,
                    700,
                    () -> {
                        Shell shell = oomShell();
                        shell.showOom(OomFixtures.view(
                                each, OomState.NORMAL, Optional.of(reading(40)), List.of(), List.of()));
                        return shell;
                    },
                    root -> root.lookup(".oom-safe-default-box").isVisible()));
        }

        assertThat(shown).containsExactly(true, false, false);
    }

    @Test
    void theSwitchesTakeTheirStateAndTheirReasonFromTheView() {
        Map<String, Object> seen = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> {
                    Shell shell = oomShell();
                    shell.showOom(OomFixtures.view(
                            settings(false, false, false),
                            OomState.NORMAL,
                            Optional.of(reading(40)),
                            List.of(),
                            List.of(),
                            Optional.empty(),
                            false,
                            OomSignalStatus.UNVALIDATED,
                            OomFixtures.unvalidated()));
                    return shell;
                },
                root -> {
                    Map<String, Object> facts = new java.util.LinkedHashMap<>();
                    for (Node node : root.lookupAll(".check-box")) {
                        javafx.scene.control.CheckBox box = (javafx.scene.control.CheckBox) node;
                        facts.put(box.getText(), box.isDisabled());
                    }
                    return facts;
                });

        assertThat(seen)
                .containsEntry("Monitor memory pressure", false)
                .containsEntry("Lower priority at Critical", true)
                .containsEntry("Suspend at Emergency", true)
                .containsEntry("Terminate at Emergency (force kill)", true);
    }
}
