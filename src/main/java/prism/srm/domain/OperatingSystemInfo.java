package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;

/** Name, version and build of the running operating system. */
public record OperatingSystemInfo(String name, Optional<String> version, Optional<String> build) {

    public OperatingSystemInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(build, "build");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
