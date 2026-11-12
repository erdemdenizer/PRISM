package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;
import static prism.srm.ui.LimitsFixtures.limited;
import static prism.srm.ui.LimitsFixtures.managed;
import static prism.srm.ui.LimitsFixtures.throttling;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcessView;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The Limits page (UI-4) in each of its states, both themes, at the default size, the smallest window
 * (720 x 480, and what is left of it inside the Windows 11 frame), wide sizes, and the sizes of the UI
 * lab matrix where its panel is a sheet (narrow or low) or docked.
 */
@Tag("ui-snapshot")
class LimitsSnapshotTest {

    private static List<ManagedProcessView> mixed() {
        return List.of(
                throttling(identity(30_324), "java.exe", 30, 29.6),
                limited(
                        identity(18_812),
                        "ffmpeg.exe",
                        1,
                        true,
                        LimitStatus.ACTIVE_ABOVE_TARGET,
                        Optional.of("floor: the limit is below the lowest share Prism can hold this process to"),
                        OptionalDouble.of(6.1),
                        OptionalDouble.of(6.1)),
                limited(
                        identity(9_120),
                        "a-build-tool-with-a-very-long-name-that-the-table-has-to-cut-somewhere.exe",
                        60,
                        true,
                        LimitStatus.ACTIVE_NOT_THROTTLING,
                        Optional.empty(),
                        OptionalDouble.of(11.4),
                        OptionalDouble.empty()),
                limited(
                        identity(7_004),
                        "node.exe",
                        25,
                        false,
                        LimitStatus.INACTIVE,
                        Optional.of("disabled"),
                        OptionalDouble.empty(),
                        OptionalDouble.empty()),
                limited(
                        identity(5_200),
                        "python.exe",
                        40,
                        true,
                        LimitStatus.HELD_BY_OTHER,
                        Optional.empty(),
                        OptionalDouble.empty(),
                        OptionalDouble.empty()),
                limited(
                        identity(4_100),
                        "stress",
                        50,
                        true,
                        LimitStatus.FAILED,
                        Optional.of("access denied"),
                        OptionalDouble.empty(),
                        OptionalDouble.empty()),
                managed(identity(3_000), "notepad.exe"));
    }

    private static List<ManagedProcessView> nativeRows() {
        return List.of(
                LimitsFixtures.enforced(identity(30_324), "java.exe", 20, 512),
                LimitsFixtures.nativeLimited(
                        identity(18_812),
                        "chrome.exe",
                        java.util.OptionalInt.of(25),
                        java.util.OptionalLong.empty(),
                        LimitStatus.FAILED,
                        prism.srm.domain.LimitNote.FALLBACK_OFFERED,
                        Optional.of("the native limit was not applied: it is in a Job Object Prism does not hold")),
                LimitsFixtures.nativeLimited(
                        identity(9_120),
                        "a-build-tool-with-a-very-long-name-that-the-table-has-to-cut-somewhere.exe",
                        java.util.OptionalInt.of(30),
                        java.util.OptionalLong.empty(),
                        LimitStatus.FAILED,
                        prism.srm.domain.LimitNote.NATIVE_UNSETTLED,
                        Optional.of("in force: CPU 20 % or CPU 30 %")),
                LimitsFixtures.enforced(identity(7_004), "node.exe", 0, 256),
                throttling(identity(5_200), "python.exe", 40, 39.2));
    }

    private static Stream<Arguments> states() {
        Stream.Builder<Arguments> cases = Stream.builder();
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
                cases.add(Arguments.of("limits-reading", theme, size, (Consumer<Shell>) shell -> {}));
                cases.add(Arguments.of("limits-empty", theme, size, (Consumer<Shell>)
                        shell -> shell.showLimits(LimitsFixtures.snapshot())));
                cases.add(Arguments.of("limits-managed", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(new LimitsSnapshot(mixed(), LimitsFixtures.EMULATED, Optional.empty()));
                    select(shell, 0);
                }));
                cases.add(Arguments.of("limits-result", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(new LimitsSnapshot(mixed(), LimitsFixtures.EMULATED, Optional.empty()));
                    select(shell, 0);
                    press(shell, "Disable");
                    shell.showLimitResult(new LimitResult(
                            LimitRequest.of(LimitAction.DISABLE, identity(30_324), "java.exe"),
                            ControlOutcome.success(),
                            "The limits of java.exe (PID 30324) are disabled.",
                            "Prism no longer stops it."));
                }));
                cases.add(Arguments.of("limits-off", theme, size, (Consumer<Shell>)
                        shell -> shell.showLimits(new LimitsSnapshot(
                                mixed().subList(5, 7),
                                LimitsFixtures.EMULATED,
                                Optional.of("Limits are off: Prism cannot record them where it would find them after a"
                                        + " crash (the journal could not be written: There is not enough space on the"
                                        + " disk).")))));
                cases.add(Arguments.of("limits-native", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(LimitsFixtures.nativeSnapshot(
                            nativeRows(),
                            new prism.srm.domain.LimitsNotice(
                                    prism.srm.domain.LimitsNotice.Kind.RESIDUE,
                                    "The job of build.exe (PID 6100), which has exited, still carries limits Prism could not"
                                            + " clear (CPU 20 %); 2 processes it started stay limited. Prism keeps trying."),
                            new prism.srm.domain.LimitsNotice(
                                    prism.srm.domain.LimitsNotice.Kind.PRIOR_SESSION,
                                    "worker.exe (PID 7300) may still be capped natively by an earlier Prism session (CPU 15 %)."
                                            + " No second limiter is offered; that limit lasts until it exits.")));
                    select(shell, 1);
                }));
                cases.add(Arguments.of("limits-native-result", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(LimitsFixtures.nativeSnapshot(nativeRows()));
                    select(shell, 0);
                    press(shell, "Disable");
                    shell.showLimitResult(new LimitResult(
                            LimitRequest.of(LimitAction.DISABLE, identity(30_324), "java.exe"),
                            ControlOutcome.of(prism.srm.domain.OperationResult.NATIVE_ERROR, "partial"),
                            "Disable limits was not carried out for java.exe (PID 30324).",
                            "Its native limits are still in force (CPU 20 %, memory 512 MiB); it stays as it is. Prism tries"
                                    + " again later."));
                }));
                cases.add(Arguments.of("limits-unavailable", theme, size, (Consumer<Shell>) shell -> {
                    shell.showLimits(new LimitsSnapshot(
                            List.of(managed(identity(3_000), "gedit")),
                            LimitsFixtures.unavailable(
                                    "Process control: this kernel has no pidfd signalling (Linux 5.3 or newer is"
                                            + " needed)"),
                            Optional.empty()));
                    select(shell, 0);
                }));
            }
        }
        return cases.build();
    }

    private static Node page(Shell shell) {
        return shell.pageArea().getContent();
    }

    private static void select(Shell shell, int row) {
        TableView<?> table = (TableView<?>) page(shell).lookup(".limits-table");
        table.getSelectionModel().select(row);
        table.requestFocus();
    }

    /** A button of the page, or of its title line, which the shell shows in the page toolbar (UI lab v2). */
    private static void press(Shell shell, String text) {
        java.util.stream.Stream.concat(
                        page(shell).lookupAll(".button").stream(), shell.toolbar().lookupAll(".button").stream())
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .fire();
    }

    private static Shell limits() {
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

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("states")
    void rendersEveryState(String name, Theme theme, int[] size, Consumer<Shell> state) {
        Path png = SnapshotHarness.render(name + "-" + size[0], theme, size[0], size[1], () -> {
            Shell shell = limits();
            state.accept(shell);
            return shell;
        });

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    private static Stream<Arguments> sheets() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (Theme theme : Theme.values()) {
            // UI lab v2: a wide and low window (1600 x 500) docks the panel beside the table now
            // (Breakpoints): the low window with a sheet is one too narrow for the side, 1100 x 540.
            for (int[] size : new int[][] {{720, 480}, {704, 441}, {800, 600}, {1100, 540}}) {
                cases.add(Arguments.of(theme, size));
            }
        }
        return cases.build();
    }

    /**
     * Where the window is narrow or low the panel is a sheet over the table, opened by the button of
     * the header. It opens only once the page is in a scene of that size, so after the layout.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sheets")
    void rendersThePanelAsASheetOverTheTable(Theme theme, int[] size) {
        Path png = SnapshotHarness.render(
                "limits-sheet-" + size[0],
                theme,
                size[0],
                size[1],
                () -> {
                    Shell shell = limits();
                    shell.showLimits(LimitsFixtures.nativeSnapshot(nativeRows()));
                    select(shell, 1);
                    return shell;
                },
                root -> press((Shell) root, "Details and actions"));

        assertThat(png).isRegularFile().isNotEmptyFile();
    }

    /** UI-8: at the smallest window the page fits its area, and the actions stay in view. */
    @Test
    void atTheSmallestWindowThePageFitsItsAreaWithoutScrollingIt() {
        List<Double> sizes = SnapshotHarness.inspect(
                Theme.LIGHT,
                704,
                441,
                () -> {
                    Shell shell = limits();
                    shell.showLimits(new LimitsSnapshot(mixed(), LimitsFixtures.EMULATED, Optional.empty()));
                    return shell;
                },
                root -> {
                    ScrollPane area = ((Shell) root).pageArea();
                    Region page = (Region) area.getContent();
                    return List.of(
                            page.minHeight(page.getWidth()),
                            area.getViewportBounds().getHeight());
                });

        assertThat(sizes.get(0)).isLessThanOrEqualTo(sizes.get(1));
    }
}
