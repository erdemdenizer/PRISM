package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.DiskRates;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Manual measurement: what one snapshot costs on the JavaFX Application Thread. Every update
 * changes every value and every history, so all four graphs redraw each time; the running
 * application does that once a second.
 *
 * <p>Two figures are printed. "Update and layout" is the work the application does itself: view
 * model, text changes, Canvas commands, CSS and layout. "With rendering to an image" adds a
 * software render of the whole scene, which is more than the toolkit does for the changed parts
 * of a window and so an upper bound, not the cost on screen.
 *
 * <p>The class name keeps it out of every build. Run it with {@code ./mvnw -q -Pui-snapshots test
 * -Dtest=OverviewUpdateCostProbe}; it needs a display.
 */
@Tag("ui-snapshot")
class OverviewUpdateCostProbe {

    private static final int WARM_UP = 200;
    private static final int UPDATES = Integer.getInteger("probe.updates", 1000);

    @Test
    void printTheCostOfAnUpdate() {
        SystemSnapshot[] snapshots = new SystemSnapshot[WARM_UP + UPDATES];
        Arrays.setAll(snapshots, OverviewUpdateCostProbe::snapshotAt);

        long[][] nanos = SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> new Shell(
                        new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, "logs", identity -> {}),
                root -> {
                    Shell shell = (Shell) root;
                    long[] update = new long[UPDATES];
                    long[] rendered = new long[UPDATES];
                    for (int index = 0; index < snapshots.length; index++) {
                        long start = System.nanoTime();
                        shell.showSnapshot(snapshots[index]);
                        shell.applyCss();
                        shell.layout();
                        long laidOut = System.nanoTime();
                        shell.snapshot(null, null);
                        long drawn = System.nanoTime();
                        if (index >= WARM_UP) {
                            update[index - WARM_UP] = laidOut - start;
                            rendered[index - WARM_UP] = drawn - start;
                        }
                    }
                    return new long[][] {update, rendered};
                });

        print("Update and layout", nanos[0]);
        print("With rendering to an image", nanos[1]);
        assertThat(nanos[0]).hasSize(UPDATES);
    }

    /** A snapshot in which every value and every history differs from the one before. */
    private static SystemSnapshot snapshotAt(int tick) {
        SystemSnapshot live = Snapshots.live();
        double phase = tick / 4.0;
        return new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                Optional.of(new CpuUsage(50 + 40 * Math.sin(phase), List.<OptionalDouble>of())),
                Optional.of(Snapshots.memory(12 + 4 * Math.sin(phase / 3))),
                Optional.empty(),
                Optional.of(new DiskRates(
                        Snapshots.MIB * (2 + Math.sin(phase)), Snapshots.KIB * (300 + 200 * Math.cos(phase)))),
                Optional.of(new NetworkUsage(
                        List.of(),
                        Optional.of(new NetworkRates(
                                Snapshots.KIB * (400 + 300 * Math.sin(phase)),
                                Snapshots.KIB * (40 + 30 * Math.cos(phase)))))),
                Optional.of(new SystemActivity(Duration.ofSeconds(100_000 + tick), 300 + tick % 50)),
                new SystemHistory(
                        Snapshots.series(Snapshots.CAPACITY, i -> 50 + 40 * Math.sin(phase + i / 5.0)),
                        List.of(),
                        Snapshots.series(
                                Snapshots.CAPACITY, i -> Snapshots.GIB * (12 + 4 * Math.sin(phase / 3 + i / 9.0))),
                        Snapshots.series(Snapshots.CAPACITY, i -> Snapshots.MIB * (2 + Math.sin(phase + i / 3.0))),
                        Snapshots.series(
                                Snapshots.CAPACITY, i -> Snapshots.KIB * (300 + 200 * Math.cos(phase + i / 4.0))),
                        Snapshots.series(
                                Snapshots.CAPACITY, i -> Snapshots.KIB * (400 + 300 * Math.sin(phase + i / 6.0))),
                        Snapshots.series(
                                Snapshots.CAPACITY, i -> Snapshots.KIB * (40 + 30 * Math.cos(phase + i / 2.0)))));
    }

    private static void print(String what, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
        System.out.println(String.format(
                Locale.ROOT,
                "%-28s %d updates: median %.3f ms, mean %.3f ms, 95th %.3f ms, worst %.3f ms",
                what,
                sorted.length,
                sorted[sorted.length / 2] / 1e6,
                mean,
                sorted[(int) (sorted.length * 0.95)] / 1e6,
                sorted[sorted.length - 1] / 1e6));
    }
}
