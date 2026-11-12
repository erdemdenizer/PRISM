package prism.srm.domain;

import java.util.Objects;
import java.util.Set;

/**
 * A process an earlier session of Prism may have left suspended, found in that session's journal
 * after it ended without a controlled shutdown (LIFE-4, LIFE-6). Prism resumes none of these by
 * itself: a journal entry is a claim, not proof that the stop is still Prism's (ADR-0011).
 *
 * @param reasons why the earlier session held it: the user, the CPU limiter, OOM Guard
 * @param state whether Prism offers to resume it after checking, or can only show it
 * @param explanation what Prism could and could not check, in plain words
 * @param resumable whether the page offers Resume; never for {@link
 *     RecoveryState#MANUAL_INTERVENTION}
 * @param evidence the whole journal entry, for whoever has to decide by hand
 */
public record RecoveredSuspension(
        ProcessIdentity identity,
        String name,
        Set<SuspensionReason> reasons,
        RecoveryState state,
        String explanation,
        boolean resumable,
        String evidence) {

    public RecoveredSuspension {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        reasons = Set.copyOf(reasons);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(evidence, "evidence");
        if (resumable && state != RecoveryState.POSSIBLY_SUSPENDED) {
            throw new IllegalArgumentException("Only a process possibly left suspended may be offered Resume");
        }
    }

    public RecoveredSuspension withExplanation(String changed, boolean stillResumable) {
        return new RecoveredSuspension(identity, name, reasons, state, changed, stillResumable && resumable, evidence);
    }
}
