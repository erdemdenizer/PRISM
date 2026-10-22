package prism.srm.platform.linux;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;
import prism.srm.platform.UserFolders;

/**
 * The per-user folders on Linux, by the XDG Base Directory Specification, version 0.8
 * (https://specifications.freedesktop.org/basedir/latest/).
 *
 * <p>Logs are state: "$XDG_STATE_HOME defines the base directory relative to which user-specific
 * state files should be stored. If $XDG_STATE_HOME is either not set or empty, a default equal to
 * $HOME/.local/state should be used", and the specification names "actions history (logs,
 * history, recently used files, ...)" as what belongs there. A relative value is ignored, as the
 * specification requires: "All paths set in these environment variables must be absolute. If an
 * implementation encounters a relative path in any of these variables it should consider the path
 * invalid and ignore it."
 *
 * <p>The default is built on the {@code HOME} variable, as the specification words it, and on
 * the given home folder only when the variable holds no absolute path; Java's {@code user.home}
 * comes from the password database and can differ from {@code HOME}.
 *
 * <p>Two things are open. The specification asks that a missing directory be created "with
 * permission 0700"; this class creates nothing, and the logging library that does creates the
 * folder with the default mode of the process. And nothing here has been run on Linux.
 */
public final class LinuxUserFolders implements UserFolders {

    private final UnaryOperator<String> environment;
    private final Path home;

    /**
     * @param environment looks up an environment variable by name; {@code null} when it is not set
     * @param home the user's home folder, used when {@code HOME} holds no absolute path
     */
    public LinuxUserFolders(UnaryOperator<String> environment, Path home) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.home = Objects.requireNonNull(home, "home");
    }

    @Override
    public Path logs() {
        return stateHome().resolve("prism-srm").resolve("logs");
    }

    private Path stateHome() {
        Path stateHome = absolute(environment.apply("XDG_STATE_HOME"));
        if (stateHome != null) {
            return stateHome;
        }
        Path homeVariable = absolute(environment.apply("HOME"));
        return (homeVariable != null ? homeVariable : home).resolve(".local").resolve("state");
    }

    /** Returns the value as a path if it is an absolute one, otherwise {@code null}. */
    private static Path absolute(String value) {
        // Checked on the text: whether Path.isAbsolute accepts a POSIX path depends on the system
        // this code runs on, and the tests run on every system.
        if (value == null || !value.startsWith("/")) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException unusable) {
            // A path the file system cannot express is no folder.
            return null;
        }
    }
}
