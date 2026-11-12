package prism.srm.domain;

import java.util.Objects;

/** A sentence the Limits page states above the list, and why (UI-4: limitations are stated, not hidden). */
public record LimitsNotice(Kind kind, String text) {

    /** What a notice is about. */
    public enum Kind {
        /** New native limits are refused: the ledger that records them failed. */
        LEDGER_FAILED,
        /** A job of an exited process still carries limits Prism could not clear. */
        RESIDUE,
        /** An earlier session may still cap a process natively. */
        PRIOR_SESSION,
        /** The native-limit ledger of another session is not settled yet: no emulated limit is offered for the processes it may name. */
        RECOVERY_PENDING,
        /** Prism itself runs inside a Job Object. */
        SELF_IN_JOB,
        /** Native CPU limits are not available on this system. */
        NATIVE_UNAVAILABLE
    }

    public LimitsNotice {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
    }
}
