package prism.srm.domain;

import java.util.Objects;

/**
 * The OOM settings as published, with the number of their publication. An observation carries the
 * number it was made under, so that an observation made before a change never authorizes an action
 * under the changed switches (ADR-0015, the gate).
 *
 * @param version 1 for the settings read at start-up, one more for each change
 */
public record VersionedSettings(long version, OomSettings settings) {

    public VersionedSettings {
        Objects.requireNonNull(settings, "settings");
        if (version < 1) {
            throw new IllegalArgumentException("A version starts at 1: " + version);
        }
    }
}
