package prism.srm.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.platform.ProcessSource;

/**
 * Takes one reading of all processes and turns it, together with the previous reading, into
 * snapshots with processor and I/O rates.
 *
 * <p>A rate is computed only between two readings of the same {@link ProcessIdentity}. A process
 * seen for the first time has none, and neither has a process that took over the PID of one that
 * exited, because its identity differs. Only the counters of the processes in the latest reading
 * are kept: when a process is gone from a reading, nothing of it remains.
 *
 * <p>Reading all processes takes tens of milliseconds, and the source does not say at which
 * moment inside that time the counters were taken. A reading is therefore dated at the midpoint
 * of the time the read took.
 *
 * <p>Used by one thread at a time.
 */
final class ProcessSampler {

    /** The cumulative counters of one process that rates are computed from. */
    private record Counters(OptionalLong cpuTimeMillis, OptionalLong bytesRead, OptionalLong bytesWritten) {}

    private final ProcessSource source;
    private final LongSupplier nanoClock;
    private final int logicalProcessors;

    private Map<ProcessIdentity, Counters> previous = Map.of();
    private boolean hasPrevious;
    private long previousReadStartNanos;
    private long previousReadEndNanos;

    /**
     * @param nanoClock monotonic clock, such as {@code System::nanoTime}
     * @param logicalProcessors the number of logical processors of the machine, which processor
     *     use is measured against
     */
    ProcessSampler(ProcessSource source, LongSupplier nanoClock, int logicalProcessors) {
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException("logicalProcessors must be positive: " + logicalProcessors);
        }
        this.source = source;
        this.nanoClock = nanoClock;
        this.logicalProcessors = logicalProcessors;
    }

    /**
     * @throws RuntimeException if the source could not list the processes; the previous reading is
     *     kept, so the next rates span the longer interval
     */
    List<ProcessSnapshot> sample() {
        long readStartNanos = nanoClock.getAsLong();
        List<ProcessReading> readings = source.processes();
        long readEndNanos = nanoClock.getAsLong();

        long elapsedNanos = RateCalculator.midpoint(readStartNanos, readEndNanos)
                - RateCalculator.midpoint(previousReadStartNanos, previousReadEndNanos);
        // The counters of the two readings were taken no further apart than this.
        long longestElapsedNanos = readEndNanos - previousReadStartNanos;

        Map<ProcessIdentity, Counters> current = new HashMap<>();
        List<ProcessSnapshot> snapshots = new ArrayList<>(readings.size());
        for (ProcessReading reading : readings) {
            Counters now = new Counters(reading.cpuTimeMillis(), reading.bytesRead(), reading.bytesWritten());
            // null for a process seen for the first time, which includes a reused PID.
            Counters before = hasPrevious ? previous.get(reading.identity()) : null;
            // Should a source list one identity twice, the first listing is the one kept.
            current.putIfAbsent(reading.identity(), now);
            snapshots.add(new ProcessSnapshot(
                    reading.identity(),
                    reading.name(),
                    reading.parentPid(),
                    reading.state(),
                    cpuPercent(before, now, elapsedNanos, longestElapsedNanos),
                    reading.residentBytes(),
                    reading.privateResidentBytes(),
                    reading.virtualBytes(),
                    reading.user(),
                    reading.threadCount(),
                    reading.priority(),
                    bytesPerSecond(
                            before == null ? OptionalLong.empty() : before.bytesRead(), now.bytesRead(), elapsedNanos),
                    bytesPerSecond(
                            before == null ? OptionalLong.empty() : before.bytesWritten(),
                            now.bytesWritten(),
                            elapsedNanos)));
        }

        previous = current;
        hasPrevious = true;
        previousReadStartNanos = readStartNanos;
        previousReadEndNanos = readEndNanos;
        return List.copyOf(snapshots);
    }

    private OptionalDouble cpuPercent(Counters before, Counters now, long elapsedNanos, long longestElapsedNanos) {
        if (before == null
                || before.cpuTimeMillis().isEmpty()
                || now.cpuTimeMillis().isEmpty()) {
            return OptionalDouble.empty();
        }
        return RateCalculator.machineCpuPercent(
                before.cpuTimeMillis().getAsLong(),
                now.cpuTimeMillis().getAsLong(),
                elapsedNanos,
                longestElapsedNanos,
                logicalProcessors);
    }

    private static OptionalDouble bytesPerSecond(OptionalLong before, OptionalLong now, long elapsedNanos) {
        if (before.isEmpty() || now.isEmpty()) {
            return OptionalDouble.empty();
        }
        return RateCalculator.perSecond(before.getAsLong(), now.getAsLong(), elapsedNanos);
    }
}
