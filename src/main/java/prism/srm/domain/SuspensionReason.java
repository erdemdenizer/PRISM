package prism.srm.domain;

/**
 * Why Prism holds a process suspended (CTL-9). A process is resumed only when no reason remains,
 * so one owner can never wake a process another still wants stopped.
 */
public enum SuspensionReason {

    /** The user asked. Never released by shutdown or recovery (LIFE-2). */
    USER,

    /** The emulated CPU limiter, while it holds the process under its cap. */
    CPU_LIMITER,

    /** OOM Guard, as an emergency action. */
    OOM_GUARD;

    /** Whether Prism releases this reason by itself on shutdown and recovery (LIFE-1, LIFE-6). */
    public boolean automatic() {
        return this != USER;
    }
}
