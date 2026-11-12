package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Facts about one process that are too expensive to read for every process on every cycle and
 * are therefore read on demand. Each is absent when the system does not provide it or does not
 * let this user read it.
 *
 * @param identity the unique identity (PID and start time) of the process
 * @param executablePath the absolute path to the process executable, if accessible
 * @param commandLine the full command line arguments the process was launched with, if accessible
 */
public record ProcessDetails(ProcessIdentity identity, Optional<String> executablePath, Optional<String> commandLine) {

    public ProcessDetails {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(executablePath, "executablePath");
        Objects.requireNonNull(commandLine, "commandLine");
    }
}
