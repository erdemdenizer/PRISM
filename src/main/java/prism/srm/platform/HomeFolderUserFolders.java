package prism.srm.platform;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The folders on a system Prism does not know: a hidden folder in the user's home. No convention
 * is claimed for such a system; this only keeps the files in one predictable place.
 */
final class HomeFolderUserFolders implements UserFolders {

    private final Path home;

    HomeFolderUserFolders(Path home) {
        this.home = Objects.requireNonNull(home, "home");
    }

    @Override
    public Path logs() {
        return home.resolve(".prism-srm").resolve("logs");
    }
}
