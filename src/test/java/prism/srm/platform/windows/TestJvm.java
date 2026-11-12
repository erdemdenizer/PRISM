package prism.srm.platform.windows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import prism.srm.domain.ProcessIdentity;

/**
 * A JVM of the test classes started by a native-limit integration test (SAFE-6: the test's own
 * process, ended by {@link #close()} if it still runs): its lines are read on a daemon thread so that
 * waiting for one is bounded, and lines can be written to its input.
 */
final class TestJvm implements AutoCloseable {

    private final Process process;
    private final Writer input;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private TestJvm(Process process) {
        this.process = process;
        this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII);
        BufferedReader output =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
        Thread reader = new Thread(
                () -> {
                    try {
                        String line;
                        while ((line = output.readLine()) != null) {
                            lines.add(line);
                        }
                    } catch (IOException ended) {
                        // The JVM exited.
                    }
                },
                "test-jvm-output");
        reader.setDaemon(true);
        reader.start();
    }

    static TestJvm start(Class<?> main, String... arguments) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(
                java,
                "-Xmx64m",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                main.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        return new TestJvm(builder.start());
    }

    ProcessIdentity identity() {
        return new ProcessIdentity(
                process.pid(), process.toHandle().info().startInstant().orElseThrow());
    }

    Process process() {
        return process;
    }

    /** The first line that starts with the prefix, within the bound; the lines before it are printed. */
    String await(String prefix, Duration bound) throws InterruptedException {
        long end = System.nanoTime() + bound.toNanos();
        while (true) {
            String line = lines.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new IllegalStateException("No line starting with " + prefix + " within " + bound);
            }
            if (line.startsWith(prefix)) {
                return line;
            }
            if (line.startsWith("not-")) {
                // The stand-in said why it could not do what was asked: fail now, not at the bound.
                throw new IllegalStateException("Instead of " + prefix + ": " + line);
            }
            System.out.println("  [" + process.pid() + "] " + line);
        }
    }

    void say(String line) throws IOException {
        input.write(line + "\n");
        input.flush();
    }

    /** Kills it as Task Manager would and waits for its exit. */
    void kill() throws InterruptedException {
        process.destroyForcibly();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("The JVM " + process.pid() + " did not exit after it was killed");
        }
    }

    /** Ends it and every process it started, all of which the test started through it (SAFE-6). */
    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /** The share of the whole machine a process used over a window, from its processor time as the JDK reads it. */
    static double use(ProcessHandle handle, Duration window) throws InterruptedException {
        int processors = Runtime.getRuntime().availableProcessors();
        long before = handle.info().totalCpuDuration().orElseThrow().toNanos();
        long start = System.nanoTime();
        Thread.sleep(window.toMillis());
        long used = handle.info().totalCpuDuration().orElseThrow().toNanos() - before;
        return 100.0 * used / ((System.nanoTime() - start) * (double) processors);
    }

    /** The identity of a process the test started through another, read at once. */
    static ProcessIdentity identityOf(long pid) {
        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
        Instant start = handle.info().startInstant().orElseThrow();
        return new ProcessIdentity(pid, start);
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
