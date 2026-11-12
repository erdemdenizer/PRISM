package prism.srm.demo;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * How a share of the whole machine is split into worker threads. The share is in the unit of PROC-2:
 * 100 means every logical processor busy. A worker that is busy for a fraction {@code dutyCycle} of
 * its time costs that fraction of one logical processor, so {@code workers * dutyCycle /
 * logicalProcessors} is the share of the machine.
 *
 * @param workers the number of worker threads, 0 for no CPU load
 * @param dutyCycle the busy fraction of each worker, in (0, 1]; 0 when there are no workers
 */
record CpuPlan(int workers, double dutyCycle) {

    static final CpuPlan NONE = new CpuPlan(0, 0.0);

    /** Keeps a share that is a whole number of processors, computed in floating point, from needing one more worker. */
    private static final double TOLERANCE = 1e-9;

    /**
     * Plans a share of the machine. Without a requested worker count, as few workers as possible are
     * used, each as busy as needed: 25 on 16 processors is four fully busy workers, 30 is five workers
     * busy 96 percent of the time, 1 is one worker busy 16 percent of the time. More workers may be
     * requested to spread the same share; fewer than the share needs is an error, as is a count above
     * the processors of the machine.
     */
    static CpuPlan plan(double percent, int logicalProcessors, OptionalInt requestedWorkers) {
        if (Double.isNaN(percent) || percent < 0 || percent > 100) {
            throw new IllegalArgumentException("--cpu must be from 0 to 100");
        }
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException("a machine has at least one logical processor");
        }
        if (percent == 0) {
            if (requestedWorkers.isPresent()) {
                throw new IllegalArgumentException("--workers needs --cpu above 0");
            }
            return NONE;
        }
        double target = percent / 100.0 * logicalProcessors;
        int minimumWorkers = (int) Math.ceil(target - TOLERANCE);
        int workers = requestedWorkers.orElse(minimumWorkers);
        if (workers < 1 || workers > logicalProcessors) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "--workers %d: expected 1 to %d, the logical processors of this machine",
                    workers,
                    logicalProcessors));
        }
        if (workers < minimumWorkers) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "--cpu %s on %d logical processors needs at least %d workers; --workers %d cannot reach it",
                    Quantities.formatPercent(percent),
                    logicalProcessors,
                    minimumWorkers,
                    workers));
        }
        return new CpuPlan(workers, Math.min(1.0, target / workers));
    }

    /** The share of the machine this plan produces when every worker keeps its duty cycle. */
    double machinePercent(int logicalProcessors) {
        return 100.0 * workers * dutyCycle / logicalProcessors;
    }
}
