package prism.srm.domain;

import java.util.Objects;

/**
 * What became of a request about a process an earlier session may have left suspended.
 *
 * @param message what came of it, in one plain sentence without native codes
 * @param explanation what the user may want to know beside it; empty when there is nothing to add
 */
public record RecoveryResult(RecoveryRequest request, ControlOutcome outcome, String message, String explanation) {

    public RecoveryResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(explanation, "explanation");
        if (message.isBlank()) {
            throw new IllegalArgumentException("A result has a message");
        }
    }

    public String text() {
        return explanation.isEmpty() ? message : message + " " + explanation;
    }
}
