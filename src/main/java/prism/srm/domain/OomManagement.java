package prism.srm.domain;

import java.util.Objects;

/**
 * The OOM settings of one Managed Process (MGD-3): whether OOM Guard may act on it at all, which
 * candidates come first, and what it does to it in an emergency. Per session, like the managed
 * list (MGD-2).
 *
 * @param managed whether OOM management is on for the process; off, OOM Guard never acts on it
 *     (OOM-7)
 */
public record OomManagement(boolean managed, OomPriority priority, EmergencyAction action) {

    /** A process just managed: OOM management off, normal priority, Notify only. */
    public static final OomManagement OFF = new OomManagement(false, OomPriority.NORMAL, EmergencyAction.NOTIFY);

    /** OOM management turned on with the defaults: normal priority, Notify only. */
    public static final OomManagement ON = new OomManagement(true, OomPriority.NORMAL, EmergencyAction.NOTIFY);

    public OomManagement {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(action, "action");
    }

    public OomManagement withManaged(boolean value) {
        return new OomManagement(value, priority, action);
    }
}
