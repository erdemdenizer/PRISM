package prism.srm.platform.windows;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import oshi.driver.common.windows.perfmon.PdhCounterWildcardProperty;
import oshi.driver.windows.registry.HkeyPerformanceDataUtilJNA;
import oshi.util.tuples.Triplet;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ProcessVirtualSizeSource;

/**
 * Virtual sizes on Windows, from the "Virtual Bytes" counter of the Process performance object.
 *
 * <p>The counter is "the current size, in bytes, of the virtual address space that the process is
 * using". {@code Win32_Process.VirtualSize} is described in the same words and as "consistent
 * with what you see in Perfmon.exe". Microsoft does not name the counter there: that the two are
 * one quantity rests on the matching descriptions and on the measurement, in which they were
 * equal for every process that did not change between the two reads.
 * https://learn.microsoft.com/en-us/previous-versions/aa394323(v=vs.85) (VirtualBytes, IDProcess,
 * ElapsedTime) https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-process
 *
 * <p>{@code Win32_Process} itself is not used: one WMI query for all processes took 620 ms in the
 * median, about ten times the whole process listing. This read took 13 ms
 * (docs/PERFORMANCE.md).
 *
 * <p>One read returns the counters of all processes. It goes through OSHI's reader of {@code
 * HKEY_PERFORMANCE_DATA}, the same performance data OSHI's own process listing is built from,
 * which is why the creation time found here equals the start time in that listing. Microsoft
 * recommends the PDH functions over this registry interface for general use; through PDH the
 * same read took 128 ms.
 * https://learn.microsoft.com/en-us/windows/win32/perfctrs/using-the-registry-functions-to-consume-counter-data
 *
 * <p>Access: on the development machine a standard user read the data without elevation.
 * Microsoft does not document the default access of a local standard user. Where the system
 * refuses the read, or performance counters are disabled, OSHI's reader returns nothing, this
 * class throws, and the virtual sizes are absent.
 *
 * <p>Resources: the reader frees the buffer it reads into on every path, and the caller holds
 * nothing to release. OSHI never calls {@code RegCloseKey(HKEY_PERFORMANCE_DATA)}, which would
 * drop the system's cached performance data; that is how its own process listing works as well.
 *
 * <p>The reader is in a package OSHI does not export. An OSHI upgrade has to re-check it. It is
 * reachable only while Prism runs on the class path: on the module path the call would fail with
 * a {@link LinkageError}, which is an {@link Error} and, like every {@code Error}, is not caught
 * and ends process monitoring.
 */
public final class WindowsProcessVirtualSizes implements ProcessVirtualSizeSource {

    private static final String PROCESS_OBJECT = "Process";

    /** The instance that holds the sums over all processes; it shares PID 0 with the idle process. */
    private static final String TOTAL_INSTANCE = "_Total";

    /**
     * A FILETIME counts 100-nanosecond intervals since January 1, 1601 (UTC).
     * https://learn.microsoft.com/en-us/windows/win32/api/minwinbase/ns-minwinbase-filetime
     */
    private static final long FILETIME_UNITS_PER_MILLI = 10_000L;

    /**
     * Milliseconds from January 1, 1601 to January 1, 1970: 11 644 473 600 seconds. Microsoft
     * gives the same offset as 116444736000000000 FILETIME units.
     * https://learn.microsoft.com/en-us/windows/win32/sysinfo/converting-a-time-t-value-to-a-file-time
     */
    private static final long FILETIME_EPOCH_TO_UNIX_EPOCH_MILLIS = 11_644_473_600_000L;

    /**
     * The counters to read, by their English names, which is how the registry indexes them on
     * every display language. OSHI's reader gives the instance name under the first constant and
     * looks up the others.
     */
    enum ProcessCounter implements PdhCounterWildcardProperty {
        NAME("*"),
        ID_PROCESS("ID Process"),
        /**
         * Documented: the raw value of an elapsed-time counter is the time the object was
         * started, on the clock of its performance object.
         * https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2003/cc756820(v=ws.10)
         *
         * <p>Observed on Windows 11 and relied on by OSHI 7.7.0, not documented: for the Process
         * object that clock is the FILETIME clock. OSHI's process listing derives the start time
         * from the same raw value in the same way. Were the assumption wrong, no identity would
         * match and every virtual size would be absent; none could be wrong.
         */
        ELAPSED_TIME("Elapsed Time"),
        VIRTUAL_BYTES("Virtual Bytes");

        private final String counter;

        ProcessCounter(String counter) {
            this.counter = counter;
        }

        @Override
        public String getCounter() {
            return counter;
        }
    }

    @Override
    public Map<ProcessIdentity, Long> virtualSizes() {
        Triplet<List<Map<ProcessCounter, Object>>, Long, Long> data =
                HkeyPerformanceDataUtilJNA.readPerfDataFromRegistry(PROCESS_OBJECT, ProcessCounter.class);
        // OSHI's reader returns null when a counter is not registered or the data cannot be read,
        // for instance where performance counters are disabled. A running system has processes,
        // so no instance at all is a failed read as well.
        if (data == null || data.getA().isEmpty()) {
            throw new IllegalStateException("The Process performance data could not be read");
        }
        return virtualSizes(data.getA());
    }

    /**
     * Turns the counters of each process instance into its identity and virtual size. An instance
     * without a usable PID, creation time or size is left out, so that process has no value.
     */
    static Map<ProcessIdentity, Long> virtualSizes(List<Map<ProcessCounter, Object>> instances) {
        Map<ProcessIdentity, Long> sizes = new HashMap<>();
        for (Map<ProcessCounter, Object> instance : instances) {
            if (TOTAL_INSTANCE.equals(instance.get(ProcessCounter.NAME))
                    || !(instance.get(ProcessCounter.ID_PROCESS) instanceof Integer pid)
                    || !(instance.get(ProcessCounter.ELAPSED_TIME) instanceof Long createdFiletime)
                    || !(instance.get(ProcessCounter.VIRTUAL_BYTES) instanceof Long virtualBytes)) {
                continue;
            }
            long createdMillis = createdFiletime / FILETIME_UNITS_PER_MILLI - FILETIME_EPOCH_TO_UNIX_EPOCH_MILLIS;
            // The counter is an unsigned 64-bit number. A value that does not fit a long, or a
            // creation time before 1970, is not a reading.
            if (createdMillis <= 0 || virtualBytes < 0) {
                continue;
            }
            // The PID is an unsigned 32-bit number. The truncation to milliseconds is the one
            // OSHI applies to the start time.
            ProcessIdentity identity =
                    new ProcessIdentity(Integer.toUnsignedLong(pid), Instant.ofEpochMilli(createdMillis));
            sizes.putIfAbsent(identity, virtualBytes);
        }
        return sizes;
    }
}
