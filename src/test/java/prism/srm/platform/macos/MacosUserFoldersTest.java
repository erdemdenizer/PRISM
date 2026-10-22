package prism.srm.platform.macos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Runs on every system. Nothing here was run on macOS. */
class MacosUserFoldersTest {

    @Test
    void logsAreInTheLogsFolderOfTheUsersLibrary() {
        Path home = Path.of("Users", "alice");

        assertThat(new MacosUserFolders(home).logs())
                .isEqualTo(home.resolve("Library").resolve("Logs").resolve("PrismSystemResourceManager"));
    }
}
