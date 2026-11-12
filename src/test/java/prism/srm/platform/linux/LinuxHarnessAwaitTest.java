package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;

/**
 * The harness's wait for a line of a started process. A process can end before the thread that reads its output has
 * handed over its last lines; on the Hyper-V Ubuntu VM (2026-10-16, a388cc0) that made T-L6 report "no child= line"
 * although the shell had printed it, and left the test's own child in its scope. Only an ended reader means no more
 * output will come.
 */
class LinuxHarnessAwaitTest {

    private static final ProcessIdentity UNUSED = new ProcessIdentity(1, Instant.EPOCH);

    @Test
    void aLineTheReaderHandsOverAfterTheProcessEndedStillCounts() throws Exception {
        Process ended = endedProcess();
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        CountDownLatch handOver = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                handOver.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            lines.add("child=4242");
        });
        reader.start();
        LinuxHarness.Started started = new LinuxHarness.Started(ended, UNUSED, lines, reader);

        Thread late = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            handOver.countDown();
        });
        late.start();

        assertThat(started.await("child=", Duration.ofSeconds(10))).isTrue();
        reader.join(10_000);
        late.join(10_000);
    }

    @Test
    void anEndedProcessWhoseOutputIsFullyReadWithoutTheLineFailsAtOnce() throws Exception {
        Process ended = endedProcess();
        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        lines.add("ready");
        Thread reader = new Thread(() -> {});
        reader.start();
        reader.join(10_000);
        LinuxHarness.Started started = new LinuxHarness.Started(ended, UNUSED, lines, reader);

        long begin = System.nanoTime();
        assertThat(started.await("child=", Duration.ofSeconds(30))).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(5));
    }

    private static Process endedProcess() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-version")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return process;
    }
}
