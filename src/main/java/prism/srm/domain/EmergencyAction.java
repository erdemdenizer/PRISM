package prism.srm.domain;

/**
 * What OOM Guard does to a Managed Process in an emergency (MGD-3), as the user chose it for the
 * process. Whether it takes effect depends on the global switches (OOM-8): see {@link EffectiveAction}.
 */
public enum EmergencyAction {
    /** A warning and a log entry only; the default. */
    NOTIFY,
    /** The priority is lowered to Low. */
    LOWER_PRIORITY,
    /** The process is suspended. */
    SUSPEND,
    /** The process is terminated at once (Force Kill), at most once per session. */
    TERMINATE;

    /** Whether this action is at least as strong as the other, in the order above (OOM-6). */
    public boolean atLeast(EmergencyAction other) {
        return compareTo(other) >= 0;
    }
}
