package prism.srm.domain;

import java.util.Objects;

/**
 * One raw reading of a network interface's cumulative byte counters.
 *
 * @param name name that identifies the interface within a reading
 * @param displayName description of the interface for people
 * @param epoch changes whenever the counters cannot be compared with earlier readings of the same
 *     name: the interface was found again after it had gone, or its identity changed. Two
 *     readings form a valid pair only if name and epoch are both equal.
 */
public record NetworkCounters(
        String name, String displayName, DeviceLayer layer, long epoch, long bytesReceived, long bytesSent) {

    public NetworkCounters {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(layer, "layer");
        if (bytesReceived < 0 || bytesSent < 0) {
            throw new IllegalArgumentException(
                    "Counters must not be negative: received=" + bytesReceived + ", sent=" + bytesSent);
        }
    }
}
