package prism.srm.demo;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Turns the command line into a {@link DemoArguments}, or into a message that says what is wrong
 * with it. Every option takes a value as the next argument; nothing is interactive.
 */
final class ArgumentParser {

    private static final String CPU = "--cpu";
    private static final String MEMORY = "--memory";
    private static final String DURATION = "--duration";
    private static final String WORKERS = "--workers";

    private ArgumentParser() {}

    /** The arguments to run with, or empty when help was asked for. */
    static Optional<DemoArguments> parse(String[] args, HostFacts host) throws UsageException {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            switch (option) {
                case "--help", "-h" -> {
                    return Optional.empty();
                }
                case CPU, MEMORY, DURATION, WORKERS -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("Option " + option + " needs a value.");
                    }
                    if (values.containsKey(option)) {
                        throw new UsageException("Option " + option + " was given twice.");
                    }
                    values.put(option, args[++i]);
                }
                default -> throw new UsageException("Unknown option: " + option + ".");
            }
        }

        double percent = values.containsKey(CPU) ? percent(values.get(CPU)) : 0;
        OptionalInt workers =
                values.containsKey(WORKERS) ? OptionalInt.of(count(values.get(WORKERS))) : OptionalInt.empty();
        CpuPlan plan;
        try {
            plan = CpuPlan.plan(percent, host.logicalProcessors(), workers);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage() + ".");
        }
        long memory = values.containsKey(MEMORY) ? memory(values.get(MEMORY), host) : 0;
        Duration duration =
                values.containsKey(DURATION) ? duration(values.get(DURATION)) : DemoArguments.DEFAULT_DURATION;
        return Optional.of(new DemoArguments(percent, plan, memory, duration));
    }

    /** The usage text, with the facts of this machine that the bounds depend on. */
    static String help(HostFacts host) {
        OptionalLong cap = host.memoryCap();
        String physical =
                host.physicalMemoryBytes() > 0 ? Quantities.formatBytes(host.physicalMemoryBytes()) : "unknown";
        String capText =
                cap.isPresent() ? Quantities.formatBytes(cap.getAsLong()) : "unknown (no memory can be requested)";
        // Built without a format string: the text block has its own line breaks.
        return """
                Usage: prism-demo [--cpu <percent>] [--memory <size>] [--duration <time>] [--workers <n>]

                A safe, bounded workload for demonstrating Prism. It uses what it is told to use,
                reports what it does, and exits by itself.

                  --cpu <percent>     share of the whole machine to keep busy, 0 to 100 (100 means
                                      every logical processor busy); for example 25 or 6.25. Default 0.
                  --memory <size>     memory to allocate and hold, as <n>MiB or <n>GiB; for example
                                      256MiB. Default 0. Never above the safety cap: the smaller of
                                      1 GiB and a quarter of physical memory.
                  --duration <time>   how long to run, as <n>s or <n>m, from 1s to 30m. Default 5m.
                                      There is no unlimited mode.
                  --workers <n>       spread the CPU share over this many threads instead of the fewest
                                      that can carry it; 1 to the number of logical processors.
                  --help, -h          this text.

                Exit codes: 0 ran for its duration, 1 memory allocation failed, 2 invalid arguments.

                """
                + "This machine: " + host.logicalProcessors() + " logical processors, " + physical
                + " physical memory, memory cap " + capText + ".\n";
    }

    private static double percent(String text) throws UsageException {
        try {
            return Quantities.parsePercent(text);
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid " + CPU + " value " + quote(text) + ": " + e.getMessage() + ".");
        }
    }

    private static int count(String text) throws UsageException {
        try {
            return Quantities.parseCount(text);
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid " + WORKERS + " value " + quote(text) + ": " + e.getMessage() + ".");
        }
    }

    private static long memory(String text, HostFacts host) throws UsageException {
        long bytes;
        try {
            bytes = Quantities.parseMemorySize(text);
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid " + MEMORY + " value " + quote(text) + ": " + e.getMessage() + ".");
        }
        if (bytes == 0) {
            return 0;
        }
        OptionalLong cap = host.memoryCap();
        if (cap.isEmpty()) {
            throw new UsageException("Option " + MEMORY + " cannot be used: the physical memory size of this machine is"
                    + " unknown, so the safety cap (the smaller of 1 GiB and a quarter of physical memory) cannot be"
                    + " computed.");
        }
        if (bytes > cap.getAsLong()) {
            throw new UsageException(String.format(
                    Locale.ROOT,
                    "%s %s is above the safety cap of %s on this machine: the smaller of 1 GiB and a quarter of"
                            + " %s physical memory.",
                    MEMORY,
                    text.strip(),
                    Quantities.formatBytes(cap.getAsLong()),
                    Quantities.formatBytes(host.physicalMemoryBytes())));
        }
        return bytes;
    }

    private static Duration duration(String text) throws UsageException {
        Duration duration;
        try {
            duration = Quantities.parseDuration(text);
        } catch (IllegalArgumentException e) {
            throw new UsageException("Invalid " + DURATION + " value " + quote(text) + ": " + e.getMessage() + ".");
        }
        if (duration.compareTo(DemoArguments.MINIMUM_DURATION) < 0) {
            throw new UsageException(DURATION + " must be at least "
                    + Quantities.formatSeconds(DemoArguments.MINIMUM_DURATION)
                    + "; 0 does not mean unlimited, there is no unlimited mode.");
        }
        if (duration.compareTo(DemoArguments.MAXIMUM_DURATION) > 0) {
            throw new UsageException(DURATION + " " + text.strip() + " is above the maximum of "
                    + DemoArguments.MAXIMUM_DURATION.toMinutes() + "m.");
        }
        return duration;
    }

    private static String quote(String text) {
        return "'" + text + "'";
    }
}
