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
                .hasValueSatisfying(why -> assertThat(why).contains("a scope with PRISM's name"));
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
                .hasValueSatisfying(r -> assertThat(r).contains("scope with PRISM's name"));
        assertThat(LinuxNativeReadiness.eligibility(harness, HARNESS, Optional.empty(), UID))
                .contains("the process's cgroup could not be read");
        assertThat(LinuxNativeReadiness.eligibility(harness, HARNESS, Optional.of(MANAGER), UID))
                .as("the manager's own cgroup, not a unit below it")
                .isPresent();
    }

    // ----- The desktop policy (built for the day K16 is lifted; the product does not use it) -----

    private static final long PRISM_PID = 9_100;
    private static final long PRISM_PARENT = 9_050;
    private static final long PRISM_GRANDPARENT = 1_830;

    private static LinuxNativeReadiness.EligibilityPolicy desktopOf(long own, Set<Long> ancestors) {
        return LinuxNativeReadiness.EligibilityPolicy.desktopExcluding(
                () -> new LinuxNativeReadiness.EligibilityPolicy.Excluded(own, ancestors));
    }

    private static ProcessIdentity pid(long pid) {
        return new ProcessIdentity(pid, Instant.ofEpochMilli(1_791_970_282_990L));
    }

    @Test
    void theDesktopPolicyAdmitsAnyProcessButPrismAndTheProcessesItRunsUnder() {
        LinuxNativeReadiness.EligibilityPolicy desktop = desktopOf(PRISM_PID, Set.of(PRISM_PARENT, PRISM_GRANDPARENT));
        String app = MANAGER + "/app.slice/app-gnome-firefox-4711.scope";

        assertThat(desktop.admitsNone()).as("unlike the product's policy").isFalse();
        assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.of(app), UID))
                .as("a process the harness did not start")
                .isEmpty();
        assertThat(LinuxNativeReadiness.eligibility(desktop, pid(PRISM_PID), Optional.of(app), UID))
                .contains(LinuxNativeReadiness.PRISM_ITSELF);
        assertThat(LinuxNativeReadiness.eligibility(desktop, pid(PRISM_PARENT), Optional.of(app), UID))
                .contains(LinuxNativeReadiness.PRISM_ANCESTOR);
        assertThat(LinuxNativeReadiness.eligibility(desktop, pid(PRISM_GRANDPARENT), Optional.of(app), UID))
                .as("an ancestor further up")
                .contains(LinuxNativeReadiness.PRISM_ANCESTOR);
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, new ProcessIdentity(PRISM_PARENT, Instant.EPOCH), Optional.of(app), UID))
                .as("compared by PID alone: a process that took an ancestor's PID is refused too, the safe side")
                .contains(LinuxNativeReadiness.PRISM_ANCESTOR);
    }

    /** A walk that did not reach the top (longer than the bound) refuses every process: it fails closed. */
    @Test
    void anAncestorWalkThatDidNotEndRefusesEveryProcess() {
        LinuxNativeReadiness.EligibilityPolicy desktop = LinuxNativeReadiness.EligibilityPolicy.desktopExcluding(
                () -> new LinuxNativeReadiness.EligibilityPolicy.Excluded(PRISM_PID, Set.of(PRISM_PARENT), false));

        assertThat(desktop.refusal(HARNESS)).contains(LinuxNativeReadiness.ANCESTORS_UNKNOWN);
        assertThat(desktop.refusal(pid(PRISM_GRANDPARENT))).contains(LinuxNativeReadiness.ANCESTORS_UNKNOWN);
    }

    /** The policy reads PRISM's ancestors again at every call: a process can get a new parent when its parent exits. */
    @Test
    void theDesktopPolicyReadsTheAncestorsAtEveryCall() {
        java.util.concurrent.atomic.AtomicReference<Set<Long>> ancestors =
                new java.util.concurrent.atomic.AtomicReference<>(Set.of(PRISM_PARENT));
        LinuxNativeReadiness.EligibilityPolicy desktop = LinuxNativeReadiness.EligibilityPolicy.desktopExcluding(
                () -> new LinuxNativeReadiness.EligibilityPolicy.Excluded(PRISM_PID, ancestors.get()));

        assertThat(desktop.admits(pid(PRISM_GRANDPARENT))).isTrue();
        ancestors.set(Set.of(PRISM_GRANDPARENT));
        assertThat(desktop.admits(pid(PRISM_GRANDPARENT)))
                .as("PRISM was given a new parent")
                .isFalse();
        assertThat(desktop.admits(pid(PRISM_PARENT))).isTrue();
    }

    /** Every other rule still applies under the desktop policy: the user manager, PRISM's scopes, the cgroup. */
    @Test
    void theDesktopPolicyKeepsEveryOtherRule() {
        LinuxNativeReadiness.EligibilityPolicy desktop = desktopOf(PRISM_PID, Set.of(PRISM_PARENT));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of("/user.slice/user-1001.slice/session-2.scope"), UID))
                .hasValueSatisfying(r -> assertThat(r).contains("outside your session manager"));
        assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.of("/system.slice/cron.service"), UID))
                .hasValueSatisfying(r -> assertThat(r).contains("outside your session manager"));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop,
                        HARNESS,
                        Optional.of(MANAGER + "/app.slice/prism-srm-limit-2071-1791970282990.scope/sub"),
                        UID))
                .hasValueSatisfying(r -> assertThat(r).contains("scope with PRISM's name"));
        assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.empty(), UID))
                .contains("the process's cgroup could not be read");
    }

    /**
     * Risk D17: the desktop policy admits only processes in the user manager's app.slice, where systemd's desktop
     * convention puts the applications the user runs (systemd.special(7), https://systemd.io/DESKTOP_ENVIRONMENTS/):
     * what a launcher and a terminal start, also in a nested slice.
     */
    @Test
    void theDesktopPolicyAdmitsTheApplicationsInAppSlice() {
        LinuxNativeReadiness.EligibilityPolicy desktop = desktopOf(PRISM_PID, Set.of(PRISM_PARENT));
        for (String app : List.of(
                MANAGER + "/app.slice/app-gnome-org.gnome.TextEditor-4711.scope",
                MANAGER
                        + "/app.slice/app-org.gnome.Terminal.slice/vte-spawn-0b6d5c1e-9f0e-4d38-8f4f-1f0e2b7d1c11.scope",
                MANAGER + "/app.slice/app-org.gnome.Terminal.slice/gnome-terminal-server.service",
                MANAGER + "/app.slice/app-flatpak-org.telegram.desktop-2345.scope")) {
            assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.of(app), UID))
                    .as(app)
                    .isEmpty();
        }
    }

    /**
     * Risk D17: the session bus broker, the compositor and the other session services (session.slice), background work
     * (background.slice), the user manager itself (init.scope) and a unit directly under the manager are refused, with
     * where they run.
     */
    @Test
    void theDesktopPolicyRefusesWhatIsNotADesktopApplication() {
        LinuxNativeReadiness.EligibilityPolicy desktop = desktopOf(PRISM_PID, Set.of(PRISM_PARENT));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/session.slice/dbus-broker.service"), UID))
                .contains("not a desktop application: it runs in session.slice");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/session.slice/dbus.service"), UID))
                .contains("not a desktop application: it runs in session.slice");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/session.slice/org.gnome.Shell@wayland.service"), UID))
                .contains("not a desktop application: it runs in session.slice");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/background.slice/tracker-miner-fs-3.service"), UID))
                .contains("not a desktop application: it runs in background.slice");
        assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.of(MANAGER + "/init.scope"), UID))
                .as("the manager itself keeps its own reason")
                .contains("it is your systemd user manager itself");
        assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.of(MANAGER + "/dbus.service"), UID))
                .contains("not a desktop application: it runs in dbus.service, directly under your systemd user"
                        + " manager, not in app.slice");
        assertThat(LinuxNativeReadiness.eligibility(desktop, HARNESS, Optional.of(MANAGER + "/app.slice"), UID))
                .as("the slice itself, in no unit")
                .contains("not a desktop application: it runs in app.slice itself, in no application's unit");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app-lookalike.slice/app-x.scope"), UID))
                .as("only the slice of that exact name")
                .contains("not a desktop application: it runs in app-lookalike.slice");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app.slice2/x.scope"), UID))
                .as("a name that only starts with app.slice")
                .hasValueSatisfying(r -> assertThat(r).startsWith(LinuxNativeReadiness.NOT_AN_APPLICATION));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app.slice.d/x.scope"), UID))
                .hasValueSatisfying(r -> assertThat(r).startsWith(LinuxNativeReadiness.NOT_AN_APPLICATION));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app.slice/app-org.gnome.Terminal.slice"), UID))
                .as("a slice holds no processes of its own (systemd.io/CGROUP_DELEGATION)")
                .contains("not a desktop application: it runs in app-org.gnome.Terminal.slice, in no application's"
                        + " unit");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app.slice/app-x.scope/payload"), UID))
                .as("below a unit: a subtree another program manages")
                .hasValueSatisfying(
                        r -> assertThat(r).contains("below app-x.scope").contains("another program"));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app.slice/podman.service/libpod-1/init"), UID))
                .hasValueSatisfying(r -> assertThat(r).contains("below podman.service"));
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/app.slice/app-x.mount"), UID))
                .as("only a scope or a service")
                .contains("not a desktop application: it runs in app-x.mount, which is not a scope or service unit");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktop, HARNESS, Optional.of(MANAGER + "/session.slice/app.slice/app-x.scope"), UID))
                .as("app.slice only directly under the manager")
                .contains("not a desktop application: it runs in session.slice");
    }

    /** The app.slice rule is the desktop policy's: the harness keeps admitting the processes it started anywhere. */
    @Test
    void theAppSliceRuleIsOnlyTheDesktopPolicys() {
        LinuxNativeReadiness.EligibilityPolicy harness =
                LinuxNativeReadiness.EligibilityPolicy.harnessOwned(Set.of(HARNESS));
        assertThat(harness.applicationsOnly()).isFalse();
        assertThat(LinuxNativeReadiness.EligibilityPolicy.none().applicationsOnly())
                .isFalse();
        assertThat(desktopOf(PRISM_PID, Set.of()).applicationsOnly()).isTrue();
        assertThat(LinuxNativeReadiness.eligibility(
                        harness, HARNESS, Optional.of(MANAGER + "/session.slice/run-u12.service"), UID))
                .isEmpty();
    }

    /** The user manager's own process lives in its init.scope (systemd.special(7)); no policy admits it. */
    @Test
    void theUserManagerItselfIsNeverEligible() {
        String init = MANAGER + "/init.scope";
        assertThat(LinuxNativeReadiness.eligibility(desktopOf(PRISM_PID, Set.of()), HARNESS, Optional.of(init), UID))
                .contains("it is your systemd user manager itself");
        assertThat(LinuxNativeReadiness.eligibility(
                        LinuxNativeReadiness.EligibilityPolicy.harnessOwned(Set.of(HARNESS)),
                        HARNESS,
                        Optional.of(init),
                        UID))
                .contains("it is your systemd user manager itself");
        assertThat(LinuxNativeReadiness.eligibility(
                        desktopOf(PRISM_PID, Set.of()),
                        HARNESS,
                        Optional.of(MANAGER + "/app.slice/init.scope-lookalike.scope"),
                        UID))
                .as("only the manager's own init.scope")
                .isEmpty();
    }

    /** The real desktop policy, on this machine: this JVM and the process that started it are refused. */
    @Test
    void theRealDesktopPolicyRefusesThisProcessAndItsParent() {
        LinuxNativeReadiness.EligibilityPolicy desktop = LinuxNativeReadiness.EligibilityPolicy.desktop();
        ProcessHandle self = ProcessHandle.current();

        assertThat(desktop.refusal(pid(self.pid()))).contains(LinuxNativeReadiness.PRISM_ITSELF);
        self.parent()
                .ifPresent(parent ->
                        assertThat(desktop.refusal(pid(parent.pid()))).contains(LinuxNativeReadiness.PRISM_ANCESTOR));
        assertThat(desktop.admitsNone()).isFalse();
    }
}
