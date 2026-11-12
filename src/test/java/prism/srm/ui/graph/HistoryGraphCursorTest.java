package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.image.PixelReader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MetricHistory;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * K5: the cursor of a history graph, read from a real scene. The pointer and the keys move it; it is
 * drawn on an overlay of its own, so that moving it never draws the graph again, and a new sample
 * draws the graph once and the overlay only while there is a cursor.
 *
 * <p>The graph is 300 x 103 with a 1 px padding: its Canvas is 298 x 101, the plot 297 px wide for
 * 60 samples, so a step is 297 / 59 px.
 */
@Tag("ui-snapshot")
class HistoryGraphCursorTest {

    private static final int WIDTH = 300;
    private static final int HEIGHT = 103;
    private static final int CAPACITY = 60;

    @Test
    void movingThePointerDrawsTheOverlayOnlyAndNeverTheGraph() {
        long[] counts = inGraph(full(), graph -> {
            long graphBefore = graph.redrawCount();
            long overlayBefore = graph.overlayRedrawCount();
            for (int x = 2; x < WIDTH - 2; x += 3) {
                Event.fireEvent(graph, mouse(MouseEvent.MOUSE_MOVED, x));
            }
            return new long[] {graph.redrawCount() - graphBefore, graph.overlayRedrawCount() - overlayBefore};
        });

        assertThat(counts[0]).as("draws of the graph").isZero();
        // One draw for each sample the pointer came to, not for each event: about 60 of 99 events.
        assertThat(counts[1]).as("draws of the overlay").isBetween(50L, 60L);
    }

    @Test
    void aNewSampleDrawsTheGraphOnceAndTheOverlayOnlyWhileThereIsACursor() {
        long[] counts = inGraph(full(), graph -> {
            long graphBefore = graph.redrawCount();
            long overlayBefore = graph.overlayRedrawCount();
            graph.show(GraphData.single(shifted(1), 100));
            long withoutCursor = graph.overlayRedrawCount() - overlayBefore;
            graph.moveCursor(KeyCode.LEFT, false);
            long overlayWithCursor = graph.overlayRedrawCount();
            graph.show(GraphData.single(shifted(2), 100));
            return new long[] {
                graph.redrawCount() - graphBefore, withoutCursor, graph.overlayRedrawCount() - overlayWithCursor
            };
        });

        assertThat(counts[0]).as("draws of the graph for two samples").isEqualTo(2);
        assertThat(counts[1]).as("draws of the overlay without a cursor").isZero();
        assertThat(counts[2]).as("draws of the overlay with a cursor").isEqualTo(1);
    }

    @Test
    void thePointerReadsTheNearestSampleAndLeavingTheGraphEndsTheReading() {
        List<Integer> ages = inGraph(full(), graph -> {
            List<Integer> seen = new ArrayList<>();
            // The padding is 1 px and the lines are half a pixel in: the newest sample is at x 298.5.
            for (double x : new double[] {298.5, 298.5 - 297.0 / 59, 1.5, 0, WIDTH - 1}) {
                Event.fireEvent(graph, mouse(MouseEvent.MOUSE_MOVED, x));
                seen.add(graph.cursorAgeProperty().get());
            }
            Event.fireEvent(graph, mouse(MouseEvent.MOUSE_EXITED, 10));
            seen.add(graph.cursorAgeProperty().get());
            return seen;
        });

        assertThat(ages).containsExactly(0, 1, 59, 59, 0, HistoryGraph.NO_CURSOR);
    }

    /** The pointer may stand where a short history has not reached yet; the keys stay on samples. */
    @Test
    void theKeysMoveTheCursorWithinTheSamplesThereAre() {
        List<Integer> ages = inGraph(GraphData.single(MetricHistory.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 100), graph -> {
            List<Integer> seen = new ArrayList<>();
            graph.moveCursor(KeyCode.LEFT, false);
            seen.add(graph.cursorAgeProperty().get());
            graph.moveCursor(KeyCode.LEFT, false);
            seen.add(graph.cursorAgeProperty().get());
            graph.moveCursor(KeyCode.LEFT, true);
            seen.add(graph.cursorAgeProperty().get());
            graph.moveCursor(KeyCode.RIGHT, false);
            seen.add(graph.cursorAgeProperty().get());
            graph.moveCursor(KeyCode.END, false);
            seen.add(graph.cursorAgeProperty().get());
            graph.moveCursor(KeyCode.RIGHT, false);
            seen.add(graph.cursorAgeProperty().get());
            graph.moveCursor(KeyCode.HOME, false);
            seen.add(graph.cursorAgeProperty().get());
            Event.fireEvent(graph, mouse(MouseEvent.MOUSE_MOVED, 2));
            seen.add(graph.cursorAgeProperty().get());
            return seen;
        });

        assertThat(ages).containsExactly(0, 1, 9, 8, 0, 0, 9, 59);
    }

    @Test
    void escapeEndsTheReadingAndAKeyThatDoesNothingIsLeftToOthers() {
        List<Boolean> handled = inGraph(
                full(),
                graph -> List.of(
                        graph.moveCursor(KeyCode.ESCAPE, false),
                        graph.moveCursor(KeyCode.RIGHT, false),
                        graph.moveCursor(KeyCode.ESCAPE, false),
                        graph.cursorAgeProperty().get() == HistoryGraph.NO_CURSOR,
                        graph.moveCursor(KeyCode.ENTER, false),
                        graph.moveCursor(KeyCode.TAB, false)));

        assertThat(handled).containsExactly(false, true, true, true, false, false);
    }

    @Test
    void aGraphWithoutSamplesHasNoCursorAndLosesItWhenItsSamplesGo() {
        List<Object> seen = inGraph(GraphData.EMPTY, graph -> {
            List<Object> facts = new ArrayList<>();
            facts.add(graph.moveCursor(KeyCode.LEFT, false));
            Event.fireEvent(graph, mouse(MouseEvent.MOUSE_MOVED, 100));
            facts.add(graph.cursorAgeProperty().get());
            graph.show(full());
            graph.moveCursor(KeyCode.LEFT, false);
            facts.add(graph.cursorAgeProperty().get());
            graph.show(GraphData.EMPTY);
            facts.add(graph.cursorAgeProperty().get());
            return facts;
        });

        assertThat(seen).containsExactly(false, HistoryGraph.NO_CURSOR, 0, HistoryGraph.NO_CURSOR);
    }

    /**
     * The cursor is a line in the cursor color down the plot at its sample, and the line of the
     * series there gets a dot; before the cursor, and after it went, the column is the graph's.
     */
    @Test
    void theCursorIsDrawnOverTheGraphAndGoesWithoutATrace() {
        List<Color> colors = inGraph(full(), graph -> {
            List<Color> seen = new ArrayList<>();
            // Age 20: x = 297 * 39 / 59 = 196.3 in the Canvas, the line in pixel column 196, at 197 in the graph.
            int column = 1 + 196;
            seen.add(pixel(graph, column, 10));
            for (int step = 0; step < 21; step++) {
                graph.moveCursor(KeyCode.LEFT, false);
            }
            seen.add(pixel(graph, column, 10));
            graph.clearCursor();
            seen.add(pixel(graph, column, 10));
            return seen;
        });

        Color muted = Color.web("#4a5262");
        assertThat(isClose(colors.get(0), muted))
                .as("before: %s", colors.get(0))
                .isFalse();
        assertThat(isClose(colors.get(1), muted))
                .as("with the cursor: %s", colors.get(1))
                .isTrue();
        assertThat(colors.get(2)).isEqualTo(colors.get(0));
    }

    private static Color pixel(HistoryGraph graph, int x, int y) {
        PixelReader reader = graph.getParent().snapshot(null, null).getPixelReader();
        return reader.getColor(x, y);
    }

    private static boolean isClose(Color actual, Color expected) {
        double close = 3.0 / 255;
        return Math.abs(actual.getRed() - expected.getRed()) <= close
                && Math.abs(actual.getGreen() - expected.getGreen()) <= close
                && Math.abs(actual.getBlue() - expected.getBlue()) <= close;
    }

    private static <T> T inGraph(GraphData data, java.util.function.Function<HistoryGraph, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                WIDTH,
                HEIGHT,
                () -> {
                    HistoryGraph graph = new HistoryGraph(CAPACITY);
                    graph.show(data);
                    return new StackPane(graph);
                },
                root -> {
                    HistoryGraph graph =
                            (HistoryGraph) root.getChildrenUnmodifiable().getFirst();
                    return scenario.apply(graph);
                });
    }

    /** Sixty samples low in the plot, so the top rows show the grid and the cursor only. */
    private static GraphData full() {
        return GraphData.single(shifted(0), 100);
    }

    private static MetricHistory shifted(int by) {
        double[] samples = new double[CAPACITY];
        Arrays.setAll(samples, index -> 20 + (index + by) % 7);
        return MetricHistory.of(samples);
    }

    private static MouseEvent mouse(EventType<MouseEvent> type, double x) {
        return new MouseEvent(
                type,
                x,
                50,
                x,
                50,
                MouseButton.NONE,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }
}
