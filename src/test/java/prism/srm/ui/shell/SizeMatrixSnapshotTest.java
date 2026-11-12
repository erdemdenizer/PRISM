package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.performance.PerformanceSnapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI lab: every page in both themes at the sizes of the lab's review matrix, from 720 x 480 (narrow,
 * low) through 1100 x 700 (default), 1366 x 768, 1440 x 900, 1600 x 500 (wide and low), 1920 x 1080
 * (docked) and 900 x 1200 (tall) to 2560 x 1440 and 3440 x 1440 (ultrawide), with the same live data,
 * a short session in the activity log and a Windows-like control profile. For the eye; each render
 * must produce a file.
 */
@Tag("ui-snapshot")
class SizeMatrixSnapshotTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    private static final int[][] SIZES = {
        {720, 480},
        {800, 600},
        {1100, 700},
        {1366, 768},
        {1440, 900},
        {1600, 500},
        {1920, 1080},
        {900, 1200},
        {2560, 1440},
        {3440, 1440}
    };

    private static final Instant T = Instant.parse("2026-10-08T07:00:00Z");

    private static final List<ActivityEntry> SESSION = List.of(
            new ActivityEntry(T, ActivitySeverity.INFO, "Prism System Resource Manager started on Java 25.0.4.1."),
            new ActivityEntry(T.plusSeconds(2), ActivitySeverity.INFO, "Monitoring started: system metrics every 1 s."),
            new ActivityEntry(
                    T.plusSeconds(55),
                    ActivitySeverity.WARNING,
                    "Change priority: Access denied: svchost.exe (PID 1288). The system does not let Prism act on"
                            + " this process. [OpenProcess failed: 5 (Access is denied.); code 5]"),
            new ActivityEntry(
                    T.plusSeconds(61),
                    ActivitySeverity.ERROR,
                    "Resume: The system did not resume java (PID 30324). The Logs page has the details. [1 live"
                            + " thread(s) still suspended by Prism: 23 resumed, 0 ended, 1 failed (6 (The handle is"
                            + " invalid.)); code 6]"));

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

    private static Stream<Arguments> pagesThemesAndSizes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Destination destination : Destination.values()) {
            for (Theme theme : Theme.values()) {
                for (int[] size : SIZES) {
                    cases.add(Arguments.of(destination, theme, size[0], size[1]));
                }
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1} {2}x{3}")
    @MethodSource("pagesThemesAndSizes")
    void rendersEveryPageAtEverySize(Destination destination, Theme theme, int width, int height) {
        String name = "matrix-" + destination.name().toLowerCase(Locale.ROOT).replace('_', '-');
        Path png = SnapshotHarness.render(name, theme, width, height, () -> {
            Shell shell = new Shell(
                    new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
            // The live values of the Overview, with 16 processors and file systems: the Performance
            // page shows its processor grid and its tables (OP-09; before, "Unavailable" everywhere).
            shell.showSnapshot(PerformanceSnapshots.live());
            shell.showControlProfile(WINDOWS_LIKE);
            shell.showLimits(NATIVE);
            shell.showActivity(SESSION);
            shell.navigation().select(destination);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }
}
