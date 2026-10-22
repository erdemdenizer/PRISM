package prism.srm.platform.windows;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import prism.srm.platform.UserFolders;

/**
 * The per-user folders on Windows, under the local (non-roaming) application data folder: logs
 * belong to one machine and must not travel with a roaming profile.
 *
 * <p>The folder is taken from the {@code LOCALAPPDATA} environment variable, which is the default
 * path of {@code FOLDERID_LocalAppData}: "%LOCALAPPDATA% (%USERPROFILE%\AppData\Local)"
 * (https://learn.microsoft.com/en-us/windows/win32/shell/knownfolderid). The variable is read
 * instead of calling {@code SHGetKnownFolderPath} so that this class needs no native library: it
 * runs before logging is set up, and a failure to load one could not be reported. When the
 * variable is missing or unusable, the documented default below the user's home is used.
 */
public final class WindowsUserFolders implements UserFolders {

    /** A drive-absolute path ({@code C:\...}) or a UNC path ({@code \\server\share\...}). */
    private static final Pattern ABSOLUTE = Pattern.compile("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\[^\\\\/]).*");

    private final UnaryOperator<String> environment;
    private final Path home;

    /**
     * @param environment looks up an environment variable by name; {@code null} when it is not set
     * @param home the user's home folder
     */
    public WindowsUserFolders(UnaryOperator<String> environment, Path home) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.home = Objects.requireNonNull(home, "home");
    }

    @Override
    public Path logs() {
        return localAppData().resolve("Prism").resolve("SystemResourceManager").resolve("logs");
    }

    private Path localAppData() {
        String value = environment.apply("LOCALAPPDATA");
        // Checked on the text: whether Path.isAbsolute accepts a Windows path depends on the
        // system this code runs on, and the tests run on every system.
        if (value != null && ABSOLUTE.matcher(value).matches()) {
            try {
                return Path.of(value);
            } catch (InvalidPathException unusable) {
                // Falls through to the default: a path the file system cannot express is no folder.
            }
        }
        return home.resolve("AppData").resolve("Local");
    }
}
