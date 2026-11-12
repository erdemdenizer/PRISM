package prism.srm.domain;

/**
 * The global switches of OOM Guard (OOM-5, UI-5): monitoring, and in a group of their own the three
 * kinds of automatic intervention.
 *
 * @param monitoring whether OOM Guard watches memory pressure at all
 * @param priorityReduction automatic priority reduction at CRITICAL
 * @param autoSuspend automatic suspension at EMERGENCY
 * @param termination emergency termination at EMERGENCY
 */
public record OomSwitches(boolean monitoring, boolean priorityReduction, boolean autoSuspend, boolean termination) {

    /** OOM-5: monitoring on, every automatic intervention off. */
    public static final OomSwitches DEFAULTS = new OomSwitches(true, false, false, false);

    public OomSwitches withMonitoring(boolean value) {
        return new OomSwitches(value, priorityReduction, autoSuspend, termination);
    }

    public OomSwitches withPriorityReduction(boolean value) {
        return new OomSwitches(monitoring, value, autoSuspend, termination);
    }

    public OomSwitches withAutoSuspend(boolean value) {
        return new OomSwitches(monitoring, priorityReduction, value, termination);
    }

    public OomSwitches withTermination(boolean value) {
        return new OomSwitches(monitoring, priorityReduction, autoSuspend, value);
    }
}
