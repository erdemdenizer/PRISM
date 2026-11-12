package prism.srm.domain;

/**
 * Which kind of memory boundary a native memory limit is (milestone 12, plan Rev 2.1 §3, K5). Windows Job Objects
 * provide only {@link #HARD}; on Linux the two kinds map to the cgroup v2 files {@code memory.max} and
 * {@code memory.high}.
 */
public enum MemoryBoundary {

    /**
     * A hard boundary: on Windows the committed-memory limit of a Job Object (allocations beyond it fail); on Linux
     * {@code memory.max}, where the kernel may invoke the OOM killer inside the cgroup when usage cannot be reduced.
     * The only kind milestone 10 knows; the default wherever no kind is named.
     */
    HARD,

    /**
     * A throttling boundary: on Linux {@code memory.high}. Above it the processes are throttled and put under
     * reclaim pressure; it never invokes the OOM killer and may be breached under extreme conditions. Not available
     * with Job Objects.
     */
    THROTTLE
}
