package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.NativeConsentOffer;

/**
 * What became of a change of the limits of a job the limiter holds (plan revision 4, §6.2). Each
 * kind is set by a call of its own; the result says what the job is known to hold afterwards.
 *
 * @param observed what the job was read back as holding; empty for {@link Phase#UNVERIFIED}
 * @param consent what the user must confirm first; present exactly for {@link Phase#CONSENT_REQUIRED}
 */
public record ChangeResult(
        Phase phase, ControlOutcome outcome, Optional<EffectiveLimits> observed, Optional<NativeConsentOffer> consent) {

    /** What is known after the change. */
    public enum Phase {
        /** The job was read back holding exactly the limits asked for. */
        APPLIED,
        /** It was read back holding something else: some kind changed and another did not. */
        PARTIAL,
        /** Something was set but nothing could be read back: per kind, the old value or the new one. */
        UNVERIFIED,
        /** Nothing was set: the process exited, PRISM is closing, or the job could not be read first. */
        NOT_DONE,
        /** The limiter holds no job for the process. */
        NO_HOLD,
        /**
         * Nothing was set, refused only because the user has not given a consent the change needs (Linux: a hard
         * memory limit, LIM-6); nothing was set or sent to systemd (the liveness of the process may have been read).
         */
        CONSENT_REQUIRED
    }

    public ChangeResult {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(consent, "consent");
        if (outcome.succeeded() != (phase == Phase.APPLIED)) {
            throw new IllegalArgumentException("Only an applied change is a success: " + phase + " " + outcome);
        }
        if (phase == Phase.UNVERIFIED && observed.isPresent()) {
            throw new IllegalArgumentException("An unverified change was not read back");
        }
        if (consent.isPresent() != (phase == Phase.CONSENT_REQUIRED)) {
            throw new IllegalArgumentException("A consent is asked for exactly where it is required: " + phase);
        }
    }

    public ChangeResult(Phase phase, ControlOutcome outcome, Optional<EffectiveLimits> observed) {
        this(phase, outcome, observed, Optional.empty());
    }

    /** Refused for a consent the user has not given; nothing was asked of the system, the limits are as they were. */
    public static ChangeResult consentRequired(
            ControlOutcome outcome, Optional<EffectiveLimits> inForce, NativeConsentOffer consent) {
        return new ChangeResult(Phase.CONSENT_REQUIRED, outcome, inForce, Optional.of(consent));
    }
}
