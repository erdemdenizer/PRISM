package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * How an action is provided on this system: its level, and the mechanism behind it (CAP-5). An
 * available action always names its mechanism; an unavailable one names none.
 */
public record Capability(CapabilityLevel level, Optional<ControlMechanism> mechanism) {

    public static final Capability UNAVAILABLE = new Capability(CapabilityLevel.UNAVAILABLE, Optional.empty());

    public Capability {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(mechanism, "mechanism");
        if (mechanism.isPresent() == (level == CapabilityLevel.UNAVAILABLE)) {
            throw new IllegalArgumentException(
                    "An available capability names its mechanism, an unavailable one none: " + level + " " + mechanism);
        }
    }

    public static Capability of(CapabilityLevel level, ControlMechanism mechanism) {
        return new Capability(level, Optional.of(mechanism));
    }

    public boolean available() {
        return level != CapabilityLevel.UNAVAILABLE;
    }
}
