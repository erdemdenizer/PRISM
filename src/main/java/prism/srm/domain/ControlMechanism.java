package prism.srm.domain;

/**
 * What stands behind a capability (CAP-5), so that the user interface can choose its explanation
 * and its warning without knowing the operating system.
 */
public enum ControlMechanism {

    /** A request to close, posted to the windows of the process. The process may ignore it. */
    CLOSE_REQUEST,

    /** Forced termination through the JDK's process handle. */
    PROCESS_HANDLE_TERMINATE,

    /** Every thread suspended one by one. Not atomic: a thread created meanwhile may be missed. */
    PER_THREAD_SUSPENSION,

    /** The scheduling priority class of the process. */
    PRIORITY_CLASS,

    /** A termination signal (SIGTERM). The process may handle it, and may ignore it. */
    TERMINATION_SIGNAL,

    /** The kill signal (SIGKILL). The process can neither handle nor ignore it. */
    KILL_SIGNAL,

    /**
     * The stop and continue signals (SIGSTOP, SIGCONT): the whole process at once. Any program
     * allowed to signal it may let it run again, and one continue ends every stop.
     */
    STOP_SIGNAL,

    /**
     * The nice value, a preference the scheduler weighs. Without privileges it can be raised (a
     * lower priority) but not lowered again.
     */
    NICE_VALUE,

    /**
     * A duty cycle Prism runs itself: it suspends and resumes the process in short cycles, steered
     * by the processor time it measures (LIM-8). Approximate, only while Prism runs, and only where
     * the system lets Prism suspend the process.
     */
    DUTY_CYCLE,

    /**
     * A Job Object of the system: a hard CPU cap on the share of the whole machine and a limit on
     * the committed memory of each process in it, enforced by the kernel. The process stays in the
     * job until it exits, and the child processes it starts afterwards join it (ADR-0002).
     */
    JOB_OBJECT,

    /**
     * A transient scope of the systemd user manager on Linux (milestone 12, ADR-0016, Proposed): the process is moved, by a
     * pidfd, into a scope Prism asked for, whose cgroup v2 limits ({@code cpu.max}, {@code memory.high},
     * {@code memory.max}) the kernel enforces. The move is one-way: the process stays in that scope until it exits,
     * and the processes it starts afterwards share it. Not offered by any system in this version.
     */
    SYSTEMD_SCOPE,

    /**
     * Prism watches the resident memory of the process and, when it goes above the limit, applies the
     * process's emergency action as far as OOM Guard's switches allow (LIM-9). Not a cap: the process
     * may use more, and only while Prism runs.
     */
    RESIDENT_POLICY
}
