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
        assertThat(folders(system).state()).startsWithRaw(HOME);
        assertThat(folders(system).state()).isNotEqualTo(folders(system).logs());
    }

    @Test
    void anUnknownSystemKeepsItsStateBesideItsLogs() {
        assertThat(folders(HostSystem.OTHER).state())
                .isEqualTo(HOME.resolve(".prism-srm").resolve("state"));
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

    /**
     * The product's own wiring on Linux since K16 was lifted on 2026-11-28 (ADR-0016, plan Rev 2.2 T-D25 as amended): the
     * limiter the application gets uses the desktop policy and has no consent of its own. On any host this test runs on,
     * PRISM's own process and the process it runs under are refused whatever consents a request carries, before
     * anything is asked of the system and with no consent asked for; a process that does not exist is refused; nothing
     * is held, so nothing is changed, forgotten or released; a record no live scope proves is never cleared. Whether an
     * eligible desktop application is admitted with the user's consent, and only with it, depends on a real user manager
     * and is pinned on the composition itself in {@code LinuxScopeLimiterTest}.
     */
    @Test
    void theProductsLinuxLimiterIsTheDesktopPolicyWithConsentPerRequest() {
        ResourceLimiter linux = HostPlatform.limiterFor(HostSystem.LINUX, () -> 0);
        ProcessHandle self = ProcessHandle.current();
        prism.srm.domain.ProcessIdentity itselfId =
                new prism.srm.domain.ProcessIdentity(self.pid(), java.time.Instant.EPOCH);
        prism.srm.domain.ProcessIdentity absent = new prism.srm.domain.ProcessIdentity(
                4_194_304 + 4_242, java.time.Instant.parse("2026-10-15T10:00:00Z"));
        prism.srm.domain.LimitSpec spec =
                new prism.srm.domain.LimitSpec(java.util.OptionalInt.of(20), java.util.OptionalLong.empty());
        prism.srm.domain.LimitSpec hard = spec.withMemoryBytes(java.util.OptionalLong.of(256L << 20));
        java.util.Set<prism.srm.domain.NativeConsentKind> every =
                java.util.EnumSet.allOf(prism.srm.domain.NativeConsentKind.class);

        assertThat(linux).isInstanceOf(prism.srm.platform.linux.LinuxScopeLimiter.class);
        assertThat(linux.capabilities())
                .containsOnlyKeys(prism.srm.domain.LimitKind.CPU, prism.srm.domain.LimitKind.MEMORY);
        assertThat(linux.mayHaveLeftLimits())
                .as("a limiter that admits can leave limits")
                .isTrue();
        assertThat(linux.recoveryClears())
                .as("proven scopes of an earlier session are cleared")
                .isTrue();

        // PRISM itself, consent or not: the desktop policy refuses it first.
        assertThat(linux.capabilitiesFor(itselfId).values()).isNotEmpty().allSatisfy(capability -> {
            assertThat(capability.available()).isFalse();
            assertThat(capability.reason().orElseThrow()).isEqualTo("it is PRISM's own process");
        });
        assertThat(linux.consentNeeded(itselfId, hard, java.util.Set.of()))
                .as("no consent is asked for a move that would not be made")
                .isEmpty();
        ApplyResult itself = linux.apply(itselfId, hard, provenance -> true, every);
        assertThat(itself.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(itself.outcome().detail().orElseThrow()).isEqualTo("it is PRISM's own process");
        assertThat(linux.apply(itselfId, spec).phase())
                .as("never without a place to record the scope's ownership")
                .isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        self.parent().ifPresent(parent -> {
            ApplyResult under = linux.apply(
                    new prism.srm.domain.ProcessIdentity(parent.pid(), java.time.Instant.EPOCH),
                    spec,
                    provenance -> true,
                    every);
            assertThat(under.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
            assertThat(under.outcome().detail().orElseThrow()).startsWith("PRISM runs under it");
        });

        // A process that does not exist (above the largest PID Linux allows) is refused, consent or not.
        assertThat(linux.capabilitiesFor(absent).values())
                .allSatisfy(capability -> assertThat(capability.available()).isFalse());
        assertThat(linux.consentNeeded(absent, spec, java.util.Set.of())).isEmpty();
        assertThat(linux.apply(absent, spec, provenance -> true, every).phase())
                .isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);

        // Nothing is held: nothing to change, forget, release, or clear without proof.
        assertThat(linux.hold(absent)).isEmpty();
        assertThat(linux.change(absent, hard, every).phase()).isEqualTo(prism.srm.platform.ChangeResult.Phase.NO_HOLD);
        assertThat(linux.change(absent, spec).phase()).isEqualTo(prism.srm.platform.ChangeResult.Phase.NO_HOLD);
        assertThat(linux.forget(absent).result()).isEqualTo(prism.srm.platform.ForgetResult.Outcome.NOT_HELD);
        prism.srm.domain.LimitRecord unproven =
                prism.srm.domain.LimitRecord.intended(absent, "demo", spec.effective(), "1-1");
        assertThat(linux.recover(unproven).ownershipProven()).isFalse();
        assertThat(linux.clearOwned(unproven).result()).isEqualTo(prism.srm.platform.ClearResult.Outcome.NOT_OWNED);
        assertThat(linux.releaseAll(java.time.Duration.ofSeconds(1)))
                .isEqualTo(prism.srm.platform.ReleaseResult.NOTHING);
    }

    /** Making a controller touches nothing native until it is used, so every one can be made here. */
    @Test
    void everySystemGetsItsOwnProcessController() {
        assertThat(HostPlatform.controllerFor(HostSystem.LINUX, () -> 0))
                .isInstanceOf(prism.srm.platform.linux.LinuxProcessController.class);
        assertThat(HostPlatform.controllerFor(HostSystem.MACOS, () -> 0))
                .isInstanceOf(prism.srm.platform.macos.MacosProcessController.class);
        assertThat(HostPlatform.controllerFor(HostSystem.OTHER, () -> 0))
                .isInstanceOf(UnavailableProcessController.class);
        assertThat(HostPlatform.controllerFor(HostSystem.OTHER, () -> 0)
                        .capabilities()
                        .values())
                .allMatch(capability -> !capability.available());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void windowsGetsItsController() {
        assertThat(HostPlatform.controllerFor(HostSystem.WINDOWS, () -> 0))
                .isInstanceOf(prism.srm.platform.windows.WindowsProcessController.class);
    }

    /** ADR-0015 §3: the Windows controller loads kernel32 when it is made, so it is checked on Windows only. */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void windowsRestoresAPriority() {
        assertThat(HostPlatform.controllerFor(HostSystem.WINDOWS, () -> 0).priorityRestorability())
                .isEqualTo(prism.srm.domain.Restorability.RESTORABLE);
    }

    /** ADR-0015 §3 and §7: fixed tables, read from nothing; a change needs evidence and an ADR note. */
    @Test
    void noOtherSystemRestoresAPriorityAndOnlyWindowsAndLinuxHaveValidatedMemorySignals() {
        for (HostSystem system : new HostSystem[] {HostSystem.LINUX, HostSystem.MACOS, HostSystem.OTHER}) {
            assertThat(HostPlatform.controllerFor(system, () -> 0).priorityRestorability())
                    .as("%s", system)
                    .isEqualTo(prism.srm.domain.Restorability.NOT_REVERSIBLE);
        }
        assertThat(HostPlatform.oomSignalStatus(HostSystem.WINDOWS))
                .isEqualTo(prism.srm.domain.OomSignalStatus.VALIDATED);
        assertThat(HostPlatform.oomSignalStatus(HostSystem.LINUX))
                .as("validated on the reviewed evidence of ADR-0015 section 7")
                .isEqualTo(prism.srm.domain.OomSignalStatus.VALIDATED);
        assertThat(HostPlatform.oomSignalStatus(HostSystem.MACOS))
                .isEqualTo(prism.srm.domain.OomSignalStatus.UNVALIDATED);
        assertThat(HostPlatform.oomSignalStatus(HostSystem.OTHER))
                .isEqualTo(prism.srm.domain.OomSignalStatus.UNVALIDATED);
    }

    @ParameterizedTest
    @EnumSource(HostSystem.class)
    void theSettingsFolderIsBelowTheHomeFolderAndApartFromTheLogs(HostSystem system) {
        assertThat(folders(system).settings()).startsWithRaw(HOME);
        assertThat(folders(system).settings()).isNotEqualTo(folders(system).logs());
    }
}
