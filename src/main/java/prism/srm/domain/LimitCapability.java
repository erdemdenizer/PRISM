package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * How a limit is provided for one process (LIM-3, CAP-3, CAP-5): its level, the mechanism behind
 * it, and, where it is unavailable, why. Unlike a {@link Capability} of an action, an unavailable
 * limit carries its reason, because the Limits page states it next to the process (UI-4: limitations
 * are stated, not hidden).
 */
public record LimitCapability(CapabilityLevel level, Optional<ControlMechanism> mechanism, Optional<String> reason) {

    public LimitCapability {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(mechanism, "mechanism");
        Objects.requireNonNull(reason, "reason");
        boolean available = level != CapabilityLevel.UNAVAILABLE;
        if (mechanism.isPresent() != available) {
            throw new IllegalArgumentException(
                    "An available limit names its mechanism, an unavailable one none: " + level + " " + mechanism);
        }
        if (reason.isPresent() == available) {
            throw new IllegalArgumentException("Exactly an unavailable limit gives its reason: " + level);
        }
    }

    public static LimitCapability of(CapabilityLevel level, ControlMechanism mechanism) {
        return new LimitCapability(level, Optional.of(mechanism), Optional.empty());
    }

    public static LimitCapability unavailable(String reason) {
        return new LimitCapability(CapabilityLevel.UNAVAILABLE, Optional.empty(), Optional.of(reason));
    }

    public boolean available() {
        return level != CapabilityLevel.UNAVAILABLE;
    }
}
