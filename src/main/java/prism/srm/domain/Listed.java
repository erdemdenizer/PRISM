package prism.srm.domain;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What OOM Guard keeps of one process from a listing: its resident memory and its priority as the
 * system numbers it. No snapshot is kept.
 */
public record Listed(OptionalLong residentBytes, OptionalInt priority) {

    public Listed {
        Objects.requireNonNull(residentBytes, "residentBytes");
        Objects.requireNonNull(priority, "priority");
    }

    public static Listed of(ProcessSnapshot process) {
        return new Listed(process.residentBytes(), process.priority());
    }
}
