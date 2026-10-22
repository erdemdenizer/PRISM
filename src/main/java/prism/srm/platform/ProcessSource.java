package prism.srm.platform;

import java.util.List;
import java.util.Optional;
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
}
