package prism.srm.domain;

import java.util.Objects;

/**
 * Capacity of one local file system, in bytes.
 *
 * @param name the system's label for the file system
 * @param mountPoint where it is mounted, for example {@code C:\} or {@code /home}
 * @param usableBytes space available to the current user
 */
public record FileSystemUsage(String name, String mountPoint, long totalBytes, long usableBytes) {

    public FileSystemUsage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mountPoint, "mountPoint");
        if (totalBytes < 0 || usableBytes < 0) {
            throw new IllegalArgumentException(
                    "Sizes must not be negative: total=" + totalBytes + ", usable=" + usableBytes);
        }
    }
}
