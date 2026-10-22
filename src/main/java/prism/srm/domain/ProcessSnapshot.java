package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 *
 *
 * @param parentPid
 * @param state
 * @param cpuPercent
 * @param priority
 * @param ioReadBytesPerSecond
 *     for disk rate see {@link ProcessReading#bytesRead()} for what each system counts
 * @param ioWriteBytesPerSecond
 * this module is mainly used for displaying the cpu percentage using the values from {@link ProcessReading}
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
        // Written so that NaN is rejected too. cus nan might cause crashes etc.
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
