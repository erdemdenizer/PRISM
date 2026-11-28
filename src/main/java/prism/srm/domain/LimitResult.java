package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What became of one request about managing a process, for the user interface.
 *
 * @param message what came of it, in one plain sentence without native codes
 * @param explanation what the user may want to know beside it; empty when there is nothing to add
 * @param fallback the emulated CPU limit offered because the native one could not be applied; the
 *     user interface asks the user, and nothing runs unless they accept (MILESTONES §10)
 * @param consent what the user must confirm before the native limit asked for is applied (LIM-6, ADR-0016 decisions
 *     4 and 6); nothing was changed, no emulated limit started, and the user interface sends the same request again
 *     with these consents only once the user confirms them
 */
public record LimitResult(
        LimitRequest request,
        ControlOutcome outcome,
        String message,
        String explanation,
        Optional<FallbackOffer> fallback,
        Optional<NativeConsentOffer> consent) {

    public LimitResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(consent, "consent");
        if (message.isBlank()) {
            throw new IllegalArgumentException("A result has a message");
        }
        if (fallback.isPresent() && outcome.succeeded()) {
            throw new IllegalArgumentException("A fallback is offered only for a limit that was not applied");
        }
        if (consent.isPresent() && (outcome.succeeded() || fallback.isPresent())) {
            throw new IllegalArgumentException("A consent is asked only where nothing was done and nothing is offered");
        }
    }

    public LimitResult(
            LimitRequest request,
            ControlOutcome outcome,
            String message,
            String explanation,
            Optional<FallbackOffer> fallback) {
        this(request, outcome, message, explanation, fallback, Optional.empty());
    }

    public LimitResult(LimitRequest request, ControlOutcome outcome, String message, String explanation) {
        this(request, outcome, message, explanation, Optional.empty());
    }

    /** The message and the explanation as one text, for the log and for a screen reader. */
    public String text() {
        return explanation.isEmpty() ? message : message + " " + explanation;
    }
}
