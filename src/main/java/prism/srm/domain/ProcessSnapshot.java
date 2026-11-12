package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The state of one process after a monitoring cycle. A value is absent when the system does not
 * provide it, and a rate also when there is no valid pair of readings of this same process to
 * compute it from. The start time is part of the identity.
 *
 * @param parentPid the PID of the process that created this one, as the system recorded it then.
 *     That process may have exited since, and its PID may belong to another process by now.
 * @param state absent where the system reports no state for a process as a whole
 * @param cpuPercent share of the whole machine's processor capacity the process used since the
 *     previous reading, 0 to 100, where 100 means every logical processor was busy with it
 * @param priority the scheduling priority as the system numbers it; the scale differs between
 *     systems
 * @param ioReadBytesPerSecond rate at which the process reads in I/O operations. It is not a
 *     disk rate: see {@link ProcessReading#bytesRead()} for what each system counts
 * @param ioWriteBytesPerSecond rate at which the process writes in I/O operations
 */
public record ProcessSnapshot(
        ProcessIdentity identity,
        String name,
        OptionalLong parentPid,
        Optional<ProcessState> state,
        OptionalDouble cpuPercent,
        OptionalLong residentBytes,
        OptionalLong privateResidentBytes,
        OptionalLong virtualBytes,
        Optional<String> user,
        OptionalInt threadCount,
        OptionalInt priority,
        OptionalDouble ioReadBytesPerSecond,
        OptionalDouble ioWriteBytesPerSecond) {

    public ProcessSnapshot {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentPid, "parentPid");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(residentBytes, "residentBytes");
        Objects.requireNonNull(privateResidentBytes, "privateResidentBytes");
        Objects.requireNonNull(virtualBytes, "virtualBytes");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(threadCount, "threadCount");
        Objects.requireNonNull(priority, "priority");
        // Written so that NaN is rejected too.
        if (cpuPercent.isPresent() && !(cpuPercent.getAsDouble() >= 0 && cpuPercent.getAsDouble() <= 100)) {
            throw new IllegalArgumentException("Expected a percentage from 0 to 100: " + cpuPercent.getAsDouble());
        }
        requireRate(ioReadBytesPerSecond, "ioReadBytesPerSecond");
        requireRate(ioWriteBytesPerSecond, "ioWriteBytesPerSecond");
    }

    private static void requireRate(OptionalDouble rate, String name) {
        if (rate.isPresent() && !(Double.isFinite(rate.getAsDouble()) && rate.getAsDouble() >= 0)) {
            throw new IllegalArgumentException(name + " must be finite and not negative: " + rate.getAsDouble());
        }
    }
}
