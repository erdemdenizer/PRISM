package prism.srm.domain;

import java.util.Objects;

/**
 * What the next start found for a native limit an earlier session recorded (LIFE-4, LIM-5). Nothing
 * is terminated, no new job is made, and no job is changed: a job that can still be opened by its
 * name has a live holder (ADR-0012), which recovery cannot prove has ended. The words follow what
 * could be checked.
 */
public record RecoveredLimit(LimitRecord record, Verdict verdict, String detail) {

    /** What the check of one record found. */
    public enum Verdict {
        /** The job was reached and holds no limit, or the record shows the limits had been cleared. */
        NOTHING_TO_CLEAR,
        /**
         * A job of Prism's name can still be opened: a running program holds it, and it holds what the
         * record allows. Recovery changes nothing; the process may be limited until it exits.
         */
        HELD_ELSEWHERE,
        /** The process is gone and the record shows the limits were cleared, or never applied. */
        GONE,
        /**
         * The process is gone; Prism cannot prove that its limit never took effect, so the
         * processes it started may still be limited until they exit.
         */
        GONE_CHILDREN_UNKNOWN,
        /** The process runs in a job Prism cannot reach any more: it may still be limited until it exits. */
        UNREACHABLE,
        /** The job was found but the system refused access to it. */
        ACCESS_DENIED,
        /** A job of Prism's name holds limits Prism did not record; nothing was changed. */
        VALUES_MISMATCH,
        /** The process runs and is in no job: the limit never took effect. */
        NOT_IN_JOB,
        /** The check failed; the process may still be limited. */
        FAILED,

        // Linux, milestone 12 (plan Rev 2.1 §4.3). Windows never gives these.

        /**
         * Linux: the record's scope is proven Prism's (the record's unit and invocation id equal the live unit's)
         * and still holds the recorded process; its limits may be in force. Classifying clears nothing: clearing is a
         * separate operation (ResourceLimiter#clearOwned), which recovery asks of a limiter that clears. The product's
         * Linux limiter does not (K16).
         */
        OWNED_LIMITED,
        /**
         * Linux: the record's scope is proven Prism's; the recorded process has exited and processes it started
         * remain in the scope, possibly limited.
         *
         * <p>Like {@link #GONE_CHILDREN_UNKNOWN}, it does not block another limiter by identity: no live process can
         * have the exited one's identity (its start time). Its record is carried as ownership evidence
         * ({@link #retainsProvenance()}), and the processes still in the scope are kept from a second limiter by the
         * scope-membership rule (plan Rev 2.2 §4.5), not by this record. A recorded process that runs outside its scope
         * is {@link #UNPROVEN_SCOPE}, not this.
         */
        OWNED_CHILDREN_ONLY,
        /** Linux: an explicit clearing of a proven scope set every limit to unlimited and read it back so. */
        CLEARED,
        /** Linux: an explicit clearing was attempted and not verified (partly cleared, or unreadable). */
        CLEAR_FAILED,
        /**
         * Linux: a scope with the record's name, or with Prism's name pattern, exists but its ownership is not proven
         * (no record, an incomplete record, or another invocation id). It is never changed; the process is blocked
         * from a second limiter and listed for the user.
         */
        UNPROVEN_SCOPE,
        /** Linux: the record's scope holds another process than the one recorded (by start time). Nothing changes. */
        FOREIGN_MEMBER
    }

    public RecoveredLimit {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(detail, "detail");
    }

    /** Whether the process, or the ones it started, may still be limited by the system: the user is warned. */
    public boolean maybeStillLimited() {
        return switch (verdict) {
            case GONE_CHILDREN_UNKNOWN, UNREACHABLE, ACCESS_DENIED, VALUES_MISMATCH, FAILED, HELD_ELSEWHERE -> true;
            case OWNED_LIMITED, OWNED_CHILDREN_ONLY, CLEAR_FAILED, UNPROVEN_SCOPE, FOREIGN_MEMBER -> true;
            case NOTHING_TO_CLEAR, GONE, NOT_IN_JOB, CLEARED -> false;
        };
    }

    /**
     * Whether the recorded process runs and an earlier session's job or scope may still cap it: no second limiter
     * for it, and the record is carried over until that process exits. A verdict for a process that has exited
     * ({@link Verdict#GONE_CHILDREN_UNKNOWN}, {@link Verdict#OWNED_CHILDREN_ONLY}) blocks nothing: it warns
     * through {@link #maybeStillLimited()} instead.
     */
    public boolean blocksAnotherLimiter() {
        return verdict == Verdict.UNREACHABLE
                || verdict == Verdict.HELD_ELSEWHERE
                || verdict == Verdict.ACCESS_DENIED
                || verdict == Verdict.VALUES_MISMATCH
                || verdict == Verdict.FAILED
                || verdict == Verdict.OWNED_LIMITED
                || verdict == Verdict.CLEAR_FAILED
                || verdict == Verdict.UNPROVEN_SCOPE
                || verdict == Verdict.FOREIGN_MEMBER;
    }

    /**
     * Whether the record must be kept as ownership evidence of a scope that may still limit processes (plan Rev 2.2
     * §4.5 c): a proven scope, or one whose clearing was not verified. Such a record is carried into the next session
     * whether or not its process still runs, and goes only when its limits were cleared and read back, or its scope has
     * no processes or no longer exists. Independent of {@link #blocksAnotherLimiter()}, the identity rule.
     */
    public boolean retainsProvenance() {
        return record.provenance().isPresent()
                && (verdict == Verdict.OWNED_LIMITED
                        || verdict == Verdict.OWNED_CHILDREN_ONLY
                        || verdict == Verdict.CLEAR_FAILED);
    }

    /**
     * Whether recovery proved that the scope is Prism's (Linux): the only verdicts an explicit clearing may act on, and
     * only after the limiter proves it again at that moment.
     */
    public boolean ownershipProven() {
        return verdict == Verdict.OWNED_LIMITED || verdict == Verdict.OWNED_CHILDREN_ONLY;
    }
}
