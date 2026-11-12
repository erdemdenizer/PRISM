package prism.srm.domain;

import java.util.Objects;

/**
 * The emulated CPU limit offered after a native one could not be applied (MILESTONES §10): it runs
 * only once the user accepts it, and the offer says what it costs.
 *
 * @param reason why the native limit was not applied, in words
 * @param mostLimits how many emulated limits may run at once here (ADR-0011 admission)
 * @param perThreadSuspension whether the emulated limit suspends thread by thread, the costly way
 */
public record FallbackOffer(String reason, int mostLimits, int processors, boolean perThreadSuspension) {

    public FallbackOffer {
        Objects.requireNonNull(reason, "reason");
    }
}
