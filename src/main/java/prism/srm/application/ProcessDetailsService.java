package prism.srm.application;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ProcessSource;

/**
 * Reads the executable path and the command line of one process when somebody asks for them
 * (PROC-5). They are too expensive to read for every process on every cycle, so the process
 * monitor never reads them; this service reads them for the one process asked for, on the
 * executor it is given, and hands the result to its listener.
 *
 * <p>Every request the executor runs gets exactly one result, which names the process that was
 * asked for; a request the executor drops gets none. A process that has exited, or whose PID
 * another process has taken over, gives {@link ProcessDetailsResult.Outcome#PROCESS_GONE}, also
 * if the source should answer with the details of that other process. A {@link RuntimeException} from the source is
 * logged once per kind and gives {@link ProcessDetailsResult.Outcome#FAILED}. An {@link Error} is
 * not caught: it ends the thread it happened on, and the listener is told from a {@code finally}
 * block that the read failed, so that nobody waits for an answer that will not come.
 */
public final class ProcessDetailsService {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessDetailsService.class);

    private final ProcessSource source;
    private final Executor executor;
    private final Consumer<ProcessDetailsResult> listener;

    // Used by the reads only. The executor runs one at a time; the lock is for one that does not.
    private final FailureLog failures = new FailureLog(LOG);

    /**
     * @param executor runs the reads, off the JavaFX Application Thread; owned by the caller. It
     *     decides what happens to requests that arrive faster than they can be served.
     * @param listener receives the result of every read, on the thread of the read, and must
     *     return quickly
     */
    public ProcessDetailsService(ProcessSource source, Executor executor, Consumer<ProcessDetailsResult> listener) {
        this.source = Objects.requireNonNull(source, "source");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Asks for the details of one process and returns at once; nothing is read on the calling
     * thread. May be called from any thread.
     */
    public void request(ProcessIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        executor.execute(() -> read(identity));
    }

    private void read(ProcessIdentity identity) {
        // What the listener is told if anything at all leaves the read.
        ProcessDetailsResult result = ProcessDetailsResult.failed(identity);
        try {
            result = source.details(identity)
                    // Details that name another identity are not those of the process asked for.
                    .filter(details -> details.identity().equals(identity))
                    .map(ProcessDetailsResult::read)
                    .orElseGet(() -> ProcessDetailsResult.gone(identity));
        } catch (RuntimeException e) {
            warnOnce("Reading the details of a process", e);
        } finally {
            deliver(result);
        }
    }

    private void deliver(ProcessDetailsResult result) {
        try {
            listener.accept(result);
        } catch (RuntimeException e) {
            // Must not take the place of an Error that is on its way out of the read.
            warnOnce("The listener for process details", e);
        }
    }

    private void warnOnce(String what, RuntimeException failure) {
        synchronized (failures) {
            failures.warnOnce(what, failure);
        }
    }
}
