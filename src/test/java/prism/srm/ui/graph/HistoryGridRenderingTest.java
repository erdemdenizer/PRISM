package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
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
 * Renders the grid and reads pixels back. This is what proves that every cell is drawn where the
 * layout puts it, and that the colors the grid draws with are the tokens of the stylesheet in both
 * themes: they reach the Canvas through styleable properties, not through anything the compiler
 * checks.
 *
 * <p>Sizes are chosen so that what is asserted on lies exactly on a pixel row. A 220 x 118 grid
 * of four cells has cells 52 px wide at 0, 56, 112 and 168, with 4 px between them. The plot of a
 * cell begins 16 px down and spans 100 rows of scale, so a value of 30 is row 86 and the line at
 * half the scale is row 66.
 */
@Tag("ui-snapshot")
class HistoryGridRenderingTest {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 118;
    private static final int CAPACITY = 60;
    private static final int SECOND_CELL = 56;

    private static final Offset<Double> CLOSE = Offset.offset(2.0 / 255);

    private record Pixels(Color frame, Color surface, Color line, Color fill, Color halfLine, Color between) {}

    @Test
    void drawsWithTheTokenColorsOfTheLightTheme() {
        Pixels pixels = render(Theme.LIGHT);

        assertSameColor(pixels.frame(), Color.web("#d5d9dd"));
        assertSameColor(pixels.surface(), Color.web("#ffffff"));
        assertSameColor(pixels.line(), Color.web("#0e7c86"));
        assertSameColor(pixels.halfLine(), Color.web("#d5d9dd"));
        // Between two cells the grid draws nothing: the background behind it shows.
        assertSameColor(pixels.between(), Color.web("#f6f7f8"));
    }

    @Test
    void drawsWithTheTokenColorsOfTheDarkTheme() {
        Pixels pixels = render(Theme.DARK);

        assertSameColor(pixels.frame(), Color.web("#363b41"));
        assertSameColor(pixels.surface(), Color.web("#1e2124"));
        assertSameColor(pixels.line(), Color.web("#3fb3bd"));
        assertSameColor(pixels.halfLine(), Color.web("#363b41"));
        assertSameColor(pixels.between(), Color.web("#25292d"));
    }

    /** The fill is the line color at low opacity: neither the surface nor the line itself. */
    @Test
    void fillsBelowTheLineFaintly() {
        Pixels pixels = render(Theme.LIGHT);

        assertThat(pixels.fill()).isNotEqualTo(pixels.surface()).isNotEqualTo(pixels.line());
        assertThat(pixels.fill().getBrightness()).isGreaterThan(0.85);
    }

    /** Label and value are text on the Canvas; without them the room above the plot is surface only. */
    @Test
    void writesTheLabelAtTheLeftAndTheValueAtTheRightOfACell() {
        boolean[] inked = SnapshotHarness.inspect(Theme.LIGHT, WIDTH, HEIGHT, () -> grid(cells(30)), root -> {
            PixelReader reader = root.snapshot(null, null).getPixelReader();
            Color surface = Color.web("#ffffff");
            return new boolean[] {
                anyOtherColor(reader, 4, 20, surface),
                anyOtherColor(reader, 30, 48, surface),
                // The third cell has a label and no value.
                anyOtherColor(reader, 112 + 4, 112 + 20, surface),
                anyOtherColor(reader, 112 + 30, 112 + 48, surface)
            };
        });

        assertThat(inked[0]).as("label of the first cell").isTrue();
        assertThat(inked[1]).as("value of the first cell").isTrue();
        assertThat(inked[2]).as("label of the third cell").isTrue();
        assertThat(inked[3]).as("value of the third cell").isFalse();
    }

    @Test
    void aHistoryThatIsNotFullIsDrawnAgainstTheRightEdgeOfItsCell() {
        boolean[] drawn = SnapshotHarness.inspect(Theme.LIGHT, WIDTH, HEIGHT, () -> grid(cells(30)), root -> {
            PixelReader reader = root.snapshot(null, null).getPixelReader();
            Color line = Color.web("#0e7c86");
            return new boolean[] {
                // The second cell holds six samples of sixty: the last tenth of its width.
                isClose(reader.getColor(SECOND_CELL + 10, 86), line),
                isClose(reader.getColor(SECOND_CELL + 52 - 4, 86), line)
            };
        });

        assertThat(drawn[0]).as("the line reaches the left part of the cell").isFalse();
        assertThat(drawn[1]).as("the line is at the right edge of the cell").isTrue();
    }

    @Test
    void withoutCellsNothingIsDrawn() {
        Color where = SnapshotHarness.inspect(
                Theme.LIGHT,
                WIDTH,
                HEIGHT,
                () -> grid(GridData.EMPTY),
                root -> root.snapshot(null, null).getPixelReader().getColor(10, 30));

        assertSameColor(where, Color.web("#f6f7f8"));
    }

    /** The cells are there before a scale is: frames and text, no history. */
    @Test
    void withoutAScaleTheCellsAreDrawnWithoutTheirHistories() {
        Color[] seen = SnapshotHarness.inspect(
                Theme.LIGHT, WIDTH, HEIGHT, () -> grid(new GridData(cells(30).cells(), 0, "30 %")), root -> {
                    PixelReader reader = root.snapshot(null, null).getPixelReader();
                    return new Color[] {reader.getColor(0, 60), reader.getColor(10, 86), reader.getColor(10, 100)};
                });

        assertSameColor(seen[0], Color.web("#d5d9dd"));
        assertSameColor(seen[1], Color.web("#ffffff"));
        assertSameColor(seen[2], Color.web("#ffffff"));
    }

    /**
     * The grid needs more height when it is narrower, and says so to the layout. A cell is as wide
     * as its label and the widest value need, here about 47 px.
     */
    @Test
    void theHeightTheGridNeedsFollowsItsWidth() {
        double[] heights = SnapshotHarness.inspect(Theme.LIGHT, WIDTH, HEIGHT, () -> grid(cells(30)), root -> {
            HistoryGrid grid = (HistoryGrid) root.getChildrenUnmodifiable().getFirst();
            return new double[] {grid.minHeight(220), grid.minHeight(110), grid.minHeight(60), grid.prefHeight(110)};
        });

        assertThat(heights[0]).as("four cells in one row").isEqualTo(64);
        assertThat(heights[1]).as("two rows of two").isEqualTo(2 * 64 + 4);
        assertThat(heights[2]).as("four rows of one").isEqualTo(4 * 64 + 3 * 4);
        assertThat(heights[3]).isEqualTo(heights[1]);
    }

    private static Pixels render(Theme theme) {
        return SnapshotHarness.inspect(theme, WIDTH, HEIGHT, () -> grid(cells(30)), root -> {
            PixelReader reader = root.snapshot(null, null).getPixelReader();
            return new Pixels(
                    reader.getColor(0, 60),
                    reader.getColor(10, 40),
                    reader.getColor(10, 86),
                    reader.getColor(10, 100),
                    reader.getColor(10, 66),
                    reader.getColor(54, 60));
        });
    }

    /** The grid on a background of its own, so that what the grid leaves undrawn has a known color. */
    private static StackPane grid(GridData data) {
        HistoryGrid grid = new HistoryGrid(CAPACITY);
        grid.show(data);
        StackPane page = new StackPane(grid);
        page.setStyle("-fx-background-color: -srm-surface-alt;");
        return page;
    }

    /** Four cells: a full history, a short one, one without a value, and one more. */
    private static GridData cells(double value) {
        return new GridData(
                List.of(
                        new GridData.Cell("0", "30 %", flat(value, CAPACITY)),
                        new GridData.Cell("1", "30 %", flat(value, 6)),
                        new GridData.Cell("2", "", flat(value, CAPACITY)),
                        new GridData.Cell("3", "30 %", flat(value, CAPACITY))),
                100,
                "30 %");
    }

    private static MetricHistory flat(double value, int samples) {
        double[] values = new double[samples];
        Arrays.fill(values, value);
        return MetricHistory.of(values);
    }

    /** Whether the text room of a cell, between two columns, holds anything but the surface. */
    private static boolean anyOtherColor(PixelReader reader, int fromX, int toX, Color surface) {
        for (int y = 3; y < 15; y++) {
            for (int x = fromX; x < toX; x++) {
                if (!isClose(reader.getColor(x, y), surface)) {
                    return true;
                }
            }
        }
        return false;
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
