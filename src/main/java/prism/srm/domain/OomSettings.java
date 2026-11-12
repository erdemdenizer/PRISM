package prism.srm.domain;

import java.util.Objects;

/** The global settings of OOM Guard: its thresholds and its switches (SET-1). */
public record OomSettings(OomThresholds thresholds, OomSwitches switches) {

    public static final OomSettings DEFAULTS = new OomSettings(OomThresholds.DEFAULTS, OomSwitches.DEFAULTS);

    public OomSettings {
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(switches, "switches");
    }

    public OomSettings withThresholds(OomThresholds value) {
        return new OomSettings(value, switches);
    }

    public OomSettings withSwitches(OomSwitches value) {
        return new OomSettings(thresholds, value);
    }
}
