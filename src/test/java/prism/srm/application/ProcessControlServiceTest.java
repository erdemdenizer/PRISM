package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;

/** CTL-7: off the calling thread, one result per request, one action per process at a time. */
class ProcessControlServiceTest {

    private static final long OWN = 1_111;
    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.EPOCH);
    private static final ProcessIdentity OTHER = new ProcessIdentity(5_555, Instant.EPOCH);

    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final QueuedExecutor executor = new QueuedExecutor();
    private final List<ProcessControlResult> results = new CopyOnWriteArrayList<>();
    private final SuspensionCoordinator coordinator = new SuspensionCoordinator(controller, activity, executor);
    private final ProcessControlService service = new ProcessControlService(
            controller, new ProtectionRules(OWN), coordinator, activity, executor, results::add);

    private static ControlRequest request(ControlAction action, ProcessIdentity target) {
        return action == ControlAction.CHANGE_PRIORITY
                ? ControlRequest.priority(target, "java", PriorityLevel.HIGH)
                : ControlRequest.of(action, target, "java");
    }

    @Test
    void nothingIsDoneOnTheCallingThread() {
        service.request(request(ControlAction.FORCE_KILL, DEMO));

        assertThat(controller.calls).isEmpty();
        assertThat(results).isEmpty();
        assertThat(service.inFlight(DEMO)).isTrue();
        executor.runAll();
        assertThat(controller.calls).containsExactly("facts 4242", "terminate 4242");
        assertThat(results).hasSize(1);
        assertThat(service.inFlight(DEMO)).isFalse();
    }

    @Test
    void eachActionReachesItsOperationAfterTheProtectionFacts() {
        for (ControlAction action :
                List.of(ControlAction.END_TASK, ControlAction.CHANGE_PRIORITY, ControlAction.SUSPEND)) {
            service.request(request(action, DEMO));
            executor.runAll();
        }
        service.request(request(ControlAction.RESUME, DEMO));
        executor.runAll();

        assertThat(controller.calls)
                .containsExactly(
                        "facts 4242",
                        "close 4242",
                        "facts 4242",
                        "priority 4242 HIGH",
                        "facts 4242",
                        "suspend 4242",
                        "resume 4242");
        assertThat(results).extracting(ProcessControlResult::result).containsOnly(OperationResult.SUCCESS);
    }

    @Test
    void theResultNamesTheRequestAndTheLogHasTheDetailBehindTheSentence() {
        service.request(request(ControlAction.END_TASK, DEMO));
        executor.runAll();

        ProcessControlResult result = results.get(0);
        assertThat(result.request()).isEqualTo(request(ControlAction.END_TASK, DEMO));
        assertThat(result.text()).isEqualTo("Close request sent to java (PID 4242). The application may ignore it.");
        assertThat(activity.entries())
                .extracting(ActivityEntry::severity, ActivityEntry::message)
                .containsExactly(tuple(
                        ActivitySeverity.INFO,
                        "End task: Close request sent to java (PID 4242). The application may ignore it. [posted]"));
    }

    /** SAFE-2: Prism is refused before anything, the system included, is asked. */
    @Test
    void prismItselfIsRefusedBeforeAnyCall() {
        service.request(request(ControlAction.FORCE_KILL, new ProcessIdentity(OWN, Instant.EPOCH)));
        executor.runAll();

        assertThat(controller.calls).isEmpty();
        assertThat(results.get(0).result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(results.get(0).text())
                .isEqualTo("Prism does not act on java (PID 1111). It is Prism's own process.");
        assertThat(activity.entries()).extracting(ActivityEntry::severity).containsExactly(ActivitySeverity.WARNING);
    }

    /** SAFE-3: a protected process is refused on the facts, before the action. */
    @Test
    void aCriticalProcessIsRefusedBeforeTheAction() {
        controller.facts = target -> new ProtectionFacts(false, Optional.of(true), false);

        service.request(request(ControlAction.SUSPEND, DEMO));
        executor.runAll();

        assertThat(controller.calls).containsExactly("facts 4242");
        assertThat(results.get(0).result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(coordinator.held()).isEmpty();
    }

    @Test
    void resumeOnlyReleasesWhatPrismHoldsAndAsksNoFacts() {
        coordinator.suspend(DEMO, "java", prism.srm.domain.SuspensionReason.USER);
        controller.calls.clear();
        controller.facts = target -> new ProtectionFacts(true, Optional.empty(), false);

        service.request(request(ControlAction.RESUME, DEMO));
        executor.runAll();

        assertThat(controller.calls).containsExactly("resume 4242");
        assertThat(results.get(0).text()).isEqualTo("java (PID 4242) was resumed.");
    }

    /** CAP-2: an action the system does not provide is answered as such, and nothing is called. */
    @Test
    void anUnavailableActionIsUnsupportedWithoutAnyCall() {
        controller.capabilities = FakeProcessController.noneAvailable();

        service.request(request(ControlAction.FORCE_KILL, DEMO));
        executor.runAll();

        assertThat(controller.calls).isEmpty();
        assertThat(results.get(0).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(results.get(0).text()).isEqualTo("Force kill is not available on this system.");
    }

    @Test
    void aSecondActionOnAProcessIsRefusedAtOnceAndNotQueuedWhileOthersAreTaken() {
        service.request(request(ControlAction.SUSPEND, DEMO));

        service.request(request(ControlAction.FORCE_KILL, DEMO));
        service.request(request(ControlAction.SUSPEND, DEMO));
        service.request(request(ControlAction.FORCE_KILL, OTHER));

        assertThat(executor.waiting()).as("one for each process").isEqualTo(2);
        assertThat(results)
                .extracting(r -> r.request().action(), ProcessControlResult::result)
                .containsExactly(
                        tuple(ControlAction.FORCE_KILL, OperationResult.INVALID_ARGUMENT),
                        tuple(ControlAction.SUSPEND, OperationResult.INVALID_ARGUMENT));
        assertThat(results.get(0).text())
                .isEqualTo("Nothing was done to java (PID 4242). Another action on it is in progress.");
        assertThat(activity.entries()).as("nothing was attempted").isEmpty();

        executor.runAll();
        assertThat(controller.calls).containsExactly("facts 4242", "suspend 4242", "facts 5555", "terminate 5555");

        service.request(request(ControlAction.RESUME, DEMO));
        assertThat(executor.waiting())
                .as("taken again once the first has its result")
                .isEqualTo(1);
    }

    @Test
    void aFailureInTheSystemIsANativeErrorAndTheProcessIsFreeAgain() {
        controller.terminate = target -> {
            throw new IllegalStateException("broken");
        };

        service.request(request(ControlAction.FORCE_KILL, DEMO));
        executor.runAll();

        assertThat(results.get(0).result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(results.get(0).text())
                .isEqualTo("The system did not terminate java (PID 4242). The Logs page has the details.");
        assertThat(service.inFlight(DEMO)).isFalse();
        assertThat(activity.entries()).extracting(ActivityEntry::severity).containsExactly(ActivitySeverity.ERROR);
    }

    /** An Error is not caught; whoever waits is still told, and the process is not left locked. */
    @Test
    void anErrorIsNotCaughtButTheListenerIsToldAndTheLockReleased() {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        controller.terminate = target -> {
            throw fatal;
        };
        service.request(request(ControlAction.FORCE_KILL, DEMO));

        assertThatThrownBy(executor::runAll).isSameAs(fatal);

        assertThat(results).extracting(ProcessControlResult::result).containsExactly(OperationResult.NATIVE_ERROR);
        assertThat(service.inFlight(DEMO)).isFalse();
        assertThat(activity.entries())
                .as("an Error is not recorded as a result")
                .isEmpty();
    }

    @Test
    void nativeCodesReachTheLogButNotTheSentence() {
        controller.priority =
                (target, level) -> ControlOutcome.of(OperationResult.ACCESS_DENIED, 5, "SetPriorityClass");

        service.request(request(ControlAction.CHANGE_PRIORITY, DEMO));
        executor.runAll();

        assertThat(results.get(0).text()).doesNotContain("5", "SetPriorityClass");
        assertThat(activity.entries())
                .extracting(ActivityEntry::severity, ActivityEntry::message)
                .containsExactly(tuple(
                        ActivitySeverity.WARNING,
                        "Change priority: Access denied: java (PID 4242). The system does not let Prism act on this process."
                                + " [SetPriorityClass; code 5]"));
    }

    @Test
    void afterTheShutdownHasBegunRequestsAreRefusedAtOnce() {
        service.refuseFurtherRequests();

        service.request(request(ControlAction.FORCE_KILL, DEMO));

        assertThat(executor.waiting()).isZero();
        assertThat(results.get(0).result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(results.get(0).text()).isEqualTo("Nothing was done to java (PID 4242). Prism is closing.");
        assertThat(service.inFlight(DEMO)).isFalse();
    }

    @Test
    void aRequestTakenBeforeTheShutdownIsAnsweredAsRefusedWhenItRuns() {
        service.request(request(ControlAction.FORCE_KILL, DEMO));
        service.refuseFurtherRequests();

        executor.runAll();

        assertThat(controller.calls).isEmpty();
        assertThat(results.get(0).result()).isEqualTo(OperationResult.UNSUPPORTED);
    }

    @Test
    void aFullExecutorRefusesTheRequestWithoutLockingTheProcess() {
        executor.refusing = true;

        service.request(request(ControlAction.FORCE_KILL, DEMO));

        assertThat(results.get(0).result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(results.get(0).text())
                .isEqualTo("Nothing was done to java (PID 4242). Too many actions are waiting.");
        assertThat(service.inFlight(DEMO)).isFalse();
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheNextAction() {
        ProcessControlService throwing = new ProcessControlService(
                controller, new ProtectionRules(OWN), coordinator, activity, executor, result -> {
                    throw new IllegalStateException("listener");
                });

        throwing.request(request(ControlAction.FORCE_KILL, DEMO));
        executor.runAll();
        throwing.request(request(ControlAction.FORCE_KILL, DEMO));
        executor.runAll();

        assertThat(controller.calls).containsExactly("facts 4242", "terminate 4242", "facts 4242", "terminate 4242");
    }

    @Test
    void aRequestThatReachedNoServiceIsAnsweredAsNotCarriedOut() {
        ProcessControlResult result = ProcessControlService.notCarriedOut(request(ControlAction.SUSPEND, DEMO));

        assertThat(result.result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(result.text()).isEqualTo("Nothing was done to java (PID 4242). Prism is not monitoring processes.");
    }

    /** Whoever is told of the result may at once ask again for the same process. */
    @Test
    void theProcessIsFreeAgainWhenTheResultIsDelivered() {
        java.util.concurrent.atomic.AtomicReference<ProcessControlService> self =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<Boolean> busyWhenTold = new CopyOnWriteArrayList<>();
        ProcessControlService watched = new ProcessControlService(
                controller,
                new ProtectionRules(OWN),
                coordinator,
                activity,
                executor,
                result -> busyWhenTold.add(self.get().inFlight(result.identity())));
        self.set(watched);

        watched.request(request(ControlAction.CHANGE_PRIORITY, DEMO));
        executor.runAll();

        assertThat(busyWhenTold).containsExactly(false);
    }

    // --- Process monitoring has stopped: a suspension Prism holds can still be released ---

    private ProcessControlResult only(ControlRequest request) {
        results.clear();
        service.request(request);
        executor.runAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void onceProcessesAreNoLongerObservedOnlyResumeIsTakenAndItReleasesPrismsIncrementOnce() {
        assertThat(only(request(ControlAction.SUSPEND, DEMO)).result()).isEqualTo(OperationResult.SUCCESS);
        FakeProcessController.FakeSuspension held = controller.suspensions.get(0);

        service.observationLost();

        for (ControlAction action : List.of(
                ControlAction.END_TASK,
                ControlAction.FORCE_KILL,
                ControlAction.SUSPEND,
                ControlAction.CHANGE_PRIORITY)) {
            ProcessControlResult refused = only(request(action, DEMO));
            assertThat(refused.result()).as(action.name()).isEqualTo(OperationResult.UNSUPPORTED);
            assertThat(refused.text())
                    .isEqualTo("Nothing was done to java (PID 4242). Prism is not monitoring processes.");
        }
        assertThat(controller.calls).containsExactly("facts 4242", "suspend 4242");

        ProcessControlResult resumed = only(request(ControlAction.RESUME, DEMO));
        assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
        assertThat(resumed.text()).isEqualTo("java (PID 4242) was resumed.");
        assertThat(held.resumes).isEqualTo(1);
        assertThat(coordinator.held()).isEmpty();

        ProcessControlResult again = only(request(ControlAction.RESUME, DEMO));
        assertThat(again.text()).isEqualTo("Prism does not hold java (PID 4242) suspended for you.");
        assertThat(held.resumes).as("nothing is taken back twice").isEqualTo(1);
        assertThat(service.inFlight(DEMO)).isFalse();
    }

    @Test
    void aSuspendTakenBeforeTheObservationWasLostIsRefusedWhenItRuns() {
        service.request(request(ControlAction.SUSPEND, DEMO));

        service.observationLost();
        executor.runAll();

        assertThat(results).extracting(ProcessControlResult::result).containsExactly(OperationResult.UNSUPPORTED);
        assertThat(controller.calls).isEmpty();
        assertThat(coordinator.held()).isEmpty();
    }

    @Test
    void aProcessPrismNeverSuspendedGainsNothingFromTheLostObservation() {
        service.observationLost();

        ProcessControlResult result = only(request(ControlAction.RESUME, OTHER));

        assertThat(result.result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(result.text()).isEqualTo("Prism does not hold java (PID 5555) suspended for you.");
        assertThat(controller.calls).isEmpty();
    }

    /** The process ended while held: nothing is resumed, Prism lets go of it and says so. */
    @Test
    void resumingAProcessThatHasExitedSaysSoAndLetsGoOfIt() {
        only(request(ControlAction.SUSPEND, DEMO));
        service.observationLost();
        controller.resume = suspension -> {
            suspension.outstanding = 0;
            return prism.srm.domain.ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
        };

        ProcessControlResult result = only(request(ControlAction.RESUME, DEMO));

        assertThat(result.result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        assertThat(result.text()).isEqualTo("java (PID 4242) has exited. Prism no longer holds it suspended.");
        assertThat(coordinator.held()).isEmpty();
        assertThat(controller.calls).containsExactly("facts 4242", "suspend 4242", "resume 4242");
        assertThat(activity.entries())
                .last()
                .satisfies(entry -> assertThat(entry.severity()).isEqualTo(prism.srm.domain.ActivitySeverity.WARNING));
    }

    /** A Resume taken before monitoring stopped still runs: it is the one action that needs no listing. */
    @Test
    void aResumeTakenBeforeTheObservationWasLostStillReleases() {
        only(request(ControlAction.SUSPEND, DEMO));
        results.clear();
        service.request(request(ControlAction.RESUME, DEMO));

        service.observationLost();
        executor.runAll();

        assertThat(results).extracting(ProcessControlResult::result).containsExactly(OperationResult.SUCCESS);
        assertThat(controller.suspensions.get(0).resumes).isEqualTo(1);
    }

    /** CAP-5: the words follow the mechanism the controller reports, without the service knowing the system. */
    @Test
    void theWordsFollowTheMechanismsOfTheController() {
        controller.capabilities = FakeProcessController.unixLike();
        controller.listedPriorities = Map.of();

        service.request(request(ControlAction.END_TASK, DEMO));
        executor.runAll();
        service.request(request(ControlAction.SUSPEND, DEMO));
        executor.runAll();
        service.request(request(ControlAction.CHANGE_PRIORITY, DEMO));
        executor.runAll();

        assertThat(results)
                .extracting(ProcessControlResult::text)
                .containsExactly(
                        "Asked java (PID 4242) to terminate. It may ignore the request.",
                        "java (PID 4242) was suspended. The system stopped the whole process.",
                        "Priority of java (PID 4242) set to High. The system confirmed it.");
    }

    @Test
    void whatTheControllerFoundMissingIsHandedOn() {
        controller.limitations = List.of("Process control: something is missing.");

        assertThat(service.limitations()).containsExactly("Process control: something is missing.");
    }
}
