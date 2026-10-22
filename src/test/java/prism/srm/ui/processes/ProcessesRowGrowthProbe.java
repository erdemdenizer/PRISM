package prism.srm.ui.processes;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Manual measurement: whether the table creates rows while listings arrive, in a real window with
 * real pulses, sorted by CPU with a selected row that moves out of view. It does not; it does
 * when, after each listing, the table is asked what an accessibility client asks after a change
 * of the selection ({@code -Dprobe.a11y=true}), because JavaFX answers a question about a row that
 * is not on screen by taking or creating a row. See docs/PERFORMANCE.md, milestone 5.
 *
 * <p>Run it with {@code ./mvnw -q -Pui-snapshots test -Dtest=ProcessesRowGrowthProbe}; it opens a
 * window for a quarter of a second per listing. {@code -Dprobe.listings}, {@code -Dprobe.select},
 * {@code -Dprobe.sortCpu} and {@code -Dprobe.varyCount} set the case.
 */
@Tag("ui-snapshot")
class ProcessesRowGrowthProbe {

    private static final int LISTINGS = Integer.getInteger("probe.listings", 60);
    private static final boolean SELECT = Boolean.parseBoolean(System.getProperty("probe.select", "true"));
    private static final boolean SORT_CPU = Boolean.parseBoolean(System.getProperty("probe.sortCpu", "true"));
    private static final boolean VARY_COUNT = Boolean.parseBoolean(System.getProperty("probe.varyCount", "true"));

    @Test
    void printRowCountsInARealWindow() throws Exception {
        // Starts the toolkit the harness way, then opens a stage of our own.
        SnapshotHarness.inspect(Theme.LIGHT, 100, 100, javafx.scene.layout.Pane::new, root -> null);
        CountDownLatch done = new CountDownLatch(1);
        List<String> lines = new ArrayList<>();
        Platform.runLater(() -> {
            ProcessesPage page = new ProcessesPage(new ValueFormatter(Locale.US), ZoneOffset.UTC, "logs", id -> {});
            Scene scene = new Scene(page, 916, 651);
            scene.getStylesheets().add(Stylesheets.app());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.setX(1300);
            stage.setY(60);
            stage.show();
            @SuppressWarnings("unchecked")
            TableView<ProcessRow> table = (TableView<ProcessRow>) page.lookup(".table-view");
            page.show(listing(0));
            if (SORT_CPU) {
                TableColumn<ProcessRow, ?> cpu = table.getColumns().get(2);
                cpu.setSortType(TableColumn.SortType.DESCENDING);
                table.getSortOrder().setAll(List.of(cpu));
            }
            if (SELECT) {
                table.getSelectionModel().select(3);
            }
            javafx.animation.Timeline ticks = new javafx.animation.Timeline();
            int[] tick = {0};
            ticks.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(250), e -> {
                tick[0]++;
                page.show(listing(tick[0]));
                if (Boolean.getBoolean("probe.a11y")) {
                    // What an accessibility client asks after a selection or focus change.
                    int focused = table.getFocusModel().getFocusedIndex();
                    table.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.FOCUS_ITEM);
                    table.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.ROW_AT_INDEX, focused);
                    table.queryAccessibleAttribute(javafx.scene.AccessibleAttribute.SELECTED_ITEMS);
                }
                if (tick[0] % 10 == 0) {
                    lines.add("tick " + tick[0] + ": rows=" + rows(page) + " selected="
                            + table.getSelectionModel().getSelectedIndex() + " items="
                            + table.getItems().size());
                }
                if (tick[0] >= LISTINGS) {
                    ticks.stop();
                    stage.close();
                    done.countDown();
                }
            }));
            ticks.setCycleCount(javafx.animation.Animation.INDEFINITE);
            ticks.play();
        });
        done.await(120, TimeUnit.SECONDS);
        System.out.println("select=" + SELECT + " sortCpu=" + SORT_CPU + " varyCount=" + VARY_COUNT);
        lines.forEach(System.out::println);
    }

    /** About 260 processes; a quarter change CPU; now and then one starts and one exits. */
    private static List<ProcessSnapshot> listing(int tick) {
        List<ProcessSnapshot> listing = new ArrayList<>(ProcessListings.windows(260, tick));
        if (VARY_COUNT) {
            for (int extra = 0; extra < 1 + tick % 3; extra++) {
                listing.add(ProcessListings.process(900_000 + tick * 3L + extra, "passing")
                        .cpu(tick % 5)
                        .build());
            }
        }
        return listing;
    }

    private static int rows(Node node) {
        int count = node instanceof TableRow ? 1 : 0;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                count += rows(child);
            }
        }
        return count;
    }
}
