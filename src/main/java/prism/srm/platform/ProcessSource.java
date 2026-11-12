package prism.srm.platform;

import java.util.List;
import java.util.Optional;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;

/**
 * Raw readings of the running processes. The source computes no rates and keeps no history.
 *
 * <p>Every method queries the operating system, so none may be called on the JavaFX Application
 * Thread. A query that fails as a whole throws a {@link RuntimeException}.
 */
public interface ProcessSource {

    /**
     * Reads every process the system lists, with the values that are cheap enough to read for
     * all of them on every cycle.
     *
     * <p>Processes come and go while they are being read, and some may not be read by this user.
     * Neither fails the reading: a process that cannot be read at all is left out, and one that
     * can be read in part is returned with the rest of its values absent.
     *
     * <p>Called by one thread at a time: the monitor's sampling thread.
     */
    List<ProcessReading> processes();

    /**
     * Reads the expensive facts of one process, on demand. Returns empty if that process no
     * longer exists, which includes the case where its PID now belongs to another process.
     *
     * <p>May be called from any background thread, also while another thread is in {@link
     * #processes()} or in this method.
     */
    Optional<ProcessDetails> details(ProcessIdentity identity);
}
