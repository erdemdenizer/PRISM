package prism.srm.domain;

/**
 * Where a CPU limit stands, as the Limits page shows it. No state claims more than was measured:
 * a limit that cannot be met is never shown as enforced (LIM-4).
 */
public enum LimitStatus {

    /** No limit is set, or it is disabled. */
    INACTIVE,

    /** The limit is applied and the process uses less than it: Prism does not suspend it (LIM-8). */
    ACTIVE_NOT_THROTTLING,

    /** Prism is still finding the share that holds the process near its limit. */
    ADJUSTING,

    /** The measured use is at or below the limit while Prism throttles the process. */
    ACTIVE_THROTTLING,

    /**
     * The measured use stays above the limit: the limit is lower than Prism can hold the process to
     * (the floor of the duty cycle), or the load varies faster than the limiter follows.
     */
    ACTIVE_ABOVE_TARGET,

    /** Another holder (the user, OOM Guard) keeps the process suspended; the limiter waits. */
    HELD_BY_OTHER,

    /** Another program has stopped the process; the limiter neither holds it nor continues it, and waits. */
    PAUSED_EXTERNAL_STOP,

    /**
     * The system enforces the limit (a Job Object): Prism does nothing per cycle (milestone 10). Set
     * only once the limits were read back as asked.
     */
    ACTIVE_ENFORCED,

    /** The limit cannot be applied to this process on this system; the report says why. */
    UNAVAILABLE,

    /** The limit ended on a failure; the report says which. The process was left running. */
    FAILED
}
