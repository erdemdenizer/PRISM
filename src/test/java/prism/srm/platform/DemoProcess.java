package prism.srm.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import prism.srm.demo.DemoWorkload;
import prism.srm.domain.ProcessIdentity;

/**
 * The milestone 6 demo workload as a child of the test, the only process the Linux and macOS
 * control tests act on (SAFE-6): started from the compiled classes with the JDK that runs the
 * tests, identified by its PID and the start time the JDK reads, its output kept, and killed in
 * {@link #close()} if it still runs. (The Windows tests keep their own, older helper.)
 */
public final class DemoProcess implements AutoCloseable {

    /** How long the workload may take to say that it runs. */
    private static final Duration START_BOUND = Duration.ofSeconds(30);

    private final Process process;
    private final ProcessIdentity identity;
    private final Queue<String> output;
    private final Thread reader;

    private DemoProcess(Process process, Queue<String> output, Thread reader) {
        this.process = process;
        this.output = output;
        this.reader = reader;
        this.identity = new ProcessIdentity(
                process.pid(), process.toHandle().info().startInstant().orElseThrow());
    }

    /**
     * The share of the machine one busy thread takes, as {@code --cpu} accepts it (at most three
     * decimals), rounded down so that it never needs a second worker, whatever the number of
     * processors: 33.333 on three.
     */
    public static String oneWorkerOfCpu() {
        double share = Math.floor(100_000.0 / Runtime.getRuntime().availableProcessors()) / 1000;
        return String.format(Locale.ROOT, "%.3f", share);
    }

    /** Starts the workload and returns once it has said that it runs, within a bound. */
    public static DemoProcess start(String... arguments) throws IOException, URISyntaxException, InterruptedException {
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
        Queue<String> lines = new ConcurrentLinkedQueue<>();
        CountDownLatch started = new CountDownLatch(1);
        Thread reader = new Thread(() -> read(process, lines, started), "demo-process-output");
        reader.setDaemon(true);
        reader.start();
        if (!started.await(START_BOUND.toMillis(), TimeUnit.MILLISECONDS)
                || lines.stream().noneMatch(line -> line.startsWith("event=start"))) {
            process.destroyForcibly();
            throw new IllegalStateException("The demo workload did not start: " + lines);
        }
        return new DemoProcess(process, lines, reader);
    }

    public ProcessIdentity identity() {
        return identity;
    }

    public Process process() {
        return process;
    }

    /** The lines the workload has printed so far. */
    public List<String> output() {
        return List.copyOf(output);
    }

    /**
     * The CPU time the workload has used so far. The JDK reads it where it can; on macOS it gives the
     * CPU time of its own process only (ProcessHandleImpl_macosx.c reads it with getrusage for the
     * current process and leaves it empty for any other), so there it is read from the process
     * listing, which reads it for any process of the same user. Neither goes through the controller
     * under test.
     */
    public Duration cpu() {
        return cpuByJdk().orElseGet(this::cpuByListing);
    }

    /** The CPU time as the JDK reads it; empty on macOS for any process but the JDK's own. */
    public Optional<Duration> cpuByJdk() {
        return process.toHandle().info().totalCpuDuration();
    }

    /** The CPU time as the process listing (OSHI) reads it. */
    public Duration cpuByListing() {
        return Duration.ofMillis(listing().processes().stream()
                .filter(reading -> reading.identity().pid() == identity.pid())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The listing does not show the workload"))
                .cpuTimeMillis()
                .orElseThrow(() -> new IllegalStateException("The listing gives no CPU time for the workload")));
    }

    private static ProcessSource listing;

    private static synchronized ProcessSource listing() {
        if (listing == null) {
            listing = HostPlatform.create().processSource();
        }
        return listing;
    }

    /**
     * Whether the workload exits within the bound; when it does, its output has been read to the
     * end, so {@link #output()} holds its last line.
     */
    public boolean exitsWithin(Duration bound) throws InterruptedException {
        if (!process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS)) {
            return false;
        }
        reader.join(bound.toMillis());
        return true;
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /** Reads every line until the workload's output ends; the latch opens at the start line, or at the end. */
    private static void read(Process process, Queue<String> lines, CountDownLatch started) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (line.startsWith("event=start")) {
                    started.countDown();
                }
            }
        } catch (IOException ended) {
            // The workload exited.
        } finally {
            started.countDown();
        }
    }
}
