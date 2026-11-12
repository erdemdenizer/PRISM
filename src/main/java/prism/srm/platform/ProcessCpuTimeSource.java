package prism.srm.platform;

import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.ProcessIdentity;

/**
 * The processor time of one process, where OSHI's lookup of a single process is too costly to
 * make once a second: on Windows OSHI builds its maps of every process for it. An implementation
 * package supplies it through this interface, because the implementation packages may not depend
 * on each other.
 */
@FunctionalInterface
public interface ProcessCpuTimeSource {

    /** See {@link ProcessSource#cpuTime}. */
    CpuTimeReading cpuTime(ProcessIdentity identity);
}
