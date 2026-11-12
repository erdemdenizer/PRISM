package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read-only cgroup file reader (S2) over folders of the test's own standing in for {@code /sys/fs/cgroup} and
 * {@code /proc}: each read answers what it found, and a path that could leave the cgroup root is refused before any
 * file is opened. The reader has no way to write.
 */
class CgroupFilesTest {

    private static final String APP = "/user.slice/user-1001.slice/user@1001.service/app.slice";

    @TempDir
    Path temp;

    private Path cgroups;
    private Path proc;
    private CgroupFiles files;

    @BeforeEach
    void layout() throws IOException {
        cgroups = Files.createDirectories(temp.resolve("cgroup"));
        proc = Files.createDirectories(temp.resolve("proc"));
        files = new CgroupFiles(cgroups, proc);
        Path app = Files.createDirectories(cgroups.resolve(APP.substring(1)));
        write(app.resolve("cgroup.controllers"), "cpu memory pids\n");
        write(app.resolve("cgroup.subtree_control"), "memory pids\n");
        Path scope = Files.createDirectories(app.resolve("prism-ci1.scope"));
        write(scope.resolve("cpu.max"), "20000 100000\n");
        write(scope.resolve("memory.max"), "max\n");
        write(scope.resolve("memory.high"), "max\n");
        write(scope.resolve("cgroup.procs"), "2666\n2676\n");
        write(
                Files.createDirectories(proc.resolve("self")).resolve("mountinfo"),
                "36 30 0:30 / /sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw,nsdelegate\n");
        write(Files.createDirectories(proc.resolve("2666")).resolve("cgroup"), "0::" + APP + "/prism-ci1.scope\n");
        write(temp.resolve("secret"), "outside the roots\n");
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void eachFileIsReadAndParsed() {
        assertThat(files.unifiedMount().value()).contains("/sys/fs/cgroup");
        assertThat(files.v1Mounted().value()).contains(false);
        assertThat(files.processCgroup(2666).value()).contains(APP + "/prism-ci1.scope");
        assertThat(files.controllers(APP).value()).contains(Set.of("cpu", "memory", "pids"));
        assertThat(files.subtreeControl(APP).value()).contains(Set.of("memory", "pids"));
        String scope = APP + "/prism-ci1.scope";
        assertThat(files.cpuMax(scope).value())
                .hasValueSatisfying(max -> assertThat(max.text()).isEqualTo("20000 100000"));
        assertThat(files.memoryMax(scope).value()).contains(OptionalLong.empty());
        assertThat(files.memoryHigh(scope).value()).contains(OptionalLong.empty());
        assertThat(files.procs(scope).value()).contains(List.of(2666, 2676));
    }

    @Test
    void whatIsNotThereIsMissingAndWhatIsMalformedFailed() throws IOException {
        assertThat(files.cpuMax(APP).status()).isEqualTo(ProcFs.Status.MISSING);
        assertThat(files.processCgroup(4242).status()).isEqualTo(ProcFs.Status.MISSING);
        write(cgroups.resolve(APP.substring(1)).resolve("cpu.max"), "garbage\n");
        assertThat(files.cpuMax(APP).status()).isEqualTo(ProcFs.Status.FAILED);
    }

    @Test
    void aPathThatCouldLeaveTheRootIsRefusedBeforeAnyRead() {
        for (String bad : List.of("relative", "/../secret", "/user.slice/../../secret", "/a//b", "/a/./b", "", "/a/")) {
            ProcFs.Reading<Set<String>> reading = files.controllers(bad);
            assertThat(reading.status()).as(bad).isEqualTo(ProcFs.Status.FAILED);
            assertThat(reading.detail()).as(bad).contains("not a cgroup path");
        }
        assertThat(files.processCgroup(0).status()).isEqualTo(ProcFs.Status.FAILED);
        assertThat(files.processCgroup(-1).status()).isEqualTo(ProcFs.Status.FAILED);
    }

    @Test
    void theReaderHasNoMethodThatWrites() {
        for (var method : CgroupFiles.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(name)
                    .as(method.getName())
                    .doesNotContain("write")
                    .doesNotContain("set")
                    .doesNotContain("create")
                    .doesNotContain("delete")
                    .doesNotContain("remove")
                    .doesNotContain("kill")
                    .doesNotContain("move");
        }
    }
}
