package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * An action the user asked for on one process.
 *
 * @param identity the process, as it was selected: PID and start time, never the PID alone
 * @param name the name of the process as listed, for the messages
 * @param priority the level to set; present exactly for {@link ControlAction#CHANGE_PRIORITY}
 */
public record ControlRequest(
        ControlAction action, ProcessIdentity identity, String name, Optional<PriorityLevel> priority) {

    public ControlRequest {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(priority, "priority");
        if (priority.isPresent() != (action == ControlAction.CHANGE_PRIORITY)) {
            throw new IllegalArgumentException("A priority is given exactly for a change of priority: " + action);
        }
    }

    public static ControlRequest of(ControlAction action, ProcessIdentity identity, String name) {
        return new ControlRequest(action, identity, name, Optional.empty());
    }

    public static ControlRequest priority(ProcessIdentity identity, String name, PriorityLevel level) {
        return new ControlRequest(ControlAction.CHANGE_PRIORITY, identity, name, Optional.of(level));
    }
}
