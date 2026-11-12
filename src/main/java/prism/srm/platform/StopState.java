package prism.srm.platform;

/**
 * Whether a process is stopped, as far as the system tells without anything being changed. Read
 * when the journal of an earlier session is recovered, to close the entries of processes that run.
 */
public enum StopState {
    /** Stopped by a signal (Linux state T, macOS SSTOP). */
    STOPPED,
    /** Stopped under a debugger or tracer (Linux state t, the macOS P_TRACED flag): never Prism's to resume. */
    TRACED,
    /** Not stopped. */
    RUNNING,
    /**
     * The system gives no reading that changes nothing (Windows keeps suspension per thread, and
     * reading a count means changing it), or the read failed.
     */
    UNKNOWN
}
