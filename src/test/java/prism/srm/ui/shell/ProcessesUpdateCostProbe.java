package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.processes.ProcessListings;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Manual measurement: what the Processes page costs on the JavaFX Application Thread. The running
 * application gives the page a listing every two seconds; this gives it a thousand in a row and
 * times each.
 *
 * <p>Per case three figures are printed. "Rows" is the page taking the listing: comparing it with
 * its rows by identity, setting the values, and changing the list of rows if a row came, went or
 * moved. "With layout" adds CSS and layout, in which the cells on screen set their texts. "With
 * rendering" adds a software render of the whole scene, which is more than the toolkit does for
 * the changed parts of a window and so an upper bound, not the cost on screen. The memory figure
 * is what the JavaFX Application Thread allocated per listing for rows and layout, as the JVM
 * counts it; the listings themselves are made before the clock starts.
 *
 * <p>The class name keeps it out of every build. Run it with {@code ./mvnw -q -Pui-snapshots test
 * -Dtest=ProcessesUpdateCostProbe}; it needs a display. {@code -Dprobe.processes=1000} sets the
 * number of processes.
 */
@Tag("ui-snapshot")
class ProcessesUpdateCostProbe {

    private static final int WARM_UP = 200;
    private static final int UPDATES = Integer.getInteger("probe.updates", 1000);
    private static final int PROCESSES = Integer.getInteger("probe.processes", 400);

    /** What one case measured, per listing. */
    private record Cost(long[] rows, long[] laidOut, long[] rendered, long bytesPerListing) {}

    @Test
    void printTheCostOfAListing() {
        System.out.println(String.format(Locale.ROOT, "Processes per listing: %d.", PROCESSES));

        measure("Nothing changes, sorted by name", tick -> ProcessListings.windows(PROCESSES, 0), page -> {});
        measure("A desktop at work, sorted by name", tick -> ProcessListings.windows(PROCESSES, tick), page -> {});
        measure(
                "A desktop at work, sorted by CPU",
                tick -> ProcessListings.windows(PROCESSES, tick),
                page -> sortBy(page, "CPU"));
        measure(
                "Every value of every row changes, sorted by name",
                ProcessesUpdateCostProbe::everythingChanges,
                page -> {});
        measure(
                "Every value of every row changes, sorted by CPU",
                ProcessesUpdateCostProbe::everythingChanges,
                page -> sortBy(page, "CPU"));
        measure(
                "Ten processes start and ten exit with every listing, sorted by CPU, one row selected",
                ProcessesUpdateCostProbe::comingAndGoing,
                page -> {
                    sortBy(page, "CPU");
                    table(page).getSelectionModel().select(0);
                });
        measure(
                "A desktop at work, filtered to one name in eighteen",
                tick -> ProcessListings.windows(PROCESSES, tick),
                page -> ((TextField) page.lookup(".process-filter")).setText("chrome"));
    }

    /** What typing a filter costs: each key filters the rows the page holds, and nothing else. */
    @Test
    void printTheCostOfAKeyInTheFilterAndOfASort() {
        String typed = "svchost.exe";
        long[][] nanos =
                SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, ProcessesUpdateCostProbe::shellOnProcesses, root -> {
                    Shell shell = (Shell) root;
                    Node page = ((ScrollPane) shell.getCenter()).getContent();
                    TextField filter = (TextField) page.lookup(".process-filter");
                    shell.showProcesses(ProcessListings.windows(PROCESSES, 0));
                    settle(shell);
                    int rounds = 200;
                    long[] keys = new long[rounds * typed.length()];
                    long[] sorts = new long[rounds * 2];
                    for (int round = -20; round < rounds; round++) {
                        for (int length = 1; length <= typed.length(); length++) {
                            long start = System.nanoTime();
                            filter.setText(typed.substring(0, length));
                            settle(shell);
                            if (round >= 0) {
                                keys[round * typed.length() + length - 1] = System.nanoTime() - start;
                            }
                        }
                        filter.setText("");
                        settle(shell);
                        for (int column = 0; column < 2; column++) {
                            long start = System.nanoTime();
                            sortBy(page, column == 0 ? "Memory" : "Name");
                            settle(shell);
                            if (round >= 0) {
                                sorts[round * 2 + column] = System.nanoTime() - start;
                            }
                        }
                    }
                    return new long[][] {keys, sorts};
                });

        System.out.println(String.format(Locale.ROOT, "Processes per listing: %d.", PROCESSES));
        print("A key typed in the filter, with layout", nanos[0]);
        print("A sort by another column, with layout", nanos[1]);
        assertThat(nanos[0]).isNotEmpty();
    }

    private static void measure(String what, IntFunction<List<ProcessSnapshot>> listingAt, Consumer<Node> state) {
        List<List<ProcessSnapshot>> listings = new ArrayList<>(WARM_UP + UPDATES);
        for (int tick = 0; tick < WARM_UP + UPDATES; tick++) {
            listings.add(List.copyOf(listingAt.apply(tick)));
        }
        Cost cost =
                SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, ProcessesUpdateCostProbe::shellOnProcesses, root -> {
                    Shell shell = (Shell) root;
                    state.accept(((ScrollPane) shell.getCenter()).getContent());
                    long[] rows = new long[UPDATES];
                    long[] laidOut = new long[UPDATES];
                    long[] rendered = new long[UPDATES];
                    com.sun.management.ThreadMXBean threads =
                            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                    long thread = Thread.currentThread().threadId();
                    long allocated = 0;
                    for (int index = 0; index < listings.size(); index++) {
                        long bytesBefore = threads.getThreadAllocatedBytes(thread);
                        long start = System.nanoTime();
                        shell.showProcesses(listings.get(index));
                        long shown = System.nanoTime();
                        shell.applyCss();
                        shell.layout();
                        long settled = System.nanoTime();
                        long bytesAfter = threads.getThreadAllocatedBytes(thread);
                        shell.snapshot(null, null);
                        long drawn = System.nanoTime();
                        if (index >= WARM_UP) {
                            rows[index - WARM_UP] = shown - start;
                            laidOut[index - WARM_UP] = settled - start;
                            rendered[index - WARM_UP] = drawn - start;
                            allocated += bytesAfter - bytesBefore;
                        }
                    }
                    return new Cost(rows, laidOut, rendered, allocated / UPDATES);
                });

        System.out.println(what + ":");
        print("  rows", cost.rows());
        print("  with layout", cost.laidOut());
        print("  with rendering", cost.rendered());
        System.out.println(String.format(
                Locale.ROOT, "  allocated per listing, rows and layout: %.1f KiB", cost.bytesPerListing() / 1024.0));
        assertThat(cost.rows()).hasSize(UPDATES);
    }

    private static Shell shellOnProcesses() {
        Shell shell =
                new Shell(new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, "logs", identity -> {});
        ((Sidebar) shell.getLeft()).entry(Destination.PROCESSES).fire();
        return shell;
    }

    /** A listing in which every process has other values than in the one before. */
    private static List<ProcessSnapshot> everythingChanges(int tick) {
        List<ProcessSnapshot> listing = new ArrayList<>(PROCESSES);
        for (int index = 0; index < PROCESSES; index++) {
            double phase = index + tick / 3.0;
            listing.add(ProcessListings.process(4L * (index + 1), "process-" + index % 40 + ".exe")
                    .cpu(Math.abs(Math.sin(phase)) * 6.25)
                    .memory((100 + (index * 37L + tick * 13L) % 900) * ProcessListings.MIB)
                    .privateMemory((50 + (index * 23L + tick * 7L) % 500) * ProcessListings.MIB)
                    .io(
                            Math.abs(Math.cos(phase)) * 800 * ProcessListings.KIB,
                            Math.abs(Math.sin(phase / 2)) * 90 * ProcessListings.KIB)
                    .threads(2 + (index + tick) % 60)
                    .build());
        }
        return listing;
    }

    /** A listing that has lost the ten oldest of its extra processes and gained ten new ones. */
    private static List<ProcessSnapshot> comingAndGoing(int tick) {
        List<ProcessSnapshot> listing = new ArrayList<>(ProcessListings.windows(PROCESSES - 50, tick));
        for (int extra = 0; extra < 50; extra++) {
            long serial = tick * 10L + extra;
            listing.add(ProcessListings.process(1_000_000 + serial, "short-lived.exe")
                    .cpu(serial % 7)
                    .build());
        }
        return listing;
    }

    @SuppressWarnings("unchecked") // Only the title of a column is used.
    private static void sortBy(Node page, String title) {
        TableView<Object> table = (TableView<Object>) table(page);
        TableColumn<Object, ?> column = table.getColumns().stream()
                .filter(candidate -> candidate.getText().equals(title))
                .findFirst()
                .orElseThrow();
        column.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().setAll(List.of(column));
    }

    private static TableView<?> table(Node page) {
        return (TableView<?>) page.lookup(".table-view");
    }

    private static void settle(Shell shell) {
        shell.applyCss();
        shell.layout();
    }

    private static void print(String what, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
        System.out.println(String.format(
                Locale.ROOT,
                "%-40s %d: median %.3f ms, mean %.3f ms, 95th %.3f ms, worst %.3f ms",
                what,
                sorted.length,
                sorted[sorted.length / 2] / 1e6,
                mean,
                sorted[(int) (sorted.length * 0.95)] / 1e6,
                sorted[sorted.length - 1] / 1e6));
    }
}
