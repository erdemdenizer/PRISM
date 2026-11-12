package prism.srm.platform.macos;

import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;

/**
 * The errno values process control meets on macOS and what each comes to (CTL-2, SAFE-4); the
 * values of xnu's bsd/sys/errno.h. A pure mapping, so that it can be tested whole.
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/errno.h
 */
final class MacosErrors {

    static final int EPERM = 1;
    static final int ESRCH = 3;
    static final int ENOMEM = 12;
    static final int EACCES = 13;
    static final int EINVAL = 22;

    private MacosErrors() {}

    /**
     * The result an action comes to when a call failed with the errno: the process is gone (ESRCH),
     * the system refused (EPERM: another user's process, or launchd; EACCES: a nice value lowered
     * without the privilege), or anything else, which is a native error.
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

    static String describe(int errno) {
        String name = switch (errno) {
            case EPERM -> "EPERM";
            case ESRCH -> "ESRCH";
            case ENOMEM -> "ENOMEM";
            case EACCES -> "EACCES";
            case EINVAL -> "EINVAL";
            default -> "errno";
        };
        return name + " (" + errno + ")";
    }
}
