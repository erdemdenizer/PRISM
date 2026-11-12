package prism.srm.platform.linux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;

/**
 * Sets a nice value on every thread of a process (CTL-6 on Linux). The nice value is a per-thread
 * attribute on Linux and {@code setpriority(PRIO_PROCESS, tid)} sets one thread, so each thread
 * listed in {@code /proc/<pid>/task} is set, the list is read once more for threads started
 * meanwhile, and every thread is read back from its own stat file (field 19).
 * https://man7.org/linux/man-pages/man2/setpriority.2.html
 * https://man7.org/linux/man-pages/man7/sched.7.html
 *
 * <p>After every listing, the caller says whether the identity has gone (through its pidfd where
 * there is one): a process that has not been collected keeps its PID, so a listing read before
 * that answer is the process's own. Between reading a thread ID and setting it, the thread may end
 * and its ID be handed to another, which needs the kernel to go round its whole PID space in that
 * moment: a race that is stated, not closed (ADR-0010).
 */
final class LinuxPriority {

    private final LinuxNative calls;
    private final ProcFs proc;

    LinuxPriority(LinuxNative calls, ProcFs proc) {
        this.calls = calls;
        this.proc = proc;
    }

    /**
     * Sets the value on every thread and reads it back.
     *
     * @param gone whether the identity has exited or its PID belongs to another process now
     */
    ControlOutcome setEveryThread(int pid, int nice, BooleanSupplier gone) {
        Set<Integer> set = new LinkedHashSet<>();
        // Twice: a thread started while the first list was set may have taken the old value.
        for (int pass = 0; pass < 2; pass++) {
            ProcFs.Reading<List<Integer>> threads = proc.taskIds(pid);
            if (gone.getAsBoolean()) {
                return exited();
            }
            if (threads.status() != ProcFs.Status.FOUND) {
                return set.isEmpty()
                        ? unreadable(threads)
                        : ControlOutcome.of(
                                OperationResult.NATIVE_ERROR,
                                "the threads could not be listed again: " + threads.detail());
            }
            for (int tid : threads.get()) {
                if (set.contains(tid)) {
                    continue;
                }
                LinuxNative.Call done = calls.setPriority(tid, nice);
                if (!done.failed()) {
                    set.add(tid);
                } else if (done.errno() != LinuxErrors.ESRCH) {
                    if (set.isEmpty()) {
                        // Refused at the first thread: nothing was changed.
                        return LinuxErrors.outcome("setpriority", done.errno());
                    }
                    return ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            done.errno(),
                            "setpriority failed with " + LinuxErrors.describe(done.errno()) + " after " + set.size()
                                    + " thread(s) were set to nice " + nice);
                }
                // ESRCH: that thread has ended; it needs no priority.
            }
        }
        return readBack(pid, nice, gone);
    }

    /**
     * Success only if every thread the process has now holds the value. A thread whose stat file is
     * gone has ended and needs no value. A thread whose stat file could not be read (refused, or a
     * read that failed) is unverified, unless a second listing shows that it has ended meanwhile: a
     * thread still running that cannot be read back is never counted as holding the value.
     */
    private ControlOutcome readBack(int pid, int nice, BooleanSupplier gone) {
        ProcFs.Reading<List<Integer>> threads = proc.taskIds(pid);
        int holding = 0;
        int read = 0;
        Set<Integer> unread = new LinkedHashSet<>();
        if (threads.status() == ProcFs.Status.FOUND) {
            for (int tid : threads.get()) {
                ProcFs.Reading<ProcStat> thread = proc.threadStat(pid, tid);
                switch (thread.status()) {
                    case FOUND -> {
                        read++;
                        if (thread.get().nice() == nice) {
                            holding++;
                        }
                    }
                    case MISSING -> {
                        // The thread has ended since the listing.
                    }
                    case DENIED, FAILED -> unread.add(tid);
                }
            }
        }
        if (!unread.isEmpty()) {
            // A thread that ended between the listing and the read may fail the read: it is no
            // longer listed then. Without a listing, nothing says it has ended.
            ProcFs.Reading<List<Integer>> again = proc.taskIds(pid);
            if (again.status() == ProcFs.Status.FOUND) {
                unread.retainAll(Set.copyOf(again.get()));
            }
        }
        if (gone.getAsBoolean()) {
            return exited();
        }
        if (threads.status() != ProcFs.Status.FOUND) {
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, "the threads could not be read back: " + threads.detail());
        }
        if (!unread.isEmpty()) {
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR,
                    unread.size() + " running thread(s) could not be read back, " + holding + " of " + read
                            + " read hold nice " + nice + "; the value is unverified");
        }
        return read > 0 && holding == read
                ? ControlOutcome.success("nice " + nice + " on " + holding + " thread(s)")
                : ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        holding + " of " + read + " thread(s) read back hold nice " + nice);
    }

    private static ControlOutcome exited() {
        return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process exited during the change");
    }

    private static ControlOutcome unreadable(ProcFs.Reading<?> reading) {
        return switch (reading.status()) {
            case MISSING -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
            case DENIED -> ControlOutcome.of(OperationResult.ACCESS_DENIED, reading.detail());
            case FAILED, FOUND -> ControlOutcome.of(OperationResult.NATIVE_ERROR, reading.detail());
        };
    }
}
