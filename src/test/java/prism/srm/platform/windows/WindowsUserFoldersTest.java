package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs on every system: the paths are compared as built, never asked whether they exist or are
 * absolute.
 */
class WindowsUserFoldersTest {

    private static final Path HOME = Path.of("home", "alice");
    private static final Path DEFAULT_LOGS = HOME.resolve("AppData")
            .resolve("Local")
            .resolve("Prism")
            .resolve("SystemResourceManager")
            .resolve("logs");

    @Test
    void logsAreBelowTheLocalApplicationDataFolder() {
        Path logs = logsWith(Map.of("LOCALAPPDATA", "C:\\Users\\alice\\AppData\\Local"));

        assertThat(logs)
                .isEqualTo(Path.of("C:\\Users\\alice\\AppData\\Local")
                        .resolve("Prism")
                        .resolve("SystemResourceManager")
                        .resolve("logs"));
    }

    @Test
    void aFolderOnANetworkShareIsAccepted() {
        Path logs = logsWith(Map.of("LOCALAPPDATA", "\\\\server\\profiles\\alice\\Local"));

        assertThat(logs)
                .isEqualTo(Path.of("\\\\server\\profiles\\alice\\Local")
                        .resolve("Prism")
                        .resolve("SystemResourceManager")
                        .resolve("logs"));
    }

    @Test
    void withoutTheVariableTheDocumentedDefaultBelowTheHomeFolderIsUsed() {
        assertThat(logsWith(Map.of())).isEqualTo(DEFAULT_LOGS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "   ", "AppData\\Local", "C:relative", "\\rooted-on-the-current-drive", "C:\\bad\u0000name"})
    void aValueThatIsNoAbsoluteFolderIsIgnored(String value) {
        assertThat(logsWith(Map.of("LOCALAPPDATA", value))).isEqualTo(DEFAULT_LOGS);
    }

    /**
     * Values the text check lets through and Windows itself refuses as a folder: the default is
     * used. What Path.of makes of them is a rule of the Windows file system, so this runs there.
     */
    @ParameterizedTest
    @EnabledOnOs(OS.WINDOWS)
    @ValueSource(
            strings = {
                "\\\\server",
                "\\\\?\\GLOBALROOT\\Device\\HarddiskVolume1",
                "\\\\.\\C:\\Users\\alice",
                "C:\\Users\\alice \\AppData"
            })
    void aValueWindowsRefusesAsAFolderIsIgnored(String value) {
        assertThat(logsWith(Map.of("LOCALAPPDATA", value))).isEqualTo(DEFAULT_LOGS);
    }

    /** The long-path form of an absolute folder is the same folder. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLongPathPrefixIsAccepted() {
        Path logs = logsWith(Map.of("LOCALAPPDATA", "\\\\?\\C:\\Users\\alice\\AppData\\Local"));

        assertThat(logs)
                .isEqualTo(Path.of("C:\\Users\\alice\\AppData\\Local")
                        .resolve("Prism")
                        .resolve("SystemResourceManager")
                        .resolve("logs"));
    }

    @Test
    void onlyTheLocalApplicationDataVariableIsRead() {
        Path logs = logsWith(Map.of("APPDATA", "C:\\Users\\alice\\AppData\\Roaming"));

        assertThat(logs).isEqualTo(DEFAULT_LOGS);
    }

    private static Path logsWith(Map<String, String> environment) {
        return new WindowsUserFolders(environment::get, HOME).logs();
    }
}
