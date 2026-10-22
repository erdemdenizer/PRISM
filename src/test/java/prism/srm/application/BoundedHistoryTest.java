package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import prism.srm.domain.MetricHistory;

class BoundedHistoryTest {

    @Test
    void startsEmpty() {
        assertThat(new BoundedHistory(3).snapshot().size()).isZero();
    }

    @Test
    void keepsSamplesOldestFirstWhileFilling() {
        BoundedHistory history = new BoundedHistory(3);

        history.add(1);
        history.add(2);

        assertThat(history.snapshot()).isEqualTo(MetricHistory.of(1, 2));
    }

    @Test
    void neverExceedsItsCapacityAndDropsTheOldestSample() {
        BoundedHistory history = new BoundedHistory(3);

        history.add(1);
        history.add(2);
        history.add(3);
        assertThat(history.snapshot()).as("exactly full").isEqualTo(MetricHistory.of(1, 2, 3));

        history.add(4);
        assertThat(history.snapshot()).as("first overwrite").isEqualTo(MetricHistory.of(2, 3, 4));

        for (int sample = 5; sample <= 10; sample++) {
            history.add(sample);
            assertThat(history.snapshot()).isEqualTo(MetricHistory.of(sample - 2, sample - 1, sample));
        }
    }

    @Test
    void aSkippedSampleKeepsItsPlace() {
        BoundedHistory history = new BoundedHistory(3);

        history.add(1);
        history.addSkipped();
        history.add(3);
        history.add(4);

        MetricHistory snapshot = history.snapshot();
        assertThat(snapshot.size()).isEqualTo(3);
        assertThat(snapshot.sample(0)).isEmpty();
        assertThat(snapshot.sample(1)).hasValue(3);
        assertThat(snapshot.sample(2)).hasValue(4);
    }

    @Test
    void aSnapshotIsNotAffectedByLaterSamples() {
        BoundedHistory history = new BoundedHistory(2);
        history.add(1);
        MetricHistory before = history.snapshot();

        history.add(2);
        history.add(3);

        assertThat(before).isEqualTo(MetricHistory.of(1));
    }

    @Test
    void aCapacityOfOneKeepsOnlyTheNewestSample() {
        BoundedHistory history = new BoundedHistory(1);

        history.add(1);
        history.add(2);

        assertThat(history.snapshot()).isEqualTo(MetricHistory.of(2));
    }

    @Test
    void rejectsACapacityBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BoundedHistory(0));
    }
}
