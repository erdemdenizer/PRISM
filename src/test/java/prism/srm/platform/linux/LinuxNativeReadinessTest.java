package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;

/**
 * The read-only readiness classification (S2, K13, K16): the facts CI-0 read make every kind ready with adoption still
 * unconfirmed; each missing or insufficient fact says why; and the product's policy refuses every process before
 * anything is asked of the system, whatever its cgroup.
 */
class LinuxNativeReadinessTest {

    @Test
    void aProcessBelowAPrismScopeIsNotEligible() {
        ProcessIdentity process = new ProcessIdentity(4242, java.time.Instant.EPOCH);
        String manager = "/user.slice/user-1000.slice/user@1000.service/app.slice/";

        assertThat(LinuxNativeReadiness.eligibility(
                        LinuxNativeReadiness.EligibilityPolicy.harnessOwned(java.util.Set.of(process)),
                        process,
                        java.util.Optional.of(manager + "prism-srm-limit-4242-17.scope/worker"),
                        1000))
                .hasValueSatisfying(why -> assertThat(why).contains("a scope with Prism's name"));
    }

    private static final long UID = 1001;
    private static final String MANAGER = "/user.slice/user-1001.slice/user@1001.service";
    private static final ProcessIdentity HARNESS = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L));

    /** What CI-0 and CI-1 read on GitHub's ubuntu-24.04 runner. */
    private static LinuxNativeReadiness.Facts runner() {
        return new LinuxNativeReadiness.Facts(
                Optional.of("/sys/fs/cgroup"),
                Optional.of(false),
                Optional.of("255.4-1ubuntu8.17"),
                Optional.of(Set.of("cpu", "memory", "pids")),
                Optional.of(true));
    }

    private static LinuxNativeReadiness.Facts with(
            Optional<String> mount,
            Optional<Boolean> v1,
            Optional<String> version,
            Optional<Set<String>> controllers,
            Optional<Boolean> lib) {
        return new LinuxNativeReadiness.Facts(mount, v1, version, controllers, lib);
    }

    @Test
    void theRunnersFactsMakeEveryKindReadyButAdoptionStaysUnconfirmed() {
        LinuxNativeReadiness.SystemReadiness readiness = LinuxNativeReadiness.classify(runner());
        for (LinuxNativeReadiness.Readiness kind :
                List.of(readiness.cpu(), readiness.memoryThrottle(), readiness.memoryHard())) {
            assertThat(kind.ready()).isTrue();
            assertThat(kind.reasons()).isEmpty();
            assertThat(kind.unconfirmed()).anySatisfy(u -> assertThat(u).contains("PIDFDs"));
        }
    }

    @Test
    void eachMissingFactSaysWhy() {
        LinuxNativeReadiness.Facts f = runner();
        assertThat(LinuxNativeReadiness.classify(with(
                                Optional.empty(),
                                f.v1Mounted(),
                                f.systemdVersion(),
                                f.appSliceControllers(),
                                f.libsystemdPresent()))
                        .cpu()
                        .reasons())
                .anySatisfy(r -> assertThat(r).contains("cgroup v2 mount could not be read"));
        assertThat(LinuxNativeReadiness.classify(with(
                                Optional.of("/sys/fs/cgroup/unified"),
                                Optional.of(true),
                                f.systemdVersion(),
                                f.appSliceControllers(),
                                f.libsystemdPresent()))
                        .cpu()
                        .reasons())
                .anySatisfy(r -> assertThat(r).contains("hybrid or legacy"))
                .anySatisfy(r -> assertThat(r).contains("cgroup v1 hierarchy is mounted"));
        assertThat(LinuxNativeReadiness.classify(with(
                                f.unifiedMount(),
                                Optional.empty(),
                                f.systemdVersion(),
                                f.appSliceControllers(),
                                f.libsystemdPresent()))
                        .cpu()
                        .ready())
                .isFalse();
        assertThat(LinuxNativeReadiness.classify(with(
                                f.unifiedMount(),
                                f.v1Mounted(),
                                Optional.empty(),
                                f.appSliceControllers(),
                                f.libsystemdPresent()))
                        .memoryThrottle()
                        .reasons())
                .anySatisfy(r -> assertThat(r).contains("version is unknown"));
        assertThat(LinuxNativeReadiness.classify(with(
                                f.unifiedMount(),
                                f.v1Mounted(),
                                f.systemdVersion(),
                                Optional.empty(),
                                f.libsystemdPresent()))
                        .memoryHard()
                        .reasons())
                .anySatisfy(r -> assertThat(r).contains("could not be read"));
        assertThat(LinuxNativeReadiness.classify(with(
                                f.unifiedMount(),
                                f.v1Mounted(),
                                f.systemdVersion(),
                                f.appSliceControllers(),
                                Optional.empty()))
                        .cpu()
                        .reasons())
                .anySatisfy(r -> assertThat(r).contains("libsystemd was not checked"));
        assertThat(LinuxNativeReadiness.classify(with(
                                f.unifiedMount(),
                                f.v1Mounted(),
                                f.systemdVersion(),
                                f.appSliceControllers(),
                                Optional.of(false)))
                        .cpu()
                        .reasons())
                .anySatisfy(r -> assertThat(r).contains("libsystemd (or a function of it) is not available"));
    }

    @Test
    void aControllerThatIsNotDelegatedTakesOnlyItsKinds() {
        LinuxNativeReadiness.Facts f = runner();
        LinuxNativeReadiness.SystemReadiness noCpu = LinuxNativeReadiness.classify(with(
                f.unifiedMount(),
                f.v1Mounted(),
                f.systemdVersion(),
                Optional.of(Set.of("memory", "pids")),
                f.libsystemdPresent()));
        assertThat(noCpu.cpu().reasons()).anySatisfy(r -> assertThat(r).contains("cpu controller is not delegated"));
        assertThat(noCpu.memoryThrottle().ready()).isTrue();
        LinuxNativeReadiness.SystemReadiness noMemory = LinuxNativeReadiness.classify(with(
                f.unifiedMount(),
                f.v1Mounted(),
                f.systemdVersion(),
                Optional.of(Set.of("cpu", "pids")),
                f.libsystemdPresent()));
        assertThat(noMemory.cpu().ready()).isTrue();
        assertThat(noMemory.memoryThrottle().ready()).isFalse();
        assertThat(noMemory.memoryHard().ready()).isFalse();
    }

    @Test
    void anOlderSystemdIsRefusedForAdoptionAndForAHardMemoryLimitBelow253() {
        LinuxNativeReadiness.Facts f = runner();
        for (String version : List.of("252.33-1~deb12u1", "254.5")) {
            LinuxNativeReadiness.SystemReadiness old = LinuxNativeReadiness.classify(with(
                    f.unifiedMount(),
                    f.v1Mounted(),
                    Optional.of(version),
                    f.appSliceControllers(),
                    f.libsystemdPresent()));
            assertThat(old.cpu().reasons())
                    .as(version)
                    .anySatisfy(r -> assertThat(r).contains("needs systemd 255"));
        }
        LinuxNativeReadiness.SystemReadiness v252 = LinuxNativeReadiness.classify(with(
                f.unifiedMount(), f.v1Mounted(), Optional.of("252"), f.appSliceControllers(), f.libsystemdPresent()));
        assertThat(v252.memoryHard().reasons()).anySatisfy(r -> assertThat(r).contains("OOMPolicy=continue"));
        assertThat(v252.memoryThrottle().reasons())
                .noneSatisfy(r -> assertThat(r).contains("OOMPolicy"));
    }

    @Test
    void versionStringsAreReadStrictly() {
        assertThat(LinuxNativeReadiness.majorVersion("255.4-1ubuntu8.17")).isEqualTo(OptionalInt.of(255));
        assertThat(LinuxNativeReadiness.majorVersion("256~rc1")).isEqualTo(OptionalInt.of(256));
        assertThat(LinuxNativeReadiness.majorVersion("1023")).isEqualTo(OptionalInt.of(1023));
        for (String bad : List.of("", "systemd 255", "99", "0255", "255 4", "v255", "25a")) {
            assertThat(LinuxNativeReadiness.majorVersion(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void theProductsPolicyRefusesEveryProcessWhateverItsCgroup() {
        LinuxNativeReadiness.EligibilityPolicy none = LinuxNativeReadiness.EligibilityPolicy.none();
        assertThat(LinuxNativeReadiness.eligibility(
                        none, HARNESS, Optional.of(MANAGER + "/app.slice/app-gnome-firefox-1.scope"), UID))
                .contains("Linux native limits are not validated for desktop applications");
        assertThat(LinuxNativeReadiness.eligibility(none, HARNESS, Optional.empty(), UID))
                .contains("Linux native limits are not validated for desktop applications");
    }

    @Test
    void aHarnessPolicyAdmitsOnlyItsOwnProcessesAndOnlyUnderTheSessionManager() {
        LinuxNativeReadiness.EligibilityPolicy harness =
                LinuxNativeReadiness.EligibilityPolicy.harnessOwned(Set.of(HARNESS));
        assertThat(LinuxNativeReadiness.eligibility(
                        harness, HARNESS, Optional.of(MANAGER + "/app.slice/prism-ci2-1-src.scope"), UID))
                .isEmpty();
        assertThat(LinuxNativeReadiness.eligibility(
                        harness,
                        new ProcessIdentity(2_071, Instant.ofEpochMilli(1)),
                        Optional.of(MANAGER + "/app.slice/x.scope"),
                        UID))
                .as("the same PID with another start time is not the harness's process")
                .isPresent();
        assertThat(LinuxNativeReadiness.eligibility(
                        harness, HARNESS, Optional.of("/system.slice/hosted-compute-agent.service"), UID))
                .hasValueSatisfying(r -> assertThat(r).contains("outside your session manager"));
        assertThat(LinuxNativeReadiness.eligibility(
                        harness, HARNESS, Optional.of("/user.slice/user-1001.slice/session-2.scope"), UID))
                .hasValueSatisfying(r -> assertThat(r).contains("outside your session manager"));
        assertThat(LinuxNativeReadiness.eligibility(
                        harness,
                        HARNESS,
                        Optional.of("/user.slice/user-1002.slice/user@1002.service/app.slice/a.scope"),
                        UID))
                .as("another user's manager")
                .hasValueSatisfying(r -> assertThat(r).contains("outside your session manager"));
        assertThat(LinuxNativeReadiness.eligibility(
                        harness,
                        HARNESS,
                        Optional.of(MANAGER + "/app.slice/prism-srm-limit-2071-1791970282990.scope"),
                        UID))
                .hasValueSatisfying(r -> assertThat(r).contains("scope with Prism's name"));
        assertThat(LinuxNativeReadiness.eligibility(harness, HARNESS, Optional.empty(), UID))
                .contains("the process's cgroup could not be read");
        assertThat(LinuxNativeReadiness.eligibility(harness, HARNESS, Optional.of(MANAGER), UID))
                .as("the manager's own cgroup, not a unit below it")
                .isPresent();
    }
}
