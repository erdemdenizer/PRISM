package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableView;
import javafx.scene.text.Text;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The layout of the OOM Guard page at the scene sizes of real windows (fix brief K8), measured in
 * the laid-out scene (UI audit LO-10 to LO-13, LO-24, LO-26): whole rows in the managed table, a
 * table of recent actions as high as its rows, times drawn whole, the values of the state band on one
 * baseline, and the safe-default chip beside the facts.
 */
@Tag("ui-snapshot")
class OomPageLayoutSceneTest {

    static Stream<Arguments> cases() {
        List<Arguments> all = new ArrayList<>();
        for (int[] size : OomSnapshotTest.REAL_SCENES) {
            for (String name : List.of("oom-off", "oom-normal", "oom-emergency-acted", "oom-journal-failed")) {
                all.add(Arguments.of(name, size));
            }
        }
        return all.stream();
    }

    /** What the page measures in one state at one size. */
    private record Measured(
            double managedHeight,
            double managedRow,
            boolean managedSideways,
            boolean recentVertical,
            int recentRows,
            List<String> timesDrawn,
            List<String> times,
            List<Double> baselines,
            double chipGap,
            boolean chipShown) {}

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    void thePageShowsWholeRowsAndUncutValues(String name, int[] size) {
        Consumer<Shell> state = OomSnapshotTest.cases().get(name);
        Measured seen = SnapshotHarness.inspect(
                Theme.DARK,
                size[0],
                size[1],
                () -> {
                    Shell shell = OomSnapshotTest.oomShell();
                    state.accept(shell);
                    return shell;
                },
                OomPageLayoutSceneTest::measure);

        SoftAssertions.assertSoftly(soft -> {
            double frame = seen.managedHeight() - 32 - 2 - (seen.managedSideways() ? 12 : 0);
            soft.assertThat(frame % seen.managedRow())
                    .as("LO-12: the managed table is a header, whole rows and its frame (%s)", frame)
                    .isCloseTo(0, within(0.01));
            soft.assertThat(frame / seen.managedRow())
                    .as("LO-12: at least two rows in view")
                    .isGreaterThanOrEqualTo(2);
            if (seen.recentRows() > 0 && seen.recentRows() <= 6) {
                soft.assertThat(seen.recentVertical())
                        .as("LO-11: no vertical scroll bar beside the recent actions")
                        .isFalse();
            }
            soft.assertThat(seen.timesDrawn())
                    .as("LO-10: every time drawn whole")
                    .isEqualTo(seen.times());
            soft.assertThat(seen.baselines().get(1))
                    .as("LO-13: State and Available on one baseline")
                    .isCloseTo(seen.baselines().get(0), within(0.75));
            soft.assertThat(seen.baselines().get(2))
                    .as("LO-13: Since and Available on one baseline")
                    .isCloseTo(seen.baselines().get(1), within(0.75));
            if (seen.chipShown()) {
                soft.assertThat(seen.chipGap())
                        .as("LO-24: the chip 24 px after the facts")
                        .isCloseTo(24, within(0.5));
            }
        });
    }

    private static Measured measure(Parent root) {
        Node page = ((Shell) root).pageArea().getContent();
        TableView<?> managed = (TableView<?>) page.lookup(".oom-table");
        TableView<?> recent = (TableView<?>) page.lookup(".oom-recent");
        double row = managed.getFixedCellSize() > 0 ? managed.getFixedCellSize() : 30;
        List<String> drawn = new ArrayList<>();
        List<String> whole = new ArrayList<>();
        for (Node cell : recent.lookupAll(".table-cell")) {
            if (cell instanceof javafx.scene.control.TableCell<?, ?> tableCell
                    && tableCell.getTableColumn() != null
                    && "Time".equals(tableCell.getTableColumn().getText())
                    && tableCell.getText() != null
                    && tableCell.isVisible()) {
                whole.add(tableCell.getText());
                Node text = tableCell.lookup(".text");
                drawn.add(text instanceof Text shown ? shown.getText() : "");
            }
        }
        // State, Available, Since, in this order in the band.
        List<Node> values = new ArrayList<>(page.lookup(".oom-state").lookupAll(".fact-value"));
        List<Double> baselines = new ArrayList<>();
        for (Node each : values.subList(0, 3)) {
            // The baseline of the text as drawn: Label.getBaselineOffset() takes no account of a
            // label aligned to its bottom.
            Text text = (Text) ((Label) each).lookup(".text");
            baselines.add(text.localToScene(0, text.getLayoutBounds().getMinY() + text.getBaselineOffset())
                    .getY());
        }
        Node since = values.get(2).getParent();
        Node chip = page.lookup(".oom-safe-default");
        Bounds sinceBounds = since.localToScene(since.getLayoutBounds());
        Bounds chipBounds = chip.localToScene(chip.getLayoutBounds());
        return new Measured(
                managed.getHeight(),
                row,
                scrollBar(managed, Orientation.HORIZONTAL),
                scrollBar(recent, Orientation.VERTICAL),
                recent.getItems().size(),
                drawn,
                whole,
                baselines,
                chipBounds.getMinX() - sinceBounds.getMaxX(),
                chip.getParent().isVisible());
    }

    private static boolean scrollBar(TableView<?> table, Orientation orientation) {
        return table.lookupAll(".scroll-bar").stream()
                .anyMatch(node ->
                        node instanceof ScrollBar bar && bar.getOrientation() == orientation && bar.isVisible());
    }
}
