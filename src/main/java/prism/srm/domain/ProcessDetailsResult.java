package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What an on-demand read of one process's details came to. The identity says which process was
 * asked for, whatever the outcome: whoever receives a result compares it with the process it is
 * showing by then, because the answer may arrive after the question has changed.
 *
 * @param identity the process the details were asked for
 * @param details the details; present exactly when the outcome is {@link Outcome#READ}
 */
public record ProcessDetailsResult(ProcessIdentity identity, Outcome outcome, Optional<ProcessDetails> details) {

    /** How the read ended. */
    public enum Outcome {
        /** The process was read. A single detail may still be absent. */
        READ,
        /** That process no longer exists; its PID may belong to another process by now. */
        PROCESS_GONE,
        /** The read itself failed. The cause is in the log. */
        FAILED
    }

    public ProcessDetailsResult {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(details, "details");
        if (details.isPresent() != (outcome == Outcome.READ)) {
            throw new IllegalArgumentException("Details are present exactly when the process was read: " + outcome);
        }
        if (details.isPresent() && !details.get().identity().equals(identity)) {
            throw new IllegalArgumentException("The details are those of another process");
        }
    }

    /** The process was read; the result is for the process the details name. */
    public static ProcessDetailsResult read(ProcessDetails details) {
        return new ProcessDetailsResult(details.identity(), Outcome.READ, Optional.of(details));
    }

    public static ProcessDetailsResult gone(ProcessIdentity identity) {
        return new ProcessDetailsResult(identity, Outcome.PROCESS_GONE, Optional.empty());
    }

    public static ProcessDetailsResult failed(ProcessIdentity identity) {
        return new ProcessDetailsResult(identity, Outcome.FAILED, Optional.empty());
    }
}
