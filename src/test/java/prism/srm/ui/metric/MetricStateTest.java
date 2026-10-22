package prism.srm.ui.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricStateTest {

    @Test
    void aValueThatIsThereIsLiveHoweverFewCyclesRan() {
        assertThat(MetricState.of(true, 0)).isEqualTo(MetricState.LIVE);
        assertThat(MetricState.of(true, 1)).isEqualTo(MetricState.LIVE);
        assertThat(MetricState.of(true, 60)).isEqualTo(MetricState.LIVE);
    }

    /** A rate needs two readings: missing from the first cycle, it is only not there yet. */
    @Test
    void aValueMissingBeforeTheSecondCycleIsWaitedFor() {
        assertThat(MetricState.of(false, 0)).isEqualTo(MetricState.WAITING);
        assertThat(MetricState.of(false, 1)).isEqualTo(MetricState.WAITING);
    }

    @Test
    void aValueMissingFromTheSecondCycleOnIsUnavailable() {
        assertThat(MetricState.of(false, 2)).isEqualTo(MetricState.UNAVAILABLE);
        assertThat(MetricState.of(false, 60)).isEqualTo(MetricState.UNAVAILABLE);
    }

    /** What stands in for a value is the same text wherever a value can be missing. */
    @Test
    void eachStateHasTheTextThatStandsInForTheValue() {
        assertThat(MetricState.WAITING.placeholder()).isEqualTo("Reading…");
        assertThat(MetricState.LIVE.placeholder()).isEmpty();
        assertThat(MetricState.UNAVAILABLE.placeholder()).isEqualTo("Unavailable");
    }
}
