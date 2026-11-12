package prism.srm.ui.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;

/** UI-6: the activity log, newest first, filtered. Needs no JavaFX toolkit. */
class LogsViewModelTest {

    private static final Instant T = Instant.parse("2026-10-08T07:05:09Z");

    private final LogsViewModel model = new LogsViewModel(ZoneOffset.ofHours(3), 5);

    private static ActivityEntry entry(int seconds, ActivitySeverity severity, String message) {
        return new ActivityEntry(T.plusSeconds(seconds), severity, message);
    }

    @Test
    void anEmptyLogSaysSo() {
        model.show(List.of());

        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo(LogsViewModel.NO_ENTRIES);
        assertThat(model.countProperty().get()).isEqualTo("0 entries");
    }

    @Test
    void theNewestEntryComesFirst() {
        model.show(List.of(
                entry(0, ActivitySeverity.INFO, "Started."),
                entry(1, ActivitySeverity.WARNING, "Suspend: refused."),
                entry(2, ActivitySeverity.ERROR, "Force kill: failed.")));

        assertThat(model.rows())
                .extracting(ActivityEntry::message)
                .containsExactly("Force kill: failed.", "Suspend: refused.", "Started.");
        assertThat(model.countProperty().get()).isEqualTo("3 entries");
        assertThat(model.time(model.rows().get(2))).isEqualTo("10:05:09");
    }

    @Test
    void theFilterLooksInTheMessageAndTheSeverityIgnoringCase() {
        model.show(List.of(
                entry(0, ActivitySeverity.INFO, "Started."),
                entry(1, ActivitySeverity.WARNING, "Suspend: refused."),
                entry(2, ActivitySeverity.ERROR, "Force kill: failed.")));

        model.filter("SUSPEND");
        assertThat(model.rows()).extracting(ActivityEntry::message).containsExactly("Suspend: refused.");
        assertThat(model.countProperty().get()).isEqualTo("1 of 3 entries");

        model.filter("error");
        assertThat(model.rows()).extracting(ActivityEntry::message).containsExactly("Force kill: failed.");

        model.filter("nothing like it");
        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo(LogsViewModel.NO_MATCH);

        model.filter("");
        assertThat(model.rows()).hasSize(3);
    }

    /** A filter compares character by character: a Turkish default locale changes nothing. */
    @Test
    void theFilterDependsOnNoLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            model.show(List.of(entry(0, ActivitySeverity.INFO, "PID 4 IS PROTECTED")));
            model.filter("is protected");
            assertThat(model.rows()).hasSize(1);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void theSeveritiesHaveTheirNames() {
        assertThat(LogsViewModel.severity(ActivitySeverity.INFO)).isEqualTo("Info");
        assertThat(LogsViewModel.severity(ActivitySeverity.WARNING)).isEqualTo("Warning");
        assertThat(LogsViewModel.severity(ActivitySeverity.ERROR)).isEqualTo("Error");
    }

    /** UI-8 loading: before the log has been handed over, the page does not say that nothing was logged. */
    @Test
    void untilTheLogArrivesThePageSaysItIsReading() {
        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).isEqualTo(LogsViewModel.READING);
        assertThat(model.countProperty().get()).isEmpty();

        model.filter("anything");
        assertThat(model.whenEmptyProperty().get()).isEqualTo(LogsViewModel.READING);
        assertThat(model.countProperty().get()).isEmpty();

        model.show(List.of());
        assertThat(model.whenEmptyProperty().get()).isEqualTo(LogsViewModel.NO_ENTRIES);
        assertThat(model.countProperty().get()).isEqualTo("0 of 0 entries");
    }

    /** The log is bounded: once it is full, the page does not let its count pass for the whole session. */
    @Test
    void aFullLogSaysTheEarlierEntriesAreInTheFile() {
        List<ActivityEntry> four = List.of(
                entry(0, ActivitySeverity.INFO, "a"),
                entry(1, ActivitySeverity.INFO, "b"),
                entry(2, ActivitySeverity.INFO, "c"),
                entry(3, ActivitySeverity.INFO, "d"));
        model.show(four);
        assertThat(model.limitNoteProperty().get()).isEmpty();

        List<ActivityEntry> five = new java.util.ArrayList<>(four);
        five.add(entry(4, ActivitySeverity.INFO, "e"));
        model.show(five);
        assertThat(model.limitNoteProperty().get())
                .isEqualTo("Only the newest 5 entries are kept here; the log file has the earlier ones.");

        model.filter("e");
        assertThat(model.limitNoteProperty().get()).isNotEmpty();
    }

    /** UI-8 error: a log folder that did not open is said, and cleared by the next request that succeeds. */
    @Test
    void aFolderThatDidNotOpenIsSaidUntilTheNextRequest() {
        assertThat(model.folderProblemProperty().get()).isEmpty();

        model.folderOutcome(Optional.of("The system could not open this folder: logs"));
        assertThat(model.folderProblemProperty().get())
                .as("the line names the button it answers (LS-13)")
                .isEqualTo("Open log folder: The system could not open this folder: logs");

        model.folderOutcome(Optional.empty());
        assertThat(model.folderProblemProperty().get()).isEmpty();
    }

    @Test
    void aLogWithoutRoomIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new LogsViewModel(ZoneOffset.UTC, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
