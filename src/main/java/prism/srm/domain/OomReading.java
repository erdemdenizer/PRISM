package prism.srm.domain;

/**
 * One reading of physical memory as OOM Guard judged it (OOM-2).
 *
 * @param atNanos when it was taken, on the monotonic clock of the monitor
 */
public record OomReading(long availableBytes, long totalBytes, long atNanos) {

    public OomReading {
        if (totalBytes <= 0 || availableBytes < 0 || availableBytes > totalBytes) {
            throw new IllegalArgumentException("Expected 0 <= available <= total and total > 0: total=" + totalBytes
                    + ", available=" + availableBytes);
        }
    }

    /** The available share of the total, 0 to 100; for the user only, never to decide by. */
    public double availablePercent() {
        return 100.0 * availableBytes / totalBytes;
    }
}
