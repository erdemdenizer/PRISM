package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LIFE-1 in a real process: a stuck step does not keep Prism alive past the bound. */
class ShutdownRealProcessTest {

    private static final long MOST_SECONDS = 45;

    /**
     * The child runs with a bound of 1 s and the clock starts once it is up, so the start of a JVM is not in
     * this. It stays under the 5 s the application runs with: a sequence that ignored its bound shows here.
     */
    private static final long BOUND_AND_MARGIN_MILLIS = 4_500;

    @TempDir
    Path journal;

    @Test
    void aSamplingCycleStuckInTheSystemDoesNotKeepTheProcessAlive() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(
                        java,
                        "-Xmx64m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        StuckShutdownChild.class.getName(),
                        journal.toString())
                .redirectErrorStream(true)
                .start();
        try {
            // The child's log lines share its output and may come before the marker: on a guest with two
            // processors "Monitoring started" was written first in two of three runs.
            BufferedReader output =
                    new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.US_ASCII));
            String line;
            do {
                line = output.readLine();
            } while (line != null && !line.equals("stuck"));
            assertThat(line).as("the child reached the stuck sampling cycle").isEqualTo("stuck");
            long shutdownAsked = System.nanoTime();

            boolean ended = child.waitFor(MOST_SECONDS, TimeUnit.SECONDS);

            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - shutdownAsked);
            assertThat(ended).as("the process ended by itself").isTrue();
            assertThat(child.exitValue()).isZero();
            assertThat(tookMillis).as("within the bound and a margin").isLessThan(BOUND_AND_MARGIN_MILLIS);
        } finally {
            child.destroyForcibly();
        }
    }
}
