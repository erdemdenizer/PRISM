package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;

/**
 * What a suspend came to.
 *
 * @param suspension present on success; present on a failure too when the failure could not be
 *     undone completely, holding what remains
 * @param stoppedElsewhere the process was not suspended because it was stopped already, by another
 *     program, by a tracer or by the user outside Prism (Linux, macOS). Nothing was done and nothing
 *     is held. Told apart from the other refusals, so that the CPU limiter waits for the process
 *     to run again instead of ending the limit.
 */
public record SuspendResult(
        ControlOutcome outcome, Optional<Suspension> suspension, boolean stoppedElsewhere, boolean transientFailure) {

    public SuspendResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(suspension, "suspension");
        if (outcome.succeeded() && suspension.isEmpty()) {
            throw new IllegalArgumentException("A successful suspend holds its suspension");
        }
        if (stoppedElsewhere && (outcome.succeeded() || suspension.isPresent())) {
            throw new IllegalArgumentException("A process stopped elsewhere was not suspended, and nothing is held");
        }
        if (transientFailure && (outcome.succeeded() || suspension.isPresent() || stoppedElsewhere)) {
            throw new IllegalArgumentException(
                    "Only a failure that was rolled back completely, holding nothing, may be transient");
        }
    }

    public SuspendResult(ControlOutcome outcome, Optional<Suspension> suspension, boolean stoppedElsewhere) {
        this(outcome, suspension, stoppedElsewhere, false);
    }

    public SuspendResult(ControlOutcome outcome, Optional<Suspension> suspension) {
        this(outcome, suspension, false, false);
    }

    /**
     * The suspend failed on one thread of the process, whose identity was confirmed and which was
     * held open, and everything it had done was undone: nothing is held. The same request a moment
     * later may succeed (a thread that was being created or was ending). Never for a failure of the
     * process as a whole (it may not be opened, its identity does not match, it cannot be listed),
     * nor for one that left anything held.
     */
    public static SuspendResult transientFailure(ControlOutcome outcome) {
        return new SuspendResult(outcome, Optional.empty(), false, true);
    }

    /** The process is stopped already, by someone else: nothing was done. */
    public static SuspendResult stoppedElsewhere(ControlOutcome outcome) {
        return new SuspendResult(outcome, Optional.empty(), true);
    }

    public static SuspendResult suspended(ControlOutcome outcome, Suspension suspension) {
        return new SuspendResult(outcome, Optional.of(suspension));
    }

    public static SuspendResult failed(ControlOutcome outcome) {
        return new SuspendResult(outcome, Optional.empty());
    }
}
