package prism.srm.domain;

import java.util.Objects;

/**
 * What became of one request, for the user interface. It names the request, so that whoever shows
 * it can compare it with the process selected by then: the answer may come after the selection
 * has changed.
 *
 * @param message what came of it, in one sentence the user reads first: plain words, no native codes
 * @param explanation what the user may want to know beside it, such as what the result does not
 *     mean; empty when there is nothing to add
 */
public record ProcessControlResult(ControlRequest request, ControlOutcome outcome, String message, String explanation) {

    public ProcessControlResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(explanation, "explanation");
        if (message.isBlank()) {
            throw new IllegalArgumentException("A result has a message");
        }
    }

    /** A result whose message says it all. */
    public ProcessControlResult(ControlRequest request, ControlOutcome outcome, String message) {
        this(request, outcome, message, "");
    }

    /** The message and the explanation as one text, for the log and for a screen reader. */
    public String text() {
        return explanation.isEmpty() ? message : message + " " + explanation;
    }

    public ProcessIdentity identity() {
        return request.identity();
    }

    public OperationResult result() {
        return outcome.result();
    }
}
