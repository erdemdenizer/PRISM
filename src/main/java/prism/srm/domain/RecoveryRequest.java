package prism.srm.domain;

import java.util.Objects;

/** What the user asks about a process an earlier session may have left suspended. */
public record RecoveryRequest(Action action, ProcessIdentity identity, String name) {

    /** Resume it after checking, or dismiss the entry and leave the process as it is. */
    public enum Action {
        RESUME,
        DISMISS
    }

    public RecoveryRequest {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
    }
}
