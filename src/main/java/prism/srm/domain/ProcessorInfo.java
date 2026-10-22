package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Static facts about the processor. The physical core count, package count and name are absent
 * when the system does not report them.
 */
public record ProcessorInfo(
        int logicalProcessorCount, OptionalInt physicalCoreCount, OptionalInt packageCount, Optional<String> name) {

    public ProcessorInfo {
        Objects.requireNonNull(physicalCoreCount, "physicalCoreCount");
        Objects.requireNonNull(packageCount, "packageCount");
        Objects.requireNonNull(name, "name");
        if (logicalProcessorCount < 1) {
            throw new IllegalArgumentException("logicalProcessorCount must be positive: " + logicalProcessorCount);
        }
        if (physicalCoreCount.isPresent() && physicalCoreCount.getAsInt() < 1) {
            throw new IllegalArgumentException("physicalCoreCount must be positive: " + physicalCoreCount);
        }
        if (packageCount.isPresent() && packageCount.getAsInt() < 1) {
            throw new IllegalArgumentException("packageCount must be positive: " + packageCount);
        }
    }
}
