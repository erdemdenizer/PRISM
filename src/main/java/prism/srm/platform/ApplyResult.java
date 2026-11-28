package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.NativeConsentOffer;

/**
 * What became of a first assignment (plan revision 3/4, §6.2). The assignment is irreversible from
 * the moment it is attempted, so only {@link Phase#NOT_ASSIGNED} means that nothing happened, and
 * that is proven by the system, not assumed from a failed call.
 *
 * @param observed what the job was read back as holding, when it could be read
 * @param fallbackEligible whether the emulated limit may be offered for this failure (LIM-5);
 *     only for {@link Phase#NOT_ASSIGNED}
 * @param consent what the user must confirm first; present exactly for {@link Phase#CONSENT_REQUIRED}
 */
public record ApplyResult(
        Phase phase,
        ControlOutcome outcome,
        Optional<EffectiveLimits> observed,
        boolean fallbackEligible,
        Optional<NativeConsentOffer> consent) {

    /** What is known after the attempt. */
    public enum Phase {
        /** The process is provably not in the job; the job is gone; nothing to keep. */
        NOT_ASSIGNED,
        /** It was assigned and exited at once; the limits were cleared and the job let go. */
        ASSIGNED_RELEASED,
        /** It is in the job, alive, and the job holds exactly the limits asked for. */
        ENFORCED,
        /** It is in the job, but the job holds other limits, or the process exited and they could not be cleared. */
        ASSIGNED_MISMATCH,
        /** Whether it is in the job could not be told; the job and the record are kept as evidence. */
        UNCERTAIN,
        /**
         * Refused only because the user has not given a consent the assignment needs (Linux, LIM-6): refused before
         * anything was asked of the system, so nothing happened, as for {@link #NOT_ASSIGNED}. No fallback is offered:
         * the user is asked first, and the same request with the consent may then be carried out.
         */
        CONSENT_REQUIRED
    }

    public ApplyResult {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(consent, "consent");
        if (outcome.succeeded() != (phase == Phase.ENFORCED)) {
            throw new IllegalArgumentException("Only an enforced limit is a success: " + phase + " " + outcome);
        }
        if (fallbackEligible && phase != Phase.NOT_ASSIGNED) {
            throw new IllegalArgumentException("A fallback is offered only where nothing was assigned: " + phase);
        }
        if (consent.isPresent() != (phase == Phase.CONSENT_REQUIRED)) {
            throw new IllegalArgumentException("A consent is asked for exactly where it is required: " + phase);
        }
        if (phase == Phase.CONSENT_REQUIRED && observed.isPresent()) {
            throw new IllegalArgumentException("Nothing was asked of the system before the consent");
        }
    }

    public ApplyResult(
            Phase phase, ControlOutcome outcome, Optional<EffectiveLimits> observed, boolean fallbackEligible) {
        this(phase, outcome, observed, fallbackEligible, Optional.empty());
    }

    public static ApplyResult notAssigned(ControlOutcome outcome, boolean fallbackEligible) {
        return new ApplyResult(Phase.NOT_ASSIGNED, outcome, Optional.empty(), fallbackEligible);
    }

    /** Refused for a consent the user has not given; nothing was asked of the system. */
    public static ApplyResult consentRequired(ControlOutcome outcome, NativeConsentOffer consent) {
        return new ApplyResult(Phase.CONSENT_REQUIRED, outcome, Optional.empty(), false, Optional.of(consent));
    }

    /** Whether the limiter keeps a job for the process after this result. */
    public boolean held() {
        return phase == Phase.ENFORCED || phase == Phase.ASSIGNED_MISMATCH || phase == Phase.UNCERTAIN;
    }
}
