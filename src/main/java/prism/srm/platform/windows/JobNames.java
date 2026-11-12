package prism.srm.platform.windows;

import java.time.Instant;
import java.util.Optional;
import prism.srm.domain.ProcessIdentity;

/**
 * The name of the job Prism makes for a process (ADR-0002): {@code Local\Prism.Job.<pid>.<start
 * time in milliseconds>}. {@code Local\} is the session namespace; the rest holds no backslash and
 * is far shorter than MAX_PATH; names are compared case-sensitively, so the format is fixed. The
 * same identity always gives the same name, and a reused PID another. A name lives only while a
 * handle to its job is open (Life Cycle of an Object), so after Prism's last handle is gone the job
 * cannot be opened by it.
 * https://learn.microsoft.com/en-us/windows/win32/termserv/kernel-object-namespaces
 */
final class JobNames {

    static final String PREFIX = "Local\\Prism.Job.";

    private JobNames() {}

    static String of(ProcessIdentity identity) {
        return PREFIX + identity.pid() + "." + identity.startTime().toEpochMilli();
    }

    /** The identity a name stands for, with its start time in milliseconds; empty for any other text. */
    static Optional<ProcessIdentity> parse(String name) {
        if (!name.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = name.substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            long pid = Long.parseLong(parts[0]);
            long millis = Long.parseLong(parts[1]);
            if (pid < 0 || !parts[0].equals(Long.toString(pid)) || !parts[1].equals(Long.toString(millis))) {
                return Optional.empty();
            }
            return Optional.of(new ProcessIdentity(pid, Instant.ofEpochMilli(millis)));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
