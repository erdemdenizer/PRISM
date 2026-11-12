package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.MonitoringFailure;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomView;
import prism.srm.domain.ThemeMode;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.settings.SettingsPage;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The Settings page (UI-7, UI-8) in both themes, with each state of what it shows. */
@Tag("ui-snapshot")
class SettingsSnapshotTest {

    private static final String LOGS = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";
    private static final String SETTINGS = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\settings";

    private static final ControlProfile WINDOWS_LIKE = new ControlProfile(
            Map.of(
                    ControlAction.END_TASK,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST),
                    ControlAction.FORCE_KILL,
                    Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.PROCESS_HANDLE_TERMINATE),
                    ControlAction.SUSPEND,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION),
                    ControlAction.RESUME,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION),
                    ControlAction.CHANGE_PRIORITY,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PRIORITY_CLASS)),
            Map.of());

    private static final LimitsSnapshot NATIVE = new LimitsSnapshot(
            List.of(),
            LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT),
            LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT),
            Optional.empty(),
            List.of());

    private static final LimitsSnapshot NOT_AVAILABLE = new LimitsSnapshot(
            List.of(),
            LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.DUTY_CYCLE),
            LimitCapability.unavailable("no native limit is available on this system in this version"),
            Optional.empty(),
            List.of());

    private static Stream<Arguments> states() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : new int[][] {{1100, 700}, {720, 480}, {704, 441}, {1600, 1000}}) {
                cases.add(
                        Arguments.of("settings-reading", theme, size, Optional.empty(), (Consumer<Shell>) shell -> {}));
                cases.add(Arguments.of(
                        "settings-all", theme, size, Optional.empty(), (Consumer<Shell>) SettingsSnapshotTest::all));
                cases.add(Arguments.of("settings-partly-read", theme, size, Optional.empty(), (Consumer<Shell>)
                        shell -> shell.showControlProfile(WINDOWS_LIKE)));
                cases.add(Arguments.of("settings-unavailable", theme, size, Optional.empty(), (Consumer<Shell>)
                        shell -> shell.showLimits(NOT_AVAILABLE)));
                cases.add(Arguments.of("settings-startup-failed", theme, size, Optional.empty(), (Consumer<Shell>)
                        shell -> shell.showFailures(EnumSet.of(MonitoringFailure.STARTUP))));
                cases.add(Arguments.of(
                        "settings-file-damaged",
                        theme,
                        size,
                        Optional.of("The settings file was damaged, so Prism uses the defaults. It is kept as"
                                + " C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\settings"
                                + "\\prism.settings.corrupt-1762500000000"),
                        (Consumer<Shell>) SettingsSnapshotTest::all));
                cases.add(Arguments.of(
                        "settings-folder-failed", theme, size, Optional.empty(), (Consumer<Shell>) shell -> {
                            all(shell);
                            shell.showFolderOpened(Optional.of("This folder does not exist yet: " + SETTINGS
                                    + ". Prism creates it when it first writes there."));
                        }));
            }
        }
        return cases.build();
    }

    /**
     * Everything a running application reports (LS-04): the actions, the limits, the OOM Guard
     * readings, and a system snapshot, so the status bar says that monitoring runs.
     */
    static void all(Shell shell) {
        shell.showSnapshot(Snapshots.live());
        shell.showControlProfile(WINDOWS_LIKE);
        shell.showLimits(NATIVE);
        shell.showOom(OomFixtures.view(
                OomSettings.DEFAULTS,
                OomState.NORMAL,
                Optional.of(OomFixtures.reading(46)),
                List.of(),
                List.of(),
                Optional.empty(),
                false,
                OomSignalStatus.VALIDATED,
                OomView.Availability.ALL));
    }

    /** All of it at the scene sizes of real windows (K8), with the folders of the lab's real data. */
    private static Stream<Arguments> realStates() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : LogsSnapshotTest.REAL_SCENES) {
                cases.add(Arguments.of("settings-all-real", theme, size));
            }
            // The scenes of the same windows at 125 % (fix round H), rendered at 100 %.
            for (ScaledScenes.Size size : ScaledScenes.AT_125) {
                cases.add(Arguments.of("settings-all-real125", theme, size.whole()));
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("realStates")
    void rendersEverythingAtRealSizes(String name, Theme theme, int[] size) {
        String home = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager";
        Path png = SnapshotHarness.render(name, theme, size[0], size[1], () -> {
            Shell shell = shell(new SettingsPage.Hooks(
                    ThemeMode.DARK,
                    mode -> {},
                    home + "\\logs",
                    home + "\\settings",
                    () -> {},
                    () -> {},
                    Optional.empty()));
            shell.navigation().entry(Destination.SETTINGS).fire();
            all(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    static Shell shell(SettingsPage.Hooks hooks) {
        return new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                LOGS,
                identity -> {},
                request -> {},
                request -> {},
                request -> {},
                () -> {},
                OomSettings.DEFAULTS,
                settings -> {},
                identity -> {},
                hooks);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("states")
    void rendersEveryState(String name, Theme theme, int[] size, Optional<String> problem, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name, theme, size[0], size[1], () -> {
            Shell shell = shell(
                    new SettingsPage.Hooks(ThemeMode.SYSTEM, mode -> {}, LOGS, SETTINGS, () -> {}, () -> {}, problem));
            shell.navigation().entry(Destination.SETTINGS).fire();
            state.accept(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }
}
