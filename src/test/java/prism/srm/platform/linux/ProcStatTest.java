package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The stat file of proc_pid_stat(5), and the reads of {@link ProcFs} on real files. */
class ProcStatTest {

    /** A line as the kernel writes it (fields 1 to 24). */
    private static String line(String name, char state, long flags, int nice, long start) {
        return "4242 (" + name + ") " + state + " 1 4242 4242 0 -1 " + flags + " 10 0 0 0 7 3 0 0 " + (20 + nice) + " "
                + nice + " 12 0 " + start + " 123456 789\n";
    }

    @Test
    void theFieldsAreReadByTheirNumbers() {
        ProcStat stat = ProcStat.parse(line("java", 'S', 0x400100, -5, 98_765)).orElseThrow();

        assertThat(stat).isEqualTo(new ProcStat("java", 'S', 0x400100, -5, 98_765));
    }

    /** The name may hold spaces and parentheses: the fields are counted from the last closing one. */
    @ParameterizedTest
    @ValueSource(strings = {"Web Content", "a) (b", "x ) T 9 9", "(", ")", ""})
    void anyNameIsReadWhole(String name) {
        ProcStat stat = ProcStat.parse(line(name, 'R', 0, 10, 77)).orElseThrow();

        assertThat(stat.name()).isEqualTo(name);
        assertThat(stat.state()).isEqualTo('R');
        assertThat(stat.nice()).isEqualTo(10);
        assertThat(stat.startTicks()).isEqualTo(77);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "4242 java S 1",
                "4242 (java) S 1 2 3",
                "4242 (java) SS 1 4242 4242 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 5 1 1"
            })
    void whatHasNotTheFormIsNotRead(String contents) {
        assertThat(ProcStat.parse(contents)).isEmpty();
    }

    @Test
    void theStatesThatMatterAreToldApart() {
        assertThat(ProcStat.parse(line("a", 'Z', 0, 0, 1)).orElseThrow().ended())
                .isTrue();
        assertThat(ProcStat.parse(line("a", 'X', 0, 0, 1)).orElseThrow().ended())
                .isTrue();
        assertThat(ProcStat.parse(line("a", 'T', 0, 0, 1)).orElseThrow().stopped())
                .isTrue();
        assertThat(ProcStat.parse(line("a", 't', 0, 0, 1)).orElseThrow().stopped())
                .isTrue();
        for (char running : List.of('R', 'S', 'D', 'I')) {
            ProcStat stat = ProcStat.parse(line("a", running, 0, 0, 1)).orElseThrow();
            assertThat(stat.ended() || stat.stopped())
                    .as(String.valueOf(running))
                    .isFalse();
        }
    }

    /** PF_KTHREAD is 0x00200000 in include/linux/sched.h. */
    @Test
    void aKernelThreadIsKnownByItsFlag() {
        assertThat(ProcStat.PF_KTHREAD).isEqualTo(0x00200000L);
        assertThat(ProcStat.parse(line("kworker/0:1", 'I', 0x04208060, 0, 1))
                        .orElseThrow()
                        .kernelThread())
                .isTrue();
        assertThat(ProcStat.parse(line("bash", 'S', 0x00400100, 0, 1))
                        .orElseThrow()
                        .kernelThread())
                .isFalse();
    }

    @Test
    void theReadsAnswerWhatTheyFound(@TempDir Path root) throws IOException {
        ProcFs proc = new ProcFs(root);
        Files.writeString(root.resolve("stat"), "cpu 1 2 3\nbtime 1790000000\n", StandardCharsets.UTF_8);
        Path task = root.resolve("4242").resolve("task");
        for (String tid : List.of("4250", "4242", "4243")) {
            Files.createDirectories(task.resolve(tid));
        }
        Files.createDirectories(task.resolve("not-a-thread"));
        Files.writeString(root.resolve("4242").resolve("stat"), line("demo", 'S', 0, 0, 5), StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("5000"));
        Files.writeString(root.resolve("5000").resolve("stat"), "garbage", StandardCharsets.UTF_8);

        assertThat(proc.stat(4242).status()).isEqualTo(ProcFs.Status.FOUND);
        assertThat(proc.stat(4242).get().name()).isEqualTo("demo");
        assertThat(proc.stat(4343).status()).isEqualTo(ProcFs.Status.MISSING);
        assertThat(proc.stat(5000).status()).isEqualTo(ProcFs.Status.FAILED);
        assertThat(proc.taskIds(4242).get()).containsExactly(4242, 4243, 4250);
        assertThat(proc.taskIds(4343).status()).isEqualTo(ProcFs.Status.MISSING);
        assertThat(proc.threadStat(4242, 4243).status()).isEqualTo(ProcFs.Status.MISSING);
    }
}
