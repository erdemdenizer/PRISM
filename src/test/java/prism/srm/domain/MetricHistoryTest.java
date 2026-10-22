package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MetricHistoryTest {

    @Test
    void holdsSamplesOldestFirst() {
        MetricHistory history = MetricHistory.of(1.5, 2.5, 3.5);

        assertThat(history.size()).isEqualTo(3);
        assertThat(history.sample(0)).hasValue(1.5);
        assertThat(history.sample(2)).hasValue(3.5);
    }

    @Test
    void aSkippedSampleIsEmptyAndKeepsItsPosition() {
        MetricHistory history = MetricHistory.of(1.0, Double.NaN, 3.0);

        assertThat(history.size()).isEqualTo(3);
        assertThat(history.sample(1)).isEmpty();
        assertThat(history.toArray()).containsExactly(1.0, Double.NaN, 3.0);
    }

    @Test
    void isNotAffectedByChangesToTheArrayItWasCreatedFrom() {
        double[] samples = {1.0, 2.0};
        MetricHistory history = MetricHistory.of(samples);

        samples[0] = 99.0;

        assertThat(history.sample(0)).hasValue(1.0);
    }

    @Test
    void isNotAffectedByChangesToTheArrayItHandsOut() {
        MetricHistory history = MetricHistory.of(1.0, 2.0);

        history.toArray()[0] = 99.0;

        assertThat(history.sample(0)).hasValue(1.0);
    }

    @Test
    void historiesWithTheSameSamplesAreEqual() {
        assertThat(MetricHistory.of(1.0, Double.NaN))
                .isEqualTo(MetricHistory.of(1.0, Double.NaN))
                .hasSameHashCodeAs(MetricHistory.of(1.0, Double.NaN))
                .isNotEqualTo(MetricHistory.of(1.0, 2.0))
                .isNotEqualTo(MetricHistory.of(1.0));
        assertThat(MetricHistory.of()).isEqualTo(MetricHistory.empty());
    }

    @Test
    void rejectsInfiniteSamples() {
        assertThatIllegalArgumentException().isThrownBy(() -> MetricHistory.of(1.0, Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> MetricHistory.of(Double.NEGATIVE_INFINITY));
    }
}
