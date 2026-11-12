package prism.srm.platform.macos;

import java.util.Objects;
import java.util.Optional;

/**
 * The calls process control makes on macOS, each returning what the call returned and the errno of
 * a failure; nothing here decides what a result means. A seam: tests give a fake that follows the
 * man pages.
 */
interface MacosNative {

    /** What a call came to: its return value, and the errno when it failed (zero otherwise). */
    record Call(long value, int errno) {

        static Call ok(long value) {
            return new Call(value, 0);
        }

        /** A failed call; without an errno it could not be told from a success, so there must be one. */
        static Call failed(int errno) {
            if (errno == 0) {
                throw new IllegalStateException("A failed call without an errno");
            }
            return new Call(-1, errno);
        }

        boolean failed() {
            return errno != 0;
        }
    }

    /**
     * The fields of a process's {@code struct kinfo_proc} that process control reads.
     *
     * @param pid {@code kp_proc.p_pid}, which must be the PID asked for
     * @param startMillis {@code kp_proc.p_starttime} in milliseconds since the epoch
     * @param flags {@code kp_proc.p_flag}, the P_* flags
     * @param state {@code kp_proc.p_stat}: SIDL, SRUN, SSLEEP, SSTOP, SZOMB
     */
    record ProcessInfo(int pid, long startMillis, int flags, int state) {}

    /** What a read of the process table found. */
    enum Status {
        FOUND,
        /** The table has no entry for the PID: no process has it. */
        MISSING,
        /** The read failed, or its answer was not an entry: nothing is known about the process. */
        FAILED
    }

    /**
     * What the process table said about one PID. Only {@link Status#MISSING} says that no process
     * has it; a failed read never does, so that it is never taken for an exit.
     */
    record Reading(Status status, Optional<ProcessInfo> info, int errno, String detail) {

        public Reading {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(detail, "detail");
            if (info.isPresent() != (status == Status.FOUND)) {
                throw new IllegalArgumentException("Only a found entry has its information: " + status);
            }
        }

        static Reading found(ProcessInfo info) {
            return new Reading(Status.FOUND, Optional.of(info), 0, "");
        }

        /** No process has the PID: the table returned nothing for it. */
        static Reading missing() {
            return new Reading(Status.MISSING, Optional.empty(), 0, "no process has the PID");
        }

        /** The read failed; {@code errno} is zero where the call succeeded but its answer was unusable. */
        static Reading failed(int errno, String detail) {
            return new Reading(Status.FAILED, Optional.empty(), errno, detail);
        }

        boolean missingProcess() {
            return status == Status.MISSING;
        }
    }

    /** {@code sysctl({CTL_KERN, KERN_PROC, KERN_PROC_PID, pid})}. */
    Reading processInfo(int pid);

    /** {@code kill(pid, signal)}: zero, or the errno. */
    Call kill(int pid, int signal);

    /** {@code setpriority(PRIO_PROCESS, pid, nice)}: zero, or the errno. */
    Call setPriority(int pid, int nice);

    /** {@code getpriority(PRIO_PROCESS, pid)} with errno cleared before: the value, or the errno when it failed. */
    Call getPriority(int pid);

    /** The file name of the executable the system gives for the process, if it gives one; for the name list only. */
    Optional<String> executable(int pid);
}
