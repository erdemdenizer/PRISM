package prism.srm.domain;

/**
 * Whether Prism has read every suspension journal of an earlier session of it that has ended
 * (ADR-0011), and so whether the list of what such a session may have left suspended is whole.
 */
public enum RecoveryCheck {

    /** Every journal of an ended session has been read: the list is whole. */
    COMPLETE,

    /**
     * A journal of an ended session cannot be read yet, because the system still holds its lock;
     * Prism keeps trying for about two minutes. Until then the list may be incomplete.
     */
    PENDING,

    /**
     * Prism stopped looking with a journal still unread. Its file stays and is read at the next
     * start; until then the list may be incomplete.
     */
    EXHAUSTED
}
