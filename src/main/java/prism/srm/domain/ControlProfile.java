package prism.srm.domain;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What process control is on this system, once, for the user interface: how each action is
 * provided (CAP-1), and which of the five levels a priority number of the process listing stands
 * for (CTL-6, "mapped per system"). With the second, the user interface marks the level a process
 * has without knowing how the system numbers priorities.
 *
 * @param capabilities how each action is provided
 * @param listedPriorities the level each priority number of the listing stands for; a number
 *     that is not a key stands for none of the five levels. Empty where Prism cannot change
 *     priorities.
 */
public record ControlProfile(
        Map<ControlAction, Capability> capabilities, Map<Integer, PriorityLevel> listedPriorities) {

    public ControlProfile {
        capabilities = Map.copyOf(capabilities);
        listedPriorities = Map.copyOf(listedPriorities);
    }

    /** The level a priority of the listing stands for; empty when it is absent or stands for none. */
    public Optional<PriorityLevel> levelOf(OptionalInt listedPriority) {
        return listedPriority.isPresent()
                ? Optional.ofNullable(listedPriorities.get(listedPriority.getAsInt()))
                : Optional.empty();
    }
}
