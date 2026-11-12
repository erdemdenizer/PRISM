package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.LimitRun.End;
import prism.srm.application.LimitRun.Phase;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.SuspendResult;

/**
 * The emulated CPU limit on a virtual clock (LIM-8): what it holds the target to, when it suspends
 * nothing, and that every way it ends leaves the target running unless someone else holds it.
 */
class LimitRunTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-08T10:00:00Z"));
    private static final int PROCESSORS = 8;

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final VirtualScheduler scheduler = new VirtualScheduler();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeProcessSource source = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final List<String> events = new ArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private Optional<End> ended = Optional.empty();
    private Optional<String> endReason = Optional.empty();

    private final LimitRun.Listener listener = new LimitRun.Listener() {
        @Override
        public void changed(LimitRun run) {
            events.add("changed " + run.state().status());
        }

        @Override
        public void ended(LimitRun run, End end, Optional<String> reason) {
            events.add("ended " + end);
            ended = Optional.of(end);
            endReason = reason;
        }
    };

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
    }

    @AfterEach
    void closeJournal() {
        journal.close();
    }

    private LimitRun limit(double share) {
        assertThat(coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "CPU limit"))
                .isTrue();
        LimitRun run = new LimitRun(
                DEMO,
                "demo",
                share,
                coordinator,
                source,
                scheduler,
                scheduler.clock(),
                PROCESSORS,
                DutyCycleController.Tuning.DEFAULTS,
                listener);
        run.start();
        return run;
    }

    private SimulatedTarget target(double demand) {
        return new SimulatedTarget(scheduler, PROCESSORS, demand).wire(controller, source);
    }

    private long suspends() {
        return controller.calls.stream().filter("suspend 4242"::equals).count();
    }

    private long resumes() {
        return controller.calls.stream().filter("resume 4242"::equals).count();
    }

    /** Advances until the run is in the phase, at most a minute. */
    private void until(LimitRun run, Phase phase) {
        for (int i = 0; i < 6_000 && run.phase() != phase; i++) {
            scheduler.advance(Duration.ofMillis(10));
        }
        assertThat(run.phase()).isEqualTo(phase);
    }

    @Test
    void aSingleThreadOnEightProcessorsIsNeverSuspendedUnderAThirtyPercentLimit() {
        target(1.0 / PROCESSORS);
        LimitRun run = limit(0.30);

        scheduler.advance(Duration.ofSeconds(30));

        assertThat(suspends()).isZero();
        assertThat(run.runShare()).isEqualTo(1.0);
        assertThat(run.state().status()).isEqualTo(LimitStatus.ACTIVE_NOT_THROTTLING);
    }

    @Test
    void aSaturatedTargetIsHeldAtItsLimit() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(15));

        double use = target.useOver(Duration.ofSeconds(10));

        assertThat(use).isCloseTo(0.30, within(0.03));
        assertThat(run.state().status()).isEqualTo(LimitStatus.ACTIVE_THROTTLING);
        assertThat(coordinator.held())
                .as("the limiter's momentary holds are not shown as Suspended by Prism")
                .isEmpty();
    }

    @Test
    void aSlowSuspendLengthensTheCycleAndTheLimitStillHolds() {
        SimulatedTarget target = target(1.0);
        target.suspendTakes = Duration.ofMillis(110);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(20));

        double use = target.useOver(Duration.ofSeconds(12));

        assertThat(run.cycle()).isBetween(Duration.ofMillis(1_000), Duration.ofMillis(1_200));
        assertThat(use).isCloseTo(0.30, within(0.04));
    }

    @Test
    void aLimitBelowTheLowestShareIsReportedAboveTheTargetWithTheLowestReached() {
        target(1.0);
        LimitRun run = limit(0.01);

        scheduler.advance(Duration.ofSeconds(40));

        LimitRun.State state = run.state();
        assertThat(state.status()).isEqualTo(LimitStatus.ACTIVE_ABOVE_TARGET);
        assertThat(state.detail().orElseThrow()).startsWith("floor");
        assertThat(state.lowest().orElseThrow()).isCloseTo(0.05, within(0.01));
        assertThat(run.runShare()).isEqualTo(DutyCycleController.Tuning.DEFAULTS.minRunShare());
    }

    @Test
    void aLoadThatFallsBelowTheLimitIsLetRunInFull() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(15));
        target.demand = 0.10;

        scheduler.advance(Duration.ofSeconds(15));

        assertThat(run.runShare()).isEqualTo(1.0);
        long before = suspends();
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(suspends()).isEqualTo(before);
        assertThat(target.stopped()).isFalse();
    }

    @Test
    void cancellingWhileStoppedReleasesTheTarget() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);

        run.cancel();

        assertThat(target.stopped()).isFalse();
        assertThat(ended).contains(End.CANCELLED);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
        long calls = controller.calls.size();
        scheduler.advance(Duration.ofSeconds(10));
        assertThat(controller.calls).hasSize((int) calls);
    }

    @Test
    void cancellingWhileRunningTouchesNothing() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long calls = controller.calls.size();

        run.cancel();

        assertThat(controller.calls).hasSize((int) calls);
        assertThat(ended).contains(End.CANCELLED);
        assertThat(scheduler.pending()).isZero();
    }

    @Test
    void aProcessThatExitsWhileRunningEndsTheLimitAndNothingIsHeld() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        source.onCpuTime = identity -> CpuTimeReading.GONE;

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.EXITED);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void aProcessThatExitsWhileStoppedEndsTheLimitAndPrismLetsGoOfIt() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        controller.resume = suspension -> {
            suspension.outstanding = 0;
            return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "exited");
        };

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.EXITED);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void aSuspendThatIsRefusedEndsTheLimitWithItsReasonAndHoldsNothing() {
        target(1.0);
        controller.suspend = target -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "denied"));
        LimitRun run = limit(0.30);

        scheduler.advance(Duration.ofSeconds(5));

        assertThat(run.phase()).isEqualTo(Phase.ENDED);
        assertThat(ended).contains(End.FAILED);
        assertThat(endReason.orElseThrow()).contains("denied");
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void aSuspendOfAProcessThatIsGoneEndsTheLimitAsExited() {
        target(1.0);
        controller.suspend =
                target -> SuspendResult.failed(ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone"));
        limit(0.30);

        scheduler.advance(Duration.ofSeconds(5));

        assertThat(ended).contains(End.EXITED);
    }

    /** When the first refusing suspend was made, on the virtual clock; -1 until then. */
    private final java.util.concurrent.atomic.AtomicLong refusedAt = new java.util.concurrent.atomic.AtomicLong(-1);

    /** When the latest refusing suspend was made, and how many were made. */
    private final java.util.concurrent.atomic.AtomicLong lastRefusedAt = new java.util.concurrent.atomic.AtomicLong(-1);

    private final AtomicInteger refusals = new AtomicInteger();

    /** Suspends of the target are refused from now on, as a thread of an exiting process refuses one. */
    private void refuseSuspends(boolean transientFailure) {
        controller.suspend = identity -> {
            long now = scheduler.clock().getAsLong();
            refusedAt.compareAndSet(-1, now);
            lastRefusedAt.set(now);
            refusals.incrementAndGet();
            ControlOutcome refused = ControlOutcome.of(
                    OperationResult.ACCESS_DENIED, 5, "SuspendThread 5092 failed: 5 (Access is denied.)");
            return transientFailure ? SuspendResult.transientFailure(refused) : SuspendResult.failed(refused);
        };
    }

    /**
     * The target reads as gone only 50 ms after the given number of refusals: on the real system it
     * did so 5 to 16 ms after a refusal (12 of 60 provoked exits), so a look made at once would still
     * find it.
     */
    private void goneAfterRefusals(int count) {
        var read = source.onCpuTime;
        source.onCpuTime = identity -> refusals.get() >= count
                        && scheduler.clock().getAsLong() - lastRefusedAt.get()
                                >= Duration.ofMillis(50).toNanos()
                ? CpuTimeReading.GONE
                : read.apply(identity);
    }

    /** Advances until the first refusal, then a little further, in 10 ms steps. */
    private void untilJustAfterTheRefusal() {
        for (int i = 0; i < 6_000 && refusedAt.get() < 0; i++) {
            scheduler.advance(Duration.ofMillis(10));
        }
        assertThat(refusedAt.get()).as("a suspend was refused").isNotNegative();
        scheduler.advance(Duration.ofMillis(10));
    }

    /**
     * A thread of a process that is exiting may refuse a suspend before the system reports the
     * process as gone (seen on the hosted Windows runner: "SuspendThread failed: 5 (Access is
     * denied.)", 2026-10-10). Nothing is held, so the limit looks once more, a cycle later, and
     * ends as an exit, not as a failure the user is told about.
     */
    @Test
    void aSuspendRefusedAsTheProcessExitsEndsTheLimitAsAnExit() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long before = suspends();
        refuseSuspends(false);
        goneAfterRefusals(1);

        untilJustAfterTheRefusal();
        assertThat(ended).as("not decided at the refusal").isEmpty();
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
        scheduler.advance(run.cycle());

        assertThat(ended).contains(End.EXITED);
        assertThat(suspends() - before).as("not tried again").isEqualTo(1);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    /** The same refusal of a process that goes on running stays a failure, with its reason, after that one look. */
    @Test
    void aRefusedSuspendOfAProcessThatRunsOnStillEndsTheLimitAsAFailure() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long before = suspends();
        refuseSuspends(false);

        untilJustAfterTheRefusal();
        assertThat(ended).as("not decided at the refusal").isEmpty();
        scheduler.advance(Duration.ofMillis(2_500));

        assertThat(ended).contains(End.FAILED);
        assertThat(endReason.orElseThrow()).contains("SuspendThread 5092 failed");
        assertThat(suspends() - before).as("not tried again").isEqualTo(1);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    /** The last of the transient failures in a row is confirmed the same way: an exiting target ends as an exit. */
    @Test
    void transientRefusalsOfAnExitingProcessEndTheLimitAsAnExit() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long before = suspends();
        refuseSuspends(true);
        // Gone only after the retries have run out.
        goneAfterRefusals(LimitRun.FAILED_SUSPENDS_TO_END);

        scheduler.advance(Duration.ofMillis(3_500));

        assertThat(ended).contains(End.EXITED);
        assertThat(suspends() - before).as("three attempts").isEqualTo(LimitRun.FAILED_SUSPENDS_TO_END);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    /** A journal that fails while the refusal is being confirmed ends the limit for the journal, not as a failure. */
    @Test
    void aJournalThatFailsWhileARefusalIsConfirmedEndsTheLimitForTheJournal() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        refuseSuspends(false);
        untilJustAfterTheRefusal();

        files.locksLost = true;
        coordinator.verifyJournal();
        scheduler.advance(Duration.ofMillis(2_500));

        assertThat(ended).contains(End.JOURNAL);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    /** Removing the limit while a refusal is being confirmed ends it as removed, with no further step. */
    @Test
    void cancellingWhileARefusalIsConfirmedEndsTheLimitAsCancelled() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long before = suspends();
        refuseSuspends(false);
        untilJustAfterTheRefusal();

        run.cancel();
        scheduler.advance(Duration.ofMillis(2_500));

        assertThat(ended).contains(End.CANCELLED);
        assertThat(suspends() - before).isEqualTo(1);
        assertThat(scheduler.pending()).isZero();
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void theUsersSuspensionDuringAStopKeepsTheProcessStoppedAndTheirResumeContinuesItAtOnce() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        assertThat(coordinator.suspend(DEMO, "demo", SuspensionReason.USER).change())
                .isEqualTo(SuspensionCoordinator.Change.ALREADY_HELD);

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(run.phase()).isEqualTo(Phase.HELD_BY_OTHER);
        assertThat(run.state().status()).isEqualTo(LimitStatus.HELD_BY_OTHER);
        assertThat(target.stopped()).isTrue();
        assertThat(coordinator.heldFor(DEMO)).containsExactly(SuspensionReason.USER);
        assertThat(coordinator.held()).containsExactly(DEMO);

        assertThat(coordinator.release(DEMO, SuspensionReason.USER).change())
                .as("the limiter holds no reason of its own on the user's suspension")
                .isEqualTo(SuspensionCoordinator.Change.RESUMED);
        assertThat(target.stopped()).isFalse();

        scheduler.advance(Duration.ofSeconds(3));
        assertThat(run.phase()).isIn(Phase.RUNNING, Phase.STOPPED);
        assertThat(target.useOver(Duration.ofSeconds(10))).isCloseTo(0.30, within(0.05));
    }

    @Test
    void aProcessTheUserHoldsIsNotStackedOnAndWaitsForTheUser() {
        target(1.0);
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);
        LimitRun run = limit(0.30);

        scheduler.advance(Duration.ofSeconds(10));

        assertThat(run.phase()).isEqualTo(Phase.HELD_BY_OTHER);
        assertThat(coordinator.heldFor(DEMO)).containsExactly(SuspensionReason.USER);
        assertThat(suspends()).as("only the user's").isEqualTo(1);
    }

    @Test
    void aProcessStoppedByAnotherProgramIsNeitherHeldNorContinued() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        var suspendForReal = controller.suspend;
        controller.suspend = target2 ->
                SuspendResult.stoppedElsewhere(ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "already stopped"));
        long resumes = resumes();

        scheduler.advance(Duration.ofSeconds(10));

        assertThat(run.phase()).isEqualTo(Phase.PAUSED);
        assertThat(run.state().status()).isEqualTo(LimitStatus.PAUSED_EXTERNAL_STOP);
        assertThat(resumes()).as("no continue is sent").isEqualTo(resumes);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();

        controller.suspend = suspendForReal;
        scheduler.advance(Duration.ofSeconds(15));
        assertThat(run.phase()).isIn(Phase.RUNNING, Phase.STOPPED);
        assertThat(target.useOver(Duration.ofSeconds(10))).isCloseTo(0.30, within(0.05));
    }

    @Test
    void aJournalThatFailsWhileStoppedReleasesTheTargetAndSuspendsNothingMore() throws Exception {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        byte[] evidence = Files.readAllBytes(journal.file());
        files.diskFull = true;
        assertThat(coordinator.declare(new ProcessIdentity(7, Instant.EPOCH), "x", SuspensionReason.CPU_LIMITER, "l"))
                .isFalse();
        long suspends = suspends();

        scheduler.advance(Duration.ofSeconds(10));

        assertThat(ended).contains(End.JOURNAL);
        assertThat(target.stopped()).isFalse();
        assertThat(suspends()).isEqualTo(suspends);
        assertThat(Files.readAllBytes(journal.file()))
                .as("the entry stays as evidence")
                .isEqualTo(evidence);
    }

    @Test
    void aJournalThatFailsWhileRunningSuspendsNothing() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long suspends = suspends();
        files.diskFull = true;
        coordinator.declare(new ProcessIdentity(7, Instant.EPOCH), "x", SuspensionReason.CPU_LIMITER, "l");

        scheduler.advance(Duration.ofSeconds(10));

        assertThat(ended).contains(End.JOURNAL);
        assertThat(suspends()).isEqualTo(suspends);
    }

    @Test
    void anExceptionInAStepWhileStoppedStillReleasesTheTarget() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        var resumeForReal = controller.resume;
        AtomicInteger thrown = new AtomicInteger();
        controller.resume = suspension -> {
            if (thrown.getAndIncrement() == 0) {
                throw new IllegalStateException("broken");
            }
            return resumeForReal.apply(suspension);
        };

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.FAILED);
        assertThat(target.stopped()).isFalse();
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void anErrorInAStepStillReleasesTheTargetAndIsNotSwallowed() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        var resumeForReal = controller.resume;
        AtomicInteger thrown = new AtomicInteger();
        controller.resume = suspension -> {
            if (thrown.getAndIncrement() == 0) {
                throw new AssertionError("an Error");
            }
            return resumeForReal.apply(suspension);
        };

        assertThatThrownBy(() -> scheduler.advance(Duration.ofSeconds(3))).isInstanceOf(AssertionError.class);

        assertThat(ended).contains(End.FAILED);
        assertThat(target.stopped()).isFalse();
    }

    @Test
    void atShutdownTheRunReleasesNothingAndTheShutdownReleasesTheLimit() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        coordinator.refuseFurtherSuspensions();

        run.stopForShutdown();
        scheduler.advance(Duration.ofSeconds(3));

        assertThat(target.stopped()).as("left to the shutdown sequence").isTrue();
        assertThat(scheduler.pending()).isZero();
        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));
        assertThat(target.stopped()).isFalse();
    }

    @Test
    void aSuspendRefusedAtShutdownEndsTheLimitAndHoldsNothing() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        coordinator.refuseFurtherSuspensions();

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.SHUTDOWN);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void aStepThatCannotBeScheduledAfterTheLimiterStoppedEndsTheLimit() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        scheduler.shutDown();

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(run.phase()).isEqualTo(Phase.ENDED);
        assertThat(ended).contains(End.SHUTDOWN);
    }

    @Test
    void aTimeThatCannotBeReadLetsTheTargetRunInFullAndThenEndsTheLimit() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(10));
        assertThat(run.runShare()).isLessThan(1.0);
        source.onCpuTime = identity -> new CpuTimeReading.Unreadable("denied");

        scheduler.advance(Duration.ofSeconds(5));
        assertThat(run.runShare()).as("after three intervals").isEqualTo(1.0);
        assertThat(run.state().detail().orElseThrow()).startsWith("not measured");
        assertThat(target.stopped()).isFalse();

        scheduler.advance(Duration.ofSeconds(10));
        assertThat(ended).contains(End.FAILED);
        assertThat(endReason.orElseThrow()).contains("denied");
    }

    @Test
    void aStopPhaseDeliveredLateIsReleasedAtOnceAndItsIntervalIsNotMeasured() {
        SimulatedTarget target = target(1.0);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(15));
        until(run, Phase.STOPPED);
        double share = run.runShare();
        // The limiter thread was held up for five seconds while the target was stopped.
        scheduler.elapse(Duration.ofSeconds(5));

        scheduler.advance(Duration.ofMillis(1));

        assertThat(target.stopped()).isFalse();
        scheduler.advance(Duration.ofSeconds(2));
        assertThat(run.runShare())
                .as("five seconds stopped are not taken as a low demand")
                .isLessThanOrEqualTo(share * 1.5);
    }

    @Test
    void aJournalThatFailsBetweenTheCheckOfAStepAndItsSuspendEndsTheLimitForTheJournal() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        var read = source.onCpuTime;
        // The measurement comes after the check of the step and before its suspend.
        source.onCpuTime = identity -> {
            files.locksLost = true;
            coordinator.verifyJournal();
            return read.apply(identity);
        };

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.JOURNAL);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    @Test
    void aCounterThatGoesBackSaysNothingAboutItsIntervalAndChangesNoShare() {
        target(1.0);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(15));
        double share = run.runShare();
        var read = source.onCpuTime;
        // The counter is reset to a small value and counts on from there (MON-10).
        long[] offset = {-1};
        source.onCpuTime = identity -> {
            long real = ((CpuTimeReading.Read) read.apply(identity)).cpuMillis();
            if (offset[0] < 0) {
                offset[0] = real - 10;
            }
            return new CpuTimeReading.Read(real - offset[0]);
        };

        scheduler.advance(Duration.ofSeconds(4));

        assertThat(run.runShare())
                .as("no burst of demand or of quiet is read into it")
                .isCloseTo(share, within(0.05));
        assertThat(run.state().status()).isEqualTo(LimitStatus.ACTIVE_THROTTLING);
    }

    @Test
    void anIntervalTheLimiterWasHeldUpInIsNotMeasured() {
        target(1.0);
        LimitRun run = limit(0.30);
        scheduler.advance(Duration.ofSeconds(15));
        until(run, Phase.RUNNING);
        var measured = run.state().measured();
        double share = run.runShare();
        // The target ran unthrottled for five seconds while the limiter thread was held up.
        scheduler.elapse(Duration.ofSeconds(5));

        scheduler.advance(Duration.ofMillis(1));

        assertThat(run.state().measured()).as("not taken as a measurement").isEqualTo(measured);
        assertThat(run.runShare()).isEqualTo(share);
    }

    @Test
    void anIncompleteSuspendEndsTheLimitAndTheCoordinatorKeepsWhatIsLeft() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        controller.suspend = target -> new SuspendResult(
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "rollback left one"),
                Optional.of(controller.adopted(target)));
        controller.resume = suspension -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "still held");

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.FAILED);
        assertThat(coordinator.held())
                .as("shown, so the user can see and resume it")
                .containsExactly(DEMO);
    }

    @Test
    void anIncompleteReleaseEndsTheLimitAndTheCoordinatorKeepsWhatIsLeft() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        controller.resume = suspension -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "still held");

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(ended).contains(End.FAILED);
        assertThat(coordinator.held()).containsExactly(DEMO);
    }

    @Test
    void cancellingWhileStoppedElsewhereSendsNoContinue() {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        controller.suspend = target ->
                SuspendResult.stoppedElsewhere(ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "already stopped"));
        until(run, Phase.PAUSED);
        long resumes = resumes();

        run.cancel();

        assertThat(resumes()).isEqualTo(resumes);
        assertThat(ended).contains(End.CANCELLED);
    }

    @Test
    void cancellingWhileTheUserHoldsTheProcessLeavesTheUsersSuspension() {
        SimulatedTarget target = target(1.0);
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);
        LimitRun run = limit(0.30);
        until(run, Phase.HELD_BY_OTHER);

        run.cancel();

        assertThat(coordinator.heldFor(DEMO)).containsExactly(SuspensionReason.USER);
        assertThat(target.stopped()).isTrue();
    }

    @Test
    void oneTransientSuspendFailureIsTriedAgainAndOnlyThreeInARowEndTheLimit() {
        SimulatedTarget target = target(1.0);
        var suspendForReal = controller.suspend;
        AtomicInteger failures = new AtomicInteger();
        controller.suspend = identity -> failures.getAndIncrement() == 0
                ? SuspendResult.transientFailure(
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread ended as it was suspended"))
                : suspendForReal.apply(identity);
        LimitRun run = limit(0.30);

        scheduler.advance(Duration.ofSeconds(15));

        assertThat(ended).isEmpty();
        assertThat(target.useOver(Duration.ofSeconds(10))).isCloseTo(0.30, within(0.03));
        long before = suspends();
        controller.suspend = identity ->
                SuspendResult.transientFailure(ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread ended"));
        scheduler.advance(Duration.ofSeconds(10));
        assertThat(ended).contains(End.FAILED);
        assertThat(run.phase()).isEqualTo(Phase.ENDED);
        assertThat(suspends() - before).as("exactly three attempts").isEqualTo(3);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
    }

    /** How each failure of a suspend ends, or does not end, a limit (milestone 9 review). */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"refused", "gone", "stopped elsewhere", "incomplete", "journal", "transient"})
    void everyKindOfFailedSuspendHasItsOwnEnd(String kind) {
        target(1.0);
        LimitRun run = limit(0.30);
        until(run, Phase.STOPPED);
        until(run, Phase.RUNNING);
        long before = suspends();
        java.util.concurrent.atomic.AtomicLong failedAt = new java.util.concurrent.atomic.AtomicLong(-1);
        switch (kind) {
            case "refused" ->
                controller.suspend =
                        identity -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "denied"));
            case "gone" ->
                controller.suspend =
                        identity -> SuspendResult.failed(ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone"));
            case "stopped elsewhere" ->
                controller.suspend = identity ->
                        SuspendResult.stoppedElsewhere(ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "stopped"));
            case "incomplete" -> {
                controller.suspend = identity -> new SuspendResult(
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "left one"),
                        Optional.of(controller.adopted(identity)));
                controller.resume = suspension -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "still held");
            }
            case "journal" -> {
                var read = source.onCpuTime;
                source.onCpuTime = identity -> {
                    files.locksLost = true;
                    coordinator.verifyJournal();
                    failedAt.set(suspends());
                    return read.apply(identity);
                };
            }
            case "transient" ->
                controller.suspend = identity -> SuspendResult.transientFailure(
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread ended"));
            default -> throw new IllegalArgumentException(kind);
        }

        scheduler.advance(Duration.ofMillis(2_500));

        switch (kind) {
            case "refused" -> {
                assertThat(ended).contains(End.FAILED);
                assertThat(suspends() - before).as("not retried").isEqualTo(1);
            }
            case "gone" -> assertThat(ended).contains(End.EXITED);
            case "stopped elsewhere" -> {
                assertThat(ended).isEmpty();
                assertThat(run.phase()).isEqualTo(Phase.PAUSED);
            }
            case "incomplete" -> {
                assertThat(ended).contains(End.FAILED);
                assertThat(suspends() - before).as("not retried").isEqualTo(1);
                assertThat(coordinator.held())
                        .as("what is left stays held and shown")
                        .containsExactly(DEMO);
            }
            case "journal" -> {
                assertThat(ended).contains(End.JOURNAL);
                assertThat(suspends()).as("nothing suspended after the failure").isEqualTo(failedAt.get());
                assertThat(coordinator.heldFor(DEMO)).isEmpty();
            }
            case "transient" -> {
                assertThat(ended).contains(End.FAILED);
                assertThat(suspends() - before)
                        .as("tried again, three times in a row at most")
                        .isEqualTo(LimitRun.FAILED_SUSPENDS_TO_END);
                assertThat(coordinator.heldFor(DEMO)).isEmpty();
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }
}
