package prism.srm.application;

import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;

/**
 * What Prism did and noticed during this session: start-up and shutdown, the detected system,
 * and later the actions on processes and their results.
 *
 * <p>Every event is kept in memory for the application to show, and written through SLF4J so that
 * it reaches the log file as well. The memory is bounded: when it is full, the oldest entry makes
 * room for the newest. Any thread may record and read.
 */
public final class ActivityLog {

    /** Entries kept when no other capacity is asked for. */
    public static final int DEFAULT_CAPACITY = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(ActivityLog.class);

    private final int capacity;
    private final InstantSource clock;
    private final ArrayDeque<ActivityEntry> entries;

    /**
     * @param capacity the number of entries kept, at least one
     * @param clock gives each entry its time
     */
    public ActivityLog(int capacity, InstantSource clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entries = new ArrayDeque<>(Math.min(capacity, 64));
    }

    /** Records an event. */
    public void record(ActivitySeverity severity, String message) {
        record(severity, message, null);
    }

    /**
     * Records an event that has a cause. The cause goes to the log file with its stack trace; the
     * entry kept in memory holds the message only.
     */
    public void record(ActivitySeverity severity, String message, Throwable cause) {
        ActivityEntry entry = new ActivityEntry(clock.instant(), severity, message);
        synchronized (entries) {
            if (entries.size() == capacity) {
                entries.removeFirst();
            }
            entries.addLast(entry);
        }
        switch (severity) {
            case INFO -> LOG.info(message, cause);
            case WARNING -> LOG.warn(message, cause);
            case ERROR -> LOG.error(message, cause);
        }
    }

    /** Returns the entries kept, oldest first. The list does not change afterwards. */
    public List<ActivityEntry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }
}
