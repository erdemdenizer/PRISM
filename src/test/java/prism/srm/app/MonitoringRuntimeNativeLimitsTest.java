package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
import prism.srm.application.FakeResourceLimiter;
import prism.srm.application.FakeSystemMetricsSource;
import prism.srm.application.JournalEntry;
import prism.srm.application.JournalFolder;
import prism.srm.application.NativeLimitLedger;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsNotice;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * The runtime of milestone 10 with its real executors, a fake native limiter and a real journal
 * folder: a controlled shutdown and the shutdown hook clear the native limits and the empty ledger
 * goes; the ledger of an earlier session is read at start-up, each of its limits is looked at, and a
 * process an earlier session may still cap is offered no second limiter (plan §6.4, §6.5, D8, D21).
 */
class MonitoringRuntimeNativeLimitsTest {

    private static final long PATIENCE_SECONDS = 15;
    private static final ProcessIdentity DEMO = FakeProcessSource.identity(4_242, 10);
    private static final ProcessIdentity OTHER = FakeProcessSource.identity(5_555, 20);
    private static final String INSTANCE = "9-9";

    /**
     * An earlier session's name whose PID no process can have, so that "is that Prism still running?"
     * has the same answer on every host (PID 1 exists on Linux and macOS).
     */
    private static final String EARLIER = Long.MAX_VALUE + "-1";

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeSystemMetricsSource system = new FakeSystemMetricsSource();
    private final FakeProcessSource processes = new FakeProcessSource();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();
    private final ActivityLog activity = new ActivityLog(500, () -> Instant.EPOCH);
    private final List<LimitResult> limitResults = new CopyOnWriteArrayList<>();
    private final List<LimitsSnapshot> limitSnapshots = new CopyOnWriteArrayList<>();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private final List<prism.srm.domain.RecoveryCheck> checks = new CopyOnWriteArrayList<>();
    private MonitoringRuntime runtime;

    MonitoringRuntimeNativeLimitsTest() {
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

    private void start() {
        start(MonitoringRuntime.RECOVERY_RETRIES);
    }

    private void start(List<Duration> recoveryRetries) {
        runtime = new MonitoringRuntime(
                () -> new PlatformSources(
                        system, processes, controller, 1, Optional.of(journalFolder.path()), INSTANCE, limiter),
                System::nanoTime,
                activity,
                new MonitoringRuntime.Listeners(
                        snapshot -> {},
                        listing -> {},
                        details -> {},
                        result -> {},
                        profile -> {},
                        held -> {},
                        failures -> {},
                        limitSnapshots::add,
                        limitResults::add,
                        recovered -> {},
                        checks::add,
                        result -> {}),
                Duration.ofSeconds(5),
                (thread, failure) -> uncaught.add(failure),
                recoveryRetries);
        runtime.start();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("reached in time").isLessThan(end);
            Thread.sleep(20);
        }
    }

    private LimitResult ask(LimitRequest request) throws InterruptedException {
        int before = limitResults.size();
        runtime.requestLimit(request);
        await(() -> limitResults.size() > before);
        return limitResults.get(before);
    }

    /** Manages a process once the limit service exists, which it does once process monitoring runs. */
    private void manage(ProcessIdentity identity) throws InterruptedException {
        await(() -> {
            try {
                return ask(LimitRequest.of(LimitAction.MANAGE, identity, "demo"))
                        .outcome()
                        .succeeded();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
    }

    private Path ledgerOf(String instance) {
        return journalFolder.path().resolve("native-limits-" + instance + ".ledger");
    }

    private List<Path> ledgers() throws Exception {
        try (Stream<Path> listed = Files.list(journalFolder.path())) {
            return listed.filter(path -> path.toString().endsWith(".ledger")).toList();
        }
    }

    @Test
    void aControlledShutdownClearsTheNativeLimitsAndTheEmptyLedgerGoes() throws Exception {
        start();
        manage(DEMO);
        LimitResult limited = ask(LimitRequest.cpuLimit(DEMO, "demo", 20));
        assertThat(limited.outcome().succeeded()).as(limited.text()).isTrue();
        assertThat(limiter.calls).contains("apply 4242 CPU 20 %");
        await(() -> Files.exists(ledgerOf(INSTANCE)));

        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(limiter.calls).contains("releaseAll");
        assertThat(limiter.holds).isEmpty();
        assertThat(ledgers()).as("a ledger whose limits were all cleared goes").isEmpty();
        assertThat(controller.calls).as("no emulated limiter ran").noneMatch(call -> call.startsWith("suspend"));
    }

    /** The exit hook is the only chance after a kill signal; the ledger stays if it cannot say CLEARED in time. */
    @Test
    void theShutdownHookClearsTheNativeLimits() throws Exception {
        start();
        manage(DEMO);
        assertThat(ask(LimitRequest.memoryLimit(DEMO, "demo", 256L << 20))
                        .outcome()
                        .succeeded())
                .isTrue();
        assertThat(limiter.holds).containsKey(DEMO);

        runtime.releaseForExit();

        assertThat(limiter.calls).contains("releaseAll");
        assertThat(limiter.holds).isEmpty();
    }

    /**
     * LIFE-5: the release at an exit has one deadline. On Windows a closed console waits five seconds by
     * default, so the native limits get what is left of it, never a bound of their own: a release that
     * gave each step the whole bound would hand over all of {@code EXIT_BOUND} here.
     */
    @Test
    void theExitHookGivesTheNativeLimitsOnlyWhatIsLeftOfOneDeadline() throws Exception {
        start();
        manage(DEMO);
        assertThat(ask(LimitRequest.cpuLimit(DEMO, "demo", 20)).outcome().succeeded())
                .isTrue();

        runtime.releaseForExit();

        assertThat(limiter.releaseWithin).isPositive().isLessThan(MonitoringRuntime.EXIT_BOUND);
    }

    /**
     * On a console close the release runs twice, from the console handler and then from the JVM's hook: the
     * second finds nothing held, touches no process and logs nothing new.
     */
    @Test
    void aSecondReleaseAtTheExitChangesNothing() throws Exception {
        start();
        manage(DEMO);
        assertThat(ask(LimitRequest.cpuLimit(DEMO, "demo", 20)).outcome().succeeded())
                .isTrue();
        runtime.releaseForExit();
        assertThat(limiter.holds).isEmpty();
        int processCalls = controller.calls.size();
        int entries = activity.entries().size();

        runtime.releaseForExit();

        assertThat(limiter.holds).isEmpty();
        assertThat(controller.calls).hasSize(processCalls);
        assertThat(activity.entries()).hasSize(entries);
    }

    /** A limit the shutdown cannot verify as cleared stays on disk for the next start, which lists it. */
    @Test
    void aLimitTheShutdownCannotVerifyAsClearedStaysInTheLedger() throws Exception {
        limiter.clears = identity -> false;
        start();
        manage(DEMO);
        assertThat(ask(LimitRequest.cpuLimit(DEMO, "demo", 20)).outcome().succeeded())
                .isTrue();

        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(limiter.calls).contains("releaseAll");
        assertThat(NativeLimitLedger.readDead(journalFolder.path(), INSTANCE, SuspensionJournal.onDisk()))
                .singleElement()
                .satisfies(record -> assertThat(record.identity()).isEqualTo(DEMO));
    }

    /**
     * An earlier session's ledger without a lock or a journal (an orphan): each record is looked at,
     * and a process it may still cap natively gets no second limiter, native or emulated.
     */
    @Test
    void anOrphanLedgerIsReadAtStartUpAndAProcessItMayStillCapGetsNoSecondLimiter() throws Exception {
        writeEarlierLedger(EARLIER);
        assertThat(ledgerOf(EARLIER)).exists();

        start();
        await(() -> limiter.calls.contains("recover 4242"));
        manage(DEMO);
        await(() -> !limitSnapshots.isEmpty()
                && limitSnapshots.get(limitSnapshots.size() - 1).notices().stream()
                        .anyMatch(notice -> notice.kind() == LimitsNotice.Kind.PRIOR_SESSION));

        LimitResult refused = ask(LimitRequest.cpuLimit(DEMO, "demo", 30));

        assertThat(refused.outcome().succeeded()).isFalse();
        assertThat(refused.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(refused.fallback()).as("no emulated limit is offered").isEmpty();
        assertThat(limiter.calls).noneMatch(call -> call.startsWith("apply 4242"));
        assertThat(controller.calls).noneMatch(call -> call.startsWith("suspend"));
        // Carried into this session's ledger, then the earlier one goes.
        await(() -> !Files.exists(ledgerOf(EARLIER)));
        assertThat(ledgerOf(INSTANCE)).exists();
    }

    /** The ledger of a session whose suspension journal is read at start-up is read with it. */
    @Test
    void theLedgerOfADeadSessionIsReadWithItsJournal() throws Exception {
        try (SuspensionJournal earlier =
                SuspensionJournal.open(journalFolder.path(), EARLIER, SuspensionJournal.onDisk())) {
            Instant written = Instant.parse("2026-10-08T09:00:00Z");
            assertThat(earlier.write(List.of(new JournalEntry(
                            OTHER,
                            "other",
                            EnumSet.of(SuspensionReason.USER),
                            Optional.empty(),
                            Optional.empty(),
                            EARLIER,
                            written,
                            written,
                            false,
                            false))))
                    .isTrue();
        }
        writeEarlierLedger(EARLIER);
        limiter.recover = record -> new prism.srm.domain.RecoveredLimit(
                record, prism.srm.domain.RecoveredLimit.Verdict.NOT_IN_JOB, "not in a job");

        start();
        await(() -> limiter.calls.contains("recover 4242"));
        await(() -> !Files.exists(ledgerOf(EARLIER)));

        manage(DEMO);
        assertThat(ask(LimitRequest.cpuLimit(DEMO, "demo", 30)).outcome().succeeded())
                .as("a verdict that blocks nothing leaves the process free to limit")
                .isTrue();
    }

    /**
     * Audit item 2, deterministic: an earlier session's lock is still held when this one starts, and
     * its ledger names the process. Until the ledger is read, no emulated limit is offered for it;
     * once the lock is let go and the ledger read, the earlier cap is known and blocks every second
     * limiter. Nothing is suspended at any point.
     */
    @Test
    void anEarlierLedgerWaitingForItsLockBlocksTheEmulatedLimitUntilItIsReadThenItsCapDoes() throws Exception {
        try (SuspensionJournal earlier =
                SuspensionJournal.open(journalFolder.path(), EARLIER, SuspensionJournal.onDisk())) {
            // A session that recorded a native limit keeps its journal: here one the user suspended.
            Instant written = Instant.parse("2026-10-08T09:00:00Z");
            assertThat(earlier.write(List.of(new JournalEntry(
                            OTHER,
                            "other",
                            EnumSet.of(SuspensionReason.USER),
                            Optional.empty(),
                            Optional.empty(),
                            EARLIER,
                            written,
                            written,
                            false,
                            false))))
                    .isTrue();
        }
        writeEarlierLedger(EARLIER);
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        java.nio.channels.FileChannel lockChannel = java.nio.channels.FileChannel.open(
                journalFolder.path().resolve("suspensions-" + EARLIER + ".lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        java.nio.channels.FileLock stillHeld = lockChannel.lock();
        try {
            // Looks again every 100 ms for a minute: the lock, not a retry budget, decides when it is read.
            start(java.util.Collections.nCopies(600, Duration.ofMillis(100)));
            manage(DEMO);

            LimitResult before = ask(LimitRequest.cpuLimit(DEMO, "demo", 30));

            assertThat(before.outcome().succeeded()).isFalse();
            assertThat(before.fallback())
                    .as("not offered before the earlier ledger is read")
                    .isEmpty();
            assertThat(before.text()).contains("No emulated limit is offered for it yet");
            assertThat(ask(LimitRequest.of(LimitAction.ACCEPT_EMULATED_CPU, DEMO, "demo"))
                            .outcome()
                            .succeeded())
                    .isFalse();
            assertThat(limiter.calls).doesNotContain("recover 4242");
            assertThat(limitSnapshots.get(limitSnapshots.size() - 1).notices())
                    .extracting(LimitsNotice::kind)
                    .contains(LimitsNotice.Kind.RECOVERY_PENDING);
            assertThat(checks)
                    .as("still looking, not given up")
                    .doesNotContain(prism.srm.domain.RecoveryCheck.EXHAUSTED);
        } finally {
            stillHeld.release();
            lockChannel.close();
        }

        await(() -> limiter.calls.contains("recover 4242"));
        await(() -> !Files.exists(ledgerOf(EARLIER)));
        // The found cap reaches the limit service on its own thread: asked once it has (deterministic).
        await(() -> !limitSnapshots.isEmpty()
                && limitSnapshots.get(limitSnapshots.size() - 1).notices().stream()
                        .anyMatch(notice -> notice.kind() == LimitsNotice.Kind.PRIOR_SESSION));
        LimitResult after = ask(LimitRequest.cpuLimit(DEMO, "demo", 30));

        assertThat(after.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(after.fallback()).as("the earlier cap is known now").isEmpty();
        assertThat(after.text()).contains("earlier Prism session");
        assertThat(controller.calls).noneMatch(call -> call.startsWith("suspend"));
        assertThat(controller.calls)
                .as("the suspension the earlier session recorded for another process is not resumed")
                .noneMatch(call -> call.startsWith("resume"));
    }

    /**
     * Final audit, at the composition root: on a system where no Prism session can have set a native
     * limit (Linux, macOS in this version), a ledger file in the folder is not looked at, nothing is
     * unsettled, and the milestone 9 emulated limit runs as before.
     */
    @Test
    void whereNoNativeLimitCanHaveBeenSetAStrayLedgerChangesNothing() throws Exception {
        writeEarlierLedger(EARLIER);
        limiter.leftLimits = false;
        prism.srm.domain.LimitCapability none = prism.srm.domain.LimitCapability.unavailable("no native limiter");
        limiter.system =
                java.util.Map.of(prism.srm.domain.LimitKind.CPU, none, prism.srm.domain.LimitKind.MEMORY, none);
        start();
        manage(DEMO);

        LimitResult set = ask(LimitRequest.cpuLimit(DEMO, "demo", 30));

        assertThat(set.outcome().succeeded()).as(set.text()).isTrue();
        // The emulated limit of milestone 9 started (the fake source here reads no processor time,
        // so the run then sees the process as gone; what matters is that it was not refused).
        assertThat(set.text()).contains("limited to 30 %").contains("Approximate");
        assertThat(limiter.calls).noneMatch(call -> call.startsWith("recover"));
        assertThat(ledgerOf(EARLIER)).as("not this system's to read").exists();
        assertThat(limitSnapshots.get(limitSnapshots.size() - 1).notices())
                .extracting(LimitsNotice::kind)
                .doesNotContain(LimitsNotice.Kind.RECOVERY_PENDING);
    }

    private void writeEarlierLedger(String instance) {
        NativeLimitLedger earlier = new NativeLimitLedger(
                Optional.of(journalFolder.path()), instance, SuspensionJournal.onDisk(), Runnable::run);
        assertThat(earlier.putDurably(prism.srm.domain.LimitRecord.intended(
                                DEMO, "demo", EffectiveLimits.of(OptionalInt.of(1_500), OptionalLong.empty()), instance)
                        .applied(EffectiveLimits.of(OptionalInt.of(1_500), OptionalLong.empty()))))
                .isTrue();
    }
}
