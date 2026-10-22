package prism.srm.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import prism.srm.domain.MetricHistory;

class AutoScaleTest {

    private static final double KIB = 1024;
    private static final double MIB = 1024 * KIB;
    private static final double GIB = 1024 * MIB;

    @Test
    void anIdleSystemGetsTheSmallestScale() {
        assertThat(AutoScale.maximumFor(0)).isEqualTo(100 * KIB);
        assertThat(AutoScale.maximumFor(3 * KIB)).isEqualTo(100 * KIB);
        assertThat(AutoScale.maximumFor(100 * KIB)).isEqualTo(100 * KIB);
    }

    @Test
    void theScaleIsTheSmallestStepThatHoldsTheLargestSample() {
        assertThat(AutoScale.maximumFor(100 * KIB + 1)).isEqualTo(200 * KIB);
        assertThat(AutoScale.maximumFor(200 * KIB)).isEqualTo(200 * KIB);
        assertThat(AutoScale.maximumFor(201 * KIB)).isEqualTo(500 * KIB);
        assertThat(AutoScale.maximumFor(501 * KIB)).isEqualTo(MIB);
        assertThat(AutoScale.maximumFor(1.5 * MIB)).isEqualTo(2 * MIB);
        assertThat(AutoScale.maximumFor(180 * MIB)).isEqualTo(200 * MIB);
        assertThat(AutoScale.maximumFor(501 * MIB)).isEqualTo(GIB);
        assertThat(AutoScale.maximumFor(3 * GIB)).isEqualTo(5 * GIB);
    }

    @Test
    void aLargerSampleNeverGetsASmallerScale() {
        double previous = 0;
        for (double sample = 1; sample < 64 * GIB; sample *= 1.37) {
            double scale = AutoScale.maximumFor(sample);

            assertThat(scale).isGreaterThanOrEqualTo(sample).isGreaterThanOrEqualTo(previous);
            previous = scale;
        }
    }

    @Test
    void aSampleBeyondTheLadderIsItsOwnScaleAndStaysFinite() {
        assertThat(AutoScale.maximumFor(Double.MAX_VALUE)).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void theLargestSampleIsTakenOverAllHistoriesAndSkippedSamplesDoNotCount() {
        MetricHistory read = MetricHistory.of(10, Double.NaN, 30);
        MetricHistory write = MetricHistory.of(Double.NaN, 70, 20);

        assertThat(AutoScale.largestSample(read, write)).isEqualTo(70);
        assertThat(AutoScale.largestSample(read)).isEqualTo(30);
    }

    /** Zero is a measurement; only a skipped sample is none. */
    @Test
    void aHistoryHasASampleUnlessAllOfItWasSkipped() {
        assertThat(AutoScale.hasSample(MetricHistory.of(0, 0))).isTrue();
        assertThat(AutoScale.hasSample(MetricHistory.of(Double.NaN, 12))).isTrue();
        assertThat(AutoScale.hasSample(MetricHistory.of(Double.NaN), MetricHistory.of(0)))
                .isTrue();
        assertThat(AutoScale.hasSample(MetricHistory.of(Double.NaN, Double.NaN)))
                .isFalse();
        assertThat(AutoScale.hasSample(MetricHistory.empty())).isFalse();
        assertThat(AutoScale.hasSample()).isFalse();
    }

    @Test
    void withoutASampleTheLargestIsZero() {
        assertThat(AutoScale.largestSample()).isZero();
        assertThat(AutoScale.largestSample(MetricHistory.empty())).isZero();
        assertThat(AutoScale.largestSample(MetricHistory.of(Double.NaN, Double.NaN)))
                .isZero();
    }

    @Test
    void graphDataRejectsAScaleThatIsNotANumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> GraphData.single(MetricHistory.empty(), Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GraphData.single(MetricHistory.empty(), Double.POSITIVE_INFINITY));
    }

    @Test
    void graphDataWithTheSameSeriesAndScaleIsEqual() {
        assertThat(GraphData.single(MetricHistory.of(1, 2), 100))
                .isEqualTo(GraphData.single(MetricHistory.of(1, 2), 100));
        assertThat(GraphData.single(MetricHistory.of(1, 2), 100))
                .isNotEqualTo(GraphData.single(MetricHistory.of(1, 3), 100));
    }
}
