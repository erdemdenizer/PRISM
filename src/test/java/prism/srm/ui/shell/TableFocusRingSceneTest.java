package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.ui.logs.LogsPage;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * KB-2 (keyboard acceptance of the packaged image, fix round H): a table that is a Tab stop draws a
 * focus ring when no row has the focus: the empty tables of OOM Guard (on the placeholder, table.css,
 * KB-1), and the Logs table tabbed into before a row was focused (on the frame, TableFocus). The real
 * acceptance found the pixels the same with and without the focus;
 * here the pixels around each table must change when the table takes the keyboard focus, and the ring
 * must give way to the ring of the row once a row has the focus.
 *
 * <p>A window that is never shown has no focus to give: the table is given {@code :focus-visible} as the
 * keyboard gives it, as the other focus snapshots do.
 */
@Tag("ui-snapshot")
class TableFocusRingSceneTest {

    private static final PseudoClass FOCUS_VISIBLE = PseudoClass.getPseudoClass("focus-visible");
    private static final Instant T = Instant.parse("2026-10-08T07:00:00Z");

    enum Stop {
        OOM_MANAGED_EMPTY(".oom-table", TableFocusRingSceneTest::emptyOom),
        OOM_RECENT_EMPTY(".oom-recent", TableFocusRingSceneTest::emptyOom),
        LOGS_NO_FOCUSED_ROW(".logs-table", TableFocusRingSceneTest::logs);

        final String table;
        final Supplier<Parent> content;

        Stop(String table, Supplier<Parent> content) {
            this.table = table;
            this.content = content;
        }
    }

    private static Parent emptyOom() {
        Shell shell = OomSnapshotTest.oomShell();
        OomSnapshotTest.cases().get("oom-empty").accept(shell);
        return shell;
    }

    private static Parent logs() {
        LogsPage page = new LogsPage(ZoneOffset.UTC, 1000, "logs", () -> {});
        page.show(List.of(
                new ActivityEntry(T, ActivitySeverity.INFO, "Started."),
                new ActivityEntry(T.plusSeconds(4), ActivitySeverity.WARNING, "Change priority: Access denied.")));
        return page;
    }

    /** What changes around a table as it takes the focus, and after a row takes it. */
    private record Seen(
            int changedByTheTable, boolean empty, boolean noRowFocused, boolean frameRing, boolean ringAfterARow) {}

    @ParameterizedTest
    @EnumSource(Stop.class)
    void aTableThatIsAStopDrawsARingWhileNoRowHasTheFocus(Stop stop) {
        for (Theme theme : Theme.values()) {
            // Two scenes, without and with the focus: in one scene a second snapshot can show the
            // cached image of the page area of the shell, which the pulses of a real window renew.
            WritableImage before =
                    SnapshotHarness.inspect(theme, 1086, 655, stop.content, TableFocusRingSceneTest::shot);
            Seen seen = SnapshotHarness.inspect(theme, 1086, 655, stop.content, root -> {
                TableView<?> table = (TableView<?>) root.lookup(stop.table);
                table.pseudoClassStateChanged(FOCUS_VISIBLE, true);
                WritableImage after = shot(root);
                int changed = changed(before, after, table.localToScene(table.getLayoutBounds()));
                boolean noRow = table.getPseudoClassStates().contains(PseudoClass.getPseudoClass("no-focused-row"));
                boolean frameRing = visibleBorder(table);
                boolean ringAfterARow = false;
                if (!table.getItems().isEmpty()) {
                    table.getFocusModel().focus(0);
                    root.applyCss();
                    ringAfterARow = visibleBorder(table);
                }
                return new Seen(changed, table.getItems().isEmpty(), noRow, frameRing, ringAfterARow);
            });

            // An empty table draws its ring on the placeholder (table.css, KB-1); a table with rows and no
            // focused row draws it on its frame. Never both: an empty table shows one ring, not two.
            assertThat(seen.noRowFocused())
                    .as("%s %s: :no-focused-row only on a table that has rows", stop, theme)
                    .isEqualTo(!seen.empty());
            assertThat(seen.frameRing())
                    .as("%s %s: the frame has the ring only when the placeholder does not", stop, theme)
                    .isEqualTo(!seen.empty());
            // A ring of 2 px around a table of some hundred px is well over a thousand pixels.
            assertThat(seen.changedByTheTable())
                    .as("KB-2 %s %s: the focus changes the pixels around the table", stop, theme)
                    .isGreaterThan(1000);
            assertThat(seen.ringAfterARow())
                    .as("%s %s: with a row focused the row has the ring, the frame none", stop, theme)
                    .isFalse();
        }
    }

    private static boolean visibleBorder(TableView<?> table) {
        return table.getBorder() != null
                && table.getBorder().getStrokes().stream()
                        .anyMatch(stroke -> stroke.getTopStroke() instanceof javafx.scene.paint.Color color
                                && color.getOpacity() > 0);
    }

    /** The focused state as an image for a person to look at. */
    @ParameterizedTest
    @EnumSource(Stop.class)
    void rendersTheFocusedTable(Stop stop) {
        for (Theme theme : Theme.values()) {
            Path png = SnapshotHarness.render(
                    "kb2-focus-" + stop.name().toLowerCase(java.util.Locale.ROOT),
                    theme,
                    1086,
                    655,
                    stop.content,
                    root -> root.lookup(stop.table).pseudoClassStateChanged(FOCUS_VISIBLE, true));
            assertThat(png).isRegularFile();
        }
    }

    /** As the harness renders: several passes, then the scene. */
    private static WritableImage shot(Parent root) {
        for (int pass = 0; pass < 3; pass++) {
            root.applyCss();
            root.layout();
        }
        return root.getScene().snapshot(null);
    }

    /** The pixels that differ within 8 px around the bounds. */
    private static int changed(WritableImage before, WritableImage after, Bounds bounds) {
        PixelReader a = before.getPixelReader();
        PixelReader b = after.getPixelReader();
        int changed = 0;
        int x0 = Math.max(0, (int) bounds.getMinX() - 8);
        int y0 = Math.max(0, (int) bounds.getMinY() - 8);
        int x1 = Math.min((int) before.getWidth(), (int) Math.ceil(bounds.getMaxX()) + 8);
        int y1 = Math.min((int) before.getHeight(), (int) Math.ceil(bounds.getMaxY()) + 8);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (a.getArgb(x, y) != b.getArgb(x, y)) {
                    changed++;
                }
            }
        }
        return changed;
    }
}
