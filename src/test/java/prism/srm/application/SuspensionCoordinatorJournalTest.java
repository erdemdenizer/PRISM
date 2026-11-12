package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.FakeProcessController.FakeSuspension;
import prism.srm.application.SuspensionCoordinator.Change;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspendedProcess;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.SuspendResult;

/**
 * The suspension journal as the coordinator keeps it (ADR-0011): written ahead of a suspension,
 * not on every cycle of the limiter, never skipped for an automatic reason, and what the user is
 * shown of the limiter's holds.
 */
class SuspensionCoordinatorJournalTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-08T10:00:00Z"));

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final List<String> journalFailures = new ArrayList<>();
    private final List<List<SuspendedProcess>> shown = new ArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
        coordinator.setJournalListener(journalFailures::add);
        coordinator.setHeldListener(shown::add);
    }

    @AfterEach
    void closeJournal() {
        // The lock file stays open until the journal is closed, and Windows keeps the folder then.
        journal.close();
    }

    private List<JournalEntry> journaled() throws Exception {
        return SuspensionJournal.decode(Files.readAllBytes(journal.file())).entries();
    }

    @Test
    void anAutomaticReasonThatIsNotInTheJournalSuspendsNothing() {
        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);

        assertThat(outcome.change()).isEqualTo(Change.FAILED);
        assertThat(controller.calls).doesNotContain("suspend 4242");
    }

    @Test
    void withoutAJournalEveryAutomaticSuspensionIsRefusedAndNothingIsDeclared() {
        SuspensionCoordinator none = new SuspensionCoordinator(controller, activity, new QueuedExecutor());

        assertThat(none.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit"))
                .isFalse();
        assertThat(none.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER).change())
                .isEqualTo(Change.FAILED);
        assertThat(controller.calls).isEmpty();
    }

    @Test
    void aDeclaredLimitIsInTheJournalBeforeAnySuspensionAndLeavesItWhenWithdrawn() throws Exception {
        assertThat(coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "CPU limit 30 %"))
                .isTrue();

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.identity()).isEqualTo(DEMO);
            assertThat(entry.reasons()).containsExactly(SuspensionReason.CPU_LIMITER);
            assertThat(entry.cause()).contains("CPU limit 30 %");
        });
        assertThat(controller.calls).isEmpty();

        assertThat(coordinator.withdraw(DEMO, SuspensionReason.CPU_LIMITER)).isTrue();
        assertThat(journaled()).isEmpty();
    }

    @Test
    void theLimitersCyclesDoNotWriteTheJournal() {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        int writes = files.writes;

        for (int cycle = 0; cycle < 10; cycle++) {
            assertThat(coordinator
                            .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                            .change())
                    .isEqualTo(Change.SUSPENDED);
            assertThat(coordinator.release(DEMO, SuspensionReason.CPU_LIMITER).change())
                    .isEqualTo(Change.RESUMED);
        }

        assertThat(files.writes).isEqualTo(writes);
    }

    @Test
    void aJournalThatFailsRefusesTheLimiterAndTellsTheListenerOnce() {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        files.diskFull = true;

        assertThat(coordinator.declare(new ProcessIdentity(5, Instant.EPOCH), "x", SuspensionReason.CPU_LIMITER, "l"))
                .isFalse();

        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                        .change())
                .as("the journal no longer holds anything Prism could rely on")
                .isEqualTo(Change.FAILED);
        assertThat(controller.calls).doesNotContain("suspend 4242");
        coordinator.withdraw(DEMO, SuspensionReason.CPU_LIMITER);
        assertThat(journalFailures).hasSize(1);
    }

    @Test
    void theUsersSuspensionIsWrittenBeforeTheProcessIsTouched() {
        List<List<JournalEntry>> atTheSuspend = new ArrayList<>();
        controller.suspend = target -> {
            try {
                atTheSuspend.add(journaled());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            FakeSuspension suspension = controller.adopted(target);
            return SuspendResult.suspended(ControlOutcome.success(), suspension);
        };

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        assertThat(outcome.recorded()).isTrue();
        assertThat(atTheSuspend)
                .singleElement()
                .satisfies(entries -> assertThat(entries)
                        .singleElement()
                        .satisfies(entry -> assertThat(entry.reasons()).containsExactly(SuspensionReason.USER)));
    }

    @Test
    void theUsersSuspensionGoesAheadWithAFailedJournalAndSaysItWasNotRecorded() {
        files.diskFull = true;

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
        assertThat(outcome.recorded()).isFalse();
    }

    @Test
    void aFailedSuspendTakesTheUsersEntryOutOfTheJournalAgain() throws Exception {
        controller.suspend = target -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "no"));

        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        assertThat(journaled()).isEmpty();
    }

    @Test
    void theUsersSuspensionKeepsItsMementoAndStaysInTheJournalAfterTheShutdown() throws Exception {
        controller.suspend = target -> {
            FakeSuspension suspension = controller.adopted(target);
            suspension.memento = Optional.of("w1:12@3");
            return SuspendResult.suspended(ControlOutcome.success(), suspension);
        };
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        coordinator.refuseFurtherSuspensions();
        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));
        coordinator.finalizeForShutdown(Duration.ofSeconds(1));

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.reasons()).containsExactly(SuspensionReason.USER);
            assertThat(entry.memento()).contains("w1:12@3");
        });
    }

    @Test
    void anIncompleteSuspensionIsInTheJournalWithWhatItStillHolds() throws Exception {
        controller.suspend = target -> {
            FakeSuspension remainder = controller.adopted(target);
            remainder.memento = Optional.of("w1:12@3");
            return new SuspendResult(
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback left one"), Optional.of(remainder));
        };

        assertThat(coordinator.suspend(DEMO, "demo", SuspensionReason.USER).change())
                .isEqualTo(Change.INCOMPLETE);

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.incomplete()).isTrue();
            assertThat(entry.memento()).contains("w1:12@3");
        });
    }

    @Test
    void theUserIsShownTheirOwnSuspensionsButNotTheLimitersMomentaryHolds() {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        shown.clear();

        coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);

        assertThat(coordinator.held()).isEmpty();
        assertThat(shown).as("nothing to tell: nothing shown changed").isEmpty();
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);
        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(shown).singleElement().satisfies(list -> assertThat(list).hasSize(1));
    }

    @Test
    void aProcessStoppedElsewhereIsToldApartAndNothingIsHeld() {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        controller.suspend = target ->
                SuspendResult.stoppedElsewhere(ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "already stopped"));

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);

        assertThat(outcome.change()).isEqualTo(Change.STOPPED_ELSEWHERE);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void releasingTheAutomaticReasonsAtTheShutdownTakesTheLimitsOutOfTheJournal() throws Exception {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);

        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));

        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
        assertThat(journaled()).isEmpty();
    }

    @Test
    void theHookReleasesTheLimitersHoldAndSaysNothingWhenNothingIsHeld() {
        assertThat(coordinator.releaseForExit(Duration.ofSeconds(1))).isFalse();
        assertThat(activity.entries()).isEmpty();
    }

    @Test
    void theHookReleasesTheLimitersHold() {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);

        assertThat(coordinator.releaseForExit(Duration.ofSeconds(1))).isTrue();

        assertThat(controller.calls).containsExactly("suspend 4242", "resume 4242");
        assertThat(coordinator.suspend(DEMO, "demo", SuspensionReason.USER).change())
                .isEqualTo(Change.REFUSED);
    }

    @Test
    void aJournalThatChangedOnDiskFailsTheCheckAndStopsTheLimiter() throws Exception {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        Files.writeString(journal.file(), "tampered");

        assertThat(coordinator.verifyJournal()).isFalse();

        assertThat(journalFailures).hasSize(1);
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                        .change())
                .isEqualTo(Change.FAILED);
    }

    @Test
    void aResumeThatThrowsLeavesTheHoldShownAsIncompleteNotHidden() {
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);
        controller.resume = suspension -> {
            throw new IllegalStateException("broken");
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.release(DEMO, SuspensionReason.USER))
                .isInstanceOf(IllegalStateException.class);

        assertThat(coordinator.held()).containsExactly(DEMO);
        assertThat(shown.get(shown.size() - 1)).as("shown, to be resumed again").hasSize(1);
    }

    @Test
    void atShutdownOneProcessThatCannotBeResumedDoesNotKeepTheOthersSuspended() {
        ProcessIdentity other = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        coordinator.declare(other, "other", SuspensionReason.CPU_LIMITER, "limit");
        coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);
        coordinator.suspend(other, "other", SuspensionReason.CPU_LIMITER);
        var resumeForReal = controller.resume;
        controller.resume = suspension -> {
            if (suspension.identity().equals(DEMO)) {
                throw new IllegalStateException("broken");
            }
            return resumeForReal.apply(suspension);
        };

        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));

        assertThat(controller.suspensions)
                .filteredOn(suspension -> suspension.identity().equals(other))
                .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
        assertThat(coordinator.held()).as("the one that failed is shown").containsExactly(DEMO);
    }

    @Test
    void atShutdownAnIncompleteSuspensionReleasedNowLeavesTheJournalAndOneStillPartlyHeldKeepsItsThreads()
            throws Exception {
        ProcessIdentity other = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));
        controller.suspend = target -> {
            FakeSuspension remainder = controller.adopted(target);
            remainder.memento = Optional.of("w1:" + target.pid() + "@1");
            return new SuspendResult(
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback left one"), Optional.of(remainder));
        };
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);
        coordinator.suspend(other, "other", SuspensionReason.USER);
        assertThat(journaled()).hasSize(2);
        controller.resume = suspension -> {
            if (suspension.identity().equals(DEMO)) {
                suspension.outstanding = 0;
                return ControlOutcome.success("released");
            }
            suspension.memento = Optional.of("w1:5555@2");
            return ControlOutcome.of(OperationResult.NATIVE_ERROR, "still held");
        };

        coordinator.refuseFurtherSuspensions();
        coordinator.finalizeForShutdown(Duration.ofSeconds(1));

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.identity()).isEqualTo(other);
            assertThat(entry.memento()).contains("w1:5555@2");
        });
    }

    @Test
    void onlyATransientFailureThatLeftNothingHeldIsRetryable() {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        controller.suspend = target ->
                SuspendResult.transientFailure(ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread ended"));
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                        .retryable())
                .isTrue();

        controller.suspend = target -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "denied"));
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                        .retryable())
                .isFalse();

        controller.suspend = target -> new SuspendResult(
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "left one"), Optional.of(controller.adopted(target)));
        controller.resume = suspension -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "still held");
        SuspensionCoordinator.Outcome incomplete = coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);
        assertThat(incomplete.change()).isEqualTo(Change.INCOMPLETE);
        assertThat(incomplete.retryable()).isFalse();

        // While an incomplete hold is left, a new suspend first tries to release it and goes no further.
        long suspendsBefore = controller.calls.stream()
                .filter(call -> call.startsWith("suspend"))
                .count();
        controller.suspend = target ->
                SuspendResult.transientFailure(ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread ended"));
        SuspensionCoordinator.Outcome withRemainder = coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);
        assertThat(withRemainder.change()).isEqualTo(Change.INCOMPLETE);
        assertThat(withRemainder.retryable())
                .as("an incomplete hold of the process is still there")
                .isFalse();
        assertThat(controller.calls.stream().filter(call -> call.startsWith("suspend")))
                .as("the process was not suspended again")
                .hasSize((int) suspendsBefore);
    }

    /** What a result may say of itself: transient and retryable only when nothing is held. */
    @Test
    void aTransientOrRetryableResultHoldsNothing() {
        ControlOutcome failure = ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread ended");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SuspendResult(failure, Optional.of(controller.adopted(DEMO)), false, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SuspendResult(failure, Optional.empty(), true, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SuspendResult(ControlOutcome.success(), Optional.empty(), false, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SuspensionCoordinator.Outcome(
                        failure, Change.INCOMPLETE, java.util.Set.of(), 1, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new SuspensionCoordinator.Outcome(failure, Change.FAILED, java.util.Set.of(), 1, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SuspensionCoordinator.Outcome(
                        failure, Change.FAILED, java.util.Set.of(SuspensionReason.USER), 0, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SuspensionCoordinator.Outcome(failure, Change.FAILED, java.util.Set.of(), 0, true, true)
                        .unrecorded()
                        .retryable())
                .as("a journal that failed is never a reason to try again")
                .isFalse();
    }

    /**
     * Milestone 9 review, item 3: a journal write that stalls (a disk that does not answer) must not
     * keep Prism from releasing the processes it holds. Whoever waits for the journal waits; a
     * release of another process does not.
     */
    @Test
    void aStalledJournalWriteDoesNotHoldUpTheReleaseOfAnotherProcess() throws Exception {
        ProcessIdentity other = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));
        coordinator.declare(other, "other", SuspensionReason.CPU_LIMITER, "limit");
        coordinator.suspend(other, "other", SuspensionReason.CPU_LIMITER);
        files.stall = new java.util.concurrent.CountDownLatch(1);
        Thread declaring =
                new Thread(() -> coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit"), "declaring");
        declaring.start();
        assertThat(files.stalled.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the write has begun and waits")
                .isTrue();
        try {
            java.util.concurrent.CompletableFuture<SuspensionCoordinator.Outcome> release =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> coordinator.release(other, SuspensionReason.CPU_LIMITER));

            SuspensionCoordinator.Outcome released = release.get(2, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(released.change()).isEqualTo(Change.RESUMED);
            assertThat(controller.suspensions)
                    .filteredOn(suspension -> suspension.identity().equals(other))
                    .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
        } finally {
            files.stall.countDown();
            declaring.join(5_000);
        }
    }

    /**
     * The user suspends a process the limiter holds, and the limiter releases it between the two
     * holds of the lock the suspension takes (milestone 9 review): the process is suspended anew, and
     * the user's entry is on disk before it is touched.
     */
    @Test
    void theUsersSuspensionIsOnDiskBeforeTheProcessIsTouchedEvenWhenTheLimiterLetGoMeanwhile() throws Exception {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                        .change())
                .isEqualTo(Change.SUSPENDED);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        files.beforeWrite = () -> {
            if (once.compareAndSet(false, true)) {
                // Between the two holds: the limiter's cycle lets the process run again.
                assertThat(coordinator
                                .release(DEMO, SuspensionReason.CPU_LIMITER)
                                .change())
                        .isEqualTo(Change.RESUMED);
            }
        };
        List<java.util.Set<SuspensionReason>> onDiskWhenTouched = new java.util.ArrayList<>();
        java.util.function.Function<ProcessIdentity, SuspendResult> suspend = controller.suspend;
        controller.suspend = target -> {
            try {
                journaled().stream()
                        .filter(entry -> entry.identity().equals(target))
                        .forEach(entry -> onDiskWhenTouched.add(java.util.Set.copyOf(entry.reasons())));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return suspend.apply(target);
        };

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        assertThat(once).as("the limiter let go between the holds").isTrue();
        assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
        assertThat(outcome.recorded()).isTrue();
        assertThat(onDiskWhenTouched)
                .as("the user's entry was on disk when the process was suspended")
                .anySatisfy(reasons -> assertThat(reasons).contains(SuspensionReason.USER));
    }

    /** Milestone 11, F33: an OOM Guard hold lasts minutes; its threads are recorded so that a crash leaves it resumable. */
    @Test
    void anOomGuardHoldCarriesItsThreadMemento() throws Exception {
        controller.suspend = target -> {
            FakeSuspension suspension = controller.adopted(target);
            suspension.memento = Optional.of("w1:12@3");
            return SuspendResult.suspended(ControlOutcome.success(), suspension);
        };
        coordinator.declare(DEMO, "demo", SuspensionReason.OOM_GUARD, "OOM Guard: emergency, 4.0 % available");

        assertThat(coordinator.suspend(DEMO, "demo", SuspensionReason.OOM_GUARD).change())
                .isEqualTo(Change.SUSPENDED);

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.reasons()).containsExactly(SuspensionReason.OOM_GUARD);
            assertThat(entry.memento()).contains("w1:12@3");
        });
    }

    @Test
    void theMomentaryHoldOfTheCpuLimiterStillWritesNoMemento() throws Exception {
        controller.suspend = target -> {
            FakeSuspension suspension = controller.adopted(target);
            suspension.memento = Optional.of("w1:12@3");
            return SuspendResult.suspended(ControlOutcome.success(), suspension);
        };
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "CPU limit 30 %");
        coordinator.suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER);

        assertThat(journaled())
                .singleElement()
                .satisfies(entry -> assertThat(entry.memento()).isEmpty());
    }

    /** Milestone 11: a CPU limit and OOM Guard on one process each keep their own cause. */
    @Test
    void twoAutomaticReasonsKeepOneCauseEachAndAWithdrawnReasonTakesItsCauseAlong() throws Exception {
        coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "CPU limit 30 %");
        coordinator.declare(DEMO, "demo", SuspensionReason.OOM_GUARD, "OOM Guard: memory limit 256 MiB exceeded");

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.reasons())
                    .containsExactlyInAnyOrder(SuspensionReason.CPU_LIMITER, SuspensionReason.OOM_GUARD);
            assertThat(entry.cause()).contains("CPU limit 30 %; OOM Guard: memory limit 256 MiB exceeded");
        });

        coordinator.withdraw(DEMO, SuspensionReason.CPU_LIMITER);

        assertThat(journaled()).singleElement().satisfies(entry -> {
            assertThat(entry.reasons()).containsExactly(SuspensionReason.OOM_GUARD);
            assertThat(entry.cause()).contains("OOM Guard: memory limit 256 MiB exceeded");
        });
    }
}
