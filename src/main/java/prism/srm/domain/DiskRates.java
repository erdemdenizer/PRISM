package prism.srm.domain;

/**
 * Disk transfer rates summed over the {@link DeviceLayer#PHYSICAL} disks, in bytes per second. A
 * logical disk on top of another one is not part of the sum.
 */
public record DiskRates(double readBytesPerSecond, double writeBytesPerSecond) {

    public DiskRates {
        if (!isRate(readBytesPerSecond) || !isRate(writeBytesPerSecond)) {
            throw new IllegalArgumentException("Expected finite, non-negative rates: read=" + readBytesPerSecond
                    + ", write=" + writeBytesPerSecond);
        }
    }

    private static boolean isRate(double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
