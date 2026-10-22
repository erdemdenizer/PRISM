package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs on every system: the paths are compared as built, never asked whether they exist or are
 * absolute. Nothing here was run on Linux.
 */
class LinuxUserFoldersTest {

    private static final Path HOME = Path.of("home", "alice");
    private static final Path DEFAULT_LOGS =
            HOME.resolve(".local").resolve("state").resolve("prism-srm").resolve("logs");

    @Test
    void logsAreBelowTheStateHome() {
        Path logs = logsWith(Map.of("XDG_STATE_HOME", "/var/state/alice"));

        assertThat(logs)
                .isEqualTo(Path.of("/var/state/alice").resolve("prism-srm").resolve("logs"));
    }

    @Test
    void withoutTheVariableTheDefaultOfTheSpecificationIsUsed() {
        assertThat(logsWith(Map.of())).isEqualTo(DEFAULT_LOGS);
    }

    /** The specification: a value that is empty counts as not set, a relative one is ignored. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "state", "./state", "~/state", "/bad\u0000name"})
    void aValueThatIsNoAbsoluteFolderIsIgnored(String value) {
        assertThat(logsWith(Map.of("XDG_STATE_HOME", value))).isEqualTo(DEFAULT_LOGS);
    }

    /** The specification builds the default on $HOME, which can differ from Java's user.home. */
    @Test
    void theDefaultIsBuiltOnTheHomeVariableWhenItIsAbsolute() {
        Path logs = logsWith(Map.of("HOME", "/srv/homes/alice"));

        assertThat(logs)
                .isEqualTo(Path.of("/srv/homes/alice")
                        .resolve(".local")
                        .resolve("state")
                        .resolve("prism-srm")
                        .resolve("logs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "alice", "./alice", "/bad\u0000home"})
    void aHomeVariableThatIsNoAbsoluteFolderGivesWayToTheGivenHomeFolder(String value) {
        assertThat(logsWith(Map.of("HOME", value))).isEqualTo(DEFAULT_LOGS);
    }

    @Test
    void theStateHomeVariableWinsOverTheHomeVariable() {
        Path logs = logsWith(Map.of("XDG_STATE_HOME", "/var/state/alice", "HOME", "/srv/homes/alice"));

        assertThat(logs)
                .isEqualTo(Path.of("/var/state/alice").resolve("prism-srm").resolve("logs"));
    }

    @Test
    void otherBaseDirectoryVariablesAreNotRead() {
        Path logs = logsWith(Map.of("XDG_DATA_HOME", "/var/data", "XDG_CACHE_HOME", "/var/cache"));

        assertThat(logs).isEqualTo(DEFAULT_LOGS);
    }

    private static Path logsWith(Map<String, String> environment) {
        return new LinuxUserFolders(environment::get, HOME).logs();
    }
}
