package prism.srm.domain;

/**
 * The five scheduling priorities a user can choose (CTL-6). Each system maps them to its own
 * mechanism. Realtime is never offered and has no level here.
 */
public enum PriorityLevel {
    LOW,
    BELOW_NORMAL,
    NORMAL,
    ABOVE_NORMAL,
    HIGH
}
