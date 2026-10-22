package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.MetricHistory;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Renders the graph and reads pixels back. This is what proves that the colors the graph draws
 * with are the tokens of the stylesheet, in both themes: they reach the Canvas through styleable
 * properties, not through anything the compiler checks.
 *
 * <p>Sizes are chosen so that the lines asserted on lie exactly on a pixel row: a 300 x 103 graph
 * has a 298 x 101 Canvas inside its 1 px frame, so the scale spans 100 rows and a value of 30 is
 * row 70 of the Canvas.
 */
@Tag("ui-snapshot")
class HistoryGraphRenderingTest {

    private static final int WIDTH = 300;
    private static final int HEIGHT = 103;
    private static final int CAPACITY = 60;
    private static final int FRAME = 1;
    /** A column that is on no vertical grid line (those are at 74, 149 and 223 of the Canvas). */
    private static final int COLUMN = FRAME + 11;

    private static final Offset<Double> CLOSE = Offset.offset(2.0 / 255);

    private record Pixels(Color primaryLine, Color secondaryLine, Color grid, Color fill, Color background) {}

    @Test
    void drawsWithTheTokenColorsOfTheLightTheme() {
        Pixels pixels = render(Theme.LIGHT);

        assertSameColor(pixels.primaryLine(), Color.web("#0e7c86"));
        assertSameColor(pixels.secondaryLine(), Color.web("#5b636b"));
        assertSameColor(pixels.grid(), Color.web("#d5d9dd"));
        assertSameColor(pixels.background(), Color.web("#ffffff"));
    }

    @Test
    void drawsWithTheTokenColorsOfTheDarkTheme() {
        Pixels pixels = render(Theme.DARK);

        assertSameColor(pixels.primaryLine(), Color.web("#3fb3bd"));
        assertSameColor(pixels.secondaryLine(), Color.web("#a0a7ae"));
        assertSameColor(pixels.grid(), Color.web("#363b41"));
        assertSameColor(pixels.background(), Color.web("#1e2124"));
    }

    /** The fill is the line color at low opacity: neither the background nor the line itself. */
    @Test
    void fillsBelowThePrimarySeriesOnlyAndFaintly() {
        Pixels pixels = render(Theme.LIGHT);

        assertThat(pixels.fill()).isNotEqualTo(pixels.background()).isNotEqualTo(pixels.primaryLine());
        // 12 % of the accent over white stays close to white.
        assertThat(pixels.fill().getBrightness()).isGreaterThan(0.85);
    }

    @Test
    void aHistoryThatIsNotFullIsDrawnAgainstTheRightEdge() {
        boolean[] drawn = SnapshotHarness.inspect(
                Theme.LIGHT,
                WIDTH,
                HEIGHT,
                () -> {
                    HistoryGraph graph = new HistoryGraph(CAPACITY);
                    // Six samples of sixty: the last tenth of the width.
                    graph.show(GraphData.single(MetricHistory.of(30, 30, 30, 30, 30, 30), 100));
                    return new StackPane(graph);
                },
                root -> {
                    PixelReader reader = root.snapshot(null, null).getPixelReader();
                    Color line = Color.web("#0e7c86");
                    return new boolean[] {
                        isClose(reader.getColor(COLUMN, FRAME + 70), line),
                        isClose(reader.getColor(WIDTH - FRAME - 6, FRAME + 70), line)
                    };
                });

        assertThat(drawn[0]).as("the line reaches the left part of the graph").isFalse();
        assertThat(drawn[1]).as("the line is at the right edge").isTrue();
    }

    @Test
    void withoutAScaleOnlyTheGridIsDrawn() {
        Color where = SnapshotHarness.inspect(
                Theme.LIGHT,
                WIDTH,
                HEIGHT,
                () -> {
                    HistoryGraph graph = new HistoryGraph(CAPACITY);
                    graph.show(GraphData.single(flat(30), 0));
                    return new StackPane(graph);
                },
                root -> root.snapshot(null, null).getPixelReader().getColor(COLUMN, FRAME + 70));

        assertSameColor(where, Color.web("#ffffff"));
    }

    private static Pixels render(Theme theme) {
        return SnapshotHarness.inspect(
                theme,
                WIDTH,
                HEIGHT,
                () -> {
                    HistoryGraph graph = new HistoryGraph(CAPACITY);
                    graph.show(new GraphData(flat(30), flat(80), 100));
                    return new StackPane(graph);
                },
                root -> {
                    PixelReader reader = root.snapshot(null, null).getPixelReader();
                    return new Pixels(
                            reader.getColor(COLUMN, FRAME + 70),
                            reader.getColor(COLUMN, FRAME + 20),
                            // The horizontal grid line at a quarter of the height, above both fills.
                            reader.getColor(COLUMN, FRAME + 25),
                            reader.getColor(COLUMN, FRAME + 85),
                            reader.getColor(COLUMN, FRAME + 10));
                });
    }

    private static MetricHistory flat(double value) {
        double[] samples = new double[CAPACITY];
        Arrays.fill(samples, value);
        return MetricHistory.of(samples);
    }

    private static void assertSameColor(Color actual, Color expected) {
        assertThat(actual.getRed()).as("red of %s", actual).isCloseTo(expected.getRed(), CLOSE);
        assertThat(actual.getGreen()).as("green of %s", actual).isCloseTo(expected.getGreen(), CLOSE);
        assertThat(actual.getBlue()).as("blue of %s", actual).isCloseTo(expected.getBlue(), CLOSE);
    }

    private static boolean isClose(Color actual, Color expected) {
        return Math.abs(actual.getRed() - expected.getRed()) <= CLOSE.value
                && Math.abs(actual.getGreen() - expected.getGreen()) <= CLOSE.value
                && Math.abs(actual.getBlue() - expected.getBlue()) <= CLOSE.value;
    }
}
