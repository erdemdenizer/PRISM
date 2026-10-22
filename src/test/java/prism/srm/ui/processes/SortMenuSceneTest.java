package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Sorting without a mouse: the context menu of the process table, opened by the keyboard, with a
 * column and a direction chosen in it. The menu is a window of its own, so the cases that open
 * it run in a real stage; the ones that only choose an item run in the harness scene.
 */
@Tag("ui-snapshot")
class SortMenuSceneTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    /** The page, its table and the requests for details it has made. */
    private record Scene2(ProcessesPage page, List<ProcessIdentity> asked) {

        @SuppressWarnings("unchecked") // The table of the Processes page is a table of process rows.
        TableView<ProcessRow> table() {
            return (TableView<ProcessRow>) page.lookup(".table-view");
        }

        ContextMenu menu() {
            return table().getContextMenu();
        }

        Menu sortBy() {
            return (Menu) menu().getItems().get(0);
        }

        RadioMenuItem column(String title) {
            return sortBy().getItems().stream()
                    .map(RadioMenuItem.class::cast)
                    .filter(item -> item.getText().equals(title))
                    .findFirst()
                    .orElseThrow();
        }

        RadioMenuItem direction(String title) {
            return menu().getItems().stream()
                    .filter(item ->
                            item instanceof RadioMenuItem && item.getText().equals(title))
                    .map(RadioMenuItem.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        TableColumn<ProcessRow, ?> header(String title) {
            return table().getColumns().stream()
                    .filter(column -> column.getText().equals(title))
                    .findFirst()
                    .orElseThrow();
        }

        List<Long> pids() {
            return table().getItems().stream().map(row -> row.identity().pid()).toList();
        }

        void settle() {
            page.applyCss();
            page.layout();
        }

        /** What a real Shift+F10 or Menu key comes to: a context menu event on the focused table. */
        void pressTheMenuKey() {
            Event.fireEvent(
                    table(),
                    new ContextMenuEvent(
                            ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            10,
                            10,
                            10,
                            10,
                            true,
                            new PickResult(table(), 10, 10)));
        }
    }

    @Test
    void theMenuOffersEveryColumnAndBothDirectionsAndNothingElse() {
        List<Object> seen = inPage(scene -> {
            List<Object> facts = new ArrayList<>();
            facts.add(scene.menu().getItems().size());
            facts.add(scene.sortBy().getText());
            facts.add(scene.sortBy().getItems().stream().map(MenuItem::getText).toList());
            facts.add(scene.menu().getItems().get(1) instanceof SeparatorMenuItem);
            facts.add(scene.menu().getItems().get(2).getText());
            facts.add(scene.menu().getItems().get(3).getText());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        4,
                        "Sort by",
                        List.of("Name", "PID", "CPU", "Memory", "I/O", "User", "Threads", "Priority", "State"),
                        true,
                        "Ascending",
                        "Descending");
    }

    @Test
    void choosingAColumnSortsByItsValuesSmallestFirstAndKeepsTheSelection() {
        List<Object> seen = inPage(scene -> {
            scene.page().show(List.of(memory(10, 900), memory(9, 2048), memory(1000, 1), bare(20)));
            scene.settle();
            ProcessRow selected = row(scene, 9);
            scene.table().getSelectionModel().select(selected);
            int askedBefore = scene.asked().size();

            scene.column("Memory").fire();
            scene.settle();

            return List.of(
                    scene.pids(),
                    scene.table().getSortOrder().size(),
                    scene.header("Memory").getSortType(),
                    scene.table().getSortOrder().get(0) == scene.header("Memory"),
                    scene.table().getSelectionModel().getSelectedItem() == selected,
                    scene.table()
                                    .getItems()
                                    .get(scene.table().getSelectionModel().getSelectedIndex())
                            == selected,
                    scene.asked().size() - askedBefore);
        });

        // 1 B, 900 B, 2 KiB as sizes, not as texts; the process without a value last; no new request.
        assertThat(seen)
                .containsExactly(List.of(1000L, 10L, 9L, 20L), 1, TableColumn.SortType.ASCENDING, true, true, true, 0);
    }

    @Test
    void choosingTheDirectionTurnsTheOrderAroundAndLeavesTheMissingValueLast() {
        List<Object> seen = inPage(scene -> {
            scene.page().show(List.of(memory(10, 900), memory(9, 2048), memory(1000, 1), bare(20)));
            scene.settle();
            scene.column("Memory").fire();
            scene.settle();
            ProcessRow selected = row(scene, 1000);
            scene.table().getSelectionModel().select(selected);

            scene.direction("Descending").fire();
            scene.settle();
            List<Long> descending = scene.pids();
            boolean stillSelected = scene.table().getSelectionModel().getSelectedItem() == selected;

            scene.direction("Ascending").fire();
            scene.settle();

            return List.of(
                    descending,
                    scene.header("Memory").getSortType() == TableColumn.SortType.ASCENDING,
                    scene.pids(),
                    stillSelected,
                    scene.table().getSelectionModel().getSelectedItem() == selected);
        });

        assertThat(seen).containsExactly(List.of(9L, 10L, 1000L, 20L), true, List.of(1000L, 10L, 9L, 20L), true, true);
    }

    @Test
    void anOrderChosenInTheMenuHoldsThroughTheListingsThatFollow() {
        List<Object> seen = inPage(scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 5), cpu(30, 3)));
            scene.settle();
            scene.column("CPU").fire();
            scene.direction("Descending").fire();
            scene.settle();
            List<Long> before = scene.pids();

            scene.page().show(List.of(cpu(10, 9), cpu(20, 5), cpu(30, 3)));
            scene.settle();

            return List.of(before, scene.pids());
        });

        assertThat(seen).containsExactly(List.of(20L, 30L, 10L), List.of(10L, 20L, 30L));
    }

    /** A click on a header is unchanged by the menu: both set the same sort order. */
    @Test
    void sortingByTheHeaderStillWorksAndTheMenuShowsWhatTheHeaderDid() {
        List<Object> seen = inStage(scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 5), cpu(30, 3)));
            scene.settle();
            TableColumn<ProcessRow, ?> cpu = scene.header("CPU");
            cpu.setSortType(TableColumn.SortType.DESCENDING);
            scene.table().getSortOrder().setAll(List.of(cpu));
            scene.settle();
            List<Long> byHeader = scene.pids();

            scene.pressTheMenuKey();
            List<Object> facts = new ArrayList<>();
            facts.add(byHeader);
            facts.add(scene.menu().isShowing());
            facts.add(scene.column("CPU").isSelected());
            facts.add(scene.column("Name").isSelected());
            facts.add(scene.direction("Descending").isSelected());
            facts.add(scene.direction("Ascending").isDisable());
            scene.menu().hide();
            return facts;
        });

        assertThat(seen).containsExactly(List.of(20L, 30L, 10L), true, true, false, true, false);
    }

    @Test
    void theMenuKeyOpensTheMenuOnTheFocusedTableAndTheMenuShowsTheOrderTheTableOpenedWith() {
        List<Object> seen = inStage(scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 5)));
            scene.settle();
            scene.table().requestFocus();

            scene.pressTheMenuKey();
            List<Object> facts = new ArrayList<>();
            facts.add(scene.menu().isShowing());
            facts.add(scene.column("Name").isSelected());
            facts.add(scene.direction("Ascending").isSelected());
            facts.add(scene.direction("Descending").isSelected());
            scene.menu().hide();
            facts.add(scene.menu().isShowing());
            facts.add(scene.page().getScene().getFocusOwner() == scene.table());
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, false, false, true);
    }

    /** The third click on a header leaves the rows unsorted; the menu says so and offers no direction. */
    @Test
    void withNoSortedColumnTheMenuSelectsNoColumnAndDisablesTheDirections() {
        List<Object> seen = inStage(scene -> {
            scene.page().show(List.of(cpu(10, 1), cpu(20, 5)));
            scene.table().getSortOrder().clear();
            scene.settle();

            scene.pressTheMenuKey();
            List<Object> facts = new ArrayList<>();
            facts.add(scene.sortBy().getItems().stream()
                    .map(RadioMenuItem.class::cast)
                    .noneMatch(RadioMenuItem::isSelected));
            facts.add(scene.direction("Ascending").isDisable());
            facts.add(scene.direction("Descending").isDisable());
            scene.menu().hide();
            // Choosing a column from that state sorts again.
            scene.column("PID").fire();
            scene.settle();
            facts.add(scene.pids());
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, List.of(10L, 20L));
    }

    /**
     * The menu is a window of its own and so not in the snapshots of the page. Its skin node is
     * rendered here in both themes, sorted and unsorted (the directions disabled), for inspection
     * against docs/UI_GUIDE.md. The submenu of the columns opens only from the keyboard or the
     * pointer inside the menu and is not rendered; the real application shows it.
     */
    @Test
    void rendersTheMenuInBothThemes() {
        for (Theme theme : Theme.values()) {
            for (String state : new String[] {"sorted", "unsorted"}) {
                java.nio.file.Path png = inStage(theme, scene -> {
                    scene.page().show(List.of(cpu(10, 1), cpu(20, 5)));
                    if (state.equals("unsorted")) {
                        scene.table().getSortOrder().clear();
                    }
                    scene.settle();
                    scene.table().requestFocus();
                    scene.pressTheMenuKey();
                    javafx.scene.Node content = scene.menu().getSkin().getNode();
                    content.applyCss();
                    javafx.scene.image.WritableImage image = content.snapshot(null, null);
                    scene.menu().hide();
                    return write("processes-sort-menu-" + state, theme, image);
                });
                assertThat(png).isRegularFile().isNotEmptyFile();
            }
        }
    }

    /** Writes the image of the menu as the harness names its files. */
    private static java.nio.file.Path write(String name, Theme theme, javafx.scene.image.WritableImage menu) {
        int width = (int) menu.getWidth();
        int height = (int) menu.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        copy(menu, out, 0);
        java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("srm.snapshot.dir", "target/ui-snapshots"))
                .resolve("%s-%s-%dx%d.png".formatted(name, theme.name().toLowerCase(Locale.ROOT), width, height));
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(out, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return file;
    }

    private static void copy(javafx.scene.image.WritableImage from, java.awt.image.BufferedImage to, int atX) {
        int width = (int) from.getWidth();
        int height = (int) from.getHeight();
        int[] pixels = new int[width * height];
        from.getPixelReader()
                .getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        to.setRGB(atX, 0, width, height, pixels, 0, width);
    }

    private static ProcessSnapshot cpu(long pid, double percent) {
        return ProcessListings.process(pid, "process-" + pid).cpu(percent).build();
    }

    private static ProcessSnapshot memory(long pid, long bytes) {
        return ProcessListings.process(pid, "process-" + pid).memory(bytes).build();
    }

    private static ProcessSnapshot bare(long pid) {
        return ProcessListings.process(pid, "process-" + pid).nothingElse().build();
    }

    private static ProcessRow row(Scene2 scene, long pid) {
        return scene.table().getItems().stream()
                .filter(row -> row.identity().pid() == pid)
                .findFirst()
                .orElseThrow();
    }

    private static <T> T inPage(Function<Scene2, T> scenario) {
        List<ProcessIdentity> asked = new ArrayList<>();
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> new ProcessesPage(new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, asked::add),
                root -> scenario.apply(new Scene2((ProcessesPage) root, asked)));
    }

    /** A real stage, because a context menu is a window and needs an owner window to show. */
    private static <T> T inStage(Function<Scene2, T> scenario) {
        return inStage(Theme.LIGHT, scenario);
    }

    private static <T> T inStage(Theme theme, Function<Scene2, T> scenario) {
        List<ProcessIdentity> asked = new ArrayList<>();
        return SnapshotHarness.inspect(Theme.LIGHT, 100, 100, javafx.scene.layout.Pane::new, ignored -> {
            ProcessesPage page =
                    new ProcessesPage(new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, asked::add);
            if (theme == Theme.DARK) {
                page.getStyleClass().add("theme-dark");
            }
            Scene scene = new Scene(page, 916, 651);
            scene.getStylesheets().add(Stylesheets.app());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.setX(1300);
            stage.setY(60);
            stage.show();
            try {
                return scenario.apply(new Scene2(page, asked));
            } finally {
                stage.close();
            }
        });
    }
}
