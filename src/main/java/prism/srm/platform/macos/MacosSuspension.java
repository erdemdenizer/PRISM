package prism.srm.platform.macos;

import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.Suspension;

/**
 * A stop Prism made with SIGSTOP. macOS has no documented descriptor that refers to one process,
 * so the stop holds only the identity, which is checked again before the continue. The whole
 * process is one unit. Holds no operating-system resource.
 */
final class MacosSuspension implements Suspension {

    private final ProcessIdentity identity;
    private int outstanding = 1;

    MacosSuspension(ProcessIdentity identity) {
        this.identity = identity;
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

    /** The stop is undone, or the process has gone: nothing is held any more. */
    synchronized void spent() {
        outstanding = 0;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
