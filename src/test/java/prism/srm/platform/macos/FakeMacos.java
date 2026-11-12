package prism.srm.platform.macos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The calls of {@link MacosNative} as Apple's man pages and xnu describe them:
 *
 * <ul>
 *   <li>sysctl KERN_PROC_PID returns the entry of the process that has the PID now, or nothing.
 *   <li>kill reaches whatever process has the PID when it is called; ESRCH if none; it succeeds on
 *       a zombie, to no effect. SIGSTOP stops (SSTOP), SIGCONT continues (SRUN), SIGKILL ends
 *       (SZOMB).
 *   <li>setpriority and getpriority set and read the one nice value of the process.
 * </ul>
 */
final class FakeMacos implements MacosNative {

    static final int SRUN = 2;
    static final int SSLEEP = 3;

    static final class Proc {
        long startMillis;
        int flags;
        int state = SSLEEP;
        int nice;
        String executable;
    }

    final Map<Integer, Proc> processes = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    int sysctlErrno;
    /** sysctl succeeds but answers fewer bytes than the fields read. */
    boolean shortAnswer;
    /** The PID the entry says it is of, when not that asked for. */
    Integer reportedPid;

    final Map<Integer, Integer> killErrno = new HashMap<>();
    final Map<Integer, Integer> setPriorityErrno = new HashMap<>();
    int getPriorityErrno;
    final Set<Integer> stubborn = new HashSet<>();

    Proc process(int pid, long startMillis, String executable) {
        Proc proc = new Proc();
        proc.startMillis = startMillis;
        proc.executable = executable;
        processes.put(pid, proc);
        return proc;
    }

    @Override
    public Reading processInfo(int pid) {
        calls.add("sysctl " + pid);
        if (sysctlErrno != 0) {
            return Reading.failed(sysctlErrno, "sysctl KERN_PROC_PID");
        }
        if (shortAnswer) {
            return Reading.failed(0, "sysctl KERN_PROC_PID returned 12 bytes");
        }
        Proc proc = processes.get(pid);
        if (proc == null) {
            return Reading.missing();
        }
        return Reading.found(
                new ProcessInfo(reportedPid != null ? reportedPid : pid, proc.startMillis, proc.flags, proc.state));
    }

    @Override
    public Call kill(int pid, int signal) {
        calls.add("kill " + pid + " " + signal);
        int errno = killErrno.getOrDefault(pid, 0);
        if (errno != 0) {
            return Call.failed(errno);
        }
        Proc proc = processes.get(pid);
        if (proc == null) {
            return Call.failed(MacosErrors.ESRCH);
        }
        if (proc.state == MacosProcessController.SZOMB) {
            return Call.ok(0);
        }
        switch (signal) {
            case MacosProcessController.SIGSTOP -> proc.state = MacosProcessController.SSTOP;
            case MacosProcessController.SIGCONT -> {
                if (proc.state == MacosProcessController.SSTOP) {
                    proc.state = SRUN;
                }
            }
            case MacosProcessController.SIGKILL -> proc.state = MacosProcessController.SZOMB;
            default -> {
                // SIGTERM is taken by the process.
            }
        }
        return Call.ok(0);
    }

    @Override
    public Call setPriority(int pid, int nice) {
        calls.add("setpriority " + pid + " " + nice);
        int errno = setPriorityErrno.getOrDefault(pid, 0);
        if (errno != 0) {
            return Call.failed(errno);
        }
        Proc proc = processes.get(pid);
        if (proc == null) {
            return Call.failed(MacosErrors.ESRCH);
        }
        if (!stubborn.contains(pid)) {
            proc.nice = nice;
        }
        return Call.ok(0);
    }

    @Override
    public Call getPriority(int pid) {
        calls.add("getpriority " + pid);
        if (getPriorityErrno != 0) {
            return Call.failed(getPriorityErrno);
        }
        Proc proc = processes.get(pid);
        return proc == null ? Call.failed(MacosErrors.ESRCH) : Call.ok(proc.nice);
    }

    @Override
    public Optional<String> executable(int pid) {
        Proc proc = processes.get(pid);
        return proc == null ? Optional.empty() : Optional.ofNullable(proc.executable);
    }
}
