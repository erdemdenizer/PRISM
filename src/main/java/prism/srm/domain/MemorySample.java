package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Physical memory at one instant, in bytes.
 *
 * @param swap swap space or pagefile; absent when the system has none
 */
public record MemorySample(long totalBytes, long availableBytes, Optional<SwapUsage> swap) {

    public MemorySample {
        Objects.requireNonNull(swap, "swap");
        if (totalBytes <= 0 || availableBytes < 0 || availableBytes > totalBytes) {
            throw new IllegalArgumentException("Expected 0 <= available <= total and total > 0: total=" + totalBytes
                    + ", available=" + availableBytes);
        }
    }

    /** Total minus available. */
    public long usedBytes() {
        return totalBytes - availableBytes;
    }

    /** Used memory as a share of the total, 0 to 100. */
    public double usedPercent() {
        return 100.0 * usedBytes() / totalBytes;
    }
}
