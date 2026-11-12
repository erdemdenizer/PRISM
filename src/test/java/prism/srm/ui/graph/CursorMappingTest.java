package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import prism.srm.domain.MetricHistory;

/**
 * K5: the arithmetic of the cursor that reads a past value. A horizontal position maps to the age of
 * the nearest sample, 0 at the right edge, by the same geometry the lines are drawn with; an age maps
 * to an index of a history only where the history reaches back that far.
 */
class CursorMappingTest {

    /** 590 px for 60 samples: 10 px a step, the newest at 590, the oldest at 0. */
    private final PlotGeometry geometry = new PlotGeometry(590, 100, 60, 100);

    @Test
    void theRightEdgeIsTheNewestSampleAndTheLeftEdgeTheOldestTheGraphSpans() {
        assertThat(geometry.ageAt(590)).isZero();
        assertThat(geometry.ageAt(0)).isEqualTo(59);
    }

    @Test
    void aPositionGoesToTheNearestSample() {
        // The newest is at 590, the next at 580: 585.1 is nearer to 590, 584.9 to 580.
        assertThat(geometry.ageAt(585.1)).isZero();
        assertThat(geometry.ageAt(584.9)).isEqualTo(1);
        assertThat(geometry.ageAt(355)).isIn(23, 24);
        assertThat(geometry.ageAt(360)).isEqualTo(23);
    }

    @Test
    void aPositionOutsideThePlotCountsAsItsNearerEdge() {
        assertThat(geometry.ageAt(-40)).isEqualTo(59);
        assertThat(geometry.ageAt(900)).isZero();
    }

    @Test
    void everyAgeMapsBackToItself() {
        for (int age = 0; age < 60; age++) {
            assertThat(geometry.ageAt(geometry.xOfAge(age))).as("age %d", age).isEqualTo(age);
        }
    }

    /** The cursor line stands where the line of the sample is drawn, whatever the size of the history. */
    @Test
    void theCursorOfAnAgeStandsOnTheSampleOfThatAge() {
        for (int size : new int[] {1, 10, 60}) {
            for (int age = 0; age < size; age++) {
                int index = size - 1 - age;
                assertThat(geometry.xOfAge(age)).isEqualTo(geometry.x(index, size));
            }
        }
    }

    @Test
    void aGraphOfOneSlotHasOnlyTheNewestSample() {
        PlotGeometry one = new PlotGeometry(200, 100, 1, 100);

        assertThat(one.ageAt(0)).isZero();
        assertThat(one.ageAt(200)).isZero();
        assertThat(one.xOfAge(0)).isEqualTo(200);
    }

    @Test
    void aPlotWithNoWidthHasOnlyTheNewestSample() {
        assertThat(new PlotGeometry(0, 100, 60, 100).ageAt(0)).isZero();
    }

    @Test
    void anAgeIsAnIndexOnlyWhereTheHistoryReachesBackThatFar() {
        // Ten samples of a graph that spans sixty, as in the first ten seconds.
        assertThat(PlotGeometry.indexOfAge(0, 10)).isEqualTo(9);
        assertThat(PlotGeometry.indexOfAge(9, 10)).isZero();
        assertThat(PlotGeometry.indexOfAge(10, 10)).isEqualTo(-1);
        assertThat(PlotGeometry.indexOfAge(59, 10)).isEqualTo(-1);
        assertThat(PlotGeometry.indexOfAge(0, 0)).isEqualTo(-1);
        assertThat(PlotGeometry.indexOfAge(-1, 10)).isEqualTo(-1);
    }

    @Test
    void aReadingHasTheSamplesOfBothSeriesAtItsAge() {
        GraphData data = new GraphData(MetricHistory.of(1, 2, 3, 4), MetricHistory.of(10, 20, 30, 40), 100);

        assertThat(data.readingAt(0)).isEqualTo(new GraphData.Reading(true, 4, 40));
        assertThat(data.readingAt(3)).isEqualTo(new GraphData.Reading(true, 1, 10));
    }

    @Test
    void aReadingBeforeTheHistoryBeganHasNoSamples() {
        GraphData data = GraphData.single(MetricHistory.of(1, 2, 3), 100);

        GraphData.Reading reading = data.readingAt(3);

        assertThat(reading.inHistory()).isFalse();
        assertThat(reading.primary()).isNaN();
        assertThat(reading.secondary()).isNaN();
    }

    @Test
    void aSkippedSampleAndAMissingSecondSeriesReadAsNotANumber() {
        GraphData skipped = new GraphData(MetricHistory.of(1, Double.NaN, 3), MetricHistory.of(7, 8, Double.NaN), 100);
        GraphData single = GraphData.single(MetricHistory.of(1, 2, 3), 100);

        assertThat(skipped.readingAt(1).inHistory()).isTrue();
        assertThat(skipped.readingAt(1).primary()).isNaN();
        assertThat(skipped.readingAt(0).secondary()).isNaN();
        assertThat(single.readingAt(0).secondary()).isNaN();
    }
}
