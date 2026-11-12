package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.ActivityLog;
import prism.srm.application.FakeProcessController;
import prism.srm.application.FakeProcessSource;
import prism.srm.application.FakeSystemMetricsSource;
import prism.srm.application.JournalEntry;
import prism.srm.application.JournalFolder;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryCheck;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.StopState;

/**
 * The runtime of milestone 9 with its real executors against fake sources and a real journal
 * folder (ADR-0011): the limits stop before anything is released at shutdown, the journal of the
 * session goes with a controlled shutdown, the journal of an earlier session is listed at start-up
 * and resumed by nobody, and the shutdown hook releases the limiter's holds and nothing else.
 */
class MonitoringRuntimeLimitsTest {

    private static final long PATIENCE_SECONDS = 15;
    private static final ProcessIdentity DEMO = FakeProcessSource.identity(4_242, 10);
    private static final ProcessIdentity OTHER = FakeProcessSource.identity(5_555, 20);

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeSystemMetricsSource system = new FakeSystemMetricsSource();
    private final FakeProcessSource processes = new FakeProcessSource();
    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(500, () -> Instant.EPOCH);
    private final List<LimitResult> limitResults = new CopyOnWriteArrayList<>();
    private final List<ProcessControlResult> controlResults = new CopyOnWriteArrayList<>();
    private final List<List<RecoveredSuspension>> recovered = new CopyOnWriteArrayList<>();
    private final List<RecoveryCheck> checks = new CopyOnWriteArrayList<>();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private MonitoringRuntime runtime;

    MonitoringRuntimeLimitsTest() {
        // A busy target: its processor time grows with the wall clock on every logical processor.
        long started = System.nanoTime();
        processes.onCpuTime = identity -> new CpuTimeReading.Read((System.nanoTime() - started) / 1_000_000 * 8);
        processes.list(
                List.of(FakeProcessSource.reading(DEMO, 100, 0, 0), FakeProcessSource.reading(OTHER, 100, 0, 0)));
    }

    @AfterEach
    void shutDown() throws InterruptedException {
        if (runtime != null) {
            runtime.shutdown();
            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(uncaught).isEmpty();
    }

    private MonitoringRuntime start() {
        return start(MonitoringRuntime.RECOVERY_RETRIES);
    }

    private MonitoringRuntime start(List<Duration> recoveryRetries) {
        runtime = new MonitoringRuntime(
                () -> new PlatformSources(system, processes, controller, 1, Optional.of(journalFolder.path()), "9-9"),
                System::nanoTime,
                activity,
                new MonitoringRuntime.Listeners(
                        snapshot -> {},
                        listing -> {},
                        details -> {},
                        controlResults::add,
                        profile -> {},
                        held -> {},
                        failures -> {},
                        limits -> {},
                        limitResults::add,
                        recovered::add,
                        checks::add,
                        result -> {}),
                Duration.ofSeconds(5),
                (thread, failure) -> uncaught.add(failure),
                recoveryRetries);
        runtime.start();
        return runtime;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("reached in time").isLessThan(end);
            Thread.sleep(20);
        }
    }

    /** Asks until the limit service exists, which it does once process monitoring runs. */
    private void limit(ProcessIdentity identity, int percent) throws InterruptedException {
        await(() -> {
            int before = limitResults.size();
            runtime.requestLimit(LimitRequest.of(LimitAction.MANAGE, identity, "demo"));
            try {
                await(() -> limitResults.size() > before);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return limitResults.get(before).outcome().succeeded();
        });
        int before = limitResults.size();
        runtime.requestLimit(LimitRequest.cpuLimit(identity, "demo", percent));
        await(() -> limitResults.size() > before);
        assertThat(limitResults.get(before).outcome().succeeded())
                .as(limitResults.get(before).text())
                .isTrue();
    }

    private List<Path> journals() throws Exception {
        try (Stream<Path> listed = Files.list(journalFolder.path())) {
            return listed.filter(path -> path.toString().endsWith(".journal")).toList();
        }
    }

    @Test
    void aControlledShutdownStopsTheLimitsReleasesTheTargetsAndRemovesTheJournalOfTheSession() throws Exception {
        start();
        limit(DEMO, 30);
        await(() -> controller.calls.contains("suspend 4242"));
        assertThat(journals()).hasSize(1);

        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        int calls = controller.calls.size();
        Thread.sleep(1_500);

        assertThat(controller.suspensions)
                .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
        assertThat(controller.calls).as("nothing suspends after the shutdown").hasSize(calls);
        assertThat(journals()).as("an empty journal goes").isEmpty();
        assertThat(runtime.executorsTerminated()).isTrue();
    }

    /**
     * The journal of an earlier session whose lock is still held when this session starts (Windows
     * lets the locks of a killed process go "depending upon available system resources"): it is not
     * read then, and once the lock is free it is listed in the same session, without a restart.
     */
    @Test
    void anEarlierJournalWhoseLockIsFreedAfterStartUpIsListedInTheSameSession() throws Exception {
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
        Path dead = journalFolder.path().resolve("suspensions-1-1.journal");
        Instant written = Instant.parse("2026-10-08T09:00:00Z");
        // What an earlier session left: a journal that names a suspension.
        try (SuspensionJournal earlier =
                SuspensionJournal.open(journalFolder.path(), "1-1", SuspensionJournal.onDisk())) {
            assertThat(earlier.write(List.of(new JournalEntry(
                            DEMO,
                            "demo",
                            EnumSet.of(SuspensionReason.USER),
                            Optional.empty(),
                            Optional.empty(),
                            "1-1",
                            written,
                            written,
                            false,
                            false))))
                    .isTrue();
        }
        assertThat(dead).exists();
        // Its lock, still held when this session starts: as the system may hold a killed process's lock.
        java.nio.channels.FileChannel lockChannel = java.nio.channels.FileChannel.open(
                journalFolder.path().resolve("suspensions-1-1.lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock stillHeld = lockChannel.lock();
        try {
            start();
            // Started, and the journal of the earlier session was not read: its lock is held.
            await(() -> activity.entries().stream()
                    .anyMatch(entry -> entry.message().startsWith("Monitoring started")));
            // The page is told the list may be incomplete, though nothing is listed.
            await(() -> checks.contains(RecoveryCheck.PENDING));
            assertThat(recovered).allSatisfy(list -> assertThat(list).isEmpty());
            assertThat(dead).exists();
        } finally {
            stillHeld.release();
            lockChannel.close();
        }

        await(() -> !recovered.isEmpty() && !recovered.get(recovered.size() - 1).isEmpty());

        assertThat(recovered.get(recovered.size() - 1))
                .singleElement()
                .satisfies(entry -> assertThat(entry.identity()).isEqualTo(DEMO));
        assertThat(controller.calls).doesNotContain("resume 4242", "adopt 4242", "suspend 4242");
        await(() -> !Files.exists(dead));
        // The user was told the list may be incomplete, and then that it is complete.
        assertThat(activity.entries()).anyMatch(entry -> entry.message().contains("cannot be read yet"));
        await(() ->
                activity.entries().stream().anyMatch(entry -> entry.message().contains("have all been read now")));
        await(() -> checks.contains(RecoveryCheck.COMPLETE));
        assertThat(checks).as("pending, then whole").containsExactly(RecoveryCheck.PENDING, RecoveryCheck.COMPLETE);
    }

    private Path earlierJournal(String instance) throws Exception {
        Instant written = Instant.parse("2026-10-08T09:00:00Z");
        try (SuspensionJournal earlier =
                SuspensionJournal.open(journalFolder.path(), instance, SuspensionJournal.onDisk())) {
            assertThat(earlier.write(List.of(new JournalEntry(
                            DEMO,
                            "demo",
                            EnumSet.of(SuspensionReason.USER),
                            Optional.empty(),
                            Optional.empty(),
                            instance,
                            written,
                            written,
                            false,
                            false))))
                    .isTrue();
        }
        return journalFolder.path().resolve("suspensions-" + instance + ".journal");
    }

    /**
     * The journal of a session that still runs (here: an instance name of this very process, its lock
     * held) is neither read nor waited for: no warning, no later look, its file as it was.
     */
    @Test
    void theJournalOfARunningSessionIsNeitherReadNorWaitedFor() throws Exception {
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
        ProcessHandle self = ProcessHandle.current();
        String running = SuspensionJournal.instanceName(self.pid(), self.info().startInstant());
        Path journal = earlierJournal(running);
        byte[] before = Files.readAllBytes(journal);
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                journalFolder.path().resolve("suspensions-" + running + ".lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock held = channel.lock();
        try {
            start();
            await(() -> activity.entries().stream()
                    .anyMatch(entry -> entry.message().startsWith("Monitoring started")));
            Thread.sleep(MonitoringRuntime.RECOVERY_RETRIES.get(0).toMillis() + 1_000);

            assertThat(recovered).allSatisfy(list -> assertThat(list).isEmpty());
            assertThat(activity.entries()).noneMatch(entry -> entry.message().contains("cannot be read yet"));
            assertThat(checks).as("the list is whole from the start").isEmpty();
            assertThat(Files.readAllBytes(journal)).isEqualTo(before);
        } finally {
            held.release();
            channel.close();
        }
    }

    /**
     * A shutdown while a journal of an earlier session still waits for its lock ends within its bound:
     * the later look is called off, the journal stays for the next start, and nothing is resumed.
     */
    @Test
    void aShutdownWhileAnEarlierJournalWaitsEndsInTimeAndKeepsTheJournal() throws Exception {
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
        Path dead = earlierJournal("1-1");
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                journalFolder.path().resolve("suspensions-1-1.lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock held = channel.lock();
        try {
            start();
            await(() -> activity.entries().stream()
                    .anyMatch(entry -> entry.message().contains("cannot be read yet")));

            long begun = System.nanoTime();
            runtime.shutdown();
            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)).isLessThan(5_000);
            assertThat(runtime.executorsTerminated()).isTrue();
            assertThat(dead).as("kept for the next start").exists();
            assertThat(recovered).allSatisfy(list -> assertThat(list).isEmpty());
            assertThat(checks).as("never said to be whole").containsExactly(RecoveryCheck.PENDING);
            assertThat(controller.calls).doesNotContain("resume 4242", "adopt 4242", "suspend 4242");
            runtime = null;
        } finally {
            held.release();
            channel.close();
        }
    }

    /**
     * A journal still locked when Prism stops looking: the page is told the list may stay
     * incomplete until the next start, the log says why, the file stays, and nothing is resumed.
     */
    @Test
    void aJournalStillLockedWhenPrismStopsLookingIsReportedAsNotCheckedAndKept() throws Exception {
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
        Path dead = earlierJournal("1-1");
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                journalFolder.path().resolve("suspensions-1-1.lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock held = channel.lock();
        try {
            start(List.of(Duration.ofMillis(20), Duration.ofMillis(20)));
            await(() -> checks.contains(RecoveryCheck.EXHAUSTED));

            assertThat(checks).containsExactly(RecoveryCheck.PENDING, RecoveryCheck.EXHAUSTED);
            assertThat(activity.entries())
                    .anyMatch(entry -> entry.message().contains("could still not be read")
                            && entry.message().contains("read at the next start"));
            assertThat(dead).as("kept for the next start").exists();
            assertThat(recovered).allSatisfy(list -> assertThat(list).isEmpty());
            assertThat(controller.calls).doesNotContain("resume 4242", "adopt 4242", "suspend 4242");
        } finally {
            held.release();
            channel.close();
        }
    }

    @Test
    void theJournalOfAnEarlierSessionIsListedAtStartUpResumedByNobodyAndCarriedOver() throws Exception {
        controller.capabilities = FakeProcessController.unixLike();
        controller.stopState = target -> StopState.STOPPED;
        Path dead = journalFolder.path().resolve("suspensions-1-1.journal");
        Instant written = Instant.parse("2026-10-08T09:00:00Z");
        // What an earlier session left: a journal that names a suspension, no lock held on it.
        try (SuspensionJournal earlier =
                SuspensionJournal.open(journalFolder.path(), "1-1", SuspensionJournal.onDisk())) {
            assertThat(earlier.write(List.of(new JournalEntry(
                            DEMO,
                            "demo",
                            EnumSet.of(SuspensionReason.USER),
                            Optional.empty(),
                            Optional.empty(),
                            "1-1",
                            written,
                            written,
                            false,
                            false))))
                    .isTrue();
        }
        assertThat(dead).exists();

        start();
        await(() -> !recovered.isEmpty() && !recovered.get(recovered.size() - 1).isEmpty());

        assertThat(recovered.get(recovered.size() - 1))
                .singleElement()
                .satisfies(entry -> assertThat(entry.identity()).isEqualTo(DEMO));
        assertThat(controller.calls).doesNotContain("resume 4242", "adopt 4242");
        // Deleted once this session's journal holds the entries, which its own thread writes.
        await(() -> !Files.exists(dead));
        assertThat(journals()).hasSize(1);
    }

    @Test
    void theShutdownHookAfterACompletedShutdownDoesNothing() throws Exception {
        start();
        limit(DEMO, 30);
        await(() -> controller.calls.contains("suspend 4242"));
        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        int calls = controller.calls.size();
        int entries = activity.entries().size();

        runtime.releaseForExit();

        assertThat(controller.calls).hasSize(calls);
        assertThat(activity.entries()).hasSize(entries);
    }

    @Test
    void theShutdownHookReleasesWhatTheLimiterHoldsAndLeavesTheUsersSuspension() throws Exception {
        start();
        limit(OTHER, 30);
        int before = controlResults.size();
        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, DEMO, "demo"));
        await(() -> controlResults.size() > before);
        assertThat(controlResults.get(before).result().name()).isEqualTo("SUCCESS");
        await(() -> controller.calls.contains("suspend 5555"));

        runtime.releaseForExit();

        assertThat(controller.suspensions)
                .filteredOn(suspension -> suspension.identity().equals(OTHER))
                .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
        assertThat(controller.suspensions)
                .filteredOn(suspension -> suspension.identity().equals(DEMO))
                .singleElement()
                .satisfies(suspension -> assertThat(suspension.outstanding)
                        .as("the user's stays")
                        .isPositive());
    }
}
