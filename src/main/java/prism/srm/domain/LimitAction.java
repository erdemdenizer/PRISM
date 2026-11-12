package prism.srm.domain;

/** What the user can ask about managing a process, its limits and its OOM settings (UI-2, UI-4, UI-5). */
public enum LimitAction {
    /** Make the process a Managed Process (MGD-2). */
    MANAGE,
    /** Stop managing it; its limit ends and the process is left running. */
    UNMANAGE,
    /** Set or change its CPU limit. */
    SET_CPU_LIMIT,
    /** Remove its CPU limit; the process stays managed. */
    REMOVE_CPU_LIMIT,
    /** Apply its limits again. */
    ENABLE,
    /** Keep its limits but stop applying them. */
    DISABLE,
    /** Set or change its memory limit: the memory it may commit (milestone 10, native only). */
    SET_MEMORY_LIMIT,
    /** Remove its memory limit; the process stays managed. */
    REMOVE_MEMORY_LIMIT,
    /**
     * Accept the emulated CPU limit Prism offered because the native one could not be applied
     * (MILESTONES section 10): valid only while that offer stands.
     */
    ACCEPT_EMULATED_CPU,
    /** Set the OOM management of the process: on or off, its OOM priority and its emergency action (MGD-3). */
    SET_OOM,
    /**
     * Accept the watched resident memory Prism offered because the native memory limit could not be
     * applied (LIM-9, plan O9): valid only while that offer stands.
     */
    ACCEPT_RESIDENT_POLICY
}
