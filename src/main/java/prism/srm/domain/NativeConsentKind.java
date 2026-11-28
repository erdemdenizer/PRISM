package prism.srm.domain;

/**
 * A consent a native limit may need from the user before PRISM asks anything of the system (LIM-6, ADR-0016 decisions
 * 4 and 6). Each is given for one request about one process and is never remembered for another process or session.
 */
public enum NativeConsentKind {

    /**
     * Linux: moving the process into a systemd scope PRISM creates. The move is one-way: the process stays there until
     * it exits, the processes it starts share the scope, and its desktop environment may see it differently.
     */
    ADOPTION,

    /**
     * Linux: a hard memory limit ({@code memory.max}), under which the kernel may terminate the process or one of its
     * child processes.
     */
    HARD_MEMORY
}
