package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.OperationResult;

/**
 * What came of an explicit clearing of the native limits an earlier session left (milestone 12, plan Rev 2.1 §4.2):
 * {@link ResourceLimiter#clearOwned}. Separate from recovery's read-only classification ({@link
 * ResourceLimiter#recover}), which never changes anything; no limiter in this version clears, and recovery never asks
 * one to.
 *
 * @param observed what was read back afterwards, per kind, when it could be read
 */
public record ClearResult(Outcome result, ControlOutcome outcome, Optional<EffectiveLimits> observed) {

    /** What is known after the attempt. */
    public enum Outcome {
        /** Every limit was set to unlimited and read back so. */
        CLEARED,
        /** Some kind was cleared and another was not, as read back. */
        PARTIAL,
        /** Something was set but nothing could be read back. */
        UNVERIFIED,
        /** Ownership could not be proven at the moment of the attempt: nothing was changed. */
        NOT_OWNED,
        /** Nothing was changed: the scope or the process is gone, or the attempt was refused before any change. */
        NOT_DONE,
        /** This limiter does not clear earlier sessions' limits: nothing was changed. */
        UNSUPPORTED
    }

    public ClearResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(observed, "observed");
        if (outcome.succeeded() != (result == Outcome.CLEARED)) {
            throw new IllegalArgumentException("Only a verified clearing is a success: " + result + " " + outcome);
        }
        if (result == Outcome.UNVERIFIED && observed.isPresent()) {
            throw new IllegalArgumentException("An unverified clearing was not read back");
        }
        if (result == Outcome.CLEARED && observed.filter(EffectiveLimits::none).isEmpty()) {
            throw new IllegalArgumentException("A clearing is verified only by a read-back of no limit");
        }
    }

    /** The answer of a limiter that never clears: nothing was changed. */
    public static ClearResult unsupported(String reason) {
        return new ClearResult(
                Outcome.UNSUPPORTED, ControlOutcome.of(OperationResult.UNSUPPORTED, reason), Optional.empty());
    }

    /** Whether anything may have been changed by the attempt. */
    public boolean mayHaveChanged() {
        return result == Outcome.CLEARED || result == Outcome.PARTIAL || result == Outcome.UNVERIFIED;
    }
}
