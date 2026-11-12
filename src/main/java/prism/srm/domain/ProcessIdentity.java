package prism.srm.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What makes a process the same process: its PID together with its start time. The operating
 * system reuses PIDs, so a PID alone may name a different process a moment later; the pair does
 * not repeat. Everything that compares two readings of a process compares identities.
 *
 * @param startTime when the process started, exactly as the system reports it. Two identities are
 *     equal only if these are equal to the last unit, so the value is never rounded or adjusted.
 */
public record ProcessIdentity(long pid, Instant startTime) {

    public ProcessIdentity {
        Objects.requireNonNull(startTime, "startTime");
        if (pid < 0) {
            throw new IllegalArgumentException("pid must not be negative: " + pid);
        }
    }
}
