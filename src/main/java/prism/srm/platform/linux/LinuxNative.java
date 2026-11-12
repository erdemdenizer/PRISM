package prism.srm.platform.linux;

import java.util.Optional;

/**
 * The system calls process control makes on Linux, each returning what the call returned and the
 * errno of a failure; nothing here decides what a result means. A seam: tests give a fake that
 * follows the man pages.
 */
interface LinuxNative {

    /** What a system call came to: its return value, and the errno when it failed (zero otherwise). */
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
     * Why the pidfd system calls cannot be made from here at all, without trying them: the
     * architecture is not one whose system call and signal numbers Prism has verified. Empty
     * where they can be tried.
     */
    Optional<String> pidfdUnreachable();

    /** {@code pidfd_open(pid, 0)}: the new descriptor, or the errno. */
    Call pidfdOpen(int pid);

    /** {@code pidfd_send_signal(fd, signal, NULL, 0)}: zero, or the errno. Signal 0 checks without sending. */
    Call pidfdSendSignal(int fd, int signal);

    /** {@code close(fd)}: zero, or the errno. */
    Call close(int fd);

    /** {@code setpriority(PRIO_PROCESS, tid, nice)}, which on Linux sets the nice value of that one thread. */
    Call setPriority(int tid, int nice);

    /** {@code sysconf(_SC_CLK_TCK)}: the clock ticks per second the times in {@code /proc} are counted in. */
    long clockTicksPerSecond();

    /** {@code getpid()}. */
    int ownPid();
}
