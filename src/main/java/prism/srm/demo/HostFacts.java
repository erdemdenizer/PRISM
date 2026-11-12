package prism.srm.demo;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.OptionalLong;

/**
 * What the workload needs to know about the machine: the logical processors, against which the
 * whole-machine CPU share is computed, and the physical memory, from which the memory cap follows.
 *
 * @param logicalProcessors the processors the JVM sees, at least one
 * @param physicalMemoryBytes the physical memory of the machine, or a non-positive number when unknown
 */
record HostFacts(int logicalProcessors, long physicalMemoryBytes) {

    HostFacts {
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException("a machine has at least one logical processor");
        }
    }

    /** Reads the facts of this machine through the JDK alone. */
    static HostFacts detect() {
        OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        long physical =
                bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getTotalMemorySize() : 0;
        return new HostFacts(Runtime.getRuntime().availableProcessors(), physical);
    }

    OptionalLong memoryCap() {
        return MemoryBudget.cap(physicalMemoryBytes);
    }
}
