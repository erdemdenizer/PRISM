package prism.srm.platform.linux;

import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;

/**
 * The errno values process control meets on Linux and what each comes to (CTL-2, SAFE-4). The
 * values are those of include/uapi/asm-generic/errno-base.h and errno.h, which x86_64 and aarch64
 * use. A pure mapping, so that it can be tested whole.
 * https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/errno-base.h
 */
final class LinuxErrors {

    static final int EPERM = 1;
    static final int ENOENT = 2;
    static final int ESRCH = 3;
    static final int EBADF = 9;
    static final int ENOMEM = 12;
    static final int EACCES = 13;
    static final int ENODEV = 19;
    static final int EINVAL = 22;
    static final int ENFILE = 23;
    static final int EMFILE = 24;
    static final int ENOSYS = 38;

    private LinuxErrors() {}

    /**
     * The result an action comes to when a call on the target failed with the errno: the process is
     * gone (ESRCH), the system refused (EPERM: not permitted for this caller and target; EACCES: a
     * nice value lowered without the privilege), or anything else, which is a native error.
     */
    static OperationResult result(int errno) {
        return switch (errno) {
            case ESRCH -> OperationResult.PROCESS_NOT_FOUND;
            case EPERM, EACCES -> OperationResult.ACCESS_DENIED;
            default -> OperationResult.NATIVE_ERROR;
        };
    }

    /** The outcome of a failed call, with the errno for the log. */
    static ControlOutcome outcome(String call, int errno) {
        return ControlOutcome.of(result(errno), errno, call + " failed: " + describe(errno));
    }

    /**
     * Whether a failure of the pidfd calls on Prism's own process means that the mechanism is not
     * there: the system call does not exist (ENOSYS), a seccomp filter refuses it (EPERM, which
     * pidfd_open does not document for itself), or the anonymous inode file system is missing
     * (ENODEV). Any other failure is unexpected, and is reported as such.
     */
    static boolean meansNoPidfd(int errno) {
        return errno == ENOSYS || errno == EPERM || errno == ENODEV;
    }

    static String describe(int errno) {
        String name = switch (errno) {
            case EPERM -> "EPERM";
            case ENOENT -> "ENOENT";
            case ESRCH -> "ESRCH";
            case EBADF -> "EBADF";
            case ENOMEM -> "ENOMEM";
            case EACCES -> "EACCES";
            case ENODEV -> "ENODEV";
            case EINVAL -> "EINVAL";
            case ENFILE -> "ENFILE";
            case EMFILE -> "EMFILE";
            case ENOSYS -> "ENOSYS";
            default -> "errno";
        };
        return name + " (" + errno + ")";
    }
}
