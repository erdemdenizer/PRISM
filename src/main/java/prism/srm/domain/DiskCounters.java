package prism.srm.domain;

import java.util.Objects;

/**
 * One raw reading of a disk's cumulative transfer counters.
 *
 * @param device name that identifies the disk within a reading
 * @param epoch changes whenever the counters cannot be compared with earlier readings of the same
 *     name: the disk was found again after it had gone, or another disk took its name. Two
 *     readings form a valid pair only if name and epoch are both equal.
 */
public record DiskCounters(String device, DeviceLayer layer, long epoch, long bytesRead, long bytesWritten) {

    public DiskCounters {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(layer, "layer");
        if (bytesRead < 0 || bytesWritten < 0) {
            throw new IllegalArgumentException(
                    "Counters must not be negative: read=" + bytesRead + ", written=" + bytesWritten);
        }
    }
}
