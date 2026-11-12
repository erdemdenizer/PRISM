package prism.srm.ui.processes;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.OomManagement;
import prism.srm.domain.ProcessIdentity;

/**
 * Managing the selected process, setting its CPU limit and adding it to or removing it from OOM
 * management from the Processes page (MGD-2, MGD-3, UI-2): which of the choices may be chosen, why
 * not, and the mark the inspector shows beside the name of a managed process. No JavaFX node and no system call: it is told the
 * limits, the selection and the results on the JavaFX Application Thread, and sends requests
 * through the function it was given.
 *
 * <p>Set CPU limit, and Add to OOM management, on a process that is not managed yet manage it
 * first: choosing a limit or OOM management for it is the explicit act MGD-2 asks for. Added to
 * OOM management, a process is acted on as Notify only until the user chooses another emergency
 * action (milestone 11). While a request about a process is on its way, all three are
 * disabled for it. Set CPU limit is disabled, with the reason, where no CPU limit is available.
 * All of them are disabled, with the reason, on Prism's own process (MGD-4, CAP-2); the service
 * refuses it as well.
 */
final class ManagedActions {

    /** The choices of the page. */
    enum Choice {
        MANAGE,
        SET_CPU_LIMIT,
        OOM_MANAGE,
        OOM_UNMANAGE,
        UNMANAGE
    }

    static final String WAITING = "Limits are available once process monitoring runs.";
    static final String IN_PROGRESS = "A request about this process is in progress.";

    private final Consumer<LimitRequest> requests;
    private final Map<Choice, ReadOnlyBooleanWrapper> disabled = new EnumMap<>(Choice.class);
    private final Map<Choice, ReadOnlyStringWrapper> explanations = new EnumMap<>(Choice.class);
    private final ReadOnlyStringWrapper mark = new ReadOnlyStringWrapper("");
    private final ReadOnlyBooleanWrapper chosen = new ReadOnlyBooleanWrapper(false);
    /** Requests on their way, by process: Set CPU limit on an unmanaged process sends two. */
    private final Map<ProcessIdentity, Integer> inFlight = new HashMap<>();

    private Optional<LimitsSnapshot> limits = Optional.empty();
    private boolean monitoring = true;
    private OptionalLong ownPid = OptionalLong.empty();
    private ProcessRow selected;

    ManagedActions(Consumer<LimitRequest> requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
        for (Choice choice : Choice.values()) {
            disabled.put(choice, new ReadOnlyBooleanWrapper(true));
            explanations.put(choice, new ReadOnlyStringWrapper(ProcessActions.SELECT_FIRST));
        }
    }

    ReadOnlyBooleanProperty disabledProperty(Choice choice) {
        return disabled.get(choice).getReadOnlyProperty();
    }

    /** What a choice does, or why it is disabled. */
    ReadOnlyStringProperty explanationProperty(Choice choice) {
        return explanations.get(choice).getReadOnlyProperty();
    }

    /** Whether a process is selected: with none, there is nothing to say about its being managed (PR-15). */
    ReadOnlyBooleanProperty selectedProperty() {
        return chosen.getReadOnlyProperty();
    }

    /** Beside the name in the inspector: "Managed", with the CPU limit when one is set; empty otherwise. */
    ReadOnlyStringProperty markProperty() {
        return mark.getReadOnlyProperty();
    }

    void showLimits(LimitsSnapshot snapshot) {
        limits = Optional.of(Objects.requireNonNull(snapshot, "snapshot"));
        refresh();
    }

    void select(ProcessRow row) {
        selected = row;
        refresh();
    }

    /** The PID of Prism itself: it is never managed (MGD-4). */
    void showOwnProcess(long pid) {
        ownPid = OptionalLong.of(pid);
        refresh();
    }

    void monitoringEnded() {
        monitoring = false;
        selected = null;
        refresh();
    }

    /** What a CPU limit is on this system; unavailable until the limits are known. */
    LimitCapability capability() {
        return limits.map(LimitsSnapshot::cpu).orElse(LimitCapability.unavailable(WAITING));
    }

    /** The selected process, as a request about it names it; empty while none is selected. */
    Optional<ProcessRow> selected() {
        return Optional.ofNullable(selected);
    }

    /** The CPU limit set for the selected process, if it is managed and has one. */
    Optional<Integer> currentLimit() {
        return view().flatMap(found -> {
            var percent = found.process().settings().cpuLimitPercent();
            return percent.isPresent() ? Optional.of(percent.getAsInt()) : Optional.empty();
        });
    }

    /** Sends Manage, Unmanage or a change of OOM management for the selected process; nothing if the choice is disabled. */
    void choose(Choice choice) {
        if (choice == Choice.SET_CPU_LIMIT) {
            throw new IllegalArgumentException("A CPU limit needs its percentage");
        }
        if (selected == null || disabled.get(choice).get()) {
            return;
        }
        ProcessIdentity identity = selected.identity();
        String name = selected.snapshot().name();
        switch (choice) {
            case MANAGE -> send(LimitRequest.of(LimitAction.MANAGE, identity, name));
            case UNMANAGE -> send(LimitRequest.of(LimitAction.UNMANAGE, identity, name));
            case OOM_MANAGE, OOM_UNMANAGE -> {
                Optional<ManagedProcessView> view = view();
                if (view.isEmpty()) {
                    send(LimitRequest.of(LimitAction.MANAGE, identity, name));
                }
                OomManagement current =
                        view.map(found -> found.process().settings().oom()).orElse(OomManagement.OFF);
                send(LimitRequest.oom(identity, name, current.withManaged(choice == Choice.OOM_MANAGE)));
            }
            case SET_CPU_LIMIT -> throw new IllegalStateException("handled above");
        }
    }

    /**
     * Sets the CPU limit of the selected process, managing it first if it is not managed. Nothing
     * if the choice is disabled or the process is no longer the one selected.
     */
    void setCpuLimit(ProcessIdentity identity, int percent) {
        if (selected == null
                || !selected.identity().equals(identity)
                || disabled.get(Choice.SET_CPU_LIMIT).get()) {
            return;
        }
        String name = selected.snapshot().name();
        if (view().isEmpty()) {
            send(LimitRequest.of(LimitAction.MANAGE, identity, name));
        }
        send(LimitRequest.cpuLimit(identity, name, percent));
    }

    /** Whether a request about the process is on its way. */
    boolean inFlight(ProcessIdentity identity) {
        return inFlight.containsKey(identity);
    }

    /** Takes the result of a request about managed processes. */
    void accept(LimitResult result) {
        ProcessIdentity identity = result.request().identity();
        inFlight.computeIfPresent(identity, (key, count) -> count > 1 ? count - 1 : null);
        refresh();
    }

    private void send(LimitRequest request) {
        inFlight.merge(request.identity(), 1, Integer::sum);
        refresh();
        requests.accept(request);
    }

    private Optional<ManagedProcessView> view() {
        if (selected == null) {
            return Optional.empty();
        }
        return limits.flatMap(snapshot -> snapshot.find(selected.identity()));
    }

    private void refresh() {
        Optional<ManagedProcessView> managed = view();
        mark.set(managed.map(ManagedActions::markOf).orElse(""));
        chosen.set(selected != null);
        Optional<String> blocked = blockedBy();
        boolean oom =
                managed.map(found -> found.process().settings().oom().managed()).orElse(false);
        for (Choice choice : Choice.values()) {
            Optional<String> reason = blocked.isPresent() ? blocked : reasonAgainst(choice, managed.isPresent(), oom);
            disabled.get(choice).set(reason.isPresent());
            explanations.get(choice).set(reason.orElseGet(() -> description(choice)));
        }
    }

    private static String markOf(ManagedProcessView view) {
        var percent = view.process().settings().cpuLimitPercent();
        String mark = percent.isPresent() ? "Managed, CPU limit " + percent.getAsInt() + " %" : "Managed";
        return view.process().settings().oom().managed() ? mark + ", OOM management" : mark;
    }

    private Optional<String> blockedBy() {
        if (!monitoring || limits.isEmpty()) {
            return Optional.of(WAITING);
        }
        if (selected == null) {
            return Optional.of(ProcessActions.SELECT_FIRST);
        }
        if (ProcessActions.isOwn(ownPid, selected.identity())) {
            return Optional.of(ProcessActions.OWN_PROCESS);
        }
        if (inFlight.containsKey(selected.identity())) {
            return Optional.of(IN_PROGRESS);
        }
        return Optional.empty();
    }

    private Optional<String> reasonAgainst(Choice choice, boolean managed, boolean oom) {
        return switch (choice) {
            case MANAGE -> managed ? Optional.of("Managed already.") : Optional.empty();
            case UNMANAGE -> managed ? Optional.empty() : Optional.of("Not managed.");
            case OOM_MANAGE -> oom ? Optional.of("Under OOM management already.") : Optional.empty();
            case OOM_UNMANAGE -> oom ? Optional.empty() : Optional.of("Not under OOM management.");
            case SET_CPU_LIMIT -> {
                LimitCapability capability = capability();
                yield capability.available()
                        ? Optional.empty()
                        : Optional.of("A CPU limit is not available here: "
                                + capability.reason().orElse("") + ".");
            }
        };
    }

    private static String description(Choice choice) {
        return switch (choice) {
            case MANAGE -> "Lets Prism limit this process. It stays managed until it exits or Prism closes.";
            case SET_CPU_LIMIT -> "Limits the share of the CPU this process uses, managing it first if needed.";
            case OOM_MANAGE ->
                "Lets OOM Guard act on this process under memory pressure, as far as its switches allow; Notify only"
                        + " until you choose its emergency action on the OOM Guard page. Manages it first if needed.";
            case OOM_UNMANAGE -> "OOM Guard takes no action on this process and lets go of what it holds on it.";
            case UNMANAGE -> "Removes its limits and takes it off the managed list.";
        };
    }
}
