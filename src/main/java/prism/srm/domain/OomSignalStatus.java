package prism.srm.domain;

/**
 * Whether the memory readings OOM Guard decides by are validated on this system (ADR-0015 §7): the
 * available physical memory of the system and the resident memory of a process. Where they are not,
 * OOM Guard warns and logs and takes no automatic action.
 */
public enum OomSignalStatus {
    VALIDATED,
    UNVALIDATED
}
