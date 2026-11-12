package prism.srm.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One automatic action of OOM Guard (OOM-12): when, in which state, on which process, what, and what
 * came of it.
 *
 * @param state the memory pressure state when it was taken
 * @param cause what justified it: system pressure, or the process's own memory limit (LIM-9)
 * @param text what came of it, in one plain sentence, for the page and the log
 */
public record OomActionRecord(
        Instant time,
        OomState state,
        Claim cause,
        ProcessIdentity identity,
        String name,
        Kind kind,
        OperationResult result,
        String text) {

    /** What was done. */
    public enum Kind {
        LOWER_PRIORITY,
        SUSPEND,
        TERMINATE,
        RESUME,
        RESTORE_PRIORITY
    }

    public OomActionRecord {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("A record says what came of the action");
        }
    }
}
