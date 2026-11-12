package prism.srm.platform.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.Suspension;

/**
 * What one suspension on Windows did: the threads Prism suspended, each with the handle it was
 * suspended through, and the process, held open.
 *
 * <p>A thread is kept by its handle, not by its ID: the handle stays valid until it is closed,
 * also after the thread has ended (Learn, below), and while it is open the thread object exists, so
 * its ID is not given to another thread. That last point is not in the reference documentation;
 * Microsoft states it in https://devblogs.microsoft.com/oldnewthing/20110107-00/?p=11803 . Each thread carries exactly one increment of its suspend
 * count that Prism made. A hold is <em>owned</em> while that increment may still be outstanding
 * and becomes <em>released</em> when ResumeThread undid it or the thread was shown to have ended;
 * a released hold is never resumed again, and only then is its handle closed. So Prism never
 * forgets an increment it made, and never takes back one it did not make.
 * https://learn.microsoft.com/en-us/windows/win32/procthread/thread-handles-and-identifiers
 *
 * <p>The process handle keeps the process object, and with it the PID, from being reused while
 * the suspension exists, and answers whether the process has exited.
 */
final class WindowsSuspension implements Suspension {

    /**
     * The form of a memento: a version, then the thread ID and the thread's creation time (a
     * FILETIME) of every thread Prism still holds, for example {@code w1:5124@133712345678901234}.
     */
    static final String MEMENTO_VERSION = "w1:";

    /** One thread Prism suspended. */
    static final class Hold {
        private final long handle;
        private final int threadId;
        private boolean owned = true;
        private boolean closed;

        Hold(long handle, int threadId) {
            this.handle = handle;
            this.threadId = threadId;
        }

        long handle() {
            return handle;
        }

        int threadId() {
            return threadId;
        }
    }

    private final Win32 win32;
    private final PinnedProcess process;
    private final List<Hold> holds;
    private final int threads;

    WindowsSuspension(Win32 win32, PinnedProcess process, List<Hold> holds) {
        this.win32 = win32;
        this.process = process;
        this.holds = new ArrayList<>(holds);
        this.threads = holds.size();
    }

    @Override
    public ProcessIdentity identity() {
        return process.identity();
    }

    @Override
    public int threads() {
        return threads;
    }

    @Override
    public synchronized int outstanding() {
        int owned = 0;
        for (Hold hold : holds) {
            if (hold.owned) {
                owned++;
            }
        }
        return owned;
    }

    synchronized List<Hold> owned() {
        List<Hold> owned = new ArrayList<>();
        for (Hold hold : holds) {
            if (hold.owned) {
                owned.add(hold);
            }
        }
        return owned;
    }

    /** Prism's increment on this thread is undone, or the thread has ended: the handle is closed. */
    synchronized void released(Hold hold) {
        hold.owned = false;
        closeHold(hold);
    }

    /**
     * The threads Prism still holds, by ID and creation time, which together name one thread: a
     * thread ID is reused once its thread object is gone, the creation time tells the new thread
     * apart. Read now, through the handles Prism holds. If a creation time cannot be read, no
     * memento is given: an entry without one would let a later session act on a thread it cannot
     * tell apart, so it gets none to act on.
     */
    @Override
    public synchronized Optional<String> memento() {
        if (process.closed()) {
            return Optional.empty();
        }
        StringBuilder memento = new StringBuilder(MEMENTO_VERSION);
        boolean first = true;
        for (Hold hold : holds) {
            if (!hold.owned) {
                continue;
            }
            OptionalLong created = win32.threadCreationTime(hold.handle);
            if (created.isEmpty()) {
                return Optional.empty();
            }
            if (!first) {
                memento.append(',');
            }
            first = false;
            memento.append(Integer.toUnsignedString(hold.threadId)).append('@').append(created.getAsLong());
        }
        return Optional.of(memento.toString());
    }

    /** Whether {@link #close()} was called: no handle is left. */
    synchronized boolean closed() {
        return process.closed();
    }

    synchronized boolean processExited() {
        return process.exited();
    }

    /**
     * Closes the handles of the threads without undoing anything, and leaves the process handle,
     * which another suspension shares, open. For threads that were checked during an adoption that
     * did not go through: their one increment is not Prism's to keep track of.
     */
    synchronized void closeHandlesOnly() {
        for (Hold hold : holds) {
            hold.owned = false;
            closeHold(hold);
        }
    }

    @Override
    public synchronized void close() {
        for (Hold hold : holds) {
            closeHold(hold);
        }
        process.close();
    }

    private void closeHold(Hold hold) {
        if (!hold.closed) {
            hold.closed = true;
            win32.close(hold.handle);
        }
    }
}
