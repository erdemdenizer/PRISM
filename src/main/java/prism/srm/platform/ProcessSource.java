package prism.srm.platform;

import java.util.List;
import java.util.Optional;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;

/**
 * Raw readings of the running processes. The source computes no rates and keeps no history.
 *
 * we use list data structure on this interface cus its very easy " import java.util.List; "
 */
public interface ProcessSource {

    /**
     * Reads every process the system lists
     */
    List<ProcessReading> processes();

    Optional<ProcessDetails> details(ProcessIdentity identity);

    /**
     * Reads the processor time one process has used so far, for the emulated CPU limiter, which
     * measures its target about once a second and must not list every process to do so. The read
     * is checked against the identity: {@link CpuTimeReading.Gone} when the process has exited or
     * its PID belongs to another process now; {@link CpuTimeReading.Unreadable} when the process
     * may still run but its time could not be read. Does not throw for either.
     *
     * <p>May be called from any background thread, also while another thread is in {@link
     * #processes()} or in this method.
     */
    CpuTimeReading cpuTime(ProcessIdentity identity);
}
