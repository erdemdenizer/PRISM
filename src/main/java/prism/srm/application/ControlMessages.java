package prism.srm.application;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.SuspensionReason;

/**
 * What the user reads for each result (CTL-2): a sentence that says what came of the request, and
 * where it helps, a second one with what the user may want to know beside it. Together they say
 * what Prism did, never what it has not seen: a close request is "sent", a termination
 * "requested"; whether the process then exited is what the process table shows. Native codes
 * never appear here; they go to the log. The one source of these words: the page shows the two
 * sentences apart, the activity log writes them as one ({@link ProcessControlResult#text()}).
 */
public final class ControlMessages {

    private ControlMessages() {}

    /** A result as the user reads it: the sentence first, then what it does not say; the second may be empty. */
    record Wording(String headline, String explanation) {
        static Wording of(String headline) {
            return new Wording(headline, "");
        }
    }

    /**
     * How the action is carried out on this system, which the words follow (CAP-5): its mechanism,
     * and whether the process table shows the priority levels, so a level that was set can be seen there.
     */
    record Means(Optional<ControlMechanism> mechanism, boolean levelsListed) {

        /** Nothing known about the means: the words of a close request, a per-thread suspension, a priority class. */
        static final Means UNKNOWN = new Means(Optional.empty(), true);

        Means {
            Objects.requireNonNull(mechanism, "mechanism");
        }

        boolean is(ControlMechanism candidate) {
            return mechanism.filter(candidate::equals).isPresent();
        }
    }

    /** The name of a level as the user interface writes it. */
    public static String levelName(PriorityLevel level) {
        return switch (level) {
            case LOW -> "Low";
            case BELOW_NORMAL -> "Below normal";
            case NORMAL -> "Normal";
            case ABOVE_NORMAL -> "Above normal";
            case HIGH -> "High";
        };
    }

    /** The name of an action as the user interface writes it. */
    public static String actionName(ControlAction action) {
        return switch (action) {
            case END_TASK -> "End task";
            case FORCE_KILL -> "Force kill";
            case SUSPEND -> "Suspend";
            case RESUME -> "Resume";
            case CHANGE_PRIORITY -> "Change priority";
        };
    }

    /** What the page says while a request is on its way: which action, on which process. */
    public static String inProgress(ControlRequest request) {
        return inProgress(request, Optional.empty());
    }

    /** The same, in the words of the mechanism that carries the action out. */
    public static String inProgress(ControlRequest request, Optional<ControlMechanism> mechanism) {
        String target = target(request);
        boolean signal =
                mechanism.filter(ControlMechanism.TERMINATION_SIGNAL::equals).isPresent();
        return switch (request.action()) {
            case END_TASK -> "Asking " + target + (signal ? " to terminate…" : " to close…");
            case FORCE_KILL -> "Requesting termination of " + target + "…";
            case SUSPEND -> "Suspending " + target + "…";
            case RESUME -> "Resuming " + target + "…";
            case CHANGE_PRIORITY ->
                "Setting the priority of " + target + " to "
                        + levelName(request.priority().orElseThrow()) + "…";
        };
    }

    /**
     * How grave a result is, for the activity log and for the tone the page gives it: the one
     * place the two are decided, so they never disagree.
     */
    public static ActivitySeverity severity(OperationResult result) {
        return switch (result) {
            case SUCCESS -> ActivitySeverity.INFO;
            case NATIVE_ERROR -> ActivitySeverity.ERROR;
            case ACCESS_DENIED, PROCESS_NOT_FOUND, UNSUPPORTED, INVALID_ARGUMENT -> ActivitySeverity.WARNING;
        };
    }

    static ProcessControlResult result(ControlRequest request, ControlOutcome outcome, Wording wording) {
        return new ProcessControlResult(request, outcome, wording.headline(), wording.explanation());
    }

    static Wording busy(ControlRequest request) {
        return new Wording(nothingDone(request), "Another action on it is in progress.");
    }

    static Wording shuttingDown(ControlRequest request) {
        return new Wording(nothingDone(request), "Prism is closing.");
    }

    static Wording notMonitoring(ControlRequest request) {
        return new Wording(nothingDone(request), "Prism is not monitoring processes.");
    }

    static Wording tooManyPending(ControlRequest request) {
        return new Wording(nothingDone(request), "Too many actions are waiting.");
    }

    static Wording unavailable(ControlRequest request) {
        return Wording.of(actionName(request.action()) + " is not available on this system.");
    }

    static Wording refused(ControlRequest request, ProtectionRules.Refusal refusal) {
        String headline = "Prism does not act on " + target(request) + ".";
        return switch (refusal) {
            case OWN_PROCESS -> new Wording(headline, "It is Prism's own process.");
            case SYSTEM_PROCESS, SYSTEM_EXECUTABLE -> new Wording(headline, "It is a system process.");
            case CRITICAL_PROCESS -> new Wording(headline, "The system marks it as critical.");
        };
    }

    static Wording suspension(ControlRequest request, SuspensionCoordinator.Outcome outcome) {
        return suspension(request, outcome, Means.UNKNOWN);
    }

    /** The same, in the words of the means the suspension is made by. */
    static Wording suspension(ControlRequest request, SuspensionCoordinator.Outcome outcome, Means means) {
        Wording wording = suspensionWording(request, outcome, means);
        if (outcome.recorded()) {
            return wording;
        }
        // The journal failed: the suspension stands, but a crash would leave no record of it.
        String note = "It is not recorded for the next start of Prism: the suspension journal cannot be written.";
        return new Wording(
                wording.headline(), wording.explanation().isEmpty() ? note : wording.explanation() + " " + note);
    }

    private static Wording suspensionWording(
            ControlRequest request, SuspensionCoordinator.Outcome outcome, Means means) {
        String target = target(request);
        return switch (outcome.change()) {
            case SUSPENDED -> suspended(target, means);
            case ALREADY_HELD ->
                outcome.remaining().contains(SuspensionReason.USER)
                                && outcome.remaining().size() > 1
                        ? new Wording(
                                target + " stays suspended, now for you as well.",
                                "Prism held it already " + reasons(withoutUser(outcome.remaining()))
                                        + "; Resume lets your part go.")
                        : Wording.of(target + " is already suspended by Prism.");
            case STOPPED_ELSEWHERE ->
                new Wording(
                        target + " is already stopped.",
                        "Another program stopped it; Prism does not take over that stop.");
            case RESUMED -> Wording.of(target + " was resumed.");
            case EXITED -> new Wording(target + " has exited.", "Prism no longer holds it suspended.");
            case STILL_HELD ->
                outcome.remaining().equals(Set.of(SuspensionReason.CPU_LIMITER))
                        ? new Wording(
                                "Your suspension of " + target + " is released.",
                                "The CPU limiter still stops it a moment at a time, to hold it under its limit.")
                        : new Wording(
                                target + " stays suspended.",
                                "Prism still holds it " + reasons(outcome.remaining()) + ".");
            case NOT_HELD -> Wording.of("Prism does not hold " + target + " suspended for you.");
            case INCOMPLETE ->
                new Wording(
                        (request.action() == ControlAction.SUSPEND ? "Suspending " : "Resuming ") + target
                                + " was not completed.",
                        means.is(ControlMechanism.STOP_SIGNAL)
                                ? "Prism could not let it run again; Resume tries again."
                                : outcome.outstanding() + " thread(s) Prism suspended could not be released; Resume"
                                        + " tries again.");
            case REFUSED -> shuttingDown(request);
            case FAILED -> of(request, outcome.outcome(), means);
        };
    }

    /** The wording of the outcome of an action carried out on the platform. */
    static Wording of(ControlRequest request, ControlOutcome outcome) {
        return of(request, outcome, Means.UNKNOWN);
    }

    /** The same, in the words of the means the action was carried out by. */
    static Wording of(ControlRequest request, ControlOutcome outcome, Means means) {
        String target = target(request);
        return switch (outcome.result()) {
            case SUCCESS ->
                switch (request.action()) {
                    case END_TASK ->
                        means.is(ControlMechanism.TERMINATION_SIGNAL)
                                ? new Wording("Asked " + target + " to terminate.", "It may ignore the request.")
                                : new Wording(
                                        "Close request sent to " + target + ".", "The application may ignore it.");
                    case FORCE_KILL ->
                        new Wording(
                                "Force termination requested for " + target + ".",
                                "The table shows when it has ended.");
                    case SUSPEND -> suspended(target, means);
                    case RESUME -> Wording.of(target + " was resumed.");
                    case CHANGE_PRIORITY ->
                        new Wording(
                                "Priority of " + target + " set to "
                                        + levelName(request.priority().orElseThrow())
                                        + ".",
                                means.levelsListed()
                                        ? "The table shows it with the next listing."
                                        : "The system confirmed it.");
                };
            case ACCESS_DENIED ->
                new Wording(
                        "Access denied: " + target + ".",
                        request.action() == ControlAction.CHANGE_PRIORITY && means.is(ControlMechanism.NICE_VALUE)
                                ? "Raising a priority, or changing another user's process, needs privileges Prism"
                                        + " does not have."
                                : "The system does not let Prism act on this process.");
            case PROCESS_NOT_FOUND -> Wording.of(target + " is no longer running.");
            case UNSUPPORTED ->
                request.action() == ControlAction.END_TASK && !means.is(ControlMechanism.TERMINATION_SIGNAL)
                        ? new Wording(target + " has no window to ask to close.", "Use Force kill instead.")
                        : unavailable(request);
            case INVALID_ARGUMENT ->
                request.action() == ControlAction.SUSPEND && means.is(ControlMechanism.STOP_SIGNAL)
                        ? new Wording(
                                target + " is already stopped.",
                                "Another program stopped it; Prism does not take over that stop.")
                        : Wording.of("Prism could not do that to " + target + ".");
            case NATIVE_ERROR ->
                new Wording(
                        "The system did not " + verb(request.action(), means) + " " + target + ".",
                        "The Logs page has the details.");
        };
    }

    private static Wording suspended(String target, Means means) {
        return new Wording(
                target + " was suspended.",
                means.is(ControlMechanism.STOP_SIGNAL)
                        ? "The system stopped the whole process."
                        : "Its threads were stopped one by one.");
    }

    private static String nothingDone(ControlRequest request) {
        return "Nothing was done to " + target(request) + ".";
    }

    private static String verb(ControlAction action, Means means) {
        return switch (action) {
            case END_TASK ->
                means.is(ControlMechanism.TERMINATION_SIGNAL)
                        ? "pass on the request to terminate"
                        : "take the close request for";
            case FORCE_KILL -> "terminate";
            case SUSPEND -> "suspend";
            case RESUME -> "resume";
            case CHANGE_PRIORITY -> "change the priority of";
        };
    }

    private static String reasons(Set<SuspensionReason> remaining) {
        return remaining.stream()
                .map(reason -> switch (reason) {
                    case USER -> "for you";
                    case CPU_LIMITER -> "for its CPU limit";
                    case OOM_GUARD -> "for OOM Guard";
                })
                .collect(Collectors.joining(" and "));
    }

    static String target(ControlRequest request) {
        String name = request.name().isBlank() ? "the process" : request.name();
        return String.format(
                Locale.ROOT, "%s (PID %d)", name, request.identity().pid());
    }

    private static Set<SuspensionReason> withoutUser(Set<SuspensionReason> reasons) {
        java.util.EnumSet<SuspensionReason> others = java.util.EnumSet.noneOf(SuspensionReason.class);
        others.addAll(reasons);
        others.remove(SuspensionReason.USER);
        return others;
    }
}
