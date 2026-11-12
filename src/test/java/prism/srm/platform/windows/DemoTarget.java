package prism.srm.platform.windows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import prism.srm.demo.DemoWorkload;
import prism.srm.domain.ProcessIdentity;

/**
 * The milestone 6 demo workload as a child of the test, the only process the control tests act on
 * (SAFE-6): started from the compiled classes with the JDK that runs the tests, identified by its
 * PID and the start time the JDK reads, and terminated in {@link #close()} if it still runs.
 */
final class DemoTarget implements AutoCloseable {

    private final Process process;
    private final ProcessIdentity identity;

    private DemoTarget(Process process) {
        this.process = process;
        this.identity = new ProcessIdentity(
                process.pid(), process.toHandle().info().startInstant().orElseThrow());
    }

    /** Starts the workload and returns once it has said that it runs. */
    static DemoTarget start(String... arguments) throws IOException, URISyntaxException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(DemoWorkload.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", classes, DemoWorkload.class.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process process = builder.start();
        BufferedReader output =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
        String line;
        while ((line = output.readLine()) != null) {
            if (line.startsWith("event=start")) {
                Thread drain = new Thread(() -> drain(output), "demo-target-output");
                drain.setDaemon(true);
                drain.start();
                return new DemoTarget(process);
            }
        }
        process.destroyForcibly();
        throw new IllegalStateException("The demo workload ended its output before it started");
    }

    ProcessIdentity identity() {
        return identity;
    }

    Process process() {
        return process;
    }

    /** The CPU time the workload has used so far, as the JDK reads it. */
    Duration cpu() {
        return process.toHandle().info().totalCpuDuration().orElseThrow();
    }

    boolean exitsWithin(Duration bound) throws InterruptedException {
        return process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void drain(BufferedReader output) {
        try {
            while (output.readLine() != null) {
                // The status lines are not needed; reading them keeps the pipe from filling.
            }
        } catch (IOException ended) {
            // The workload exited.
        }
    }
}
