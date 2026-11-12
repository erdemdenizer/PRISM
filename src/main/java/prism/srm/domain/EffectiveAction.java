package prism.srm.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What OOM Guard would do to a Managed Process now (OOM-6, OOM-8): its emergency action as far as the
 * switches and the system allow, and whether it lowers the priority at CRITICAL. An action that is not
 * allowed falls back to Notify only, never to another automatic action (plan O8), and says why.
 *
 * @param action what happens at EMERGENCY
 * @param lowersAtCritical whether the priority is lowered at CRITICAL (OOM-6: "Lower priority or
 *     stronger", with automatic priority reduction on)
 * @param notInEffect why the chosen emergency action is not in effect; empty when it is, or when
 *     Notify only was chosen
 */
public record EffectiveAction(EmergencyAction action, boolean lowersAtCritical, Optional<String> notInEffect) {

    public static final String OOM_MANAGEMENT_OFF = "OOM management is off for this process";
    public static final String NOT_VALIDATED = "not available on this system: memory signals are not validated";
    public static final String NOT_RESTORABLE =
            "not available on this system: a lowered priority cannot be raised back without privilege";
    public static final String PRIORITY_OFF = "automatic priority reduction is off";
    public static final String SUSPEND_OFF = "automatic suspend is off";
    public static final String TERMINATION_OFF = "emergency termination is off";
    public static final String JOURNAL = "the suspension journal cannot be written";

    public EffectiveAction {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(notInEffect, "notInEffect");
        if (notInEffect.isPresent() && action != EmergencyAction.NOTIFY) {
            throw new IllegalArgumentException("An action not in effect falls back to Notify only: " + action);
        }
    }

    /**
     * The action in effect.
     *
     * @param capabilities how each action is provided on this system: a suspension needs Suspend and
     *     Resume, a termination Force Kill, a lowering Change Priority
     * @param journalHealthy whether the suspension journal can be written: no automatic suspension
     *     without it (ADR-0011)
     */
    public static EffectiveAction of(
            OomManagement oom,
            OomSwitches switches,
            Map<ControlAction, Capability> capabilities,
            boolean journalHealthy,
            Restorability restorability,
            OomSignalStatus signal) {
        Objects.requireNonNull(oom, "oom");
        Objects.requireNonNull(switches, "switches");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(restorability, "restorability");
        Objects.requireNonNull(signal, "signal");
        if (!oom.managed()) {
            return new EffectiveAction(EmergencyAction.NOTIFY, false, Optional.of(OOM_MANAGEMENT_OFF));
        }
        EmergencyAction chosen = oom.action();
        if (chosen == EmergencyAction.NOTIFY) {
            return new EffectiveAction(EmergencyAction.NOTIFY, false, Optional.empty());
        }
        Optional<String> lowering = whyNotLowering(switches, capabilities, restorability, signal);
        boolean lowers = lowering.isEmpty();
        Optional<String> why = switch (chosen) {
            case NOTIFY -> Optional.empty();
            case LOWER_PRIORITY -> lowering;
            case SUSPEND -> whyNotSuspending(switches, capabilities, journalHealthy, signal);
            case TERMINATE -> whyNotTerminating(switches, capabilities, signal);
        };
        return why.isPresent()
                ? new EffectiveAction(EmergencyAction.NOTIFY, lowers, why)
                : new EffectiveAction(chosen, lowers, Optional.empty());
    }

    /** Why the priority of a process is not lowered automatically; empty when it is. */
    public static Optional<String> whyNotLowering(
            OomSwitches switches,
            Map<ControlAction, Capability> capabilities,
            Restorability restorability,
            OomSignalStatus signal) {
        if (signal != OomSignalStatus.VALIDATED) {
            return Optional.of(NOT_VALIDATED);
        }
        if (!available(capabilities, ControlAction.CHANGE_PRIORITY)) {
            return Optional.of("not available on this system: priorities cannot be changed");
        }
        if (restorability != Restorability.RESTORABLE) {
            return Optional.of(NOT_RESTORABLE);
        }
        return switches.priorityReduction() ? Optional.empty() : Optional.of(PRIORITY_OFF);
    }

    /** Why a process is not suspended automatically; empty when it is. */
    public static Optional<String> whyNotSuspending(
            OomSwitches switches,
            Map<ControlAction, Capability> capabilities,
            boolean journalHealthy,
            OomSignalStatus signal) {
        if (signal != OomSignalStatus.VALIDATED) {
            return Optional.of(NOT_VALIDATED);
        }
        if (!available(capabilities, ControlAction.SUSPEND) || !available(capabilities, ControlAction.RESUME)) {
            return Optional.of("not available on this system: processes cannot be suspended and resumed");
        }
        if (!switches.autoSuspend()) {
            return Optional.of(SUSPEND_OFF);
        }
        return journalHealthy ? Optional.empty() : Optional.of(JOURNAL);
    }

    /** Why a process is not terminated automatically; empty when it is. */
    public static Optional<String> whyNotTerminating(
            OomSwitches switches, Map<ControlAction, Capability> capabilities, OomSignalStatus signal) {
        if (signal != OomSignalStatus.VALIDATED) {
            return Optional.of(NOT_VALIDATED);
        }
        if (!available(capabilities, ControlAction.FORCE_KILL)) {
            return Optional.of("not available on this system: processes cannot be terminated");
        }
        return switches.termination() ? Optional.empty() : Optional.of(TERMINATION_OFF);
    }

    private static boolean available(Map<ControlAction, Capability> capabilities, ControlAction action) {
        return capabilities.getOrDefault(action, Capability.UNAVAILABLE).available();
    }
}
