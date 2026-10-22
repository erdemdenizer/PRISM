package prism.srm.domain;

/** Size and use of the swap space or pagefile, in bytes. */
public record SwapUsage(long totalBytes, long usedBytes) {

    public SwapUsage {
        if (totalBytes <= 0 || usedBytes < 0) {
            throw new IllegalArgumentException(
                    "Expected totalBytes > 0 and usedBytes >= 0: total=" + totalBytes + ", used=" + usedBytes);
        }
    }
}
