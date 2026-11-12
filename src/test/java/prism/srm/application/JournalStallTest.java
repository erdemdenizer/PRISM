package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.SuspensionCoordinator.Change;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * A disk that stops answering while Prism holds processes (milestone 9 review, item 3), with the
 * journal written on a thread of its own as in the running application: whatever must wait for the
 * journal waits within {@link SuspensionCoordinator#JOURNAL_WAIT} and fails it; nothing that
 * releases a process waits at all.
 */
class JournalStallTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-08T10:00:00Z"));
    private static final ProcessIdentity OTHER = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));
    /** What a release may take while the disk is stalled: far less than the journal's bound. */
    private static final long RELEASE_BOUND_MILLIS = 500;

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(50, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final ExecutorService journalThread = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "test-journal");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * The test's own threads for what runs beside the test thread: never the common pool, whose one
     * worker on a machine of two processors runs a second task only after the first has returned
     * (JDK 25), so a release queued behind a stalled declaration would wait without being blocked.
     */
    private final ExecutorService declaringThread = ownThread("test-declaring");

    private final ExecutorService releasingThread = ownThread("test-releasing");
    private final List<String> journalFailures = new CopyOnWriteArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal), journalThread);
        coordinator.setJournalListener(journalFailures::add);
        assertThat(coordinator.declare(OTHER, "other", SuspensionReason.CPU_LIMITER, "limit"))
                .isTrue();
        assertThat(coordinator
                        .suspend(OTHER, "other", SuspensionReason.CPU_LIMITER)
                        .change())
                .isEqualTo(Change.SUSPENDED);
    }

    private static ExecutorService ownThread(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Runs whether the test passed or not: the gate opens first, so no thread stays caught in the stall. */
    @AfterEach
    void tearDown() throws InterruptedException {
        CountDownLatch gate = files.stall;
        if (gate != null) {
            gate.countDown();
        }
        for (ExecutorService own : List.of(declaringThread, releasingThread, journalThread)) {
            own.shutdownNow();
        }
        boolean ended = true;
        for (ExecutorService own : List.of(declaringThread, releasingThread, journalThread)) {
            ended &= own.awaitTermination(5, TimeUnit.SECONDS);
        }
        journal.close();
        assertThat(ended).as("the test's threads have ended").isTrue();
    }

    private void stallTheDisk() throws InterruptedException {
        files.stall = new CountDownLatch(1);
        // Something to write, so the journal's thread is caught in the stalled write.
        assertThat(coordinator.declare(new ProcessIdentity(7, Instant.EPOCH), "x", SuspensionReason.CPU_LIMITER, "l"))
                .as("not durable")
                .isFalse();
        assertThat(files.stalled.tryAcquire(5, TimeUnit.SECONDS))
                .as("the write waits at the stall")
                .isTrue();
    }

    @Test
    void aReleaseDoesNotWaitForAStalledDiskEvenWhenItChangesTheJournal() throws Exception {
        files.stall = new CountDownLatch(1);
        Future<Boolean> declaring =
                declaringThread.submit(() -> coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit"));
        // The declaration's write has begun on the journal's thread and waits at the gate.
        assertThat(files.stalled.tryAcquire(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        // On a thread of its own, free the moment it is asked: a release that waited for the disk
        // runs out of the bound here (TimeoutException) instead of hanging the build.
        SuspensionCoordinator.Outcome released = releasingThread
                .submit(() -> coordinator.release(OTHER, SuspensionReason.CPU_LIMITER))
                .get(RELEASE_BOUND_MILLIS, TimeUnit.MILLISECONDS);
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(released.change()).isEqualTo(Change.RESUMED);
        assertThat(took).as("released while the disk stalls").isLessThan(RELEASE_BOUND_MILLIS);
        assertThat(controller.suspensions)
                .filteredOn(suspension -> suspension.identity().equals(OTHER))
                .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
        assertThat(declaring.get(10, TimeUnit.SECONDS)).as("never durable").isFalse();
    }

    @Test
    void aDeclarationOnAStalledDiskFailsWithinTheBoundAndStopsEveryAutomaticSuspension() throws Exception {
        files.stall = new CountDownLatch(1);

        long start = System.nanoTime();
        boolean declared = coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(declared).isFalse();
        assertThat(took)
                .as("within the bound of the journal")
                .isBetween(
                        SuspensionCoordinator.JOURNAL_WAIT.toMillis() - 100,
                        SuspensionCoordinator.JOURNAL_WAIT.toMillis() + 1_000);
        assertThat(coordinator.journalHealthy()).isFalse();
        assertThat(coordinator.journalProblem().orElseThrow()).contains("did not answer");
        assertThat(journalFailures).hasSize(1);
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.CPU_LIMITER)
                        .change())
                .as("nothing suspends for a reason the journal may not hold")
                .isEqualTo(Change.FAILED);
        assertThat(coordinator
                        .suspend(OTHER, "other", SuspensionReason.CPU_LIMITER)
                        .change())
                .as("nor for one it held before it stalled")
                .isEqualTo(Change.FAILED);
    }

    /**
     * The check the limiter makes once a second, while a write nobody waits for has stalled: it fails
     * within its bound and fails the journal, so the limiter's thread is never held longer.
     */
    @Test
    void aCheckWhileAWriteNobodyWaitsForStallsFailsWithinTheBound() throws Exception {
        assertThat(coordinator.suspend(DEMO, "demo", SuspensionReason.USER).recorded())
                .isTrue();
        files.stall = new CountDownLatch(1);
        // Releasing the user's suspension changes the journal, and waits for nothing.
        assertThat(coordinator.release(DEMO, SuspensionReason.USER).change()).isEqualTo(Change.RESUMED);
        assertThat(files.stalled.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.journalHealthy()).as("not yet known").isTrue();

        long start = System.nanoTime();
        boolean healthy = coordinator.verifyJournal();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(healthy).isFalse();
        assertThat(took)
                .isBetween(
                        SuspensionCoordinator.JOURNAL_WAIT.toMillis() - 100,
                        SuspensionCoordinator.JOURNAL_WAIT.toMillis() + 1_000);
        assertThat(coordinator.journalProblem().orElseThrow()).contains("did not finish");
        assertThat(journalFailures).hasSize(1);
    }

    @Test
    void aCheckOfAStalledJournalFailsWithinTheBound() throws Exception {
        stallTheDisk();

        long start = System.nanoTime();
        boolean healthy = coordinator.verifyJournal();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(healthy).isFalse();
        assertThat(took).isLessThan(SuspensionCoordinator.JOURNAL_WAIT.toMillis() + 1_000);
        assertThat(coordinator.retryJournal())
                .as("not tried again in this session")
                .isFalse();
    }

    @Test
    void theUsersSuspensionOnAStalledDiskGoesAheadAndSaysItWasNotRecorded() throws Exception {
        stallTheDisk();

        SuspensionCoordinator.Outcome outcome = coordinator.suspend(DEMO, "demo", SuspensionReason.USER);

        assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
        assertThat(outcome.recorded()).isFalse();
        long start = System.nanoTime();
        assertThat(coordinator.release(DEMO, SuspensionReason.USER).change()).isEqualTo(Change.RESUMED);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(RELEASE_BOUND_MILLIS);
    }

    @Test
    void atShutdownTheAutomaticReasonsAreReleasedWhileTheDiskStalls() throws Exception {
        stallTheDisk();
        coordinator.refuseFurtherSuspensions();

        long start = System.nanoTime();
        assertThat(coordinator.releaseAutomaticReasons(Duration.ofSeconds(1))).isTrue();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(took).isLessThan(RELEASE_BOUND_MILLIS);
        assertThat(controller.suspensions)
                .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
        long closing = System.nanoTime();
        coordinator.closeJournal(Duration.ofMillis(300));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closing))
                .as("the close gives up within its bound")
                .isLessThan(1_500);
    }

    /**
     * Once the journal has stalled, nothing waits for it again: a user's suspension queued behind
     * another must not hold up the release after it by a whole bound each (milestone 9 review).
     */
    @Test
    void afterAStallNoWaitRunsItsBoundOutAgain() throws Exception {
        files.stall = new CountDownLatch(1);
        assertThat(coordinator.declare(DEMO, "demo", SuspensionReason.CPU_LIMITER, "limit"))
                .isFalse();
        assertThat(coordinator.journalProblem().orElseThrow()).contains("did not answer");

        long start = System.nanoTime();
        for (int pid = 100; pid < 103; pid++) {
            SuspensionCoordinator.Outcome outcome =
                    coordinator.suspend(new ProcessIdentity(pid, Instant.EPOCH), "user", SuspensionReason.USER);
            assertThat(outcome.change()).isEqualTo(Change.SUSPENDED);
            assertThat(outcome.recorded()).isFalse();
        }
        assertThat(coordinator.verifyJournal()).isFalse();
        assertThat(coordinator.declare(OTHER, "other", SuspensionReason.CPU_LIMITER, "limit"))
                .isFalse();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(took)
                .as("three suspensions, a check and a declaration after the stall")
                .isLessThan(RELEASE_BOUND_MILLIS);
        assertThat(journalFailures).as("told once").hasSize(1);
    }
}
