package prism.srm.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.ProcessController;

/**
 * Carries out the actions the user asks for on processes (CTL-1, CTL-7): off the calling thread,
 * on the executor it is given, one result for every request, written to the activity log.
 *
 * <p><b>One action per process at a time.</b> While an action on a process identity is waiting or
 * running, a further request for that identity, whatever its action, is not queued: it is answered
 * at once with {@link OperationResult#INVALID_ARGUMENT}. Requests for other processes are taken.
 * The executor may run actions one at a time for all processes; this rule keeps two actions on one
 * process from following each other unseen.
 *
 * <p><b>Order of checks</b>, each of which ends the request with its own answer before anything is
 * done to the process: shutting down; the action unavailable on this system; Prism itself; the
 * protection facts the system gives (SAFE-2, SAFE-3). Resume releases only what Prism holds, so it
 * needs no protection check. Then the action: End Task, Force Kill and Change Priority through
 * the controller, Suspend and Resume through the suspension coordinator, which alone suspends and
 * resumes (CTL-9).
 *
 * <p><b>Failures.</b> A {@link RuntimeException} becomes {@link OperationResult#NATIVE_ERROR},
 * logged once per kind. An {@link Error} is not caught; the listener is still told, and the process
 * released for further actions, from a {@code finally} block.
 *
 * <p>A request refused at once (another action on the process is running, the shutdown has
 * begun, process monitoring has stopped and the action is not Resume, the executor is full) is
 * answered on the calling thread and not written to the activity log, because nothing was
 * attempted: that log records what was done to processes. A request that was taken and is
 * refused when it runs, because the shutdown began or monitoring stopped while it waited, is
 * written, so that the log says why a request the user saw accepted came to nothing.
 */
public final class ProcessControlService {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessControlService.class);

    private final ProcessController controller;
    private final ProtectionRules rules;
    private final SuspensionCoordinator coordinator;
    private final ActivityLog activity;
    private final Executor executor;
    private final Consumer<ProcessControlResult> listener;
    private final Set<ProcessIdentity> inFlight = ConcurrentHashMap.newKeySet();
    private final FailureLog failures = new FailureLog(LOG);
    private volatile boolean refusing;
    private volatile boolean observing = true;
    private volatile Consumer<RecoveryResult> recoveryListener = result -> {};

    /**
     * @param executor runs the actions off the JavaFX Application Thread; owned by the caller. It
     *     must not drop a task it accepted; one it rejects is answered as too many waiting
     * @param listener receives every result, on the thread that produced it, and must return quickly
     */
    public ProcessControlService(
            ProcessController controller,
            ProtectionRules rules,
            SuspensionCoordinator coordinator,
            ActivityLog activity,
            Executor executor,
            Consumer<ProcessControlResult> listener) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** What process control is on this system, for the user interface: the capabilities and the priority levels. */
    public ControlProfile profile() {
        return new ControlProfile(controller.capabilities(), controller.listedPriorities());
    }

    /** What the controller found missing on this system, for the activity log; see {@link ProcessController#limitations()}. */
    public List<String> limitations() {
        return controller.limitations();
    }

    /** Takes a request and returns at once; may be called from any thread. */
    public void request(ControlRequest request) {
        Objects.requireNonNull(request, "request");
        if (refusing) {
            answerAtOnce(request, OperationResult.UNSUPPORTED, ControlMessages.shuttingDown(request));
            return;
        }
        if (needsObservation(request)) {
            answerAtOnce(request, OperationResult.UNSUPPORTED, ControlMessages.notMonitoring(request));
            return;
        }
        if (!inFlight.add(request.identity())) {
            // Not queued: the action already waiting or running on this process goes first and alone.
            deliver(ControlMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "another action on the process is in progress"),
                    ControlMessages.busy(request)));
            return;
        }
        try {
            executor.execute(() -> run(request));
        } catch (RejectedExecutionException full) {
            inFlight.remove(request.identity());
            answerAtOnce(request, OperationResult.INVALID_ARGUMENT, ControlMessages.tooManyPending(request));
        }
    }

    /**
     * The answer to a request that reached no service, because monitoring does not run: nothing
     * was attempted, and whoever waits for a result is told so.
     */
    public static ProcessControlResult notCarriedOut(ControlRequest request) {
        return ControlMessages.result(
                request,
                ControlOutcome.of(OperationResult.UNSUPPORTED, "monitoring is not running"),
                ControlMessages.notMonitoring(request));
    }

    /** Receives every result of a request about a process an earlier session may have left suspended. */
    public void setRecoveryListener(Consumer<RecoveryResult> listener) {
        recoveryListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Takes a request about a process an earlier session of Prism may have left suspended
     * (ADR-0011) and returns at once; any thread. Resume releases the process only after the
     * coordinator's check; Dismiss closes the entry and touches nothing. Like Resume, neither
     * needs process monitoring. One action per process at a time, as for every other action.
     */
    public void recover(RecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        if (refusing) {
            deliverRecovery(RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "not attempted"),
                    RecoveryMessages.shuttingDown(request)));
            return;
        }
        if (!inFlight.add(request.identity())) {
            deliverRecovery(RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "another action on the process is in progress"),
                    RecoveryMessages.busy(request)));
            return;
        }
        try {
            executor.execute(() -> runRecovery(request));
        } catch (RejectedExecutionException full) {
            inFlight.remove(request.identity());
            deliverRecovery(RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "not attempted"),
                    RecoveryMessages.tooManyPending(request)));
        }
    }

    private void runRecovery(RecoveryRequest request) {
        RecoveryResult result = RecoveryMessages.result(
                request,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "the action ended without a result"),
                RecoveryMessages.failed(request));
        boolean concluded = false;
        try {
            result = carryOutRecovery(request);
            concluded = true;
        } catch (RuntimeException e) {
            warnOnce(RecoveryMessages.actionName(request.action()), e);
            result = RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, e.toString()),
                    RecoveryMessages.failed(request));
            concluded = true;
        } finally {
            if (concluded) {
                recordRecovery(result);
            }
            inFlight.remove(request.identity());
            deliverRecovery(result);
        }
    }

    private RecoveryResult carryOutRecovery(RecoveryRequest request) {
        if (refusing) {
            return RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "shutting down"),
                    RecoveryMessages.shuttingDown(request));
        }
        if (request.action() == RecoveryRequest.Action.DISMISS) {
            return coordinator.dismissRecovered(request.identity())
                    ? RecoveryMessages.result(request, ControlOutcome.success(), RecoveryMessages.dismissed(request))
                    : RecoveryMessages.result(
                            request,
                            ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "no such entry"),
                            RecoveryMessages.noEntry(request));
        }
        Capability resume = controller.capabilities().getOrDefault(ControlAction.RESUME, Capability.UNAVAILABLE);
        if (!resume.available()) {
            return RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "the action is unavailable on this system"),
                    RecoveryMessages.unavailable(request));
        }
        // Prism never suspends itself or a protected process: an entry that names one is not to be acted on.
        Optional<ProtectionRules.Refusal> refusal = rules.refusal(request.identity());
        if (refusal.isEmpty()) {
            refusal = rules.refusal(controller.protectionFacts(request.identity()));
        }
        if (refusal.isPresent()) {
            return RecoveryMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.ACCESS_DENIED, "refused by Prism: " + refusal.get()),
                    RecoveryMessages.refused(request, refusal.get()));
        }
        SuspensionCoordinator.RecoveryOutcome outcome = coordinator.resumeRecovered(request.identity());
        return RecoveryMessages.result(request, outcome.outcome(), RecoveryMessages.resume(request, outcome));
    }

    private void recordRecovery(RecoveryResult result) {
        ControlOutcome outcome = result.outcome();
        StringBuilder entry = new StringBuilder(
                        RecoveryMessages.actionName(result.request().action()))
                .append(": ")
                .append(result.text());
        outcome.detail().ifPresent(detail -> entry.append(" [").append(detail).append(']'));
        activity.record(ControlMessages.severity(outcome.result()), entry.toString());
    }

    private void deliverRecovery(RecoveryResult result) {
        try {
            recoveryListener.accept(result);
        } catch (RuntimeException e) {
            warnOnce("The listener for recovery results", e);
        }
    }

    /** The answer to a recovery request that reached no service, because monitoring does not run. */
    public static RecoveryResult recoveryNotCarriedOut(RecoveryRequest request) {
        return RecoveryMessages.result(
                request,
                ControlOutcome.of(OperationResult.UNSUPPORTED, "monitoring is not running"),
                RecoveryMessages.notMonitoring(request));
    }

    /** Whether an action on the identity is waiting or running. */
    public boolean inFlight(ProcessIdentity identity) {
        return inFlight.contains(identity);
    }

    /** From now on every new request is answered as refused; what was taken still runs. */
    public void refuseFurtherRequests() {
        refusing = true;
    }

    /**
     * Process monitoring has stopped for good: no action that needs a process as it is now is
     * taken any more, also none that waits already. Resume still is: it releases a suspension
     * Prism holds through what Prism kept of it, and needs no listing. Requests for it go on as
     * before, one at a time per process, until the shutdown.
     */
    public void observationLost() {
        observing = false;
    }

    private boolean needsObservation(ControlRequest request) {
        return !observing && request.action() != ControlAction.RESUME;
    }

    private void run(ControlRequest request) {
        ControlMessages.Means means = means(request);
        ProcessControlResult result = ControlMessages.result(
                request,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "the action ended without a result"),
                ControlMessages.of(request, ControlOutcome.of(OperationResult.NATIVE_ERROR, ""), means));
        boolean concluded = false;
        try {
            result = carryOut(request);
            concluded = true;
        } catch (RuntimeException e) {
            warnOnce("Carrying out " + request.action(), e);
            result = ControlMessages.result(
                    request,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, e.toString()),
                    ControlMessages.of(request, ControlOutcome.of(OperationResult.NATIVE_ERROR, ""), means));
            concluded = true;
        } finally {
            if (concluded) {
                record(result);
            }
            inFlight.remove(request.identity());
            deliver(result);
        }
    }

    private ProcessControlResult carryOut(ControlRequest request) {
        if (refusing) {
            return answer(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "shutting down"),
                    ControlMessages.shuttingDown(request));
        }
        if (needsObservation(request)) {
            return answer(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "process monitoring has stopped"),
                    ControlMessages.notMonitoring(request));
        }
        Capability capability = controller.capabilities().getOrDefault(request.action(), Capability.UNAVAILABLE);
        if (!capability.available()) {
            return answer(
                    request,
                    ControlOutcome.of(OperationResult.UNSUPPORTED, "the action is unavailable on this system"),
                    ControlMessages.unavailable(request));
        }
        if (request.action() != ControlAction.RESUME) {
            Optional<ProtectionRules.Refusal> refusal = rules.refusal(request.identity());
            if (refusal.isEmpty()) {
                ProtectionFacts facts = controller.protectionFacts(request.identity());
                refusal = rules.refusal(facts);
            }
            if (refusal.isPresent()) {
                return answer(
                        request,
                        ControlOutcome.of(OperationResult.ACCESS_DENIED, "refused by Prism: " + refusal.get()),
                        ControlMessages.refused(request, refusal.get()));
            }
        }
        ControlMessages.Means means = means(request);
        return switch (request.action()) {
            case END_TASK -> platform(request, controller.requestClose(request.identity()), means);
            case FORCE_KILL -> platform(request, controller.terminate(request.identity()), means);
            case CHANGE_PRIORITY ->
                platform(
                        request,
                        controller.changePriority(
                                request.identity(), request.priority().orElseThrow()),
                        means);
            case SUSPEND ->
                suspension(
                        request, coordinator.suspend(request.identity(), request.name(), SuspensionReason.USER), means);
            case RESUME -> suspension(request, coordinator.release(request.identity(), SuspensionReason.USER), means);
        };
    }

    /** How the action is carried out on this system, which its words follow. */
    private ControlMessages.Means means(ControlRequest request) {
        return new ControlMessages.Means(
                controller
                        .capabilities()
                        .getOrDefault(request.action(), Capability.UNAVAILABLE)
                        .mechanism(),
                !controller.listedPriorities().isEmpty());
    }

    private static ProcessControlResult platform(
            ControlRequest request, ControlOutcome outcome, ControlMessages.Means means) {
        return ControlMessages.result(request, outcome, ControlMessages.of(request, outcome, means));
    }

    private static ProcessControlResult suspension(
            ControlRequest request, SuspensionCoordinator.Outcome outcome, ControlMessages.Means means) {
        return ControlMessages.result(request, outcome.outcome(), ControlMessages.suspension(request, outcome, means));
    }

    private static ProcessControlResult answer(
            ControlRequest request, ControlOutcome outcome, ControlMessages.Wording wording) {
        return ControlMessages.result(request, outcome, wording);
    }

    private void answerAtOnce(ControlRequest request, OperationResult result, ControlMessages.Wording wording) {
        deliver(ControlMessages.result(request, ControlOutcome.of(result, "not attempted"), wording));
    }

    /** The activity log gets the user's sentence and, for whoever reads the log, what lies behind it. */
    private void record(ProcessControlResult result) {
        ControlOutcome outcome = result.outcome();
        StringBuilder entry = new StringBuilder(
                        ControlMessages.actionName(result.request().action()))
                .append(": ")
                .append(result.text());
        if (outcome.detail().isPresent() || outcome.nativeCode().isPresent()) {
            entry.append(" [");
            outcome.detail().ifPresent(entry::append);
            if (outcome.nativeCode().isPresent()) {
                entry.append(outcome.detail().isPresent() ? "; " : "")
                        .append("code ")
                        .append(outcome.nativeCode().getAsInt());
            }
            entry.append(']');
        }
        activity.record(ControlMessages.severity(outcome.result()), entry.toString());
    }

    private void deliver(ProcessControlResult result) {
        try {
            listener.accept(result);
        } catch (RuntimeException e) {
            warnOnce("The listener for process actions", e);
        }
    }

    private void warnOnce(String what, RuntimeException failure) {
        synchronized (failures) {
            failures.warnOnce(what, failure);
        }
    }
}
