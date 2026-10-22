package prism.srm.platform.macos;

import java.nio.file.Path;
import java.util.Objects;
import prism.srm.platform.UserFolders;

/**
 * The per-user folders on macOS. Logs go to the {@code Logs} folder of the user's Library, which
 * Apple's File System Programming Guide describes as: "Contains log files for the console and
 * specific system services. Users can also view these logs using the Console app."
 * (https://developer.apple.com/library/archive/documentation/FileManagement/Conceptual/FileSystemProgrammingGuide/MacOSXDirectories/MacOSXDirectories.html)
 *
 * <p>The guide lists the folder among the subdirectories of a Library directory without saying
 * in which domains it exists; that the user's Library has one is the convention applications
 * follow, not a quotation. Not run on macOS yet.
 */
public final class MacosUserFolders implements UserFolders {

    private final Path home;

    /** @param home the user's home folder */
    public MacosUserFolders(Path home) {
        this.home = Objects.requireNonNull(home, "home");
    }

    @Override
    public Path logs() {
        return home.resolve("Library").resolve("Logs").resolve("PrismSystemResourceManager");
    }
}
