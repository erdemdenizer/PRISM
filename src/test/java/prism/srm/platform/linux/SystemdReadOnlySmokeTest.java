package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Read-only smoke test on a real Linux system (milestone 12, S3A, plan Rev 2.2 T-L0, T-L0b): the probe loads libsystemd
 * only after its symbol check, talks to the user manager with read-only methods, and leaves the system as it found it:
 * no socket left open after repeated probes, the test JVM's own cgroup unchanged, and no Prism scope in the user
 * manager's unit list. Units that other programs start or stop meanwhile (a live desktop session does) are printed, not
 * failed on. Whatever the system lacks (no user bus, no libsystemd) must come back as a reason, never as a crash. The
 * report is printed for the evidence.
 */
@EnabledOnOs(OS.LINUX)
class SystemdReadOnlySmokeTest {

    @TempDir
    Path temp;

    private static long uid() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
            if (line.startsWith("Uid:")) {
                return Long.parseLong(line.substring(4).strip().split("\\s+")[0]);
            }
        }
        throw new IOException("no Uid line in /proc/self/status");
    }

    private static long openSockets() throws IOException {
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            return fds.filter(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).toString().startsWith("socket:");
                        } catch (IOException gone) {
                            return false;
                        }
                    })
                    .count();
        }
    }

    /** The user manager's units as systemctl lists them, or empty where systemctl cannot reach it in 20 s. */
    private Optional<List<String>> userUnits(String label) throws InterruptedException {
        Path out = temp.resolve("units-" + label + ".txt");
        try {
            Process systemctl = new ProcessBuilder(
                            "systemctl", "--user", "list-units", "--all", "--plain", "--no-legend", "--no-pager")
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile())
                    .start();
            if (!systemctl.waitFor(20, TimeUnit.SECONDS)) {
                systemctl.destroyForcibly();
                System.out.println("systemctl --user did not answer in 20 s");
                return Optional.empty();
            }
            String text = Files.readString(out, StandardCharsets.UTF_8);
            if (systemctl.exitValue() != 0) {
                System.out.println("systemctl --user not usable here: " + text.strip());
                return Optional.empty();
            }
            return Optional.of(text.lines()
                    .map(l -> l.strip().split("\\s+")[0])
                    .filter(u -> !u.isEmpty())
                    .sorted()
                    .toList());
        } catch (IOException noSystemctl) {
            System.out.println("systemctl not usable: " + noSystemctl);
            return Optional.empty();
        }
    }

    @Test
    void theProbeReadsAndLeavesEverythingAsItWas() throws IOException, InterruptedException {
        CgroupFiles files = new CgroupFiles(Path.of("/sys/fs/cgroup"), Path.of("/proc"));
        String cgroupBefore = Files.readString(Path.of("/proc/self/cgroup"), StandardCharsets.UTF_8);
        Optional<List<String>> unitsBefore = userUnits("before");
        long uid = uid();

        SystemdCapabilityProbe.Report first =
                SystemdCapabilityProbe.onThisSystem(files, uid).run();
        long socketsAfterFirst = openSockets();
        SystemdCapabilityProbe.onThisSystem(files, uid).run();
        SystemdCapabilityProbe.onThisSystem(files, uid).run();
        long socketsAfterThird = openSockets();
        Optional<List<String>> unitsAfter = userUnits("after");

        System.out.println("=== S3A read-only probe report (uid " + uid + ") ===");
        System.out.println(first.describe());
        System.out.println("sockets after probe 1: " + socketsAfterFirst + ", after probe 3: " + socketsAfterThird);
        if (unitsBefore.isPresent() && unitsAfter.isPresent()) {
            List<String> added = new ArrayList<>(unitsAfter.get());
            added.removeAll(unitsBefore.get());
            List<String> removed = new ArrayList<>(unitsBefore.get());
            removed.removeAll(unitsAfter.get());
            System.out.println("user units: " + unitsBefore.get().size() + " before, "
                    + unitsAfter.get().size() + " after; added " + added + ", removed " + removed);
            assertThat(added).as("no Prism scope created").noneMatch(u -> u.startsWith("prism-srm-limit-"));
            assertThat(unitsAfter.get()).noneMatch(u -> u.startsWith("prism-srm-limit-"));
        } else {
            System.out.println("user units: not listable here");
        }
        System.out.println("=== end of report ===");

        assertThat(socketsAfterThird)
                .as("no connection left open by repeated probes")
                .isEqualTo(socketsAfterFirst);
        assertThat(Files.readString(Path.of("/proc/self/cgroup"), StandardCharsets.UTF_8))
                .as("the test JVM was not moved")
                .isEqualTo(cgroupBefore);
        assertThat(first.productPolicy()).contains("every process UNAVAILABLE");
        first.manager().flatMap(SystemdUserBus.Reading::value).ifPresent(manager -> {
            assertThat(LinuxNativeReadiness.majorVersion(manager.version())).isPresent();
            assertThat(manager.controlGroup()).startsWith("/");
        });
        if (first.manager().isEmpty()) {
            assertThat(first.library()).as("no bus is made without the library").isNotBlank();
        }
        if (Boolean.getBoolean(LinuxNativeLimitIntegrationTest.REQUIRED)) {
            // In the linux-native-validation job the read-only path must have been exercised, not reported away.
            assertThat(first.manager().flatMap(SystemdUserBus.Reading::value))
                    .as("the user manager answered")
                    .isPresent();
            assertThat(unitsBefore).as("the user units were listed").isPresent();
        }
    }
}
