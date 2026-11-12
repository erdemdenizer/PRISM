package prism.srm.platform;

import java.util.Map;
import prism.srm.domain.ProcessIdentity;

/**
 * The size of the virtual address space of every process, on a system where the process listing
 * of the cross-platform metrics library does not hold that value and the system has to be asked
 * in its own way.
 */
public interface ProcessVirtualSizeSource {

    /**
     * Returns the virtual size in bytes of every process the system lists, from one query for all
     * of them. The key is the full identity, so a value can never be given to another process
     * that has taken over the PID. A process that is not in the map has no known virtual size.
     *
     * <p>Queries the operating system: never call it on the JavaFX Application Thread. A query
     * that fails throws a {@link RuntimeException}.
     */
    Map<ProcessIdentity, Long> virtualSizes();
}
