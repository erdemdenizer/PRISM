package prism.srm.demo;

import java.util.OptionalLong;

/**
 * DEMO-2: the memory the workload holds on purpose is never above the smaller of 1 GiB and a quarter
 * of the physical memory of the machine. The memory of the JVM itself comes on top of that and is
 * not part of the bound.
 */
final class MemoryBudget {

    static final long ABSOLUTE_CAP_BYTES = Quantities.GIB;
    static final int PHYSICAL_MEMORY_DIVISOR = 4;

    private MemoryBudget() {}

    /**
     * The cap for a machine with the given physical memory. Empty when the size is unknown (not
     * positive): then no cap can be computed, and no memory may be requested at all.
     */
    static OptionalLong cap(long physicalMemoryBytes) {
        if (physicalMemoryBytes <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.min(ABSOLUTE_CAP_BYTES, physicalMemoryBytes / PHYSICAL_MEMORY_DIVISOR));
    }
}
