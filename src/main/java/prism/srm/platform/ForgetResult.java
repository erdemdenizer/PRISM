package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;

/**
 * What became of letting go of the job of a process that exited (plan §8.6). The job outlives the
 * process while the processes it started run, and Prism's limits go on applying to them; the job is
 * let go only once nothing it enforces is left.
 *
 * @param survivors how many processes the job still held, as its accounting said; -1 when unknown
 * @param inForce what the job still holds, for a residue; empty when unknown
 */
public record ForgetResult(Outcome result, ControlOutcome outcome, int survivors, Optional<EffectiveLimits> inForce) {

    /** What is left after the attempt. */
    public enum Outcome {
        /** The limits were cleared and read back as none, or there were none; the job is let go. */
        RELEASED,
        /** Nothing was left in the job; it is let go. */
        NOTHING_TO_RELEASE,
        /** The job still carries limits Prism could not verify as cleared; it is kept and retried. */
        RESIDUE,
        /** Nothing could be told about the job; it is kept and retried. */
        UNRESOLVED,
        /** The limiter held no job for the process. */
        NOT_HELD,
        /** The process was not shown to have exited; nothing was done. */
        STILL_RUNS
    }

    public ForgetResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(inForce, "inForce");
    }

    /** Whether the job is let go and its record may be removed. */
    public boolean released() {
        return result == Outcome.RELEASED || result == Outcome.NOTHING_TO_RELEASE || result == Outcome.NOT_HELD;
    }
}
