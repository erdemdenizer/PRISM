package prism.srm.domain;

import java.util.Objects;

/**
 * A process Prism holds suspended, as the user is to be shown it: its identity and its name. Lets
 * the user release a suspension Prism still holds after process monitoring has stopped, when no
 * listing shows the process any more.
 */
public record SuspendedProcess(ProcessIdentity identity, String name) {

    public SuspendedProcess {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
    }
}
