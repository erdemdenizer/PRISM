package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.FakeProcessController.FakeSuspension;
import prism.srm.application.SuspensionCoordinator.Change;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.SuspendResult;

/** CTL-9: one owner, reasons per process, nothing forgotten, nothing resumed that another holds. */
class SuspensionCoordinatorTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.EPOCH);
    private static final ProcessIdentity OTHER = new ProcessIdentity(5_555, Instant.EPOCH);

    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final QueuedExecutor checks = new QueuedExecutor();

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", SuspensionJournal.onDisk());
        coordinator =
                new SuspensionCoordinator(controller, activity, checks, () -> Instant.EPOCH, Optional.of(journal));
    }

    @org.junit.jupiter.api.AfterEach
    void closeJournal() {
        // The lock file stays open until the journal is closed, and Windows keeps the folder then.
        journal.close();
    }

    /** An automatic reason suspends only once it is in the journal (ADR-0011): declared first, as the limit service does. */
    private SuspensionCoordinator.Outcome automatic(ProcessIdentity identity, String name, SuspensionReason reason) {
        assertThat(coordinator.declare(identity, name, reason, "test")).isTrue();
        return coordinator.suspend(identity, name, reason);
    }

    @Test
    void theFirstReasonSuspendsTheProcess() {
        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
        assertThat(outcome.outcome().succeeded()).isTrue();
        assertThat(controller.calls).containsExactly("suspend 4242");
        assertThat(coordinator.held()).containsExactly(DEMO);
    }

    @Test
    void aRepeatedSuspendForTheSameReasonDoesNothingToTheProcess() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        SuspensionCoordinator.Outcome again = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(again.change()).isEqualTo(Change.ALREADY_HELD);
        assertThat(controller.calls).containsExactly("suspend 4242");
    }

    @Test
    void releasingTheLastReasonResumesAndForgetsTheProcess() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        SuspensionCoordinator.Outcome outcome = coordinator.release(DEMO, SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.RESUMED);
        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
        assertThat(coordinator.held()).isEmpty();
    }

    /** The example of the plan: the user resumes, the limiter's reason keeps the process stopped. */
    @Test
    void anotherReasonKeepsTheProcessSuspendedWhenTheUserResumes() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);

        SuspensionCoordinator.Outcome outcome = coordinator.release(DEMO, SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.STILL_HELD);
        assertThat(outcome.remaining()).containsExactly(SuspensionReason.CPU_LIMITER);
        assertThat(controller.calls).containsExactly("suspend 4242");
        assertThat(coordinator.held())
                .as("the limiter's own hold is shown by its limit, not as suspended by Prism")
                .isEmpty();
        assertThat(coordinator.heldFor(DEMO)).containsExactly(SuspensionReason.CPU_LIMITER);

        assertThat(coordinator.release(DEMO, SuspensionReason.CPU_LIMITER).change())
                .isEqualTo(Change.RESUMED);
        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
    }

    @Test
    void aReasonPrismDoesNotHoldIsNotReleasedAndNothingIsResumed() {
        assertThat(coordinator.release(DEMO, SuspensionReason.USER).change()).isEqualTo(Change.NOT_HELD);

        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);
        SuspensionCoordinator.Outcome outcome = coordinator.release(DEMO, SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.NOT_HELD);
        assertThat(outcome.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(coordinator.reasons(DEMO)).containsExactly(SuspensionReason.CPU_LIMITER);
        assertThat(controller.calls).doesNotContain("resume 4242");
    }

    @Test
    void aFailedSuspendHoldsNothing() {
        controller.suspend =
                target -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, 5, "OpenThread"));

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.FAILED);
        assertThat(outcome.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(coordinator.held()).isEmpty();
    }

    /** A rollback that left part of a suspension in place is kept, shown, and released by the next resume. */
    @Test
    void anIncompleteRollbackIsKeptAndTheNextResumeReleasesIt() {
        FakeSuspension remainder = new FakeSuspension(DEMO, 1);
        controller.suspend = target -> new SuspendResult(
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback left 1"), Optional.of(remainder));

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.INCOMPLETE);
        assertThat(outcome.outstanding()).isEqualTo(1);
        assertThat(coordinator.held()).as("Prism still holds part of it").containsExactly(DEMO);
        assertThat(coordinator.reasons(DEMO)).isEmpty();

        SuspensionCoordinator.Outcome resumed = coordinator.release(DEMO, SuspensionReason.USER);

        assertThat(resumed.change()).isEqualTo(Change.RESUMED);
        assertThat(remainder.resumes).isEqualTo(1);
        assertThat(coordinator.held()).isEmpty();
    }

    @Test
    void aResumeThatLeavesThreadsHeldKeepsTheSuspensionAsIncomplete() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        FakeSuspension suspension = controller.suspensions.get(0);
        controller.resume = s -> {
            s.outstanding = 1;
            return ControlOutcome.of(OperationResult.NATIVE_ERROR, 6, "ResumeThread");
        };

        SuspensionCoordinator.Outcome outcome = coordinator.release(DEMO, SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.INCOMPLETE);
        assertThat(outcome.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(suspension.closes).as("its handles stay with it").isZero();

        controller.resume = s -> {
            s.outstanding = 0;
            return ControlOutcome.success();
        };
        assertThat(coordinator.release(DEMO, SuspensionReason.USER).change()).isEqualTo(Change.RESUMED);
        assertThat(coordinator.held()).isEmpty();
    }

    @Test
    void aSuspendOnTopOfAnIncompleteSuspensionFirstReleasesWhatIsLeft() {
        FakeSuspension remainder = new FakeSuspension(DEMO, 1);
        controller.suspend = target ->
                new SuspendResult(ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback"), Optional.of(remainder));
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        controller.suspend = target -> {
            FakeSuspension fresh = new FakeSuspension(target, 2);
            return SuspendResult.suspended(ControlOutcome.success(), fresh);
        };

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242", "suspend 4242");
        assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
    }

    // --- A held process missing from a listing ---

    /** One missing listing is no proof of exit: the check finds it alive, and the suspension stays. */
    @Test
    void aProcessMissingFromOneListingButAliveKeepsItsSuspension() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        coordinator.listed(Set.of(OTHER));

        assertThat(controller.calls)
                .as("nothing is asked on the monitoring thread")
                .containsExactly("suspend 4242");
        assertThat(checks.waiting()).isEqualTo(1);
        checks.runAll();
        assertThat(controller.calls).containsExactly("suspend 4242", "liveness 4242");
        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(controller.suspensions.get(0).closes).isZero();
        assertThat(coordinator.checkPending(DEMO)).isFalse();
    }

    @Test
    void aConfirmedExitReleasesTheSuspensionWithoutResumingAndSaysSo() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "exited");

        coordinator.listed(Set.of());
        checks.runAll();

        assertThat(coordinator.held()).isEmpty();
        assertThat(controller.suspensions.get(0).closes).isEqualTo(1);
        assertThat(controller.calls).doesNotContain("resume 4242");
        assertThat(activity.entries())
                .extracting(ActivityEntry::message)
                .containsExactly("java (PID 4242) exited while Prism held it suspended.");
    }

    @Test
    void aProcessThatIsBackInTheNextListingIsKept() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        coordinator.listed(Set.of());
        coordinator.listed(Set.of(DEMO));

        checks.runAll();

        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(controller.calls)
                .as("the check that was queued runs once, and the listing that has it back queues none")
                .containsExactly("suspend 4242", "liveness 4242");
        assertThat(controller.suspensions.get(0).closes).isZero();
    }

    @Test
    void listingsThatKeepMissingTheProcessQueueOneCheckOnly() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        coordinator.listed(Set.of());
        coordinator.listed(Set.of());
        coordinator.listed(Set.of());

        assertThat(checks.waiting()).isEqualTo(1);
        checks.runAll();
        coordinator.listed(Set.of());
        assertThat(checks.waiting()).as("a new check once the last one is done").isEqualTo(1);
    }

    @Test
    void aCheckThatCannotDecideKeepsTheSuspensionAndIsReportedAfterThreeInARow() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        controller.liveness = target -> ControlOutcome.of(OperationResult.ACCESS_DENIED, 5, "OpenProcess");

        for (int i = 0; i < SuspensionCoordinator.UNDECIDED_CHECKS_REPORTED; i++) {
            coordinator.listed(Set.of());
            checks.runAll();
        }

        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(activity.entries()).extracting(ActivityEntry::severity).containsExactly(ActivitySeverity.WARNING);
    }

    @Test
    void aFullExecutorDropsTheCheckSoTheNextListingQueuesItAgain() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        checks.refusing = true;

        coordinator.listed(Set.of());

        assertThat(coordinator.checkPending(DEMO)).isFalse();
        checks.refusing = false;
        coordinator.listed(Set.of());
        assertThat(checks.waiting()).isEqualTo(1);
    }

    @Test
    void aListingThatHoldsEveryHeldProcessQueuesNothing() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        coordinator.listed(Set.of(DEMO, OTHER));

        assertThat(checks.waiting()).isZero();
    }

    // --- Shutdown ---

    @Test
    void afterTheShutdownHasBegunNothingNewIsSuspendedButReleaseStillWorks() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        coordinator.refuseFurtherSuspensions();

        assertThat(coordinator.suspend(OTHER, "x", SuspensionReason.USER).change())
                .isEqualTo(Change.REFUSED);
        assertThat(coordinator.release(DEMO, SuspensionReason.USER).change()).isEqualTo(Change.RESUMED);
        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
        coordinator.listed(Set.of());
        assertThat(checks.waiting()).as("no check once the shutdown has begun").isZero();
    }

    @Test
    void releasingTheAutomaticReasonsResumesOnlyWhatTheUserDoesNotHold() {
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);
        coordinator.suspend(OTHER, "x", SuspensionReason.USER);
        automatic(OTHER, "x", SuspensionReason.OOM_GUARD);

        assertThat(coordinator.releaseAutomaticReasons(Duration.ofSeconds(1))).isTrue();

        assertThat(controller.calls).containsExactly("suspend 4242", "suspend 5555", "resume 4242");
        assertThat(coordinator.held()).containsExactly(OTHER);
        assertThat(coordinator.reasons(OTHER)).containsExactly(SuspensionReason.USER);
    }

    /** LIFE-2: what the user suspended stays suspended when Prism closes; its resources are let go. */
    @Test
    void finalizingLeavesTheUsersSuspensionInPlaceAndClosesItsResources() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(coordinator.finalizeForShutdown(Duration.ofSeconds(1))).isTrue();

        assertThat(controller.calls).containsExactly("suspend 4242");
        assertThat(controller.suspensions.get(0).closes).isEqualTo(1);
        assertThat(coordinator.held()).isEmpty();
        assertThat(activity.entries())
                .extracting(ActivityEntry::message)
                .containsExactly(
                        "Left suspended, as the user asked: java (PID 4242). They stay suspended after Prism closes.");
    }

    @Test
    void finalizingTriesAnIncompleteSuspensionOnceMoreAndReportsWhatIsLeft() {
        FakeSuspension remainder = new FakeSuspension(DEMO, 2);
        controller.suspend = target ->
                new SuspendResult(ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback"), Optional.of(remainder));
        controller.resume = s -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "still");
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        coordinator.finalizeForShutdown(Duration.ofSeconds(1));

        assertThat(remainder.resumes).isEqualTo(1);
        assertThat(remainder.closes).isEqualTo(1);
        assertThat(activity.entries())
                .extracting(ActivityEntry::severity, ActivityEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        ActivitySeverity.ERROR, "Threads Prism suspended could not all be released: java (PID 4242)."));
    }

    /**
     * LIFE-3 with LIFE-1: the step that releases the automatic reasons failed or ran out of time, and
     * this step gets the lock. What is Prism's own is never let go of as it stands: a process nobody
     * asked to suspend would stay frozen after Prism closes.
     */
    @Test
    void finalizingReleasesAnAutomaticHoldTheEarlierStepLeftInsteadOfAbandoningIt() {
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);

        assertThat(coordinator.finalizeForShutdown(Duration.ofSeconds(1))).isTrue();

        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
        assertThat(controller.suspensions.get(0).outstanding).isZero();
        assertThat(coordinator.held()).isEmpty();
    }

    /** A resume that throws is not the end of the hold: tried once more, and what is left is said aloud. */
    @Test
    void finalizingReportsAnAutomaticHoldWhoseResumeThrewAndThenDidNotComplete() {
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);
        List<String> tries = new ArrayList<>();
        controller.resume = s -> {
            tries.add("resume");
            if (tries.size() == 1) {
                throw new IllegalStateException("native call failed");
            }
            return ControlOutcome.of(OperationResult.NATIVE_ERROR, "still");
        };

        assertThat(coordinator.finalizeForShutdown(Duration.ofSeconds(1))).isTrue();

        assertThat(tries).hasSize(2);
        assertThat(controller.suspensions.get(0).outstanding).isPositive();
        assertThat(activity.entries())
                .filteredOn(entry -> entry.severity() == ActivitySeverity.ERROR)
                .extracting(ActivityEntry::message)
                .anyMatch(message -> message.startsWith("java (PID 4242) could not be resumed: "))
                .contains("Threads Prism suspended could not all be released: java (PID 4242).");
    }

    /** Both tries throw: nothing is let go of, so that the exit hook still finds the hold. */
    @Test
    void finalizingLetsGoOfNothingWhenTheSecondTryThrowsToo() {
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);
        controller.resume = s -> {
            throw new IllegalStateException("native call failed");
        };

        assertThatThrownBy(() -> coordinator.finalizeForShutdown(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(controller.suspensions.get(0).closes).isZero();
        assertThat(coordinator.held()).isNotEmpty();
    }

    /** The same, on a process the user holds too: the user's reason stays, and the process stays suspended. */
    @Test
    void finalizingKeepsTheUsersReasonWhenItDropsAnAutomaticOneLeftBeside() {
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(coordinator.finalizeForShutdown(Duration.ofSeconds(1))).isTrue();

        assertThat(controller.calls).containsExactly("suspend 4242");
        assertThat(controller.suspensions.get(0).outstanding).isPositive();
        assertThat(activity.entries())
                .extracting(ActivityEntry::message)
                .contains(
                        "Left suspended, as the user asked: java (PID 4242). They stay suspended after Prism closes.");
    }

    /** A shutdown never waits for ever on an action that holds the coordinator in the system. */
    @Test
    void theShutdownStepsGiveUpWhenTheyCannotHaveTheLockInTime() throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        controller.suspend = target -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SuspendResult.failed(ControlOutcome.of(OperationResult.NATIVE_ERROR, "stuck"));
        };
        Thread stuck = new Thread(() -> coordinator.suspend(DEMO, "java", SuspensionReason.USER), "stuck-action");
        stuck.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(coordinator.releaseAutomaticReasons(Duration.ofMillis(50)))
                    .isFalse();
            assertThat(coordinator.finalizeForShutdown(Duration.ofMillis(50))).isFalse();
            assertThat(activity.entries()).extracting(ActivityEntry::severity).containsOnly(ActivitySeverity.WARNING);
        } finally {
            release.countDown();
            stuck.join(10_000);
        }
    }

    /** The retry of an incomplete suspension fails again: nothing new is suspended and nothing is forgotten. */
    @Test
    void aSuspendOnTopOfAnIncompleteSuspensionWhoseReleaseFailsAgainSuspendsNothing() {
        FakeSuspension remainder = new FakeSuspension(DEMO, 1);
        controller.suspend = target ->
                new SuspendResult(ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback"), Optional.of(remainder));
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        controller.resume = s -> ControlOutcome.of(OperationResult.NATIVE_ERROR, 6, "still held");

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.INCOMPLETE);
        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(remainder.outstanding).isEqualTo(1);
        assertThat(remainder.closes).isZero();
    }

    /** Only undecided checks in a row count: one that finds the process alive starts the count again. */
    @Test
    void anAnswerInBetweenStartsTheCountOfUndecidedChecksAgainAndTheWarningComesOnce() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        ControlOutcome undecided = ControlOutcome.of(OperationResult.ACCESS_DENIED, 5, "OpenProcess");
        Runnable missed = () -> {
            coordinator.listed(Set.of());
            checks.runAll();
        };

        controller.liveness = target -> undecided;
        missed.run();
        missed.run();
        controller.liveness = target -> ControlOutcome.success();
        missed.run();
        controller.liveness = target -> undecided;
        missed.run();
        missed.run();
        assertThat(warnings()).isEmpty();

        for (int i = 0; i < 6; i++) {
            missed.run();
        }
        assertThat(warnings()).hasSize(1);
        assertThat(coordinator.held()).containsExactly(DEMO);
    }

    @Test
    void aCheckThatThrowsKeepsTheSuspensionAndALaterListingChecksAgain() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        controller.liveness = target -> {
            throw new IllegalStateException("simulated");
        };

        coordinator.listed(Set.of());
        checks.runAll();

        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(coordinator.checkPending(DEMO)).isFalse();
        assertThat(controller.suspensions.get(0).closes).isZero();
        coordinator.listed(Set.of());
        assertThat(checks.waiting()).isEqualTo(1);
    }

    private java.util.List<ActivityEntry> warnings() {
        return activity.entries().stream()
                .filter(entry -> entry.severity() == ActivitySeverity.WARNING)
                .toList();
    }

    private static prism.srm.domain.ProcessSnapshot listed(ProcessIdentity identity) {
        return new prism.srm.domain.ProcessSnapshot(
                identity,
                "java",
                java.util.OptionalLong.of(1),
                Optional.empty(),
                java.util.OptionalDouble.of(25),
                java.util.OptionalLong.of(100),
                java.util.OptionalLong.empty(),
                java.util.OptionalLong.empty(),
                Optional.of("ED"),
                java.util.OptionalInt.of(24),
                java.util.OptionalInt.of(8),
                java.util.OptionalDouble.empty(),
                java.util.OptionalDouble.empty());
    }

    @Test
    void withNothingHeldAListingPassesAsItIs() {
        java.util.List<prism.srm.domain.ProcessSnapshot> listing = java.util.List.of(listed(DEMO), listed(OTHER));

        assertThat(coordinator.observe(listing)).isSameAs(listing);
        assertThat(checks.waiting()).isZero();
    }

    @Test
    void aListingShowsWhatPrismHoldsAndQueuesACheckForAHeldProcessItMisses() {
        coordinator.suspend(DEMO, "java", SuspensionReason.USER);
        coordinator.suspend(OTHER, "java", SuspensionReason.USER);

        java.util.List<prism.srm.domain.ProcessSnapshot> shown = coordinator.observe(java.util.List.of(listed(DEMO)));

        assertThat(shown)
                .singleElement()
                .satisfies(process ->
                        assertThat(process.state()).contains(prism.srm.domain.ProcessState.SUSPENDED_BY_PRISM));
        assertThat(checks.waiting()).as("a check for the one it misses").isEqualTo(1);
        assertThat(controller.calls).containsExactly("suspend 4242", "suspend 5555");
    }

    @Test
    void theHeldProcessesAreHandedOverWithTheirNamesWhenTheyChange() {
        java.util.List<java.util.List<prism.srm.domain.SuspendedProcess>> seen = new java.util.ArrayList<>();
        coordinator.setHeldListener(seen::add);

        coordinator.suspend(OTHER, "zeta", SuspensionReason.USER);
        coordinator.suspend(DEMO, "alpha", SuspensionReason.USER);
        coordinator.release(OTHER, SuspensionReason.USER);

        assertThat(seen)
                .containsExactly(
                        java.util.List.of(),
                        java.util.List.of(new prism.srm.domain.SuspendedProcess(OTHER, "zeta")),
                        java.util.List.of(
                                new prism.srm.domain.SuspendedProcess(DEMO, "alpha"),
                                new prism.srm.domain.SuspendedProcess(OTHER, "zeta")),
                        java.util.List.of(new prism.srm.domain.SuspendedProcess(DEMO, "alpha")));
    }

    @Test
    void aListenerThatThrowsChangesNothingHeld() {
        coordinator.setHeldListener(processes -> {
            if (!processes.isEmpty()) {
                throw new IllegalStateException("simulated");
            }
        });

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "java", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
        assertThat(coordinator.held()).containsExactly(DEMO);
    }

    /** At shutdown, an automatic reason on a process that has exited is not an error. */
    @Test
    void anAutomaticReasonOnAProcessThatHasExitedIsReleasedWithoutAnError() {
        automatic(DEMO, "java", SuspensionReason.CPU_LIMITER);
        controller.resume = suspension -> {
            suspension.outstanding = 0;
            return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited");
        };

        assertThat(coordinator.releaseAutomaticReasons(Duration.ofSeconds(1))).isTrue();

        assertThat(coordinator.held()).isEmpty();
        assertThat(activity.entries())
                .extracting(ActivityEntry::severity, ActivityEntry::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        ActivitySeverity.INFO, "java (PID 4242) has exited; Prism no longer holds it suspended."));
    }
}
