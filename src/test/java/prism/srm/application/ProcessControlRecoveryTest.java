package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.StopState;

/**
 * The user's requests about processes an earlier session may have left suspended, through the
 * process control service (ADR-0011): off the calling thread, one per process at a time, without
 * process monitoring, and never on Prism or a protected process.
 */
class ProcessControlRecoveryTest {

    private static final long OWN = 1_111;
    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.EPOCH);
    private static final Instant WRITTEN = Instant.parse("2026-10-08T09:00:00Z");

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final QueuedExecutor executor = new QueuedExecutor();
    private final List<RecoveryResult> results = new CopyOnWriteArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private ProcessControlService service;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "2-2", SuspensionJournal.onDisk());
        coordinator =
                new SuspensionCoordinator(controller, activity, executor, () -> Instant.EPOCH, Optional.of(journal));
        service = new ProcessControlService(
                controller, new ProtectionRules(OWN), coordinator, activity, executor, result -> {});
        service.setRecoveryListener(results::add);
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
    }

    @AfterEach
    void closeJournal() {
        journal.close();
    }

    private void listed(ProcessIdentity identity) {
        coordinator.recover(List.of(new JournalEntry(
                identity,
                "demo",
                EnumSet.of(SuspensionReason.USER),
                Optional.empty(),
                Optional.empty(),
                "1-1",
                WRITTEN,
                WRITTEN,
                false,
                false)));
        controller.calls.clear();
    }

    private RecoveryResult ask(RecoveryRequest.Action action, ProcessIdentity identity) {
        service.recover(new RecoveryRequest(action, identity, "demo"));
        executor.runAll();
        assertThat(results).isNotEmpty();
        return results.get(results.size() - 1);
    }

    @Test
    void aResumeIsCarriedOutOffTheCallingThreadAfterTheCheckAndWithoutMonitoring() {
        listed(DEMO);
        controller.adopt =
                (target, memento) -> AdoptResult.adopted(ControlOutcome.success("stopped"), controller.adopted(target));
        service.observationLost();

        service.recover(new RecoveryRequest(RecoveryRequest.Action.RESUME, DEMO, "demo"));
        assertThat(controller.calls).isEmpty();
        executor.runAll();

        RecoveryResult result = results.get(0);
        assertThat(result.outcome().succeeded()).isTrue();
        assertThat(result.text()).contains("is resumed").contains("does not prove the stop was Prism's");
        assertThat(controller.calls).containsExactly("facts 4242", "adopt 4242", "resume 4242");
        assertThat(activity.entries())
                .anySatisfy(entry ->
                        assertThat(entry.message()).startsWith("Resume after a crash: demo (PID 4242) is resumed."));
    }

    @Test
    void aRefusedCheckIsReportedWithItsReasonAndNothingIsResumed() {
        listed(DEMO);
        controller.adopt = (target, memento) -> AdoptResult.of(
                AdoptResult.Verdict.REFUSED,
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "another suspension also holds it"));

        RecoveryResult result = ask(RecoveryRequest.Action.RESUME, DEMO);

        assertThat(result.text())
                .isEqualTo("Prism did not resume demo (PID 4242). Another suspension also holds it. Prism changed"
                        + " nothing.");
        assertThat(controller.calls).doesNotContain("resume 4242");
    }

    @Test
    void anEntryNamingPrismItselfIsNotActedOn() {
        ProcessIdentity own = new ProcessIdentity(OWN, Instant.EPOCH);
        listed(own);

        RecoveryResult result = ask(RecoveryRequest.Action.RESUME, own);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(controller.calls).doesNotContain("adopt 1111");
    }

    @Test
    void anEntryNamingAProtectedProcessIsNotActedOn() {
        listed(DEMO);
        controller.facts = target -> new ProtectionFacts(true, Optional.empty(), false);

        RecoveryResult result = ask(RecoveryRequest.Action.RESUME, DEMO);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.text()).contains("never suspends");
        assertThat(controller.calls).doesNotContain("adopt 4242");
    }

    @Test
    void withoutResumeOnThisSystemNothingIsChecked() {
        listed(DEMO);
        controller.capabilities = FakeProcessController.noneAvailable();

        RecoveryResult result = ask(RecoveryRequest.Action.RESUME, DEMO);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(controller.calls).isEmpty();
    }

    @Test
    void dismissingTouchesNothingAndASecondDismissFindsNoEntry() {
        listed(DEMO);

        RecoveryResult first = ask(RecoveryRequest.Action.DISMISS, DEMO);
        RecoveryResult second = ask(RecoveryRequest.Action.DISMISS, DEMO);

        assertThat(first.text())
                .isEqualTo("The entry for demo (PID 4242) is dismissed. Prism did not touch the process.");
        assertThat(second.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(controller.calls).isEmpty();
        assertThat(coordinator.recovered()).isEmpty();
    }

    @Test
    void aSecondActionOnTheProcessWaitsForNone() {
        listed(DEMO);
        service.request(ControlRequest.of(ControlAction.SUSPEND, DEMO, "demo"));

        service.recover(new RecoveryRequest(RecoveryRequest.Action.RESUME, DEMO, "demo"));

        assertThat(results)
                .singleElement()
                .satisfies(result -> assertThat(result.text()).contains("Another action on it is in progress"));
    }

    @Test
    void atShutdownRequestsAreAnsweredAtOnce() {
        listed(DEMO);
        service.refuseFurtherRequests();

        service.recover(new RecoveryRequest(RecoveryRequest.Action.RESUME, DEMO, "demo"));

        assertThat(results)
                .singleElement()
                .satisfies(result -> assertThat(result.text()).contains("Prism is closing"));
        assertThat(controller.calls).isEmpty();
    }
}
