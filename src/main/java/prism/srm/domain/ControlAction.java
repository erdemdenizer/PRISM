package prism.srm.domain;

/** The actions on a process the user can take (CTL-1). */
public enum ControlAction {

    /** Ask the process to close; it may decline. */
    END_TASK,

    /** Terminate the process at once. */
    FORCE_KILL,

    /** Stop the process from running, for the user. */
    SUSPEND,

    /** Release the user's suspension. */
    RESUME,

    /** Set one of the five priority levels. */
    CHANGE_PRIORITY
}
