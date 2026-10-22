package prism.srm.domain;

/** How much attention an entry of the activity log asks for. */
public enum ActivitySeverity {
    /** Something happened as intended. */
    INFO,
    /** Something did not happen as intended, and Prism carries on. */
    WARNING,
    /** Something failed. */
    ERROR
}
