package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;

/**
 * What became of a change of the limits of a job the limiter holds (plan revision 4, §6.2). Each
 * kind is set by a call of its own; the result says what the job is known to hold afterwards.
 *
 * @param observed what the job was read back as holding; empty for {@link Phase#UNVERIFIED}
 */
public record ChangeResult(Phase phase, ControlOutcome outcome, Optional<EffectiveLimits> observed) {

    /** What is known after the change. */
    public enum Phase {
        /** The job was read back holding exactly the limits asked for. */
        APPLIED,
        /** It was read back holding something else: some kind changed and another did not. */
        PARTIAL,
        /** Something was set but nothing could be read back: per kind, the old value or the new one. */
        UNVERIFIED,
        /** Nothing was set: the process exited, Prism is closing, or the job could not be read first. */
        NOT_DONE,
        /** The limiter holds no job for the process. */
        NO_HOLD
    }

    public ChangeResult {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(observed, "observed");
        if (outcome.succeeded() != (phase == Phase.APPLIED)) {
            throw new IllegalArgumentException("Only an applied change is a success: " + phase + " " + outcome);
        }
        if (phase == Phase.UNVERIFIED && observed.isPresent()) {
            throw new IllegalArgumentException("An unverified change was not read back");
        }
    }
}
