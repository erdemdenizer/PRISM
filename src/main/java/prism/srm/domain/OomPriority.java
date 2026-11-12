package prism.srm.domain;

/**
 * Which Managed Processes OOM Guard acts on first (MGD-3, OOM-10). Processes of the same priority
 * are taken by resident memory, the largest first.
 */
public enum OomPriority {
    FIRST,
    NORMAL,
    LAST
}
