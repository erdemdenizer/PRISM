package prism.srm.domain;

/**
 * Whether Prism can put back a priority it lowered (OOM-11), on this system (ADR-0015 §3). Even where
 * it can, a restore is an attempt: the process may have exited or someone may have changed it.
 */
public enum Restorability {
    /** Prism can raise a priority back without privilege, and knows the level it had. */
    RESTORABLE,
    /** Raising a priority back needs privilege, or the level it had is not known. */
    NOT_REVERSIBLE
}
