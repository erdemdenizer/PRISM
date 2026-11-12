package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What OOM Guard holds on one Managed Process (ADR-0015 §4): the claims behind a suspension and behind
 * a lowered priority, the suspension as the coordinator holds it, and what is left over from earlier
 * actions.
 *
 * @param suspendClaims why the process is held suspended; empty when it is not, or while a release is
 *     incomplete
 * @param physical the suspension, as the suspension coordinator holds it for OOM Guard
 * @param exceededPending the process crossed its memory limit and no action has answered that yet
 * @param priorityClaims why its priority is held lowered
 * @param previousPriority the level its priority had before OOM Guard lowered it
 * @param terminateRequested whether OOM Guard asked for its termination in this session
 * @param failed what went wrong last, in words; empty when nothing did
 */
public record OomHold(
        Set<Claim> suspendClaims,
        Physical physical,
        boolean exceededPending,
        Set<Claim> priorityClaims,
        Optional<PriorityLevel> previousPriority,
        boolean terminateRequested,
        Optional<String> failed) {

    /** The suspension OOM Guard holds through the coordinator. */
    public enum Physical {
        NONE,
        SUSPENDED,
        /** A release that left part of the suspension in place; retried, never forgotten. */
        RELEASE_INCOMPLETE
    }

    public static final OomHold NONE =
            new OomHold(Set.of(), Physical.NONE, false, Set.of(), Optional.empty(), false, Optional.empty());

    public OomHold {
        suspendClaims = Set.copyOf(suspendClaims);
        Objects.requireNonNull(physical, "physical");
        priorityClaims = Set.copyOf(priorityClaims);
        Objects.requireNonNull(previousPriority, "previousPriority");
        Objects.requireNonNull(failed, "failed");
    }

    /** Whether OOM Guard holds anything on the process that the user could release. */
    public boolean holdsAnything() {
        return !suspendClaims.isEmpty() || physical != Physical.NONE || !priorityClaims.isEmpty();
    }
}
