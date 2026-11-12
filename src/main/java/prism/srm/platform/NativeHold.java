package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.EffectiveLimits;

/**
 * A job the limiter holds for a process.
 *
 * @param inForce what the job is known to enforce; empty when that is not known (an uncertain
 *     assignment, a change that could not be read back): then no second limiter may start
 */
public record NativeHold(State state, Optional<EffectiveLimits> inForce) {

    /** Whether the process is known to be in the job. */
    public enum State {
        /** The process is in the job. */
        ASSIGNED,
        /** The assignment was attempted and its outcome could not be told. */
        UNCERTAIN
    }

    public NativeHold {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(inForce, "inForce");
    }

    /** Whether what the job enforces is known. */
    public boolean settled() {
        return state == State.ASSIGNED && inForce.isPresent();
    }

    /** Whether a CPU cap may be in force: true unless the job is known to have none. */
    public boolean mayCapCpu() {
        return !settled() || inForce.get().cpuRate().isPresent();
    }
}
