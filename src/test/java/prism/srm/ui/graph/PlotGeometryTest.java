package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class PlotGeometryTest {

    private final PlotGeometry geometry = new PlotGeometry(590, 100, 60, 100);

    @Test
    void aFullHistorySpansTheWholeWidthOldestAtTheLeft() {
        assertThat(geometry.x(0, 60)).isEqualTo(0);
        assertThat(geometry.x(59, 60)).isEqualTo(590);
        assertThat(geometry.x(30, 60)).isCloseTo(300, within(1e-9));
    }

    /** Ten samples occupy the last ten of the sixty slots; they are not stretched over the width. */
    @Test
    void aPartialHistorySitsAgainstTheRightEdge() {
        assertThat(geometry.x(9, 10)).isEqualTo(590);
        assertThat(geometry.x(0, 10)).isCloseTo(500, within(1e-9));
    }

    @Test
    void aSampleKeepsItsStepFromOneCycleToTheNext() {
        double step = geometry.x(1, 60) - geometry.x(0, 60);

        // The newest sample of a 10-sample history is the second newest of an 11-sample one.
        assertThat(geometry.x(9, 10) - geometry.x(9, 11)).isCloseTo(step, within(1e-9));
    }

    @Test
    void aSingleSlotIsAtTheRightEdge() {
        assertThat(new PlotGeometry(200, 100, 1, 100).x(0, 1)).isEqualTo(200);
    }

    @Test
    void zeroIsAtTheBottomAndTheMaximumAtTheTop() {
        assertThat(geometry.y(0)).isEqualTo(100);
        assertThat(geometry.y(100)).isEqualTo(0);
        assertThat(geometry.y(25)).isEqualTo(75);
    }

    @Test
    void aValueOutsideTheScaleIsDrawnAtTheEdgeItExceeds() {
        assertThat(geometry.y(250)).isEqualTo(0);
        assertThat(geometry.y(-5)).isEqualTo(100);
    }

    @Test
    void withoutAScaleEverythingIsAtTheBottom() {
        assertThat(new PlotGeometry(590, 100, 60, 0).y(42)).isEqualTo(100);
        assertThat(new PlotGeometry(590, 100, 60, -1).y(42)).isEqualTo(100);
    }

    @Test
    void aCapacityBelowOneIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PlotGeometry(590, 100, 0, 100));
    }
}
