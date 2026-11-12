package prism.srm.platform.linux;

import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.Suspension;

/**
 * A stop Prism made with SIGSTOP, holding the pidfd it was sent through: the continue goes through
 * the same descriptor, so it reaches exactly the process that was stopped, or fails with ESRCH once
 * that process has gone, whatever has taken its PID since. The whole process is one unit. The
 * descriptor is closed exactly once: when the stop is undone or the process has gone, or by
 * {@link #close()}.
 */
final class LinuxSuspension implements Suspension {

    private final LinuxNative calls;
    private final ProcessIdentity identity;
    private final int fd;
    private int outstanding = 1;
    private boolean closed;

    LinuxSuspension(LinuxNative calls, ProcessIdentity identity, int fd) {
        this.calls = calls;
        this.identity = identity;
        this.fd = fd;
    }

    @Override
    public ProcessIdentity identity() {
        return identity;
    }

    @Override
    public int threads() {
        return 1;
    }

    @Override
    public synchronized int outstanding() {
        return outstanding;
    }

    /** The descriptor the stop was sent through; the stop must not be closed yet. */
    synchronized int fd() {
        if (closed) {
            throw new IllegalStateException("The pidfd of PID " + identity.pid() + " is closed");
        }
        return fd;
    }

    /** The stop is undone, or the process has gone: nothing is held any more, and the descriptor is closed. */
    synchronized void spent() {
        outstanding = 0;
        close();
    }

    synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            calls.close(fd);
        }
    }
}
