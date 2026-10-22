package prism.srm.ui.metric;

/** Whether a part of a page has a value to show, and if not, why not. */
public enum MetricState {
    /** Monitoring has not run long enough to have the value. */
    WAITING("Reading…"),
    /** There is a current value. */
    LIVE(""),
    /** Monitoring runs and has no value: the system does not report it, or reading it failed. */
    UNAVAILABLE("Unavailable");

    /** A rate needs two readings, so a value missing from the first cycle is not missing yet. */
    private static final int CYCLES_BEFORE_A_VALUE_IS_DUE = 2;

    private final String placeholder;

    MetricState(String placeholder) {
        this.placeholder = placeholder;
    }

    /** The text shown in place of the value; empty while there is a value. */
    public String placeholder() {
        return placeholder;
    }

    /**
     * @param present whether the snapshot has the value
     * @param cycles the number of monitoring cycles so far, as far as the histories show it
     */
    public static MetricState of(boolean present, int cycles) {
        if (present) {
            return LIVE;
        }
        return cycles < CYCLES_BEFORE_A_VALUE_IS_DUE ? WAITING : UNAVAILABLE;
    }
}
