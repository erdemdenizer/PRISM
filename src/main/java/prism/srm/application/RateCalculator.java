package prism.srm.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import prism.srm.domain.CpuSample;
import prism.srm.domain.CpuTicks;
import prism.srm.domain.CpuUsage;

/**
 * Rates between two readings of cumulative counters. A pair of readings that cannot yield a
 * meaningful rate (no time passed, or a counter went backwards because it was reset or wrapped)
 * yields no value: the sample is skipped, never reported as negative or absurdly large.
 */
final class RateCalculator {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * Operating systems charge processor time to a process in whole clock ticks, so a counter can
     * run ahead of the time that really passed by up to one tick per logical processor. The
     * longest tick in use is 15.625 ms (Windows); 20 ms covers it.
     */
    private static final long CPU_ACCOUNTING_SLACK_NANOS = 20_000_000L;

    private RateCalculator() {}

    /**
     * Returns the instant halfway through a read that started and ended at the given monotonic
     * times. A source does not say at which moment inside a read it took its counters; dating
     * the reading at the midpoint halves the largest possible error compared with either end.
     * Written with a difference so that it stays correct when the clock value wraps.
     */
    static long midpoint(long startNanos, long endNanos) {
        return startNanos + (endNanos - startNanos) / 2;
    }

    /** Returns the increase of a counter per second, given the monotonic time between its readings. */
    static OptionalDouble perSecond(long previous, long current, long elapsedNanos) {
        if (elapsedNanos <= 0 || current < previous) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((current - previous) * NANOS_PER_SECOND / elapsedNanos);
    }

    /**
     * Returns the share of the whole machine's processor capacity, 0 to 100, that a process used
     * between two readings of its cumulative processor time. 100 means that every logical
     * processor was busy with it for the whole interval, so a process that keeps two of eight
     * processors busy is at 25.
     *
     * <p>The counter is read somewhere inside the time each reading took, so the interval is
     * known only between two bounds. The share is computed over the best estimate; where
     * sampling makes it come out slightly above the capacity, it is the capacity. If the process
     * used more time than the machine could have supplied even in the longest interval the two
     * readings allow, the counters do not belong together and there is no value.
     *
     * @param elapsedNanos best estimate of the monotonic time between the two readings
     * @param longestElapsedNanos the longest that time can have been, at least {@code
     *     elapsedNanos}
     */
    static OptionalDouble machineCpuPercent(
            long previousCpuMillis,
            long currentCpuMillis,
            long elapsedNanos,
            long longestElapsedNanos,
            int logicalProcessors) {
        long usedMillis = currentCpuMillis - previousCpuMillis;
        if (usedMillis < 0 || elapsedNanos <= 0 || logicalProcessors < 1) {
            return OptionalDouble.empty();
        }
        double usedNanos = usedMillis * NANOS_PER_MILLI;
        double longestCapacityNanos = (longestElapsedNanos + CPU_ACCOUNTING_SLACK_NANOS) * (double) logicalProcessors;
        if (usedNanos > longestCapacityNanos) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.min(100.0, 100.0 * usedNanos / (elapsedNanos * (double) logicalProcessors)));
    }

    /** Returns the share of time, 0 to 100, that was not idle between two readings. */
    static OptionalDouble busyPercent(CpuTicks previous, CpuTicks current) {
        long total = current.total() - previous.total();
        long idle = current.idle() - previous.idle();
        if (total <= 0 || idle < 0 || idle > total) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(100.0 * (total - idle) / total);
    }

    /**
     * Returns the utilization between two readings, or empty if the machine-wide counters do not
     * form a valid pair. Logical processors are judged one by one; if their number changed, none
     * of them has a value.
     */
    static Optional<CpuUsage> cpuUsage(CpuSample previous, CpuSample current) {
        OptionalDouble total = busyPercent(previous.total(), current.total());
        if (total.isEmpty()) {
            return Optional.empty();
        }
        int processors = current.perProcessor().size();
        boolean comparable = previous.perProcessor().size() == processors;
        List<OptionalDouble> perProcessor = new ArrayList<>(processors);
        for (int i = 0; i < processors; i++) {
            perProcessor.add(
                    comparable
                            ? busyPercent(
                                    previous.perProcessor().get(i),
                                    current.perProcessor().get(i))
                            : OptionalDouble.empty());
        }
        return Optional.of(new CpuUsage(total.getAsDouble(), perProcessor));
    }
}
