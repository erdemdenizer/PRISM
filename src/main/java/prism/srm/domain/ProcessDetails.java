package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * grabs process details nothing much :P
 */
public record ProcessDetails(ProcessIdentity identity, Optional<String> executablePath, Optional<String> commandLine) {

    public ProcessDetails {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(executablePath, "executablePath");
        Objects.requireNonNull(commandLine, "commandLine");
    }
}
