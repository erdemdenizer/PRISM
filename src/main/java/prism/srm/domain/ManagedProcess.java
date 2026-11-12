package prism.srm.domain;

import java.util.Objects;

/**
 * A process the user chose to manage (MGD-2), by its identity for the current session, with the
 * name it was listed under and its settings.
 */
public record ManagedProcess(ProcessIdentity identity, String name, ManagedSettings settings) {

    public ManagedProcess {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(settings, "settings");
    }

    public ManagedProcess with(ManagedSettings changed) {
        return new ManagedProcess(identity, name, changed);
    }
}
