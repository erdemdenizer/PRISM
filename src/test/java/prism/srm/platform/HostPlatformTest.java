package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.platform.linux.LinuxUserFolders;
import prism.srm.platform.macos.MacosUserFolders;
import prism.srm.platform.windows.WindowsUserFolders;

class HostPlatformTest {

    private static final Path HOME = Path.of("home", "alice");
    private static final Map<String, String> EMPTY_ENVIRONMENT = Map.of();

    @Test
    void everySupportedSystemGetsItsOwnFolderRules() {
        assertThat(folders(HostSystem.WINDOWS)).isInstanceOf(WindowsUserFolders.class);
        assertThat(folders(HostSystem.LINUX)).isInstanceOf(LinuxUserFolders.class);
        assertThat(folders(HostSystem.MACOS)).isInstanceOf(MacosUserFolders.class);
    }

    @Test
    void anUnknownSystemKeepsItsFilesInAHiddenFolderOfTheHomeFolder() {
        assertThat(folders(HostSystem.OTHER).logs())
                .isEqualTo(HOME.resolve(".prism-srm").resolve("logs"));
    }

    @ParameterizedTest
    @EnumSource(HostSystem.class)
    void theLogFolderIsBelowTheHomeFolderWhenTheEnvironmentSaysNothing(HostSystem system) {
        assertThat(folders(system).logs()).startsWithRaw(HOME);
    }

    @Test
    void theFoldersOfTheRunningSystemAreAvailableWithoutCreatingThePlatform() {
        assertThat(HostPlatform.userFolders().logs()).isAbsolute();
    }

    /** This runs before logging exists: a home folder that is no path must not end the application. */
    @Test
    void aHomeFolderThatIsNoPathGivesTheWorkingDirectoryInsteadOfAnException() {
        assertThat(HostPlatform.home("bad\u0000home")).isEqualTo(Path.of(""));
        assertThat(HostPlatform.home("")).isEqualTo(Path.of(""));
        assertThat(HostPlatform.home("home")).isEqualTo(Path.of("home"));
    }

    @ParameterizedTest
    @EnumSource(HostSystem.class)
    void withoutAHomeFolderTheLogFolderIsRelativeAndStillAFolder(HostSystem system) {
        Path logs = HostPlatform.userFolders(system, EMPTY_ENVIRONMENT::get, HostPlatform.home(""))
                .logs();

        assertThat(logs.getNameCount()).isGreaterThanOrEqualTo(2);
        assertThat(logs.toAbsolutePath()).isAbsolute();
    }

    @Test
    void theDescriptionNamesTheFamilyAndWhatItWasDetectedFrom() {
        assertThat(HostPlatform.systemDescription(HostSystem.WINDOWS, "Windows 11", "10.0", "amd64"))
                .isEqualTo("Windows (os.name=Windows 11, os.version=10.0, os.arch=amd64)");
        assertThat(HostPlatform.systemDescription(HostSystem.MACOS, "Mac OS X", "15.1", "aarch64"))
                .startsWith("macOS (");
        assertThat(HostPlatform.systemDescription(HostSystem.LINUX, "Linux", "6.8", "amd64"))
                .startsWith("Linux (");
    }

    @Test
    void anUnknownSystemIsDescribedAsUnsupportedWithItsName() {
        assertThat(HostPlatform.systemDescription(HostSystem.OTHER, "FreeBSD", "14.1", "amd64"))
                .isEqualTo("unsupported system (os.name=FreeBSD, os.version=14.1, os.arch=amd64)");
    }

    @Test
    void theDescriptionOfTheRunningSystemContainsItsName() {
        assertThat(HostPlatform.systemDescription()).contains("os.name=" + System.getProperty("os.name"));
    }

    private static UserFolders folders(HostSystem system) {
        return HostPlatform.userFolders(system, EMPTY_ENVIRONMENT::get, HOME);
    }
}
