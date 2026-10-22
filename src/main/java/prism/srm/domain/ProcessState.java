package prism.srm.domain;

/**
 * The state of a process as the operating system reports it. Not every system has one for a
 * process as a whole: on Windows only threads have states.
 */
public enum ProcessState {

    /** Being created. */
    NEW,

    /** Running or ready to run. */
    RUNNING,

    /** Sleeping until something wakes it. */
    SLEEPING,

    /** Blocked, waiting for a resource. */
    WAITING,

    /** Stopped by a signal or a debugger. */
    STOPPED,

    /** Suspended, where the system reports that state. */
    SUSPENDED,

    /** Terminated but not yet collected by its parent. */
    ZOMBIE,

    /** A state the system reports that has no counterpart here. */
    OTHER
}
