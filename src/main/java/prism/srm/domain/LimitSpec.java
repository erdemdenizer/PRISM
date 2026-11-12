package prism.srm.domain;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The native limits Prism means a process to have: a CPU limit as a whole percentage of the whole
 * machine (LIM-2) and a memory limit in bytes of the given boundary; either may be absent, and {@link #NONE}
 * asks for no limit at all.
 *
 * @param memoryBoundary which kind of memory boundary is meant (milestone 12): {@link MemoryBoundary#HARD}, the only
 *     one milestone 10 knows and the default, or {@link MemoryBoundary#THROTTLE}. Without a memory limit it is always
 *     {@code HARD}.
 */
public record LimitSpec(OptionalInt cpuPercent, OptionalLong memoryBytes, MemoryBoundary memoryBoundary) {

    public static final LimitSpec NONE = new LimitSpec(OptionalInt.empty(), OptionalLong.empty());

    public LimitSpec {
        Objects.requireNonNull(cpuPercent, "cpuPercent");
        Objects.requireNonNull(memoryBytes, "memoryBytes");
        Objects.requireNonNull(memoryBoundary, "memoryBoundary");
        if (cpuPercent.isPresent() && !ManagedSettings.validCpuPercent(cpuPercent.getAsInt())) {
            throw new IllegalArgumentException("A CPU limit is a whole percentage from 1 to 100: " + cpuPercent);
        }
        if (memoryBytes.isPresent() && !ManagedSettings.validMemoryBytes(memoryBytes.getAsLong())) {
            throw new IllegalArgumentException("Not a memory limit: " + memoryBytes);
        }
        if (memoryBytes.isEmpty()) {
            memoryBoundary = MemoryBoundary.HARD;
        }
    }

    /** Limits whose memory limit, if any, is a hard one: what every milestone 10 limit is. */
    public LimitSpec(OptionalInt cpuPercent, OptionalLong memoryBytes) {
        this(cpuPercent, memoryBytes, MemoryBoundary.HARD);
    }

    /** What the system holds once these limits are in force: the rate is the percentage times 100. */
    public EffectiveLimits effective() {
        return EffectiveLimits.of(
                cpuPercent.isPresent() ? OptionalInt.of(cpuPercent.getAsInt() * 100) : OptionalInt.empty(),
                memoryBytes,
                memoryBoundary);
    }

    public boolean none() {
        return cpuPercent.isEmpty() && memoryBytes.isEmpty();
    }

    public LimitSpec withCpuPercent(OptionalInt percent) {
        return new LimitSpec(percent, memoryBytes, memoryBoundary);
    }

    public LimitSpec withMemoryBytes(OptionalLong bytes) {
        return new LimitSpec(cpuPercent, bytes, memoryBoundary);
    }

    public LimitSpec withMemoryBoundary(MemoryBoundary boundary) {
        return new LimitSpec(cpuPercent, memoryBytes, boundary);
    }
}
