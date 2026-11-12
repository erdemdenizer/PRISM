package prism.srm.demo;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Duration;
import java.util.Optional;

/**
 * Entry point of the demo workload: {@code java -jar prism-<version>-demo.jar --cpu 25
 * --memory 256MiB --duration 5m}. See {@link ArgumentParser#help} for the options and
 * {@code docs/TESTING.md} for how it is used.
 */
public final class DemoWorkload {

    static final String NAME = "prism-demo";

    /** How long the shutdown hook waits for the end line after Ctrl+C or a termination request. */
    private static final Duration SHUTDOWN_BOUND = Duration.ofSeconds(2);

    private DemoWorkload() {}

    public static void main(String[] args) {
        HostFacts host = HostFacts.detect();
        DemoArguments arguments;
        try {
            Optional<DemoArguments> parsed = ArgumentParser.parse(args, host);
            if (parsed.isEmpty()) {
                System.out.print(ArgumentParser.help(host));
                return;
            }
            arguments = parsed.get();
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println("Try --help.");
            System.exit(UsageException.EXIT_CODE);
            return;
        }

        System.out.println(NAME + " " + version());
        MemoryWorkload memory =
                MemoryWorkload.offHeap(arguments.memoryBytes(), host.memoryCap().orElse(0));
        DemoRun run = new DemoRun(
                arguments,
                host,
                memory,
                DemoWorkload::processCpuTimeNanos,
                System.out,
                DemoRun.DEFAULT_STATUS_INTERVAL);
        // Ctrl+C and a termination request run the shutdown hooks: the run is asked to stop and the
        // hook waits for its end line, so that what happened to the process is reported.
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            run.requestStop();
                            run.awaitEnd(SHUTDOWN_BOUND);
                        },
                        NAME + "-shutdown"));

        int exitCode = run.run();
        // After a signal the JVM is already shutting down and holds the shutdown lock: System.exit
        // would block forever. Returning is enough; the JVM exits with its own code for the signal.
        if (!run.interrupted()) {
            System.exit(exitCode);
        }
    }

    private static String version() {
        String version = DemoWorkload.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** The CPU time this process has used so far, in nanoseconds, or -1 when the JVM does not report it. */
    private static long processCpuTimeNanos() {
        OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getProcessCpuTime() : -1;
    }
}
