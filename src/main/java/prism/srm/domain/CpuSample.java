package prism.srm.domain;

import java.util.List;
import java.util.Objects;

/**
 * One raw reading of the CPU time counters.
 *
 * @param total counters of the whole machine
 * @param perProcessor counters of each logical processor, in the system's processor order
 */
public record CpuSample(CpuTicks total, List<CpuTicks> perProcessor) {

    public CpuSample {
        Objects.requireNonNull(total, "total");
        perProcessor = List.copyOf(perProcessor);
    }
}
