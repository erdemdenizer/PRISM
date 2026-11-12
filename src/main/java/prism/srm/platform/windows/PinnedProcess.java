package prism.srm.platform.windows;

import prism.srm.domain.ProcessIdentity;

/**
 * An open handle to a process whose identity has been checked. While it is open the process
 * object exists, so its PID is not given to another process: whatever is done through it is done
 * to the identity that was checked. The reference documentation does not say this; Microsoft does
 * in https://devblogs.microsoft.com/oldnewthing/20110107-00/?p=11803 . Closed exactly once.
 */
final class PinnedProcess implements AutoCloseable {

    private final Win32 win32;
    private final ProcessIdentity identity;
    private final long handle;
    private boolean closed;

    PinnedProcess(Win32 win32, ProcessIdentity identity, long handle) {
        this.win32 = win32;
        this.identity = identity;
        this.handle = handle;
    }

    ProcessIdentity identity() {
        return identity;
    }

    int pid() {
        return (int) identity.pid();
    }

    long handle() {
        if (closed) {
            throw new IllegalStateException("The handle of PID " + identity.pid() + " is closed");
        }
        return handle;
    }

    /** Whether the process has ended; false also when the system did not say. */
    boolean exited() {
        var code = win32.processExitCode(handle());
        return code.isPresent() && code.getAsInt() != Win32.STILL_ACTIVE;
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            win32.close(handle);
        }
    }
}
