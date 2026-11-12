package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.application.ControlMessages;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessState;
import prism.srm.ui.control.ResultLineState;
import prism.srm.ui.control.ResultTone;

/**
 * What the actions on the selected process may do right now, and what the last one came to. No
 * JavaFX node and no system call: it is told the control profile, the selection, the listings and
 * the results on the JavaFX Application Thread, and sends requests through the function it was
 * given.
 *
 * <p><b>Enabled.</b> An action is enabled when a process is selected, the action is available on
 * this system, monitoring runs, and no action on that process is in progress. An unavailable action
 * is disabled, never hidden, and says why (CAP-2); the reason is chosen from the capability, not
 * from the operating system (CAP-1, CAP-5). Suspend is enabled for a process Prism does not hold
 * suspended, Resume for one it does.
 *
 * <p><b>Prism's own process.</b> Once the page knows the PID of Prism, every action on that process
 * is disabled with the reason (CAP-2, SAFE-2). The service refuses such a request as well, so that a
 * request that reaches it by another way is still refused.
 *
 * <p><b>One action per process.</b> From a request until its result, every action on that process
 * is disabled; actions on other processes are not.
 *
 * <p><b>The line</b> beside the actions says, in this order of precedence: which action is on its
 * way to the selected process; what the last action on it came to, as a sentence and an
 * explanation in the tone of its result; or why the actions are disabled. A reason is never shown
 * as a result. A result for another process is not shown here; the Logs page has it. A result
 * stays while its process stays selected, also through listings and when the process exits and
 * the selection goes with it; it goes when another process is selected, when the selection goes
 * for another reason (a filter, monitoring stopping), and when a new action on the process
 * starts. Nothing goes on a timer. {@link #newResultProperty()} counts the results that came to be
 * shown, so the view can mark the arrival of each, also of one that reads like the one before.
 *
 * <p>The line also carries the requests of the page about managed processes and limits (Manage,
 * Set CPU limit, Unmanage; {@link ManagedActions}): the one on its way, then its result, by the
 * same rules. The newest of the two kinds of result is shown.
 *
 * <p><b>Priority.</b> The level the selected process has is the one the last listing shows, as
 * the control profile maps its number (CTL-6): never the level asked for until a listing shows
 * it, and none when the number stands for none of the five.
 */
final class ProcessActions {

    static final String WAITING = "Actions are available once monitoring runs.";
    static final String NOT_MONITORING = "Actions need process monitoring, which is not running.";
    static final String IN_PROGRESS = "An action on this process is in progress.";
    static final String NONE_AVAILABLE = "Actions on processes are not available on this system.";
    static final String SELECT_FIRST = "Select a process first.";
    static final String OWN_PROCESS =
            "This is Prism's own process. Prism does not suspend, end, change, limit or manage itself.";

    private final Consumer<ControlRequest> requests;
    private final Map<ControlAction, ReadOnlyBooleanWrapper> disabled = new EnumMap<>(ControlAction.class);
    private final Map<ControlAction, ReadOnlyStringWrapper> explanations = new EnumMap<>(ControlAction.class);
    private final ResultLineState line = new ResultLineState();
    private final ReadOnlyObjectWrapper<PriorityLevel> observedLevel = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyStringWrapper currentPriority = new ReadOnlyStringWrapper("unknown");
    private final ReadOnlyStringWrapper unmappedPriority = new ReadOnlyStringWrapper("");

    /** The request on its way to each process, until its result. */
    private final Map<ProcessIdentity, ControlRequest> inFlight = new HashMap<>();

    /** What a result said about a suspension, until the next listing shows it. */
    private final Map<ProcessIdentity, Boolean> heldByResult = new HashMap<>();

    /** Empty until the profile is known. */
    private Optional<ControlProfile> profile = Optional.empty();

    private boolean monitoring = true;
    private OptionalLong ownPid = OptionalLong.empty();
    private ProcessRow selected;
    /** The process that was selected when it exited and left the table; its action may still answer. */
    private ProcessIdentity exited;

    private ProcessControlResult shown;

    /** The last result of a request about limits for the selection; never shown together with {@link #shown}. */
    private LimitResult shownLimit;

    /** The request about limits on its way to each process, the latest sent, until its result. */
    private final Map<ProcessIdentity, LimitRequest> limitInFlight = new HashMap<>();

    ProcessActions(Consumer<ControlRequest> requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
        for (ControlAction action : ControlAction.values()) {
            disabled.put(action, new ReadOnlyBooleanWrapper(true));
            explanations.put(action, new ReadOnlyStringWrapper(SELECT_FIRST));
        }
    }

    ReadOnlyBooleanProperty disabledProperty(ControlAction action) {
        return disabled.get(action).getReadOnlyProperty();
    }

    /** What the control of an action says when it is pointed at: what it does, or why it is disabled. */
    ReadOnlyStringProperty explanationProperty(ControlAction action) {
        return explanations.get(action).getReadOnlyProperty();
    }

    /** The line beside the actions: the action on its way, the result, or the reason. */
    ResultLineState line() {
        return line;
    }

    /** The whole line as one text, for a screen reader and for the tooltip. */
    ReadOnlyStringProperty messageProperty() {
        return line.wholeProperty();
    }

    /** The first part of the line: the result, the action on its way, or the reason. */
    ReadOnlyStringProperty lineHeadlineProperty() {
        return line.headlineProperty();
    }

    /** The second part of the line: what a result does not say; empty for a reason or an action on its way. */
    ReadOnlyStringProperty lineExplanationProperty() {
        return line.explanationProperty();
    }

    /** The tone of the line: that of its result, or {@link ResultTone#NONE} for a reason or an action on its way. */
    ReadOnlyObjectProperty<ResultTone> lineToneProperty() {
        return line.toneProperty();
    }

    /** Counts the results that came to be shown on the line. */
    ReadOnlyIntegerProperty newResultProperty() {
        return line.resultsProperty();
    }

    /** The level the last listing shows for the selected process; {@code null} when none of the five, or unknown. */
    ReadOnlyObjectProperty<PriorityLevel> observedLevelProperty() {
        return observedLevel.getReadOnlyProperty();
    }

    /** The current priority in words, for the label of the priority control: a level, or "unknown". */
    ReadOnlyStringProperty currentPriorityProperty() {
        return currentPriority.getReadOnlyProperty();
    }

    /** A line for the priority menu when the listing shows a number that stands for none of the levels; else empty. */
    ReadOnlyStringProperty unmappedPriorityProperty() {
        return unmappedPriority.getReadOnlyProperty();
    }

    void showProfile(ControlProfile controlProfile) {
        profile = Optional.of(Objects.requireNonNull(controlProfile, "controlProfile"));
        refresh();
    }

    /** The PID of Prism itself: its row offers no action (SAFE-2, CAP-2). */
    void showOwnProcess(long pid) {
        ownPid = OptionalLong.of(pid);
        refresh();
    }

    /** Whether a process is Prism itself, as far as the page knows. */
    boolean isOwnProcess(ProcessIdentity identity) {
        return isOwn(ownPid, identity);
    }

    /** The one test of Prism's own process, for this class and {@link ManagedActions}. */
    static boolean isOwn(OptionalLong ownPid, ProcessIdentity identity) {
        return ownPid.isPresent() && identity.pid() == ownPid.getAsLong();
    }

    /** Monitoring has ended or never started: no action is possible from now on. */
    void monitoringEnded() {
        monitoring = false;
        // The table is emptied: nothing is selected, and no answer belongs to this line any more.
        selected = null;
        exited = null;
        shown = null;
        shownLimit = null;
        refresh();
    }

    /** @param row {@code null} for no selection */
    void select(ProcessRow row) {
        if (row == null || shown == null || !shown.identity().equals(row.identity())) {
            shown = null;
        }
        if (row == null
                || shownLimit == null
                || !shownLimit.request().identity().equals(row.identity())) {
            shownLimit = null;
        }
        selected = row;
        exited = null;
        refresh();
    }

    /**
     * The selected process has exited and left the table: the result of the last action on it
     * stays, and the answer to an action still on its way to it is shown when it comes.
     */
    void selectedExited() {
        exited = selected == null ? exited : selected.identity();
        selected = null;
        refresh();
    }

    /** A new listing arrived: from now on it, not an earlier result, says what Prism holds and what priority a process has. */
    void listingArrived() {
        heldByResult.clear();
        refresh();
    }

    /** The selected process, as the request names it; empty while none is selected. */
    Optional<ControlRequest> requestFor(ControlAction action, Optional<PriorityLevel> level) {
        if (selected == null || disabled.get(action).get()) {
            return Optional.empty();
        }
        String name = selected.snapshot().name();
        return Optional.of(
                action == ControlAction.CHANGE_PRIORITY
                        ? ControlRequest.priority(selected.identity(), name, level.orElseThrow())
                        : ControlRequest.of(action, selected.identity(), name));
    }

    /**
     * Sends a request the caller has confirmed where confirmation is needed. Nothing if the action
     * is disabled, or if it would set the level the process already has.
     */
    void request(ControlRequest request) {
        if (selected == null
                || !selected.identity().equals(request.identity())
                || disabled.get(request.action()).get()) {
            return;
        }
        if (request.action() == ControlAction.CHANGE_PRIORITY
                && request.priority().equals(Optional.ofNullable(observedLevel.get()))) {
            return;
        }
        inFlight.put(request.identity(), request);
        shown = null;
        shownLimit = null;
        refresh();
        requests.accept(request);
    }

    /** A request about limits went out for a process: the line says so until its result. */
    void limitRequested(LimitRequest request) {
        limitInFlight.put(request.identity(), request);
        if (selected != null && selected.identity().equals(request.identity())) {
            shown = null;
            shownLimit = null;
        }
        refresh();
    }

    /** Takes the result of a request about limits; shown if it is about the selected process. */
    void acceptLimit(LimitResult result) {
        ProcessIdentity identity = result.request().identity();
        if (result.request().equals(limitInFlight.get(identity))) {
            limitInFlight.remove(identity);
        }
        boolean forTheSelection =
                selected != null && selected.identity().equals(identity) || selected == null && identity.equals(exited);
        if (forTheSelection) {
            shownLimit = result;
            shown = null;
        }
        refresh();
        if (forTheSelection) {
            line.arrived();
        }
    }

    void accept(ProcessControlResult result) {
        inFlight.remove(result.identity());
        if (result.result() == OperationResult.SUCCESS) {
            switch (result.request().action()) {
                case SUSPEND -> heldByResult.put(result.identity(), true);
                case RESUME -> heldByResult.put(result.identity(), false);
                default -> {}
            }
        }
        boolean forTheSelection = selected != null && selected.identity().equals(result.identity())
                || selected == null && result.identity().equals(exited);
        if (forTheSelection) {
            shown = result;
            shownLimit = null;
        }
        refresh();
        if (forTheSelection) {
            line.arrived();
        }
    }

    private void refresh() {
        Optional<String> blocked = blockedBy();
        for (ControlAction action : ControlAction.values()) {
            Optional<String> reason = blocked.isPresent() ? blocked : reasonAgainst(action);
            disabled.get(action).set(reason.isPresent());
            explanations.get(action).set(reason.orElseGet(() -> description(action)));
        }
        showPriority();
        ProcessIdentity described = selected != null ? selected.identity() : exited;
        ControlRequest onItsWay = described == null ? null : inFlight.get(described);
        LimitRequest limitOnItsWay = described == null ? null : limitInFlight.get(described);
        if (onItsWay != null) {
            line.showText(ControlMessages.inProgress(onItsWay, mechanism(onItsWay.action())));
        } else if (limitOnItsWay != null) {
            line.showText(prism.srm.application.LimitMessages.inProgress(limitOnItsWay));
        } else if (shown != null) {
            line.showResult(shown);
        } else if (shownLimit != null) {
            line.showResult(shownLimit);
        } else if (!monitoring) {
            line.showText(NOT_MONITORING);
        } else {
            line.showText(
                    selected == null ? "" : blocked.or(this::unavailableOnes).orElse(""));
        }
    }

    private void showPriority() {
        OptionalInt number =
                selected == null ? OptionalInt.empty() : selected.snapshot().priority();
        Optional<PriorityLevel> level = profile.flatMap(known -> known.levelOf(number));
        observedLevel.set(level.orElse(null));
        if (level.isPresent()) {
            currentPriority.set(ControlMessages.levelName(level.get()));
            unmappedPriority.set("");
        } else if (number.isPresent()
                && profile.isPresent()
                && !profile.get().listedPriorities().isEmpty()) {
            currentPriority.set("unknown (" + number.getAsInt() + ")");
            unmappedPriority.set("Current: " + number.getAsInt() + ", not one of the five levels");
        } else {
            currentPriority.set("unknown");
            unmappedPriority.set("");
        }
    }

    /** CAP-2: which actions are not there, on the page and not only in their tooltips; every one of them. */
    private Optional<String> unavailableOnes() {
        List<String> names = new ArrayList<>();
        for (ControlAction action : ControlAction.values()) {
            if (!capability(action).available()) {
                names.add(ControlMessages.actionName(action));
            }
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        if (names.size() == 1) {
            return Optional.of(names.get(0) + " is not available on this system.");
        }
        // The fact first: where the line is cut, what is left of it still says it.
        return Optional.of("Not available on this system: " + String.join(", ", names) + ".");
    }

    /** A reason that disables every action, if there is one. */
    private Optional<String> blockedBy() {
        if (!monitoring) {
            return Optional.of(NOT_MONITORING);
        }
        if (profile.isEmpty()) {
            return Optional.of(WAITING);
        }
        if (profile.get().capabilities().values().stream().noneMatch(Capability::available)) {
            return Optional.of(NONE_AVAILABLE);
        }
        if (selected == null) {
            return Optional.of(SELECT_FIRST);
        }
        if (isOwnProcess(selected.identity())) {
            return Optional.of(OWN_PROCESS);
        }
        if (inFlight.containsKey(selected.identity())) {
            return Optional.of(IN_PROGRESS);
        }
        return Optional.empty();
    }

    private Optional<String> reasonAgainst(ControlAction action) {
        if (!capability(action).available()) {
            return Optional.of(ControlMessages.actionName(action) + " is not available on this system.");
        }
        boolean held = heldByPrism();
        if (action == ControlAction.SUSPEND && held) {
            return Optional.of("Already suspended by Prism; use Resume.");
        }
        if (action == ControlAction.RESUME && !held) {
            return Optional.of("Prism holds no suspension of this process.");
        }
        return Optional.empty();
    }

    private boolean heldByPrism() {
        Boolean result = heldByResult.get(selected.identity());
        if (result != null) {
            return result;
        }
        return selected.snapshot()
                .state()
                .filter(ProcessState.SUSPENDED_BY_PRISM::equals)
                .isPresent();
    }

    /** The mechanism behind an action on this system, if it is available; its words follow it (CAP-5). */
    Optional<ControlMechanism> mechanism(ControlAction action) {
        return capability(action).mechanism();
    }

    private Capability capability(ControlAction action) {
        return profile.map(known -> known.capabilities().getOrDefault(action, Capability.UNAVAILABLE))
                .orElse(Capability.UNAVAILABLE);
    }

    /** What an enabled action does, from its mechanism: the user learns what it may and may not achieve. */
    private String description(ControlAction action) {
        Optional<ControlMechanism> mechanism = capability(action).mechanism();
        if (mechanism.isEmpty()) {
            return ControlMessages.actionName(action);
        }
        return switch (mechanism.get()) {
            case CLOSE_REQUEST -> "Asks the application to close. It may ask to save its work, or ignore the request.";
            case PROCESS_HANDLE_TERMINATE -> "Terminates the process at once. Work it has not saved is lost.";
            case PER_THREAD_SUSPENSION ->
                action == ControlAction.SUSPEND
                        ? "Stops the threads of the process one by one. Not atomic: a thread started meanwhile runs on."
                        : "Releases the suspension Prism holds; a suspension another program made stays.";
            case PRIORITY_CLASS -> "Sets the scheduling priority. The table shows it with the next listing.";
            case TERMINATION_SIGNAL ->
                "Asks the process to terminate. It may save its work first, or ignore the request.";
            case KILL_SIGNAL -> "Terminates the process at once. Work it has not saved is lost.";
            case STOP_SIGNAL ->
                action == ControlAction.SUSPEND
                        ? "Stops the whole process at once. Another program can let it run again."
                        : "Lets the process run again. If another program also stopped it, that stop ends too.";
            case NICE_VALUE -> "Sets the scheduling priority. Without privileges it can only be lowered, not raised.";
            // A limit, not an action on a process: no action of this page is provided this way.
            case DUTY_CYCLE -> "Stops and continues the process in short cycles to hold it near a limit.";
            case JOB_OBJECT -> "The system caps the process and the processes it starts afterwards.";
            case SYSTEMD_SCOPE -> "The kernel caps the process in a systemd scope; it stays there until it exits.";
            case RESIDENT_POLICY -> "Watches the resident memory of the process; not a cap.";
        };
    }
}
