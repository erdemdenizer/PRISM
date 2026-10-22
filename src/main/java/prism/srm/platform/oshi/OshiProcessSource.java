package prism.srm.platform.oshi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.software.os.OSProcess;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.domain.ProcessState;
import prism.srm.platform.HostSystem;
import prism.srm.platform.ProcessSource;
import prism.srm.platform.ProcessVirtualSizeSource;

/**
 * Process readings through OSHI, from the long-lived OSHI objects that {@link OshiHost} owns.
 *
 * <p>A reading asks OSHI for all processes in one call and takes from each process only values
 * OSHI holds after that call. OSHI's listing is not free per process: on Windows it reads the
 * performance data and the process list once for all processes, and it also opens every process
 * briefly to learn its path, which the system refuses for protected processes. What this class
 * adds on top is nothing per process and cycle. It never asks a process for its command line,
 * arguments, environment, working directory or threads: OSHI fetches those with a further query
 * per process, and the command line alone was measured at about 54 ms per process on Windows.
 *
 * <p>On Windows the virtual size is not among the values OSHI holds. It comes from the system's
 * own source, with one further read for all processes per listing.
 *
 * <p>Several values OSHI returns are not what their name says on every system, or stand for
 * "could not read" as well as for a real zero. Each such value is absent here instead; the
 * methods below say which and why. Only the Windows behavior has been run.
 */
final class OshiProcessSource implements ProcessSource {

    private static final Logger LOG = LoggerFactory.getLogger(OshiProcessSource.class);

    private final Supplier<List<OSProcess>> allProcesses;
    private final IntFunction<OSProcess> oneProcess;
    private final HostSystem system;
    private final ProcessVirtualSizeSource windowsVirtualSizes;

    // Belong to the listing thread; details() must not touch them.
    private final Map<ProcessIdentity, Optional<String>> windowsUsers = new HashMap<>();
    private final Map<String, Optional<String>> userNamesById = new HashMap<>();
    private final Set<String> loggedFailures = new HashSet<>();

    /**
     * @param allProcesses OSHI's listing of every process
     * @param oneProcess OSHI's lookup of one process by PID; null when there is none
     * @param windowsVirtualSizes where the virtual sizes come from on Windows; not asked on any
     *     other system
     */
    OshiProcessSource(
            Supplier<List<OSProcess>> allProcesses,
            IntFunction<OSProcess> oneProcess,
            HostSystem system,
            ProcessVirtualSizeSource windowsVirtualSizes) {
        this.allProcesses = allProcesses;
        this.oneProcess = oneProcess;
        this.system = system;
        this.windowsVirtualSizes = windowsVirtualSizes;
    }

    @Override
    public List<ProcessReading> processes() {
        List<OSProcess> listed = allProcesses.get();
        Map<ProcessIdentity, Long> virtualSizes = system == HostSystem.WINDOWS ? readWindowsVirtualSizes() : Map.of();
        List<ProcessReading> readings = new ArrayList<>(listed.size());
        Set<ProcessIdentity> live = new HashSet<>();
        Set<String> liveUserIds = new HashSet<>();
        for (OSProcess process : listed) {
            try {
                Optional<ProcessReading> reading = read(process, virtualSizes, liveUserIds);
                if (reading.isPresent()) {
                    readings.add(reading.get());
                    live.add(reading.get().identity());
                }
            } catch (RuntimeException e) {
                // One process that cannot be read must not cost the others their reading.
                if (loggedFailures.add(e.getClass().getName())) {
                    LOG.warn(
                            "A process could not be read and was left out; further failures of this kind"
                                    + " are not logged",
                            e);
                }
            }
        }
        // Nothing is kept for a process, or a user, that is gone.
        windowsUsers.keySet().retainAll(live);
        userNamesById.keySet().retainAll(liveUserIds);
        return readings;
    }

    /**
     * Callable from any thread while a listing is in progress: it uses nothing of this object but
     * the lookup, and OSHI declares its operating system and process classes thread-safe.
     */
    @Override
    public Optional<ProcessDetails> details(ProcessIdentity identity) {
        if (identity.pid() > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        int pid = (int) identity.pid();
        OSProcess process = oneProcess.apply(pid);
        if (!isProcess(process, identity)) {
            return Optional.empty();
        }
        Optional<String> path = OshiSystemMetricsSource.text(process.getPath());
        Optional<String> commandLine = OshiSystemMetricsSource.text(process.getCommandLine());
        // OSHI reads the command line with a new query by PID. Had the process exited and its PID
        // been given to another in between, that query would have answered for the other one.
        if (!isProcess(oneProcess.apply(pid), identity)) {
            return Optional.empty();
        }
        return Optional.of(new ProcessDetails(identity, path, commandLine));
    }

    private static boolean isProcess(OSProcess process, ProcessIdentity identity) {
        return process != null && identityOf(process).equals(Optional.of(identity));
    }

    /**
     * One read for all processes, after the listing. The virtual size is one value of many: when
     * this read fails, the listing is still a reading, without virtual sizes.
     *
     * <p>The read lengthens the time a listing takes, which the sampler dates at its midpoint
     * while the processor counters were taken in the first part. The midpoint is therefore late
     * by half the read, about 4 to 7 ms. As long as the read takes about the same time in every
     * cycle, that cancels between two readings; what remains is the difference between two
     * reads, a few milliseconds in 2000. A read that stalled once would move one interval by
     * half the stall and the next one back by as much.
     */
    private Map<ProcessIdentity, Long> readWindowsVirtualSizes() {
        try {
            return Objects.requireNonNull(windowsVirtualSizes.virtualSizes(), "virtualSizes");
        } catch (RuntimeException e) {
            if (loggedFailures.add("virtual sizes " + e.getClass().getName())) {
                LOG.warn(
                        "The virtual sizes could not be read and are absent; further failures of this"
                                + " kind are not logged",
                        e);
            }
            return Map.of();
        }
    }

    private Optional<ProcessReading> read(
            OSProcess process, Map<ProcessIdentity, Long> virtualSizes, Set<String> liveUserIds) {
        Optional<ProcessIdentity> identity = identityOf(process);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        boolean ioKnown = ioCountersAreReadings(process);
        return Optional.of(new ProcessReading(
                identity.get(),
                process.getName() == null ? "" : process.getName().strip(),
                notNegative(process.getParentProcessID()),
                stateOf(system, process.getState()),
                cpuTimeMillis(process),
                notNegative(process.getResidentMemory()),
                privateResidentBytes(process),
                virtualBytes(identity.get(), process, virtualSizes),
                user(identity.get(), process, liveUserIds),
                process.getThreadCount() >= 0 ? OptionalInt.of(process.getThreadCount()) : OptionalInt.empty(),
                OptionalInt.of(process.getPriority()),
                ioKnown ? notNegative(process.getBytesRead()) : OptionalLong.empty(),
                ioKnown ? notNegative(process.getBytesWritten()) : OptionalLong.empty()));
    }

    /**
     * Returns the identity, or empty for a process that cannot be tracked: one that OSHI marks as
     * gone while it was being read, or one without a start time, because a PID alone does not
     * identify a process.
     */
    private static Optional<ProcessIdentity> identityOf(OSProcess process) {
        if (process.getState() == OSProcess.State.INVALID
                || process.getStartTime() <= 0
                || process.getProcessID() < 0) {
            return Optional.empty();
        }
        return Optional.of(new ProcessIdentity(process.getProcessID(), Instant.ofEpochMilli(process.getStartTime())));
    }

    private static OptionalLong cpuTimeMillis(OSProcess process) {
        long kernel = process.getKernelTime();
        long user = process.getUserTime();
        return kernel >= 0 && user >= 0 ? OptionalLong.of(kernel + user) : OptionalLong.empty();
    }

    /**
     * On Windows OSHI does not read a process state: without its expensive suspended-thread scan,
     * which is off, it sets every process to running, including ones that have no thread at all.
     * That default is not a reading, so it is absent. Any other state OSHI returns on Windows did
     * come from a check and is kept.
     */
    static Optional<ProcessState> stateOf(HostSystem system, OSProcess.State state) {
        if (system == HostSystem.WINDOWS && state == OSProcess.State.RUNNING) {
            return Optional.empty();
        }
        return Optional.of(
                switch (state) {
                    case NEW -> ProcessState.NEW;
                    case RUNNING -> ProcessState.RUNNING;
                    case SLEEPING -> ProcessState.SLEEPING;
                    case WAITING -> ProcessState.WAITING;
                    case STOPPED -> ProcessState.STOPPED;
                    case SUSPENDED -> ProcessState.SUSPENDED;
                    case ZOMBIE -> ProcessState.ZOMBIE;
                    case OTHER, INVALID -> ProcessState.OTHER;
                });
    }

    /**
     * The part of the resident memory that belongs to this process alone.
     *
     * <ul>
     *   <li>Windows: the private working set, which is that quantity. Checked against the
     *       system's own counter.
     *   <li>Linux: OSHI subtracts the file-backed and shared-memory pages from the resident pages
     *       ({@code /proc/<pid>/statm}), which leaves the anonymous resident memory. Kept as the
     *       customary reading of "private"; not run.
     *   <li>macOS: OSHI returns the physical footprint, which also counts memory that is
     *       compressed and no longer resident. A different quantity, so absent.
     *   <li>Other systems: OSHI's fallback returns the whole resident memory. Absent.
     * </ul>
     */
    private OptionalLong privateResidentBytes(OSProcess process) {
        return switch (system) {
            case WINDOWS, LINUX -> notNegative(process.getPrivateResidentMemory());
            case MACOS, OTHER -> OptionalLong.empty();
        };
    }

    /**
     * On Windows, what OSHI returns as the virtual size is {@code PagefileUsage} of {@code
     * WTS_PROCESS_INFO_EX}: the committed memory of the process, not the size of its address
     * space, and a 32-bit field that cannot hold more than 4 GiB. Measured for one JVM: OSHI
     * 1441 MiB, {@code Win32_Process.VirtualSize} 13731 MiB. It is a different quantity and is
     * never used.
     * https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/ns-wtsapi32-wts_process_info_exw
     *
     * <p>The value on Windows is the one the system's own source holds for this identity. A
     * process the source does not list, which includes one whose PID the source saw in the hands
     * of another process, has no virtual size.
     */
    private OptionalLong virtualBytes(
            ProcessIdentity identity, OSProcess process, Map<ProcessIdentity, Long> windowsVirtualSizes) {
        if (system != HostSystem.WINDOWS) {
            return notNegative(process.getVirtualSize());
        }
        Long size = windowsVirtualSizes.get(identity);
        return size == null ? OptionalLong.empty() : notNegative(size);
    }

    /**
     * Tells whether the two I/O counters of a process are readings.
     *
     * <p>On Windows they come from the performance data that lists every process, so a zero is
     * the system's own value. Elsewhere OSHI reads them per process, which the system refuses for
     * processes of other users (on Linux {@code /proc/<pid>/io} needs the same permission as
     * attaching a debugger), and OSHI then returns zero for both. A process whose counters are
     * both zero is therefore either one that has done no I/O or one that could not be read; the
     * two cannot be told apart from here, so its counters are absent rather than a zero that
     * may be false.
     * https://man7.org/linux/man-pages/man5/proc_pid_io.5.html
     */
    private boolean ioCountersAreReadings(OSProcess process) {
        return system == HostSystem.WINDOWS || process.getBytesRead() != 0 || process.getBytesWritten() != 0;
    }

    /**
     * The name of the user the process runs as.
     *
     * <p>On Windows OSHI opens the process and its token for it (about 0.2 ms per process). The
     * user of a Windows process is fixed when the process is created, so it is asked for once per
     * identity and kept, also when the answer was that it may not be read.
     *
     * <p>Elsewhere a process can change its user while it runs, so the user ID, which OSHI holds
     * after the listing, is taken on every reading. Only turning an ID into a name is remembered,
     * per ID: OSHI may start a helper program for that lookup.
     */
    private Optional<String> user(ProcessIdentity identity, OSProcess process, Set<String> liveUserIds) {
        if (system == HostSystem.WINDOWS) {
            return windowsUsers.computeIfAbsent(identity, unused -> OshiSystemMetricsSource.text(process.getUser()));
        }
        Optional<String> userId = OshiSystemMetricsSource.text(process.getUserID());
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        liveUserIds.add(userId.get());
        return userNamesById.computeIfAbsent(userId.get(), unused -> OshiSystemMetricsSource.text(process.getUser()));
    }

    /** OSHI reports a count or size it could not determine as a negative number. */
    private static OptionalLong notNegative(long value) {
        return value >= 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }
}
