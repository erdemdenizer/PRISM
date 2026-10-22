package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * @param name
 * @param parentPid
 * @param state
 * @param cpuTimeMillis
 * @param residentBytes
 * @param privateResidentBytes
 * @param virtualBytes
 * @param priority
 * @param bytesRead
 * @param bytesWritten
 *
 * this module is used for reading a processes status. waiting,thread status etc.
 **/
public record ProcessReading(
        ProcessIdentity identity,
        String name,
        OptionalLong parentPid,
        Optional<ProcessState> state,
        OptionalLong cpuTimeMillis,
        OptionalLong residentBytes,
        OptionalLong privateResidentBytes,
        OptionalLong virtualBytes,
        Optional<String> user,
        OptionalInt threadCount,
        OptionalInt priority,
        OptionalLong bytesRead,
        OptionalLong bytesWritten) {





    public ProcessReading {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(priority, "priority");
        requireNotNegative(parentPid, "parentPid");
        requireNotNegative(cpuTimeMillis, "cpuTimeMillis");
        requireNotNegative(residentBytes, "residentBytes");
        requireNotNegative(privateResidentBytes, "privateResidentBytes");
        requireNotNegative(virtualBytes, "virtualBytes");
        requireNotNegative(bytesRead, "bytesRead");
        requireNotNegative(bytesWritten, "bytesWritten");
        if (threadCount.isPresent() && threadCount.getAsInt() < 0) {
            throw new IllegalArgumentException("threadCount must not be negative: " + threadCount.getAsInt());
        }
    }

// process value cant be 0. so we wrote a function for it
    private static void requireNotNegative(OptionalLong value, String name) {
        if (value.isPresent() && value.getAsLong() < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value.getAsLong());
        }
    }
}
