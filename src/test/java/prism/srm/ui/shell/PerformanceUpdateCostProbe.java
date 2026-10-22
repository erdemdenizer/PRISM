package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CpuUsage;
import prism.srm.domain.DeviceLayer;
import prism.srm.domain.DiskRates;
import prism.srm.domain.FileSystemUsage;
import prism.srm.domain.MemorySample;
import prism.srm.domain.MetricHistory;
import prism.srm.domain.NetworkInterfaceUsage;
import prism.srm.domain.NetworkRates;
import prism.srm.domain.NetworkUsage;
import prism.srm.domain.SwapUsage;
import prism.srm.domain.SystemActivity;
import prism.srm.domain.SystemHistory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.performance.PerformanceSnapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Manual measurement: what one snapshot costs on the JavaFX Application Thread while a section of
 * the Performance page is on screen. Every update changes every value, every history and every
 * row, so everything on the section redraws each time; the running application does that once a
 * second. The machine of the snapshots has as many logical processors and interfaces as given.
 *
 * <p>Two figures are printed per section. "Update and layout" is the work the application does
 * itself: view model, text changes, Canvas commands, CSS and layout. "With rendering to an image"
 * adds a software render of the whole scene, which is more than the toolkit does for the changed
 * parts of a window and so an upper bound, not the cost on screen.
 *
 * <p>The class name keeps it out of every build. Run it with {@code ./mvnw -q -Pui-snapshots test
 * -Dtest=PerformanceUpdateCostProbe}; it needs a display. {@code -Dprobe.processors=64} and
 * {@code -Dprobe.interfaces=36} set the size of the machine.
 */
@Tag("ui-snapshot")
class PerformanceUpdateCostProbe {

    private static final int WARM_UP = 200;
    private static final int UPDATES = Integer.getInteger("probe.updates", 1000);
    private static final int PROCESSORS = Integer.getInteger("probe.processors", 16);
    private static final int INTERFACES = Integer.getInteger("probe.interfaces", 36);

    @Test
    void printTheCostOfAnUpdate() {
        SystemSnapshot[] snapshots = new SystemSnapshot[WARM_UP + UPDATES];
        Arrays.setAll(snapshots, PerformanceUpdateCostProbe::snapshotAt);

        System.out.println(
                String.format(Locale.ROOT, "Machine: %d logical processors, %d interfaces.", PROCESSORS, INTERFACES));
        for (String section : new String[] {"CPU", "Memory", "Disk", "Network"}) {
            long[][] nanos = SnapshotHarness.inspect(
                    Theme.LIGHT,
                    1100,
                    700,
                    () -> {
                        Shell shell = new Shell(
                                new ValueFormatter(Locale.US),
                                ZoneOffset.UTC,
                                Snapshots.CAPACITY,
                                "logs",
                                identity -> {});
                        ((Sidebar) shell.getLeft())
                                .entry(Destination.PERFORMANCE)
                                .fire();
                        Node page = ((ScrollPane) shell.getCenter()).getContent();
                        page.lookupAll(".section-tab").stream()
                                .map(ToggleButton.class::cast)
                                .filter(entry -> entry.getText().equals(section))
                                .findFirst()
                                .orElseThrow()
                                .fire();
                        return shell;
                    },
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

            print(section + ": update and layout", nanos[0]);
            print(section + ": with rendering to an image", nanos[1]);
            assertThat(nanos[0]).hasSize(UPDATES);
        }
    }

    /** A snapshot in which every value, every history and every row differs from the one before. */
    private static SystemSnapshot snapshotAt(int tick) {
        SystemSnapshot live = PerformanceSnapshots.live(PROCESSORS);
        double phase = tick / 4.0;
        List<OptionalDouble> perProcessor = new ArrayList<>();
        List<MetricHistory> perProcessorHistory = new ArrayList<>();
        for (int index = 0; index < PROCESSORS; index++) {
            int processor = index;
            perProcessor.add(OptionalDouble.of(50 + 40 * Math.sin(phase + processor)));
            perProcessorHistory.add(Snapshots.series(
                    Snapshots.CAPACITY, i -> 50 + 40 * Math.sin(phase + processor + i / 5.0)));
        }
        List<NetworkInterfaceUsage> interfaces = new ArrayList<>();
        for (int index = 0; index < INTERFACES; index++) {
            interfaces.add(new NetworkInterfaceUsage(
                    "eth" + index,
                    "Ethernet adapter " + index,
                    index < 2 ? DeviceLayer.PHYSICAL : DeviceLayer.LOGICAL,
                    Optional.of(new NetworkRates(
                            Snapshots.KIB * (400 + 300 * Math.sin(phase + index)),
                            Snapshots.KIB * (40 + 30 * Math.cos(phase + index))))));
        }
        long usable = Snapshots.GIB * (100 + tick % 50);
        return new SystemSnapshot(
                live.processor(),
                live.operatingSystem(),
                Optional.of(new CpuUsage(50 + 40 * Math.sin(phase), perProcessor)),
                Optional.of(new MemorySample(
                        32 * Snapshots.GIB,
                        Math.round(Snapshots.GIB * (20 - 4 * Math.sin(phase / 3))),
                        Optional.of(new SwapUsage(8 * Snapshots.GIB, Snapshots.MIB * (1000 + tick % 500))))),
                Optional.of(List.of(
                        new FileSystemUsage("Local Fixed Disk (C:)", "C:\\", 475 * Snapshots.GIB, usable),
                        new FileSystemUsage("Local Fixed Disk (D:)", "D:\\", 931 * Snapshots.GIB, usable * 2),
                        new FileSystemUsage("Removable Disk (E:)", "E:\\", 29 * Snapshots.GIB, usable / 8))),
                Optional.of(new DiskRates(
                        Snapshots.MIB * (2 + Math.sin(phase)), Snapshots.KIB * (300 + 200 * Math.cos(phase)))),
                Optional.of(new NetworkUsage(
                        interfaces,
                        Optional.of(new NetworkRates(
                                Snapshots.KIB * (400 + 300 * Math.sin(phase)),
                                Snapshots.KIB * (40 + 30 * Math.cos(phase)))))),
                Optional.of(new SystemActivity(Duration.ofSeconds(100_000 + tick), 300 + tick % 50)),
                new SystemHistory(
                        Snapshots.series(Snapshots.CAPACITY, i -> 50 + 40 * Math.sin(phase + i / 5.0)),
                        perProcessorHistory,
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
                "%-40s %d updates: median %.3f ms, mean %.3f ms, 95th %.3f ms, worst %.3f ms",
                what,
                sorted.length,
                sorted[sorted.length / 2] / 1e6,
                mean,
                sorted[(int) (sorted.length * 0.95)] / 1e6,
                sorted[sorted.length - 1] / 1e6));
    }
}
