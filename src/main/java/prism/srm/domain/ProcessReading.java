package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One raw reading of a process: facts and the cumulative counters that rates are computed from.
 * A value the system does not provide for this process is absent, never zero.
 *
 * @param name the system's short name for the process; empty when it reports none
 * @param parentPid the PID of the process that created this one, as the system recorded it then.
 *     That process may have exited since, and its PID may belong to another process by now.
 * @param state absent where the system reports no state for a process as a whole
 * @param cpuTimeMillis processor time the process has used since it started, kernel and user
 *     together, summed over all its threads
 * @param residentBytes memory of the process that is currently in physical memory
 * @param privateResidentBytes the part of the resident memory that is not shared with other
 *     processes
 * @param virtualBytes current size of the virtual address space of the process, which says
 *     nothing about how much memory or disk the process really uses
 * @param priority the scheduling priority as the system numbers it; the scale differs between
 *     systems
 * @param bytesRead bytes the process has read in I/O operations since it started, by the
 *     system's own accounting. This is process I/O, not disk I/O: Windows counts every read the
 *     process issues, to files, the network and devices alike. Linux counts only what the
 *     process caused to be fetched from storage. The fields read on macOS are named for disk
 *     I/O; Apple does not document what they count.
 * @param bytesWritten bytes the process has written in I/O operations since it started; the same
 *     differences apply
 */
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

    private static void requireNotNegative(OptionalLong value, String name) {
        if (value.isPresent() && value.getAsLong() < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value.getAsLong());
        }
    }
}
