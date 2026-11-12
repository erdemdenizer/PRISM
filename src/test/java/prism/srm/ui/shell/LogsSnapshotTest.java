package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The Logs page (UI-6) and its states (UI-8) in both themes at the default, the smallest and a wide size. */
@Tag("ui-snapshot")
class LogsSnapshotTest {

    private static final Instant T = Instant.parse("2026-10-08T07:00:00Z");

    private static List<ActivityEntry> session() {
        List<ActivityEntry> entries = new ArrayList<>();
        entries.add(
                new ActivityEntry(T, ActivitySeverity.INFO, "Prism System Resource Manager started on Java 25.0.4.1."));
        entries.add(new ActivityEntry(
                T.plusSeconds(2), ActivitySeverity.INFO, "Monitoring started: system metrics every 1 s."));
        entries.add(new ActivityEntry(
                T.plusSeconds(40),
                ActivitySeverity.INFO,
                "Suspend: java (PID 30324) was suspended. Its threads were stopped one by one. [24 thread(s)"
                        + " suspended one by one]"));
        entries.add(new ActivityEntry(
                T.plusSeconds(55),
                ActivitySeverity.WARNING,
                "Change priority: Access denied: svchost.exe (PID 1288). The system does not let Prism act on this"
                        + " process. [OpenProcess failed: 5 (Access is denied.); code 5]"));
        entries.add(new ActivityEntry(
                T.plusSeconds(61),
                ActivitySeverity.ERROR,
                "Resume: The system did not resume java (PID 30324). The Logs page has the details. [1 live thread(s) still"
                        + " suspended by Prism: 23 resumed, 0 ended, 1 failed (6 (The handle is invalid.)); code 6]"));
        entries.add(new ActivityEntry(
                T.plusSeconds(70),
                ActivitySeverity.INFO,
                "Force kill: Force termination requested for java (PID 30324). The table shows when it has ended."
                        + " [termination started]"));
        return entries;
    }

    /**
     * A session as the real window logs it (LS-04): start-up lines with the folders, the OOM Guard
     * settings line, which at 1100 px is cut just where the switches that matter most are named, and
     * the actions on a managed process.
     */
    static List<ActivityEntry> realSession() {
        String home = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager";
        List<ActivityEntry> entries = new ArrayList<>();
        entries.add(new ActivityEntry(
                T,
                ActivitySeverity.INFO,
                "Prism System Resource Manager started on Java 25.0.4.1 (Eclipse Adoptium), JavaFX 25.0.4+2."));
        entries.add(new ActivityEntry(
                T,
                ActivitySeverity.INFO,
                "Detected system: Windows (os.name=Windows 11, os.version=10.0, os.arch=amd64)."));
        entries.add(new ActivityEntry(T, ActivitySeverity.INFO, "Log folder: " + home + "\\logs"));
        entries.add(new ActivityEntry(
                T, ActivitySeverity.INFO, "Settings read from " + home + "\\settings\\prism.settings"));
        entries.add(new ActivityEntry(
                T.plusSeconds(2), ActivitySeverity.INFO, "Monitoring started: system metrics every 1 s."));
        entries.add(new ActivityEntry(
                T.plusSeconds(4),
                ActivitySeverity.INFO,
                "Process monitoring started: every 2 s, 16 logical processors."));
        entries.add(new ActivityEntry(T.plusSeconds(271), ActivitySeverity.INFO, OOM_SETTINGS));
        entries.add(new ActivityEntry(T.plusSeconds(272), ActivitySeverity.INFO, "Settings saved: appearance DARK."));
        entries.add(new ActivityEntry(
                T.plusSeconds(584),
                ActivitySeverity.INFO,
                "Manage: prism-demo (PID 37720) is managed. Its limits are set on the Limits page."));
        entries.add(new ActivityEntry(
                T.plusSeconds(601),
                ActivitySeverity.WARNING,
                "Change priority: Access denied: svchost.exe (PID 1288). The system does not let Prism act on this"
                        + " process. [OpenProcess failed: 5 (Access is denied.); code 5]"));
        entries.add(new ActivityEntry(
                T.plusSeconds(640),
                ActivitySeverity.ERROR,
                "Resume: The system did not resume java (PID 30324). The Logs page has the details. [1 live thread(s) still"
                        + " suspended by Prism: 23 resumed, 0 ended, 1 failed (6 (The handle is invalid.)); code 6]"));
        entries.add(new ActivityEntry(
                T.plusSeconds(707), ActivitySeverity.INFO, "Unmanage: prism-demo (PID 37720) is no longer managed."));
        return entries;
    }

    /** The OOM Guard settings line as the real window logs it; its end is the safety switches. */
    static final String OOM_SETTINGS = "OOM Guard settings changed: monitoring on, warning 15 %, critical 8 %,"
            + " emergency 5 % or under 256 MiB; automatic priority reduction off, automatic suspend off, emergency"
            + " termination off.";

    /**
     * The sizes of the real Windows windows the lab is checked at, as their scenes (K8): at 100 %
     * scale a window's scene is 14 px narrower and 45 px shorter than its outer frame. 720 x 480,
     * 760 x 560, 1100 x 700, 1177 x 784, 1366 x 768, 1440 x 900, 1600 x 500 and 1920 x 1032 windows.
     */
    static final int[][] REAL_SCENES = {
        {706, 435}, {746, 515}, {1086, 655}, {1163, 739}, {1352, 723}, {1426, 855}, {1586, 455}, {1906, 987}
    };

    /** As many entries as the activity log keeps: the earlier ones are only in the file. */
    private static List<ActivityEntry> full() {
        List<ActivityEntry> entries = new ArrayList<>();
        for (int index = 0; index < ActivityLog.DEFAULT_CAPACITY; index++) {
            entries.add(new ActivityEntry(
                    T.plusSeconds(index),
                    index % 7 == 0 ? ActivitySeverity.WARNING : ActivitySeverity.INFO,
                    "OOM Guard: demo (PID 4242) is above its memory threshold; sample " + index + "."));
        }
        return entries;
    }

    private static Stream<Arguments> states() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : new int[][] {{1100, 700}, {720, 480}, {704, 441}, {1600, 1000}}) {
                cases.add(Arguments.of(
                        "logs-session", theme, size, (Consumer<Shell>) shell -> shell.showActivity(session())));
                cases.add(Arguments.of(
                        "logs-empty", theme, size, (Consumer<Shell>) shell -> shell.showActivity(List.of())));
                cases.add(Arguments.of("logs-reading", theme, size, (Consumer<Shell>) shell -> {}));
                cases.add(
                        Arguments.of("logs-full", theme, size, (Consumer<Shell>) shell -> shell.showActivity(full())));
                cases.add(Arguments.of("logs-folder-failed", theme, size, (Consumer<Shell>) shell -> {
                    shell.showActivity(session());
                    ((Button) java.util.Objects.requireNonNull(
                                    shell.getCenter().lookup(".logs-open"), "the Open log folder button"))
                            .fire();
                    shell.showFolderOpened(
                            Optional.of(
                                    "This folder does not exist yet: C:\\Users\\alice\\AppData"
                                            + "\\Local\\Prism\\SystemResourceManager\\logs. Prism creates it when it first writes there."));
                }));
                cases.add(Arguments.of("logs-filtered", theme, size, (Consumer<Shell>) shell -> {
                    shell.showActivity(session());
                    ((TextField) shell.getCenter().lookup(".logs-filter")).setText("pid 30324");
                }));
            }
        }
        return cases.build();
    }

    /**
     * The real session at the scene sizes of real windows (file names end in "-real", the size in
     * them is the scene's): with nothing selected, the detail pane says how to fill it; with the cut
     * OOM Guard settings line selected in the focused table, the pane shows it whole.
     */
    private static Stream<Arguments> realStates() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            for (int[] size : REAL_SCENES) {
                cases.add(Arguments.of("logs-session-real", theme, size, -1));
                cases.add(Arguments.of("logs-selected-real", theme, size, 5));
            }
        }
        // Ultrawide: the message column keeps its readable width (LS-14).
        cases.add(Arguments.of("logs-selected-ultrawide", Theme.DARK, new int[] {3440, 1440}, 5));
        return cases.build();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("states")
    void rendersEveryState(String name, Theme theme, int[] size, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name, theme, size[0], size[1], () -> {
            Shell shell = shell();
            shell.navigation().entry(Destination.LOGS).fire();
            if (!name.equals("logs-reading")) {
                // The status bar says what the content shows: monitoring runs (LS-04).
                shell.showSnapshot(Snapshots.live());
            }
            state.accept(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("realStates")
    void rendersTheRealSessionAtRealSizes(String name, Theme theme, int[] size, int selected) {
        Path png = SnapshotHarness.render(
                name,
                theme,
                size[0],
                size[1],
                () -> {
                    Shell shell = shell();
                    shell.navigation().entry(Destination.LOGS).fire();
                    shell.showSnapshot(Snapshots.live());
                    shell.showActivity(realSession());
                    return shell;
                },
                root -> {
                    if (selected >= 0) {
                        @SuppressWarnings("unchecked")
                        TableView<ActivityEntry> table = (TableView<ActivityEntry>)
                                java.util.Objects.requireNonNull(root.lookup(".logs-table"), "the log table");
                        table.requestFocus();
                        table.getSelectionModel().select(selected);
                        table.getFocusModel().focus(selected);
                    }
                });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Shell shell() {
        return new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs",
                identity -> {},
                request -> {},
                () -> {});
    }
}
