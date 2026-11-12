package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The system calls of {@link LinuxNative} as the man pages describe them, over a {@link FakeProc}:
 *
 * <ul>
 *   <li>pidfd_open refers to the process that has the PID when it is called; ESRCH if none has.
 *   <li>pidfd_send_signal reaches that process only: once it has gone, or another process has
 *       taken its PID, ESRCH. A zombie still takes a signal, to no effect, as the kernel does.
 *       SIGSTOP stops it (state T), SIGCONT continues a stopped one (S), SIGKILL ends it (Z).
 *   <li>setpriority sets the nice value of one thread; ESRCH for a thread that has gone.
 * </ul>
 *
 * Failures are injected per call; every call is recorded, and so is every descriptor left open or
 * closed twice.
 */
final class FakeLinux implements LinuxNative {

    final FakeProc proc;
    final List<String> calls = new ArrayList<>();
    final int ownPid = 4_000;

    Optional<String> unreachable = Optional.empty();
    /** The errno of every pidfd_open, when not zero. */
    int openErrno;
    /** The errno of signal 0 sent to Prism's own process, the probe. */
    int probeSignalErrno;
    /** The errno of signals to a process, by its PID. */
    final Map<Integer, Integer> signalErrno = new HashMap<>();
    /** The errno of setpriority, by thread. */
    final Map<Integer, Integer> priorityErrno = new HashMap<>();
    /** Threads whose nice value setpriority does not change, though it answers success. */
    final Set<Integer> stubbornThreads = new HashSet<>();
    /** Thrown by pidfd_open, when set: a native layer that fails in an unexpected way. */
    RuntimeException openThrows;
    /** Runs right after a pidfd was opened. */
    Runnable afterOpen = () -> {};
    /** Runs before the descriptor is made: a process that takes the PID here is the one the descriptor names. */
    Runnable beforeOpen = () -> {};
    /** Runs after each setpriority, with the thread it set. */
    IntConsumer afterSetPriority = tid -> {};

    private final Map<Integer, Integer> fdPid = new HashMap<>();
    private final Map<Integer, Long> fdStart = new HashMap<>();
    private int nextFd = 10;
    private int doubleCloses;

    FakeLinux(FakeProc proc) {
        this.proc = proc;
        proc.process(ownPid, "java", 50);
    }

    @Override
    public Optional<String> pidfdUnreachable() {
        return unreachable;
    }

    @Override
    public Call pidfdOpen(int pid) {
        calls.add("pidfd_open " + pid);
        beforeOpen.run();
        if (openThrows != null) {
            throw openThrows;
        }
        if (openErrno != 0) {
            return Call.failed(openErrno);
        }
        if (!proc.exists(pid)) {
            return Call.failed(LinuxErrors.ESRCH);
        }
        int fd = nextFd++;
        fdPid.put(fd, pid);
        fdStart.put(fd, proc.startTicks(pid));
        afterOpen.run();
        return Call.ok(fd);
    }

    @Override
    public Call pidfdSendSignal(int fd, int signal) {
        calls.add("pidfd_send_signal " + fd + " " + signal);
        Integer pid = fdPid.get(fd);
        if (pid == null) {
            return Call.failed(LinuxErrors.EBADF);
        }
        if (pid == ownPid && signal == 0 && probeSignalErrno != 0) {
            return Call.failed(probeSignalErrno);
        }
        if (!proc.exists(pid) || proc.startTicks(pid) != fdStart.get(fd)) {
            return Call.failed(LinuxErrors.ESRCH);
        }
        int errno = signalErrno.getOrDefault(pid, 0);
        if (errno != 0) {
            return Call.failed(errno);
        }
        if (proc.state(pid) == 'Z') {
            return Call.ok(0);
        }
        switch (signal) {
            case LinuxProcessController.SIGSTOP -> proc.state(pid, 'T');
            case LinuxProcessController.SIGCONT -> {
                if (proc.state(pid) == 'T') {
                    proc.state(pid, 'S');
                }
            }
            case LinuxProcessController.SIGKILL -> proc.state(pid, 'Z');
            default -> {
                // SIGTERM is taken by the process; signal 0 checks only.
            }
        }
        return Call.ok(0);
    }

    @Override
    public Call close(int fd) {
        calls.add("close " + fd);
        if (fdPid.remove(fd) == null) {
            doubleCloses++;
            return Call.failed(LinuxErrors.EBADF);
        }
        fdStart.remove(fd);
        return Call.ok(0);
    }

    @Override
    public Call setPriority(int tid, int nice) {
        calls.add("setpriority " + tid + " " + nice);
        int errno = priorityErrno.getOrDefault(tid, 0);
        if (errno != 0) {
            return Call.failed(errno);
        }
        if (proc.processOf(tid) == 0) {
            return Call.failed(LinuxErrors.ESRCH);
        }
        if (!stubbornThreads.contains(tid)) {
            proc.setNice(tid, nice);
        }
        afterSetPriority.accept(tid);
        return Call.ok(0);
    }

    @Override
    public long clockTicksPerSecond() {
        return FakeProc.TICKS_PER_SECOND;
    }

    @Override
    public int ownPid() {
        return ownPid;
    }

    /** The process a descriptor refers to; zero when the descriptor is not open. */
    int pidOf(int fd) {
        return fdPid.getOrDefault(fd, 0);
    }

    /** Whether the descriptor still refers to the process it was opened for (alive, same start). */
    boolean refersToLiveProcess(int fd) {
        Integer pid = fdPid.get(fd);
        return pid != null && proc.exists(pid) && proc.startTicks(pid) == fdStart.get(fd);
    }

    /** The descriptors open now. */
    Set<Integer> openFds() {
        return Set.copyOf(fdPid.keySet());
    }

    int doubleCloses() {
        return doubleCloses;
    }
}
