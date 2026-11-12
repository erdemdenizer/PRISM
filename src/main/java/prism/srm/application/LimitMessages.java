package prism.srm.application;

import java.util.Optional;
import prism.srm.application.ControlMessages.Wording;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.FallbackOffer;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.OomManagement;

/**
 * The words of the results of managing processes and limiting them (UI-4, CAP-5). They say what
 * was done and never claim more: an emulated limit is called approximate, and a limit Prism cannot
 * reach is not called enforced.
 */
public final class LimitMessages {

    /** What every emulated CPU limit is, said where one is set (CAP-5). */
    static final String APPROXIMATE = "Approximate: Prism stops and continues the process in short cycles."
            + " It cannot hold a process below about 5 % of what it would use.";

    private LimitMessages() {}

    /** What every native limit is, said where one is set (CAP-5, plan §10), in the words of its mechanism. */
    static String enforced(LimitCapability capability) {
        if (capability
                .mechanism()
                .filter(mechanism -> mechanism == ControlMechanism.JOB_OBJECT)
                .isPresent()) {
            return "Enforced by the system (Job Object). The process and the child processes it starts from now on"
                    + " share the cap; it stays in the job until it exits.";
        }
        return "Enforced by the system.";
    }

    static LimitResult result(LimitRequest request, ControlOutcome outcome, Wording wording) {
        return new LimitResult(request, outcome, wording.headline(), wording.explanation());
    }

    static LimitResult offered(LimitRequest request, ControlOutcome outcome, Wording wording, FallbackOffer offer) {
        return new LimitResult(request, outcome, wording.headline(), wording.explanation(), Optional.of(offer));
    }

    static String target(LimitRequest request) {
        return request.name() + " (PID " + request.identity().pid() + ")";
    }

    static String actionName(LimitAction action) {
        return switch (action) {
            case MANAGE -> "Manage";
            case UNMANAGE -> "Unmanage";
            case SET_CPU_LIMIT -> "Set CPU limit";
            case REMOVE_CPU_LIMIT -> "Remove CPU limit";
            case ENABLE -> "Enable limits";
            case DISABLE -> "Disable limits";
            case SET_MEMORY_LIMIT -> "Set memory limit";
            case REMOVE_MEMORY_LIMIT -> "Remove memory limit";
            case ACCEPT_EMULATED_CPU -> "Use the emulated CPU limit";
            case SET_OOM -> "OOM management";
            case ACCEPT_RESIDENT_POLICY -> "Watch the resident memory";
        };
    }

    private static String nothingDone(LimitRequest request) {
        return actionName(request.action()) + " was not carried out for " + target(request) + ".";
    }

    /** The line while a request is on its way, for every page that sends one. */
    public static String inProgress(LimitRequest request) {
        String target = target(request);
        return switch (request.action()) {
            case MANAGE -> "Managing " + target + "…";
            case UNMANAGE -> "Taking " + target + " off the managed list…";
            case SET_CPU_LIMIT -> "Setting the CPU limit of " + target + "…";
            case REMOVE_CPU_LIMIT -> "Removing the CPU limit of " + target + "…";
            case ENABLE -> "Enabling the limits of " + target + "…";
            case DISABLE -> "Disabling the limits of " + target + "…";
            case SET_MEMORY_LIMIT -> "Setting the memory limit of " + target + "…";
            case REMOVE_MEMORY_LIMIT -> "Removing the memory limit of " + target + "…";
            case ACCEPT_EMULATED_CPU -> "Starting the emulated CPU limit of " + target + "…";
            case SET_OOM -> "Setting the OOM management of " + target + "…";
            case ACCEPT_RESIDENT_POLICY -> "Watching the resident memory of " + target + "…";
        };
    }

    static Wording notMonitoring(LimitRequest request) {
        return new Wording(nothingDone(request), "Prism is not monitoring processes.");
    }

    static Wording shuttingDown(LimitRequest request) {
        return new Wording(nothingDone(request), "Prism is closing.");
    }

    static Wording tooManyPending(LimitRequest request) {
        return new Wording(nothingDone(request), "Too many requests are waiting.");
    }

    static Wording failed(LimitRequest request) {
        return new Wording(nothingDone(request), "Something went wrong; the Logs page has the details.");
    }

    static Wording refused(LimitRequest request, ProtectionRules.Refusal refusal) {
        String headline = "Prism does not manage " + target(request) + ".";
        return switch (refusal) {
            case OWN_PROCESS -> new Wording(headline, "It is Prism's own process.");
            case SYSTEM_PROCESS, SYSTEM_EXECUTABLE -> new Wording(headline, "It is a system process.");
            case CRITICAL_PROCESS -> new Wording(headline, "The system marks it as critical.");
        };
    }

    static Wording managed(LimitRequest request) {
        return new Wording(target(request) + " is managed.", "Its limits are set on the Limits page.");
    }

    static Wording alreadyManaged(LimitRequest request) {
        return Wording.of(target(request) + " is managed already.");
    }

    static Wording notManaged(LimitRequest request) {
        return new Wording(nothingDone(request), "Prism limits only a process you manage; manage it first.");
    }

    static Wording gone(LimitRequest request) {
        return new Wording(nothingDone(request), "The process has exited.");
    }

    static Wording unmanaged(LimitRequest request, boolean hadLimit) {
        return new Wording(
                target(request) + " is no longer managed.",
                hadLimit ? "Its CPU limit is removed; it runs freely." : "");
    }

    static Wording invalidPercent(LimitRequest request) {
        return new Wording(nothingDone(request), "A CPU limit is a whole percentage from 1 to 100.");
    }

    static Wording unavailable(LimitRequest request, String reason) {
        return new Wording(nothingDone(request), "A CPU limit is not available here: " + reason + ".");
    }

    static Wording tooManyLimits(LimitRequest request, int most) {
        return new Wording(nothingDone(request), "Prism limits at most " + most + " processes at a time.");
    }

    /**
     * Where suspension goes thread by thread (Windows): the number this machine allows and why. It
     * says what one limit can cost, never what all of them will.
     */
    static Wording tooManyPerThreadLimits(LimitRequest request, int most, int processors) {
        return new Wording(
                nothingDone(request),
                "On this computer Prism runs at most " + most + (most == 1 ? " CPU limit" : " CPU limits")
                        + " at a time (one for every 4 of its " + processors + " logical processors, at most "
                        + LimitService.MOST_PER_THREAD_LIMITS + "): each suspends the process thread by thread"
                        + " and can take Prism about a tenth of a processor while it holds the process back."
                        + " Remove or disable another limit first.");
    }

    static Wording journalFailed(LimitRequest request, String problem) {
        return new Wording(
                "The CPU limit of " + target(request) + " did not start.",
                "Prism cannot record it where it would find it after a crash: " + problem + ".");
    }

    static Wording limited(LimitRequest request, int percent) {
        return new Wording(target(request) + " is limited to " + percent + " % of the CPU.", APPROXIMATE);
    }

    static Wording limitStored(LimitRequest request, int percent) {
        return new Wording(
                "The CPU limit of " + target(request) + " is set to " + percent + " %.",
                "Its limits are disabled; enable them to apply it.");
    }

    static Wording limitRemoved(LimitRequest request) {
        return new Wording("The CPU limit of " + target(request) + " is removed.", "Prism no longer stops it.");
    }

    static Wording noLimit(LimitRequest request) {
        return Wording.of(target(request) + " has no CPU limit.");
    }

    static Wording enabled(LimitRequest request, boolean hasLimit) {
        return new Wording(
                "The limits of " + target(request) + " are enabled.", hasLimit ? APPROXIMATE : "It has no limit set.");
    }

    static Wording disabled(LimitRequest request, boolean hadLimit) {
        return new Wording(
                "The limits of " + target(request) + " are disabled.", hadLimit ? "Prism no longer stops it." : "");
    }

    // ----- Native limits (milestone 10) -----

    static Wording limitedNatively(LimitRequest request, int percent, LimitCapability capability) {
        return new Wording(target(request) + " is limited to " + percent + " % of the CPU.", enforced(capability));
    }

    static Wording memoryLimited(LimitRequest request, String bytes) {
        return new Wording(
                target(request) + " may commit at most " + bytes + ".",
                "A process that reaches the limit cannot allocate memory and may fail or crash. The child processes it"
                        + " starts from now on have the same limit each.");
    }

    static Wording memoryRemoved(LimitRequest request) {
        return new Wording(
                "The memory limit of " + target(request) + " is removed.", "It stays in its job until it exits.");
    }

    static Wording noMemoryLimit(LimitRequest request) {
        return Wording.of(target(request) + " has no memory limit.");
    }

    static Wording invalidBytes(LimitRequest request) {
        return new Wording(nothingDone(request), "A memory limit is at least 1 MiB.");
    }

    static Wording memoryUnavailable(LimitRequest request, String reason) {
        return new Wording(nothingDone(request), "A memory limit is not available for this process: " + reason + ".");
    }

    static Wording memoryStored(LimitRequest request, String bytes) {
        return new Wording(
                "The memory limit of " + target(request) + " is set to " + bytes + ".",
                "Its limits are disabled; enable them to apply it.");
    }

    /** The native limit was not applied; the emulated one is offered and runs only if the user accepts. */
    static Wording nativeNotApplied(LimitRequest request, String reason) {
        return new Wording(
                "The native CPU limit of " + target(request) + " was not applied.",
                "Reason: " + reason + ". Prism offers the emulated limit instead; nothing runs unless you accept it.");
    }

    static Wording nativeFailed(LimitRequest request, String what) {
        return new Wording(actionName(request.action()) + " did not complete for " + target(request) + ".", what);
    }

    static Wording uncertain(LimitRequest request) {
        return new Wording(
                "Prism cannot tell whether the limit of " + target(request) + " took effect.",
                "It keeps the job, checks again at the next request and clears it at shutdown. No second limiter is"
                        + " started meanwhile; remove the limit to force a check.");
    }

    static Wording mismatch(LimitRequest request, String inForce) {
        return new Wording(
                "The limit of " + target(request) + " is not what was asked.",
                "The system holds " + inForce + ". Remove the limit or set it again.");
    }

    static Wording unverified(LimitRequest request, String candidates) {
        return new Wording(
                "Prism could not read back the limits of " + target(request) + ".",
                "In force: " + candidates + ". Prism checks again at the next request; remove the limits to be sure.");
    }

    static Wording partial(LimitRequest request, String inForce) {
        return new Wording(
                actionName(request.action()) + " was only partly carried out for " + target(request) + ".",
                "The system now holds " + inForce + ".");
    }

    static Wording nativeUnknown(LimitRequest request, String inForce) {
        return new Wording(
                nothingDone(request),
                "A native CPU limit may already be in force on this process (" + inForce + "). Prism will not add a"
                        + " second limiter; remove the native limit first, or wait for the process to exit.");
    }

    static Wording earlierUnsettled(LimitRequest request, String why) {
        return new Wording(
                nothingDone(request),
                "No emulated limit is offered for it yet: " + why + ", and one of them may still cap this process"
                        + " natively. Prism will not add a second limiter until that is settled.");
    }

    /**
     * The scope-membership rule (milestone 12, plan Rev 2.2 §4.5 b): the process runs inside a scope a Prism session
     * created, so no second limiter is started on it, native or emulated, whatever the records say.
     */
    static Wording inPrismScope(LimitRequest request, String why) {
        return new Wording(
                nothingDone(request),
                "No limit is offered for it: " + why + ". Prism changes nothing in that scope; whatever limit it holds"
                        + " lasts until the processes in it exit.");
    }

    static Wording priorSession(LimitRequest request, String inForce) {
        return new Wording(
                nothingDone(request),
                "An earlier Prism session may still cap this process natively (" + inForce + "). No second limiter is"
                        + " offered; that limit lasts until the process exits.");
    }

    static Wording releaseIncomplete(LimitRequest request, int threads) {
        return new Wording(
                "The CPU limit of " + target(request) + " was not changed.",
                "The emulated limit could not release " + threads + (threads == 1 ? " thread" : " threads")
                        + " of the process. Resume the process on the Processes page, then set the limit again.");
    }

    static Wording ledgerFailed(LimitRequest request, String problem) {
        return new Wording(
                nothingDone(request),
                "Prism cannot record the native limit where it would find it after a crash: " + problem + ".");
    }

    static Wording noFallbackOffered(LimitRequest request) {
        return new Wording(nothingDone(request), "No emulated limit was offered for this process.");
    }

    static Wording emulatedChosen(LimitRequest request, int percent) {
        return new Wording(
                target(request) + " is limited to " + percent + " % of the CPU (emulated, your choice).", APPROXIMATE);
    }

    static Wording cannotClear(LimitRequest request, String inForce) {
        return new Wording(
                actionName(request.action()) + " was not carried out for " + target(request) + ".",
                "Its native limits are still in force (" + inForce + "); it stays as it is. Prism tries again later.");
    }

    static Wording unmanagedNative(LimitRequest request) {
        return new Wording(
                target(request) + " is no longer managed.",
                "Its native limits are cleared; it stays in Prism's job, without limits, until it exits.");
    }

    static Wording disabledNative(LimitRequest request) {
        return new Wording("The limits of " + target(request) + " are disabled.", "Its native limits are cleared.");
    }

    // ----- OOM Guard (milestone 11) -----

    /** What every watched memory limit is, said where one is set (LIM-9, CAP-3): never a cap. */
    static final String WATCHED = "Not a cap: Prism watches the resident memory of the process and, when it goes above"
            + " the limit, applies its emergency action as far as the automatic intervention switches of OOM Guard"
            + " allow. A process held for its limit is usually let go only by you, since a suspended process keeps"
            + " its memory.";

    static Wording oomSet(LimitRequest request, OomManagement oom, Optional<String> notInEffect) {
        if (!oom.managed()) {
            return new Wording(
                    "OOM management is off for " + target(request) + ".", "OOM Guard takes no action on it.");
        }
        String chosen = switch (oom.action()) {
            case NOTIFY -> "Notify only";
            case LOWER_PRIORITY -> "Lower priority";
            case SUSPEND -> "Suspend";
            case TERMINATE -> "Terminate (force kill)";
        };
        String headline = target(request) + " is under OOM management: " + chosen + ", "
                + oom.priority().name().toLowerCase(java.util.Locale.ROOT) + " priority.";
        return new Wording(
                headline,
                notInEffect
                        .map(why -> "Not in effect: " + why + ", so OOM Guard only notifies.")
                        .orElse(""));
    }

    static Wording memoryWatched(LimitRequest request, String bytes) {
        return new Wording("The resident memory of " + target(request) + " is watched against " + bytes + ".", WATCHED);
    }

    static Wording memoryNoLongerWatched(LimitRequest request) {
        return Wording.of("The resident memory of " + target(request) + " is no longer watched.");
    }

    /** The native memory limit was not applied; the watched resident memory is offered and runs only if the user accepts. */
    static Wording residentOffered(LimitRequest request, String reason) {
        return new Wording(
                "The native memory limit of " + target(request) + " was not applied.",
                "Reason: " + reason + ". Prism offers to watch its resident memory instead; that is not a cap, and"
                        + " nothing is watched unless you accept it.");
    }

    static Wording noResidentOffered(LimitRequest request) {
        return new Wording(nothingDone(request), "No watched memory limit was offered for this process.");
    }

    static Wording heldByOomGuard(LimitRequest request) {
        return new Wording(
                nothingDone(request),
                "OOM Guard holds the process suspended; set the limit again once it lets go of it.");
    }

    static Wording enabledNative(LimitRequest request, LimitCapability capability) {
        return new Wording("The limits of " + target(request) + " are enabled.", enforced(capability));
    }
}
