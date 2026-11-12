package prism.srm.domain;

/**
 * Which of the explanations of a limit's status applies, so that whoever shows it decides by this
 * and never by the words of {@link LimitReport#detail()}, which may change.
 */
public enum LimitNote {
    /** Nothing beyond the status. */
    NONE,
    /** The limits of the process are disabled; the limit is kept and not applied. */
    DISABLED,
    /** A limit is set and enabled but nothing applies it (it ended, or has not started). */
    NOT_AT_WORK,
    /** The first interval is being measured. */
    MEASURING,
    /** The processor time could not be read; the process runs in full until it can. */
    NOT_MEASURED,
    /** The limit is below the lowest share Prism can hold the process to. */
    FLOOR,
    /** The use changes faster than the limiter follows. */
    VARIABLE_LOAD,
    /** The limit ended because the suspension journal failed (ADR-0011). */
    JOURNAL,
    /** The native limit was not applied; the emulated one is offered and waits for the user. */
    FALLBACK_OFFERED,
    /** The emulated limit runs because the user chose it after the native one failed. */
    EMULATED_BY_CHOICE,
    /** What the system holds could not be read back, or is not what was asked: see the detail. */
    NATIVE_UNSETTLED,
    /** An earlier Prism session may still cap the process natively; no second limiter is started. */
    PRIOR_SESSION,
    /**
     * The process runs inside a scope a Prism session created (or whether it does could not be read); no second
     * limiter, native or emulated, is started on it. It may or may not hold a limit (milestone 12, scope-membership
     * rule).
     */
    IN_PRISM_SCOPE,
    /** The emulated limit could not release every thread; nothing else starts until it can. */
    RELEASE_INCOMPLETE,
    /** The resident memory went above the watched limit and OOM Guard applied the emergency action (LIM-9). */
    POLICY_ACTED,
    /** OOM Guard holds the process for its memory limit; the hold ends when the memory falls or the user ends it. */
    POLICY_HOLD_USER_ENDS
}
