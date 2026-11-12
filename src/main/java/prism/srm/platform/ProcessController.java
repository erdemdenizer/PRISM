package prism.srm.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;

/**
 * The actions on other processes (CTL-1), each on a process identity: every method reads the live
 * process again and acts only if its PID and its start time are those of the identity, so a PID
 * that another process has taken over is never acted on. Expected outcomes are results, not
 * exceptions: a process can exit between any two calls, and the system may refuse (CTL-2, SAFE-4).
 *
 * <p>Which of these may be used on which process is not decided here. The platform supplies facts
 * ({@link #protectionFacts}); the rules that refuse Prism itself and protected processes are
 * applied above, before any of the actions is called (SAFE-2, SAFE-3).
 *
 * <p>Every method may query the operating system: none may be called on the JavaFX Application
 * Thread. Suspend and resume are called by the suspension coordinator only (CTL-9).
 */
public interface ProcessController {

    /** How each action is provided on this system; the same answer on every call. */
    Map<ControlAction, Capability> capabilities();

    /**
     * What the controller found missing on this system when it was set up, in words for the
     * activity log: why an action that the system could offer is unavailable here. Empty when
     * nothing is missing. The same answer on every call.
     */
    default List<String> limitations() {
        return List.of();
    }

    /**
     * Which of the five levels each priority number of the process listing stands for on this
     * system (CTL-6); a number that is not a key stands for none. A fixed table: it reads nothing,
     * and the same answer on every call. Empty where priorities cannot be changed.
     */
    Map<Integer, PriorityLevel> listedPriorities();

    /**
     * Whether Prism can put back a priority it lowered on this system, without privilege and knowing
     * the level the process had (OOM-11, ADR-0015 §3). A fixed table: it reads nothing, and the same
     * answer on every call. No default: an implementation that does not say cannot claim it.
     */
    Restorability priorityRestorability();

    /** What the system says about the process that bears on protecting it. Changes nothing. */
    ProtectionFacts protectionFacts(ProcessIdentity target);

    /**
     * Reads whether the process still runs as that identity. Changes nothing. {@code SUCCESS}: it
     * does; {@code PROCESS_NOT_FOUND}: it exited, or its PID belongs to another process now; any
     * other result: it could not be determined.
     */
    ControlOutcome liveness(ProcessIdentity target);

    /** Asks the process to close (End Task). Success means the request was delivered, not that the process exited. */
    ControlOutcome requestClose(ProcessIdentity target);

    /** Terminates the process (Force Kill). Success means termination was started; the exit is observed by the next listing. */
    ControlOutcome terminate(ProcessIdentity target);

    /**
     * Suspends the process. On success the result holds the suspension, which is all that is
     * needed to undo exactly what was done. If a failure could not be undone completely, the
     * result is a failure and still holds the suspension of what remains, so that nothing Prism
     * did is forgotten. Where a stop is a signal (Linux, macOS), a process that is stopped already
     * is not suspended: the result is {@code INVALID_ARGUMENT}, nothing was done and nothing is
     * held, so that Prism never takes over, and later ends, a stop another program made.
     */
    SuspendResult suspend(ProcessIdentity target);

    /**
     * Undoes what the suspension did. Where threads were suspended one by one (Windows), nothing
     * else: a suspension another program holds stays. Where a stop is a signal (Linux, macOS),
     * one continue ends every stop, so a stop another program made after Prism's ends too; the
     * system cannot tell them apart. On success the suspension is spent and closed. If the process had exited, the result is
     * {@code PROCESS_NOT_FOUND}: nothing was resumed, and the suspension is spent and closed all the
     * same. If part of it could not be undone, the result is a failure and the suspension still
     * holds that part; calling this again tries that part only. Whether anything is still held is
     * {@link Suspension#outstanding()}, not whether the result is a success.
     */
    ControlOutcome resume(Suspension suspension);

    /** Sets one of the five levels and reads it back; success only if the system holds it. */
    ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level);

    /**
     * Whether the process is stopped, as far as the system tells without anything being changed;
     * for the recovery of an earlier session's journal. {@link StopState#UNKNOWN} where the system
     * has no such reading (Windows) or the read failed. Assumes the identity was checked with
     * {@link #liveness} just before.
     */
    StopState stopState(ProcessIdentity target);

    /**
     * Checks whether the process is still held the way an earlier session of Prism recorded in its
     * journal, and if so returns a suspension that undoes that hold once (LIFE-6, ADR-0011). Called
     * by the suspension coordinator only, and only when the user asks to resume such a process.
     *
     * <p>Nothing here proves that the hold is Prism's: another program may have stopped the
     * process after Prism ended. The checks only narrow that down, and every refusal leaves the
     * process exactly as it was:
     *
     * <ul>
     *   <li>Linux and macOS: the identity, and the process stopped by a signal; never a process
     *       under a tracer or debugger.
     *   <li>Windows: the recorded threads (ID and creation time), each of which must carry exactly
     *       one suspension, none more; no debugger. Without a memento nothing is checked and nothing
     *       is offered: an anonymous suspension count does not show whose it is.
     * </ul>
     *
     * @param memento what the earlier session's {@link Suspension#memento()} recorded
     */
    AdoptResult adopt(ProcessIdentity target, Optional<String> memento);
}
