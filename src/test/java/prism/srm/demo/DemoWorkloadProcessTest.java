package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The entry point as a process of its own, started from the JDK that runs the tests with the
 * compiled classes on its classpath: the exit codes and the lines a script would look for. Each
 * run is as small as it can be (no load, one second), so the suite costs a few JVM starts.
 */
class DemoWorkloadProcessTest {

    private static final long TIMEOUT_SECONDS = 60;

    @Test
    void anIdleRunEndsByItselfWithExitCodeZero() throws Exception {
        Result result = run("--cpu", "0", "--memory", "0", "--duration", "1s");

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        assertThat(result.stdout.get(0)).isEqualTo("prism-demo dev");
        assertThat(result.stdout.get(1))
                .startsWith("event=start pid=" + result.pid + " ")
                .contains("cpu=0.0 workers=0 duty=0.00 memory=0MiB duration=1s mode=idle");
        // The elapsed time is rounded from the real one; a stall between the deadline and the line can make it 2.
        assertThat(result.stdout.get(result.stdout.size() - 1))
                .matches("event=end reason=completed elapsed=[12]s exit=0");
    }

    /** DEMO-2 from the outside: the cap and the maximum duration cannot be passed by any argument. */
    @Test
    void memoryAboveTheCapIsRefusedBeforeAnythingStarts() throws Exception {
        Result result = run("--memory", "64GiB");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr.get(0)).contains("--memory 64GiB is above the safety cap of");
        assertThat(result.stderr.get(1)).isEqualTo("Try --help.");
    }

    @Test
    void aDurationAboveThirtyMinutesIsRefused() throws Exception {
        Result result = run("--duration", "31m");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).containsExactly("--duration 31m is above the maximum of 30m.", "Try --help.");
    }

    @Test
    void helpIsPrintedWithExitCodeZero() throws Exception {
        Result result = run("--help");

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        assertThat(result.stdout.get(0))
                .isEqualTo("Usage: prism-demo [--cpu <percent>] [--memory <size>] [--duration <time>] [--workers <n>]");
        assertThat(result.stdout.get(result.stdout.size() - 1)).startsWith("This machine: ");
    }

    private record Result(long pid, int exitCode, List<String> stdout, List<String> stderr) {}

    private static Result run(String... arguments) throws IOException, InterruptedException, URISyntaxException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(DemoWorkload.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", classes, DemoWorkload.class.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        // The launcher of the child would print "Picked up ..." on stderr for these; the test is
        // about what the workload prints, on any machine.
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process process = builder.start();
        try {
            // Both streams are small, so reading one to its end before the other cannot block.
            List<String> stdout = read(process.getInputStream().readAllBytes());
            List<String> stderr = read(process.getErrorStream().readAllBytes());
            assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the workload exits by itself")
                    .isTrue();
            return new Result(process.pid(), process.exitValue(), stdout, stderr);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static List<String> read(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII).lines().toList();
    }
}
