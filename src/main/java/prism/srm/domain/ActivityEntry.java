package prism.srm.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One event of the activity log: what Prism did or noticed, in words meant for the user.
 *
 * @param time when the event was recorded
 * @param message one plain sentence; never blank
 */
public record ActivityEntry(Instant time, ActivitySeverity severity, String message) {

    public ActivityEntry {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
