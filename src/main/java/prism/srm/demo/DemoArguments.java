package prism.srm.demo;

import java.time.Duration;
import java.util.Objects;

/**
 * A validated request: what the workload is to do and for how long.
 *
 * @param cpuPercent the requested share of the whole machine, 0 to 100
 * @param cpuPlan how that share is split into workers
 * @param memoryBytes the memory to hold, 0 for none; never above the cap of the machine
 * @param duration how long to run: positive and never above {@link #MAXIMUM_DURATION}. The command
 *     line asks for at least {@link #MINIMUM_DURATION}; tests run shorter.
 */
record DemoArguments(double cpuPercent, CpuPlan cpuPlan, long memoryBytes, Duration duration) {

    static final Duration DEFAULT_DURATION = Duration.ofMinutes(5);
    static final Duration MINIMUM_DURATION = Duration.ofSeconds(1);
    static final Duration MAXIMUM_DURATION = Duration.ofMinutes(30);

    DemoArguments {
        Objects.requireNonNull(cpuPlan, "cpuPlan");
        Objects.requireNonNull(duration, "duration");
        if (memoryBytes < 0) {
            throw new IllegalArgumentException("memory cannot be negative");
        }
        if (duration.isZero() || duration.isNegative() || duration.compareTo(MAXIMUM_DURATION) > 0) {
            throw new IllegalArgumentException("duration must be positive and at most "
                    + Quantities.formatSeconds(MAXIMUM_DURATION) + ", was " + duration);
        }
    }

    /** What the run does, as one word for the start line. */
    String mode() {
        boolean cpu = cpuPlan.workers() > 0;
        boolean memory = memoryBytes > 0;
        if (cpu && memory) {
            return "cpu+memory";
        }
        if (cpu) {
            return "cpu";
        }
        return memory ? "memory" : "idle";
    }
}
