package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Correlating the user manager with the cgroup files (milestone 12, S3A, plan Rev 2.2 P1–P7, T-D4 read part, T-D24):
 * agreement gives readiness with adoption still unconfirmed, every disagreement is a finding, a missing library means
 * no bus is ever made, and the product policy stays "every process UNAVAILABLE".
 */
class SystemdCapabilityProbeTest {

    private static final String MANAGER_GROUP = SystemdUserBusTest.MANAGER_GROUP;
    private static final String APP = MANAGER_GROUP + "/app.slice";
    private static final String OWN = APP + "/" + SystemdUserBusTest.OWN_SCOPE;

    @TempDir
    Path temp;

    private FakeSdBus fake;
    private CgroupFiles files;
    private int busesMade;

    @BeforeEach
    void layout() throws IOException {
        Path cgroups = Files.createDirectories(temp.resolve("cgroup"));
        Path proc = Files.createDirectories(temp.resolve("proc"));
        Path app = Files.createDirectories(cgroups.resolve(APP.substring(1)));
        write(app.resolve("cgroup.controllers"), "cpu memory pids\n");
        write(app.resolve("cgroup.subtree_control"), "memory pids\n");
        write(
                Files.createDirectories(proc.resolve("self")).resolve("mountinfo"),
                "36 30 0:30 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw,nsdelegate\n");
        write(
                Files.createDirectories(proc.resolve(
                                Long.toString(ProcessHandle.current().pid())))
                        .resolve("cgroup"),
                "0::" + OWN + "\n");
        files = new CgroupFiles(cgroups, proc);
        fake = new FakeSdBus();
        SystemdUserBusTest.scriptManager(fake, MANAGER_GROUP);
        SystemdUserBusTest.scriptUnit(
                fake, "app.slice", SystemdUserBusTest.APP_SLICE_PATH, "org.freedesktop.systemd1.Slice", APP);
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnitByPID",
                List.of((int) ProcessHandle.current().pid()),
                FakeSdBus.Answer.reply("o", FakeSdBus.Item.object(SystemdUserBusTest.OWN_PATH)));
        SystemdUserBusTest.scriptUnitProperties(
                fake, SystemdUserBusTest.OWN_PATH, SystemdUserBusTest.OWN_SCOPE, "org.freedesktop.systemd1.Scope", OWN);
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private SystemdCapabilityProbe probe(LibsystemdCheck.Outcome library) {
        return new SystemdCapabilityProbe(
                library,
                api -> {
                    busesMade++;
                    return new SystemdUserBus(api);
                },
                files,
                1000);
    }

    private SystemdCapabilityProbe.Report runAvailable() {
        return probe(new LibsystemdCheck.Outcome(Optional.of(fake), "found")).run();
    }

    @Test
    void agreeingSourcesGiveReadinessWithAdoptionUnconfirmed() {
        SystemdCapabilityProbe.Report report = runAvailable();

        assertThat(report.findings()).isEmpty();
        assertThat(report.facts().systemdVersion()).contains("255.4-1ubuntu8.18");
        assertThat(report.appSliceDelegated()).contains(Set.of("cpu", "memory", "pids"));
        assertThat(report.appSliceEnabled()).contains(Set.of("memory", "pids"));
        assertThat(report.readiness().cpu().ready()).isTrue();
        assertThat(report.readiness().memoryHard().ready()).isTrue();
        assertThat(report.readiness().cpu().unconfirmed()).contains("acceptance of PIDFDs by this user manager");
        assertThat(report.productPolicy()).contains("every process UNAVAILABLE");
        assertThat(report.describe()).contains("product:").doesNotContain("finding:");
        assertThat(fake.sent)
                .allMatch(call ->
                        call.contains(".Get[") || call.contains(".GetUnit[") || call.contains(".GetUnitByPID["));
        assertThat(fake.liveMessages()).isZero();
        assertThat(fake.openBuses()).isZero();
    }

    @Test
    void withoutTheLibraryNoBusIsMadeAndNothingIsReady() {
        SystemdCapabilityProbe.Report report = probe(
                        LibsystemdCheck.Outcome.unavailable("libsystemd.so.0 is not available"))
                .run();

        assertThat(busesMade).isZero();
        assertThat(report.manager()).isEmpty();
        assertThat(report.facts().libsystemdPresent()).contains(false);
        assertThat(report.readiness().cpu().ready()).isFalse();
        assertThat(report.readiness().cpu().reasons()).contains("libsystemd (or a function of it) is not available");
        assertThat(report.library()).contains("not available");
    }

    @Test
    void eachDisagreementIsAFinding() {
        SystemdUserBusTest.scriptManager(fake, "/user.slice/user-1001.slice/user@1001.service");
        SystemdUserBusTest.scriptUnitProperties(
                fake,
                SystemdUserBusTest.OWN_PATH,
                SystemdUserBusTest.OWN_SCOPE,
                "org.freedesktop.systemd1.Scope",
                APP + "/other.scope");

        SystemdCapabilityProbe.Report report = runAvailable();

        assertThat(report.findings())
                .anyMatch(f -> f.contains("manager's control group is /user.slice/user-1001.slice"))
                .anyMatch(f -> f.contains("is not inside the manager's"))
                .anyMatch(f -> f.contains("but /proc/self/cgroup names"));
    }

    @Test
    void anUnreachableBusLeavesTheVersionUnknown() {
        fake.openCode = -SystemdUserBus.ENOMEDIUM;

        SystemdCapabilityProbe.Report report = runAvailable();

        assertThat(report.manager().orElseThrow().status()).isEqualTo(SystemdUserBus.Status.NO_BUS);
        assertThat(report.facts().systemdVersion()).isEmpty();
        assertThat(report.readiness().cpu().reasons()).anyMatch(reason -> reason.contains("no user manager reachable"));
        assertThat(fake.sent).isEmpty();
    }
}
