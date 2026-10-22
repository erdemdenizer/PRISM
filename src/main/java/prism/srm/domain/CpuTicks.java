package prism.srm.domain;

/**
 * Cumulative CPU time counters of one logical processor, or of the whole machine, since boot.
 * The unit is whatever the system counts in; only the ratio of two differences is meaningful.
 *
 * @param idle time spent idle, including waiting for I/O
 * @param total all time accounted for, idle included
 */
public record CpuTicks(long idle, long total) {

    public CpuTicks {
        if (idle < 0 || total < idle) {
            throw new IllegalArgumentException("Expected 0 <= idle <= total: idle=" + idle + ", total=" + total);
        }
    }
}
