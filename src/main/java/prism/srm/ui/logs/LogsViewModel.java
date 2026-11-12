package prism.srm.ui.logs;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;

/**
 * What the Logs page shows: the entries of the activity log, newest first, those the filter lets
 * through. No JavaFX node; it is given the whole log, which is bounded, each time it changes.
 *
 * <p>The filter looks for its text in the message and in the name of the severity, ignoring case
 * character by character, without a locale.
 *
 * <p>States (UI-8): reading until the log has been handed over once; then the entries, or that
 * nothing has been logged, or that nothing matches the filter. The log keeps a bounded number of
 * entries; once it holds that many, the page says the earlier ones are only in the log file. A
 * request to open the log folder that did not succeed is said under the filter until the next one.
 * The log itself cannot be unavailable: it is kept in memory by Prism.
 */
final class LogsViewModel {

    static final String READING = "Reading the activity log\u2026";
    static final String NO_ENTRIES = "Nothing has been logged yet.";
    static final String NO_MATCH = "No entry matches the filter.";

    /** What the detail pane says while no entry is selected. */
    static final String NO_SELECTION = "Select an entry to read it in full.";

    /** Put before why the log folder did not open, so the line names the button it answers. */
    static final String FOLDER_PROBLEM_PREFIX = "Open log folder: ";

    private final DateTimeFormatter time;
    private final DateTimeFormatter dateTime;
    private final int capacity;
    private final ObservableList<ActivityEntry> rows = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper count = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper whenEmpty = new ReadOnlyStringWrapper(READING);
    private final ReadOnlyStringWrapper limitNote = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper folderProblem = new ReadOnlyStringWrapper("");
    private List<ActivityEntry> all = List.of();
    private boolean read;
    private String filter = "";

    /** @param capacity the number of entries the activity log keeps, at least one */
    LogsViewModel(ZoneId zone, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(zone);
        this.dateTime =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zone);
        this.capacity = capacity;
    }

    ObservableList<ActivityEntry> rows() {
        return rows;
    }

    ReadOnlyStringProperty countProperty() {
        return count.getReadOnlyProperty();
    }

    ReadOnlyStringProperty whenEmptyProperty() {
        return whenEmpty.getReadOnlyProperty();
    }

    /** Empty while the log holds fewer entries than it can keep; otherwise that the earlier ones are in the file. */
    ReadOnlyStringProperty limitNoteProperty() {
        return limitNote.getReadOnlyProperty();
    }

    /** Empty unless the last request to open the log folder did not succeed; then why. */
    ReadOnlyStringProperty folderProblemProperty() {
        return folderProblem.getReadOnlyProperty();
    }

    /** @param entries the whole activity log, oldest first, as the log returns it */
    void show(List<ActivityEntry> entries) {
        all = List.copyOf(Objects.requireNonNull(entries, "entries"));
        read = true;
        refresh();
    }

    /** @param problem empty when the system was asked to open the log folder, otherwise why not */
    void folderOutcome(Optional<String> problem) {
        folderProblem.set(Objects.requireNonNull(problem, "problem")
                .map(why -> FOLDER_PROBLEM_PREFIX + why)
                .orElse(""));
    }

    void filter(String typed) {
        filter = typed == null ? "" : typed.strip();
        refresh();
    }

    String time(ActivityEntry entry) {
        return time.format(entry.time());
    }

    /** When the entry was recorded, with its date: a session can pass midnight, and a copied line leaves it. */
    String dateTime(ActivityEntry entry) {
        return dateTime.format(entry.time());
    }

    /**
     * The text Ctrl+C, the Copy button and the context menu copy for an entry: its date and time, its
     * severity and its whole message.
     */
    String copied(ActivityEntry entry) {
        return dateTime(entry) + " " + severity(entry.severity()) + " " + entry.message();
    }

    /** The widest text the count can take for this log, so that the filter beside it never moves. */
    String widestCount() {
        return capacity + " of " + capacity + " entries";
    }

    static String severity(ActivitySeverity severity) {
        return switch (severity) {
            case INFO -> "Info";
            case WARNING -> "Warning";
            case ERROR -> "Error";
        };
    }

    private void refresh() {
        List<ActivityEntry> shown = new ArrayList<>();
        for (int index = all.size() - 1; index >= 0; index--) {
            ActivityEntry entry = all.get(index);
            if (matches(entry)) {
                shown.add(entry);
            }
        }
        rows.setAll(shown);
        if (!read) {
            return;
        }
        String entries = all.size() + (all.size() == 1 ? " entry" : " entries");
        count.set(filter.isEmpty() ? entries : shown.size() + " of " + entries);
        whenEmpty.set(all.isEmpty() ? NO_ENTRIES : NO_MATCH);
        limitNote.set(
                all.size() >= capacity
                        ? "Only the newest " + capacity + " entries are kept here; the log file has the earlier ones."
                        : "");
    }

    private boolean matches(ActivityEntry entry) {
        return filter.isEmpty()
                || containsIgnoringCase(entry.message(), filter)
                || containsIgnoringCase(severity(entry.severity()), filter);
    }

    /** Case is compared character by character, so that no locale can change a letter. */
    static boolean containsIgnoringCase(String text, String part) {
        int last = text.length() - part.length();
        for (int start = 0; start <= last; start++) {
            if (text.regionMatches(true, start, part, 0, part.length())) {
                return true;
            }
        }
        return false;
    }
}
