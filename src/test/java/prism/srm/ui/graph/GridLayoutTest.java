package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GridLayoutTest {

    /** The width of the page area: the default window, and the smallest one. */
    private static final double DEFAULT_WIDTH = 916;

    private static final double MINIMUM_WIDTH = 520;

    /** The smallest width of a cell in these tests: room for a short label and a value. */
    private static final double MIN_CELL = 52;

    @ParameterizedTest(name = "{0} cells in {1} px: {2} columns, {3} rows")
    @CsvSource({
        // Few cells share one row, however wide it is.
        "1, 916, 1, 1",
        "4, 916, 4, 1",
        "4, 520, 4, 1",
        // 16 fit one row in the default window and need two in the smallest.
        "16, 916, 16, 1",
        "16, 520, 8, 2",
        // 64: four rows of 16, or eight rows of 8 in the smallest window.
        "64, 916, 16, 4",
        "64, 520, 8, 8",
        // A width that fits 13 gives two even rows of 8, not 13 and 3.
        "16, 724, 8, 2",
        // More cells than any machine of today has still get a place each.
        "256, 916, 16, 16",
        // Too narrow for one cell: one column, and the cells are as narrow as the grid.
        "3, 10, 1, 3",
        "3, 0, 1, 3"
    })
    void withTheFewestRowsItUsesAsManyColumnsAsFitAndNoMoreThanItsRowsNeed(
            int cells, double width, int columns, int rows) {
        GridLayout layout = GridLayout.of(cells, width, MIN_CELL);

        assertThat(layout.columns()).isEqualTo(columns);
        assertThat(layout.rows()).isEqualTo(rows);
        assertThat(layout.columns() * layout.rows()).isGreaterThanOrEqualTo(cells);
    }

    /** While the number of cells is not known the grid keeps the room of one row. */
    @Test
    void withoutCellsThereIsOneEmptyRow() {
        GridLayout layout = GridLayout.of(0, DEFAULT_WIDTH, MIN_CELL);

        assertThat(layout.columns()).isEqualTo(1);
        assertThat(layout.rows()).isEqualTo(1);
        assertThat(layout.minHeight()).isEqualTo(64);
    }

    @Test
    void theLeastHeightIsThatOfItsRowsAndTheGapsBetweenThem() {
        assertThat(GridLayout.of(4, DEFAULT_WIDTH, MIN_CELL).minHeight()).isEqualTo(64);
        assertThat(GridLayout.of(16, MINIMUM_WIDTH, MIN_CELL).minHeight()).isEqualTo(2 * 64 + 4);
        assertThat(GridLayout.of(64, DEFAULT_WIDTH, MIN_CELL).minHeight()).isEqualTo(4 * 64 + 3 * 4);
    }

    /** With no width to go by, the widest arrangement is the answer: one row. */
    @Test
    void anUnboundedWidthGivesOneRow() {
        GridLayout layout = GridLayout.of(64, Double.MAX_VALUE, MIN_CELL);

        assertThat(layout.columns()).isEqualTo(64);
        assertThat(layout.rows()).isEqualTo(1);
    }

    @Test
    void theCellsFillTheWidthAndTheHeightExactlyWithGapsBetweenThem() {
        GridLayout layout = GridLayout.of(64, DEFAULT_WIDTH, MIN_CELL);
        double height = 300;

        GridLayout.Cell first = layout.cell(0, DEFAULT_WIDTH, height);
        GridLayout.Cell second = layout.cell(1, DEFAULT_WIDTH, height);
        GridLayout.Cell lastOfFirstRow = layout.cell(15, DEFAULT_WIDTH, height);
        GridLayout.Cell firstOfSecondRow = layout.cell(16, DEFAULT_WIDTH, height);
        GridLayout.Cell last = layout.cell(63, DEFAULT_WIDTH, height);

        assertThat(first.x()).isZero();
        assertThat(first.y()).isZero();
        assertThat(second.x() - (first.x() + first.width())).isEqualTo(4);
        assertThat(second.y()).isZero();
        assertThat(lastOfFirstRow.x() + lastOfFirstRow.width()).isEqualTo(916);
        assertThat(firstOfSecondRow.x()).isZero();
        assertThat(firstOfSecondRow.y() - (first.y() + first.height())).isEqualTo(4);
        assertThat(last.x() + last.width()).isEqualTo(916);
        assertThat(last.y() + last.height()).isEqualTo(300);
    }

    /** 916 px do not divide evenly by 16 columns: the cells take up the rest a pixel at a time. */
    @Test
    void cellsOfOneRowDifferByOnePixelAtMostAndNeverOverlap() {
        GridLayout layout = GridLayout.of(64, DEFAULT_WIDTH, MIN_CELL);

        int narrowest = Integer.MAX_VALUE;
        int widest = 0;
        for (int index = 0; index < 16; index++) {
            GridLayout.Cell cell = layout.cell(index, DEFAULT_WIDTH, 300);
            narrowest = Math.min(narrowest, cell.width());
            widest = Math.max(widest, cell.width());
            if (index > 0) {
                GridLayout.Cell before = layout.cell(index - 1, DEFAULT_WIDTH, 300);
                assertThat(cell.x()).isEqualTo(before.x() + before.width() + 4);
            }
        }

        assertThat(widest - narrowest).isLessThanOrEqualTo(1);
        assertThat(narrowest).isGreaterThanOrEqualTo(52);
    }

    /** At the least height every cell is at least as high as a readable history. */
    @Test
    void atTheLeastHeightEveryCellHasTheSmallestHeight() {
        GridLayout layout = GridLayout.of(64, MINIMUM_WIDTH, MIN_CELL);

        for (int index = 0; index < 64; index++) {
            assertThat(layout.cell(index, MINIMUM_WIDTH, layout.minHeight()).height())
                    .isEqualTo(64);
        }
    }

    /** A cell needs room for its text: wider labels mean fewer columns. */
    @Test
    void aWiderSmallestCellGivesFewerColumns() {
        assertThat(GridLayout.of(64, DEFAULT_WIDTH, 52).columns()).isEqualTo(16);
        assertThat(GridLayout.of(64, DEFAULT_WIDTH, 60).columns()).isEqualTo(13);
        assertThat(GridLayout.of(64, DEFAULT_WIDTH, 60).rows()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0} cells in {1} x {2} px: {3} columns, {4} rows")
    @CsvSource({
        // One row of 16 would be strips of 53 x 232: two rows of 8 are about as wide as high.
        "16, 916, 232, 8, 2",
        // Two rows of 8 stay two rows with more height: 111 x 130 is no strip, and a third row
        // would leave the last one uneven for nothing.
        "16, 916, 264, 8, 2",
        // The strips stay when a second row would be lower than 64 px.
        "16, 916, 120, 16, 1",
        // On a large window too: 16 strips of 104 x 420 become two rows of 213 x 208.
        "16, 1736, 420, 8, 2",
        // Four cells in one row are about square already and stay.
        "4, 916, 232, 4, 1",
        // 64 in four rows of 53 x 72 are no strips yet.
        "64, 916, 300, 16, 4",
        // With the height for it the fifth row is taken.
        "64, 916, 400, 13, 5",
        // At the least height nothing is added: the fewest rows are what fits.
        "16, 520, 132, 8, 2",
        "64, 916, 268, 16, 4",
        // One cell stays one cell however tall the grid is.
        "1, 100, 900, 1, 1"
    })
    void givenMoreHeightItAddsRowsWhileTheCellsAreTallStripsAndEveryRowKeepsTheSmallestHeight(
            int cells, double width, double height, int columns, int rows) {
        GridLayout layout = GridLayout.of(cells, width, height, MIN_CELL);

        assertThat(layout.columns()).isEqualTo(columns);
        assertThat(layout.rows()).isEqualTo(rows);
    }

    /** The height the grid asks for must not depend on the height it is given. */
    @Test
    void theLeastHeightIsThatOfTheFewestRowsWhateverHeightIsGiven() {
        GridLayout fewest = GridLayout.of(16, DEFAULT_WIDTH, MIN_CELL);
        GridLayout given = GridLayout.of(16, DEFAULT_WIDTH, 232, MIN_CELL);

        assertThat(fewest.rows()).isEqualTo(1);
        assertThat(given.rows()).isEqualTo(2);
        assertThat(fewest.minHeight()).isEqualTo(64);
        // Every row of the arrangement that is drawn has at least the smallest height.
        for (int index = 0; index < 16; index++) {
            assertThat(given.cell(index, DEFAULT_WIDTH, 232).height()).isGreaterThanOrEqualTo(64);
        }
    }

    @Test
    void aNegativeNumberOfCellsIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> GridLayout.of(-1, DEFAULT_WIDTH, MIN_CELL));
    }
}
