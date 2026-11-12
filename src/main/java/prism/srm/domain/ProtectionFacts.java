package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What the system says about a process that bears on whether Prism may act on it (SAFE-3). These
 * are facts, read from the system; which of them protect a process is decided above the platform.
 *
 * @param systemProcess the process is one of the system's own by its identity, such as the idle
 *     and the kernel process on Windows
 * @param critical whether the system marks the process as critical; empty when it could not be
 *     determined
 * @param listedSystemName the executable has the name of a well-known system process. A name alone
 *     never protects a process on its own merits; it is an additional defense only
 */
public record ProtectionFacts(boolean systemProcess, Optional<Boolean> critical, boolean listedSystemName) {

    public static final ProtectionFacts NONE = new ProtectionFacts(false, Optional.empty(), false);

    public ProtectionFacts {
        Objects.requireNonNull(critical, "critical");
    }
}
