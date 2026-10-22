package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActivityRecordsTest {

    private static final Instant TIME = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void anEntryKeepsWhatItWasGiven() {
        ActivityEntry entry = new ActivityEntry(TIME, ActivitySeverity.WARNING, "Process monitoring was not started.");

        assertThat(entry.time()).isEqualTo(TIME);
        assertThat(entry.severity()).isEqualTo(ActivitySeverity.WARNING);
        assertThat(entry.message()).isEqualTo("Process monitoring was not started.");
    }

    @Test
    void anEntryWithoutAMessageIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivityEntry(TIME, ActivitySeverity.INFO, ""));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivityEntry(TIME, ActivitySeverity.INFO, "  "));
    }

    @Test
    void everyPartOfAnEntryIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ActivityEntry(null, ActivitySeverity.INFO, "Started."));
        assertThatNullPointerException().isThrownBy(() -> new ActivityEntry(TIME, null, "Started."));
        assertThatNullPointerException().isThrownBy(() -> new ActivityEntry(TIME, ActivitySeverity.INFO, null));
    }
}
