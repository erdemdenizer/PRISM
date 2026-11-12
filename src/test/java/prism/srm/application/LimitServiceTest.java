package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.platform.SuspendResult;

/**
 * Managing processes and limiting their CPU (MGD-1, MGD-2, MGD-4, LIM-1 to LIM-4, ADR-0011) on a
 * virtual clock: what may be managed, that a limit starts only after its journal entry, and that
 * every way a limit ends leaves its process running.
 */
class LimitServiceTest {

    private static final long OWN_PID = 1;
    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-08T10:00:00Z"));
    private static final int PROCESSORS = 8;

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final VirtualScheduler scheduler = new VirtualScheduler();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeProcessSource source = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(200, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final List<LimitResult> results = new ArrayList<>();
    private final List<LimitsSnapshot> snapshots = new ArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private LimitService service;
    private SimulatedTarget target;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
        service = new LimitService(
                controller,
                new ProtectionRules(OWN_PID),
                coordinator,
                source,
                activity,
                scheduler,
                scheduler.clock(),
                PROCESSORS,
                results::add,
                snapshots::add);
        coordinator.setJournalListener(service::journalFailed);
        target = new SimulatedTarget(scheduler, PROCESSORS, 1.0).wire(controller, source);
    }

    @AfterEach
    void closeJournal() {
        journal.close();
    }

    private LimitResult ask(LimitRequest request) {
        int before = results.size();
        service.request(request);
        scheduler.advance(Duration.ofMillis(1));
        assertThat(results).hasSize(before + 1);
        return results.get(results.size() - 1);
    }

    private LimitResult ask(LimitAction action) {
        return ask(LimitRequest.of(action, DEMO, "demo"));
    }

    private LimitResult limit(int percent) {
        return ask(LimitRequest.cpuLimit(DEMO, "demo", percent));
    }

    private ManagedProcessView view() {
        return service.snapshot().find(DEMO).orElseThrow();
    }

    private long suspends() {
        return controller.calls.stream().filter("suspend 4242"::equals).count();
    }

    private List<JournalEntry> journaled() throws Exception {
        return SuspensionJournal.decode(Files.readAllBytes(journal.file())).entries();
    }

    @Test
    void aManagedProcessIsLimitedToItsShareAndTheLimitIsCalledEmulatedAndApproximate() {
        assertThat(ask(LimitAction.MANAGE).outcome().succeeded()).isTrue();

        LimitResult set = limit(30);
        scheduler.advance(Duration.ofSeconds(15));

        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.text()).contains("limited to 30 %").contains("Approximate");
        ManagedProcessView view = view();
        assertThat(view.cpu().inEffect().level()).isEqualTo(CapabilityLevel.EMULATED);
        assertThat(view.cpu().inEffect().mechanism()).contains(ControlMechanism.DUTY_CYCLE);
        assertThat(view.cpu().status()).isEqualTo(LimitStatus.ACTIVE_THROTTLING);
        assertThat(view.cpu().measuredPercent().orElseThrow()).isBetween(25.0, 35.0);
        assertThat(target.useOver(Duration.ofSeconds(10))).isBetween(0.27, 0.33);
    }

    @Test
    void prismItselfCannotBeManaged() {
        LimitResult result =
                ask(LimitRequest.of(LimitAction.MANAGE, new ProcessIdentity(OWN_PID, Instant.EPOCH), "prism"));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(result.text()).contains("Prism's own process");
        assertThat(service.snapshot().processes()).isEmpty();
    }

    @Test
    void aProtectedProcessCannotBeManaged() {
        controller.facts = identity -> new ProtectionFacts(true, Optional.empty(), false);

        LimitResult result = ask(LimitAction.MANAGE);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(service.snapshot().processes()).isEmpty();
    }

    @Test
    void aProcessThatIsNotManagedGetsNoLimitAndIsNotTouched() {
        LimitResult result = limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(result.text()).contains("manage it first");
        assertThat(controller.calls).isEmpty();
    }

    @Test
    void aPercentageOutsideOneToAHundredIsRefused() {
        ask(LimitAction.MANAGE);

        assertThat(limit(0).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(limit(101).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(view().cpu().requestedPercent()).isEmpty();
    }

    @Test
    void whereNothingCanBeSuspendedTheLimitIsUnavailableWithTheReason() {
        controller.capabilities = FakeProcessController.noneAvailable();
        controller.limitations = List.of("this kernel has no pidfd");
        ask(LimitAction.MANAGE);

        LimitResult result = limit(30);

        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(result.text()).contains("this kernel has no pidfd");
        assertThat(service.cpuCapability().available()).isFalse();
        assertThat(view().cpu().inEffect().reason()).contains("this kernel has no pidfd");
        assertThat(controller.calls).doesNotContain("suspend 4242");
    }

    @Test
    void aLimitWhoseEntryCannotBeWrittenDoesNotStartAndTouchesNothing() throws Exception {
        ask(LimitAction.MANAGE);
        byte[] before = Files.readAllBytes(journal.file());
        files.diskFull = true;

        LimitResult result = limit(30);
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(result.outcome().detail().orElseThrow()).startsWith("suspension journal");
        assertThat(controller.calls).doesNotContain("suspend 4242");
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
        assertThat(view().cpu().detail().orElseThrow()).startsWith("journal");
        assertThat(view().cpu().note()).isEqualTo(prism.srm.domain.LimitNote.JOURNAL);
        assertThat(service.snapshot().limitsOff()).isPresent();
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.severity()).isEqualTo(ActivitySeverity.ERROR));
        assertThat(Files.readAllBytes(journal.file())).isEqualTo(before);
    }

    @Test
    void aLostLockKeepsTheLimitFromStarting() {
        ask(LimitAction.MANAGE);
        files.locksLost = true;
        coordinator.verifyJournal();

        LimitResult result = limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(suspends()).isZero();
    }

    @Test
    void aNewLimitRequestTriesTheJournalOnceMore() {
        ask(LimitAction.MANAGE);
        files.diskFull = true;
        limit(30);
        files.diskFull = false;

        LimitResult again = limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(again.outcome().succeeded()).isTrue();
        assertThat(suspends()).isPositive();
        assertThat(service.snapshot().limitsOff()).isEmpty();
    }

    /** Where a suspension is a signal (Linux, macOS), sixteen limits run at once, whatever the processors. */
    @Test
    void atMostSixteenLimitsRunAtOnce() {
        controller.capabilities = FakeProcessController.unixLike();
        assertThat(service.mostActiveLimits()).isEqualTo(LimitService.MOST_LIMITS);
        for (int pid = 100; pid < 100 + LimitService.MOST_LIMITS; pid++) {
            ProcessIdentity identity = new ProcessIdentity(pid, Instant.EPOCH);
            ask(LimitRequest.of(LimitAction.MANAGE, identity, "p" + pid));
            assertThat(ask(LimitRequest.cpuLimit(identity, "p" + pid, 50))
                            .outcome()
                            .succeeded())
                    .isTrue();
        }
        ask(LimitAction.MANAGE);

        LimitResult seventeenth = limit(50);

        assertThat(seventeenth.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(seventeenth.text()).contains("at most 16");
    }

    // --- Admission with per-thread suspension (Windows; ADR-0011, the performance exception) ---

    private static ProcessIdentity process(long pid) {
        return new ProcessIdentity(pid, Instant.EPOCH);
    }

    private LimitResult limit(long pid, int percent) {
        return ask(LimitRequest.cpuLimit(process(pid), "p" + pid, percent));
    }

    private LimitResult act(LimitAction action, long pid) {
        return ask(LimitRequest.of(action, process(pid), "p" + pid));
    }

    private long suspendsOf(long pid) {
        return controller.calls.stream().filter(("suspend " + pid)::equals).count();
    }

    private ManagedProcessView view(long pid) {
        return service.snapshot().find(process(pid)).orElseThrow();
    }

    /** The other processes of a test read a steady processor time: none ends as gone while the test runs. */
    private void steadyOthers() {
        java.util.function.Function<ProcessIdentity, prism.srm.domain.CpuTimeReading> demo = source.onCpuTime;
        source.onCpuTime =
                identity -> identity.equals(DEMO) ? demo.apply(identity) : new prism.srm.domain.CpuTimeReading.Read(0);
    }

    /** One limit for every four logical processors, at least one and at most four; integer division. */
    @Test
    void thePerThreadAllowanceFollowsTheLogicalProcessors() {
        java.util.Optional<prism.srm.domain.ControlMechanism> perThread =
                java.util.Optional.of(prism.srm.domain.ControlMechanism.PER_THREAD_SUSPENSION);
        java.util.Map<Integer, Integer> expected = new java.util.LinkedHashMap<>();
        expected.put(1, 1);
        expected.put(2, 1);
        expected.put(3, 1);
        expected.put(4, 1);
        expected.put(7, 1);
        expected.put(8, 2);
        expected.put(11, 2);
        expected.put(12, 3);
        expected.put(16, 4);
        expected.put(32, 4);
        expected.put(128, 4);
        expected.forEach((processors, most) -> assertThat(LimitService.mostActiveLimits(perThread, processors))
                .as("%d logical processors", processors)
                .isEqualTo(most));
        for (int processors : new int[] {1, 2, 8, 16, 128}) {
            assertThat(LimitService.mostActiveLimits(
                            java.util.Optional.of(prism.srm.domain.ControlMechanism.STOP_SIGNAL), processors))
                    .isEqualTo(LimitService.MOST_LIMITS);
            assertThat(LimitService.mostActiveLimits(java.util.Optional.empty(), processors))
                    .isEqualTo(LimitService.MOST_LIMITS);
        }
        assertThat(service.mostActiveLimits())
                .as("this service: per-thread, 8 processors")
                .isEqualTo(2);
    }

    /** One limit in the singular, the processors and the cap said as numbers, never a promised share. */
    @Test
    void theRefusalSaysTheActualAllowance() {
        LimitRequest request = LimitRequest.cpuLimit(process(9), "p9", 20);
        ControlMessages.Wording one = LimitMessages.tooManyPerThreadLimits(request, 1, 2);
        ControlMessages.Wording four = LimitMessages.tooManyPerThreadLimits(request, 4, 16);

        assertThat(one.explanation())
                .contains("at most 1 CPU limit at a time")
                .contains("of its 2 logical processors, at most 4");
        assertThat(four.explanation())
                .contains("at most 4 CPU limits at a time")
                .contains("16 logical processors");
        assertThat(one.explanation() + four.explanation()).doesNotContain("%");
    }

    /** The third limit on eight logical processors is refused, says the number and why, and does nothing. */
    @Test
    void aLimitBeyondThePerThreadAllowanceIsRefusedWithItsReasonAndDoesNothing() throws Exception {
        steadyOthers();
        for (long pid : new long[] {101, 102, 103}) {
            act(LimitAction.MANAGE, pid);
        }
        assertThat(limit(101, 30).outcome().succeeded()).isTrue();
        assertThat(limit(102, 30).outcome().succeeded()).isTrue();
        List<JournalEntry> before = journaled();
        long callsBefore = controller.calls.size();

        LimitResult third = limit(103, 30);

        assertThat(third.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(third.text())
                .contains("at most 2 CPU limits at a time")
                .contains("8 logical processors")
                .contains("a tenth of a processor")
                .doesNotContain("%");
        assertThat(controller.calls.subList((int) callsBefore, controller.calls.size()))
                .as("no call to the system")
                .isEmpty();
        assertThat(journaled()).as("nothing declared").isEqualTo(before);
        assertThat(coordinator.heldFor(process(103))).isEmpty();
        assertThat(view(103).cpu().requestedPercent())
                .as("the percentage is not kept")
                .isEmpty();
        assertThat(service.limiting(process(103))).isFalse();
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(suspendsOf(103)).isZero();
    }

    /** A disabled limit runs nothing and takes no place; enabling it asks for one again; Remove frees one. */
    @Test
    void aDisabledLimitTakesNoPlaceEnablingItAsksAgainAndRemovingFreesOne() throws Exception {
        steadyOthers();
        for (long pid : new long[] {101, 102, 103}) {
            act(LimitAction.MANAGE, pid);
        }
        assertThat(limit(101, 30).outcome().succeeded()).isTrue();
        assertThat(limit(102, 30).outcome().succeeded()).isTrue();

        assertThat(act(LimitAction.DISABLE, 102).outcome().succeeded()).isTrue();
        assertThat(limit(103, 30).outcome().succeeded())
                .as("the place of the disabled one")
                .isTrue();
        // The disabled process may still be given a percentage: it starts nothing.
        assertThat(limit(102, 40).outcome().succeeded()).isTrue();
        assertThat(service.limiting(process(102))).isFalse();

        LimitResult enable = act(LimitAction.ENABLE, 102);

        assertThat(enable.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(enable.text()).contains("at most 2 CPU limits");
        assertThat(view(102).process().settings().enabled())
                .as("still disabled")
                .isFalse();
        assertThat(view(102).cpu().requestedPercent()).hasValue(40);
        assertThat(service.limiting(process(102))).isFalse();
        assertThat(journaled()).noneMatch(entry -> entry.identity().equals(process(102)));
        long suspended = suspendsOf(102);
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(suspendsOf(102)).as("nothing runs for it").isEqualTo(suspended);

        assertThat(act(LimitAction.REMOVE_CPU_LIMIT, 101).outcome().succeeded()).isTrue();
        assertThat(act(LimitAction.ENABLE, 102).outcome().succeeded())
                .as("a place again")
                .isTrue();
        assertThat(service.limiting(process(102))).isTrue();
        assertThat(act(LimitAction.UNMANAGE, 103).outcome().succeeded()).isTrue();
        assertThat(limit(101, 30).outcome().succeeded())
                .as("Unmanage frees one too")
                .isTrue();
    }

    /**
     * Requests sent from many threads at once: the limiter thread takes them one at a time, so no
     * more limits start than may run, whatever the order.
     */
    @Test
    void requestsFromManyThreadsAtOnceStartNoMoreLimitsThanMayRun() throws Exception {
        java.util.concurrent.ScheduledThreadPoolExecutor thread =
                new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
                    Thread limiter = new Thread(task, "test-limiter");
                    limiter.setDaemon(true);
                    return limiter;
                });
        LimiterScheduler real = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                thread.execute(task);
            }

            @Override
            public java.util.concurrent.Future<?> schedule(Runnable task, Duration delay) {
                return thread.schedule(task, delay.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        };
        List<LimitResult> answered = new java.util.concurrent.CopyOnWriteArrayList<>();
        LimitService concurrent = new LimitService(
                controller,
                new ProtectionRules(OWN_PID),
                coordinator,
                source,
                activity,
                real,
                System::nanoTime,
                PROCESSORS,
                answered::add,
                snapshot -> {});
        // A steady reading: no limit ends on its own while the requests arrive.
        source.onCpuTime = identity -> new prism.srm.domain.CpuTimeReading.Read(0);
        int contenders = 8;
        List<Thread> senders = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                concurrent.request(LimitRequest.of(LimitAction.MANAGE, process(200 + i), "p" + i));
            }
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < contenders; i++) {
                ProcessIdentity identity = process(200 + i);
                Thread sender = new Thread(
                        () -> {
                            try {
                                go.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            concurrent.request(LimitRequest.cpuLimit(identity, "p", 30));
                        },
                        "test-sender-" + i);
                senders.add(sender);
                sender.start();
            }
            go.countDown();
            for (Thread sender : senders) {
                sender.join(5_000);
            }
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (answered.size() < 2 * contenders && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            List<LimitResult> limits = answered.stream()
                    .filter(result -> result.request().action() == LimitAction.SET_CPU_LIMIT)
                    .toList();

            assertThat(limits).hasSize(contenders);
            assertThat(limits.stream().filter(result -> result.outcome().succeeded()))
                    .hasSize(concurrent.mostActiveLimits())
                    .hasSize(2);
            assertThat(limits.stream().filter(result -> !result.outcome().succeeded()))
                    .allSatisfy(result -> assertThat(result.text()).contains("at most 2 CPU limits"));
            assertThat(journaled().stream().map(JournalEntry::identity))
                    .as("declared: exactly the ones that started")
                    .containsExactlyInAnyOrderElementsOf(limits.stream()
                            .filter(result -> result.outcome().succeeded())
                            .map(result -> result.request().identity())
                            .toList());
        } finally {
            for (Thread sender : senders) {
                sender.interrupt();
            }
            concurrent.refuseFurtherLimits();
            concurrent.stopForShutdown();
            thread.shutdown();
            assertThat(thread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    @Test
    void requestsBeyondThoseThatMayWaitAreAnsweredAtOnce() {
        for (int i = 0; i < LimitService.MOST_PENDING; i++) {
            service.request(LimitRequest.of(LimitAction.MANAGE, new ProcessIdentity(100 + i, Instant.EPOCH), "p"));
        }
        assertThat(results).isEmpty();

        service.request(LimitRequest.of(LimitAction.MANAGE, DEMO, "demo"));

        assertThat(results)
                .singleElement()
                .satisfies(result -> assertThat(result.text()).contains("Too many requests"));
        scheduler.advance(Duration.ofMillis(1));
        assertThat(results).hasSize(LimitService.MOST_PENDING + 1);
    }

    @Test
    void removingTheLimitLetsTheProcessRunAndTakesItsEntryOutOfTheJournal() throws Exception {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(10));

        LimitResult removed = ask(LimitAction.REMOVE_CPU_LIMIT);
        long suspends = suspends();
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(removed.outcome().succeeded()).isTrue();
        assertThat(target.stopped()).isFalse();
        assertThat(suspends()).isEqualTo(suspends);
        assertThat(journaled()).isEmpty();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.INACTIVE);
        assertThat(view().cpu().requestedPercent()).isEmpty();
    }

    @Test
    void aChangedLimitReplacesTheOldOneAndTheJournalHoldsOneEntry() throws Exception {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        limit(60);
        scheduler.advance(Duration.ofSeconds(15));

        assertThat(journaled())
                .singleElement()
                .satisfies(entry -> assertThat(entry.cause()).contains("CPU limit 60 %"));
        assertThat(target.useOver(Duration.ofSeconds(10))).isBetween(0.55, 0.65);
    }

    @Test
    void disablingStopsTheLimitAndEnablingStartsItAgain() {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(10));

        ask(LimitAction.DISABLE);
        assertThat(target.stopped()).isFalse();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.INACTIVE);
        assertThat(view().cpu().requestedPercent()).hasValue(30);
        long suspends = suspends();
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(suspends()).isEqualTo(suspends);

        assertThat(ask(LimitAction.ENABLE).outcome().succeeded()).isTrue();
        scheduler.advance(Duration.ofSeconds(15));
        assertThat(target.useOver(Duration.ofSeconds(10))).isBetween(0.27, 0.33);
    }

    @Test
    void aLimitSetWhileDisabledIsKeptAndNotApplied() {
        ask(LimitAction.MANAGE);
        ask(LimitAction.DISABLE);

        LimitResult result = limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(result.text()).contains("disabled");
        assertThat(suspends()).isZero();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.INACTIVE);
    }

    @Test
    void unmanagingReleasesTheProcessAndDropsIt() throws Exception {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(10));

        LimitResult result = ask(LimitAction.UNMANAGE);

        assertThat(result.text()).contains("no longer managed");
        assertThat(target.stopped()).isFalse();
        assertThat(service.snapshot().processes()).isEmpty();
        assertThat(journaled()).isEmpty();
    }

    @Test
    void aLimitedProcessThatExitsLeavesTheManagedListAndTheExitIsLogged() throws Exception {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(5));
        source.onCpuTime = identity -> prism.srm.domain.CpuTimeReading.GONE;

        scheduler.advance(Duration.ofSeconds(5));

        assertThat(service.snapshot().processes()).isEmpty();
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message()).contains("has exited and is no longer managed"));
        assertThat(journaled()).isEmpty();
    }

    @Test
    void aManagedProcessWithoutALimitThatExitsIsFoundByTheListing() {
        ask(LimitAction.MANAGE);
        controller.liveness = identity -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");

        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));

        assertThat(service.snapshot().processes()).isEmpty();
    }

    @Test
    void aListingThatMissesARunningProcessDropsNothing() {
        ask(LimitAction.MANAGE);

        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));

        assertThat(service.snapshot().processes()).hasSize(1);
    }

    @Test
    void aJournalThatFailsStopsEveryLimitReleasesThemAndKeepsTheEntries() throws Exception {
        ProcessIdentity other = new ProcessIdentity(5_555, Instant.EPOCH);
        ask(LimitAction.MANAGE);
        limit(30);
        ask(LimitRequest.of(LimitAction.MANAGE, other, "other"));
        ask(LimitRequest.cpuLimit(other, "other", 30));
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(journaled()).hasSize(2);
        byte[] evidence = Files.readAllBytes(journal.file());

        files.diskFull = true;
        coordinator.declare(
                new ProcessIdentity(7, Instant.EPOCH), "x", prism.srm.domain.SuspensionReason.CPU_LIMITER, "l");
        scheduler.advance(Duration.ofMillis(1));
        long suspends =
                controller.calls.stream().filter(c -> c.startsWith("suspend")).count();
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(service.limiting(other)).isFalse();
        assertThat(controller.calls.stream()
                        .filter(c -> c.startsWith("suspend"))
                        .count())
                .isEqualTo(suspends);
        assertThat(coordinator.heldFor(DEMO)).isEmpty();
        assertThat(coordinator.heldFor(other)).isEmpty();
        assertThat(controller.suspensions)
                .allSatisfy(s -> assertThat(s.outstanding).isZero());
        assertThat(Files.readAllBytes(journal.file())).isEqualTo(evidence);
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
        assertThat(service.snapshot().limitsOff()).isPresent();
    }

    @Test
    void aJournalChangedOnDiskIsFoundWithinASecondAndStopsTheLimits() throws Exception {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(3));

        Files.writeString(journal.file(), "tampered");
        scheduler.advance(Duration.ofMillis(1_500));

        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(target.stopped()).isFalse();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
    }

    @Test
    void aLimitThatCannotSuspendEndsAsFailedWithItsReasonAndWithdrawsItsEntry() throws Exception {
        controller.suspend =
                identity -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "access denied"));
        ask(LimitAction.MANAGE);
        limit(30);

        scheduler.advance(Duration.ofSeconds(5));

        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
        assertThat(view().cpu().detail().orElseThrow()).contains("access denied");
        assertThat(journaled()).isEmpty();
    }

    @Test
    void atShutdownRequestsAreRefusedAndTheLimitsLeaveTheReleaseToTheShutdown() {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(10));
        while (!target.stopped()) {
            scheduler.advance(Duration.ofMillis(10));
        }

        coordinator.refuseFurtherSuspensions();
        service.refuseFurtherLimits();
        service.stopForShutdown();
        service.request(LimitRequest.of(LimitAction.MANAGE, new ProcessIdentity(9, Instant.EPOCH), "x"));
        assertThat(results.get(results.size() - 1).text()).contains("Prism is closing");
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(target.stopped()).isTrue();

        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));
        assertThat(target.stopped()).isFalse();
    }

    private static final ProcessIdentity OTHER = new ProcessIdentity(5_555, Instant.parse("2026-10-08T10:00:00Z"));

    private void manageBoth() {
        ask(LimitAction.MANAGE);
        ask(LimitRequest.of(LimitAction.MANAGE, OTHER, "other"));
    }

    @Test
    void aDeclarationThatWasNotWrittenIsForgottenAndALaterWriteDoesNotCarryIt() throws Exception {
        manageBoth();
        files.diskFull = true;
        assertThat(limit(30).outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        files.diskFull = false;

        assertThat(ask(LimitRequest.cpuLimit(OTHER, "other", 30)).outcome().succeeded())
                .isTrue();

        assertThat(journaled()).extracting(JournalEntry::identity).containsExactly(OTHER);
        assertThat(coordinator
                        .suspend(DEMO, "demo", prism.srm.domain.SuspensionReason.CPU_LIMITER)
                        .change())
                .as("no limit owns the demo's entry any more")
                .isEqualTo(SuspensionCoordinator.Change.FAILED);
    }

    @Test
    void aLimitEndedByTheJournalIsNotCarriedByTheNextWrite() throws Exception {
        manageBoth();
        limit(30);
        scheduler.advance(Duration.ofSeconds(3));
        files.diskFull = true;
        coordinator.declare(
                new ProcessIdentity(7, Instant.EPOCH), "x", prism.srm.domain.SuspensionReason.CPU_LIMITER, "l");
        scheduler.advance(Duration.ofSeconds(2));
        assertThat(service.limiting(DEMO)).isFalse();
        files.diskFull = false;

        assertThat(ask(LimitRequest.cpuLimit(OTHER, "other", 30)).outcome().succeeded())
                .isTrue();

        assertThat(journaled()).extracting(JournalEntry::identity).containsExactly(OTHER);
    }

    /** Every way a durable, checked write can fail (ADR-0011) keeps the limit from starting. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"disk full", "move refused", "read back otherwise", "denied"})
    void aLimitWhoseEntryCannotBeWrittenDurablyDoesNotStart(String failure) {
        ask(LimitAction.MANAGE);
        switch (failure) {
            case "disk full" -> files.diskFull = true;
            case "move refused" -> files.failingMoves = 3;
            case "read back otherwise" -> files.readsBackOtherwise = true;
            case "denied" -> files.denied = true;
            default -> throw new IllegalArgumentException(failure);
        }

        LimitResult result = limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(result.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(suspends()).isZero();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
    }

    @Test
    void aWithdrawThatFailsAtTheNormalEndOfALimitStopsTheOthers() {
        manageBoth();
        limit(30);
        ask(LimitRequest.cpuLimit(OTHER, "other", 30));
        scheduler.advance(Duration.ofSeconds(3));
        files.diskFull = true;

        ask(LimitAction.REMOVE_CPU_LIMIT);
        scheduler.advance(Duration.ofSeconds(2));

        assertThat(service.limiting(OTHER)).isFalse();
        assertThat(coordinator.heldFor(OTHER)).isEmpty();
        assertThat(controller.suspensions)
                .allSatisfy(suspension -> assertThat(suspension.outstanding).isZero());
    }

    @Test
    void aShutdownWithAFailedJournalKeepsItsFileAsEvidence() throws Exception {
        ask(LimitAction.MANAGE);
        limit(30);
        scheduler.advance(Duration.ofSeconds(3));
        byte[] evidence = Files.readAllBytes(journal.file());
        files.diskFull = true;
        coordinator.verifyJournal();
        coordinator.declare(
                new ProcessIdentity(7, Instant.EPOCH), "x", prism.srm.domain.SuspensionReason.CPU_LIMITER, "l");

        service.stopForShutdown();
        coordinator.refuseFurtherSuspensions();
        coordinator.releaseAutomaticReasons(Duration.ofSeconds(1));
        coordinator.finalizeForShutdown(Duration.ofSeconds(1));
        journal.close();

        assertThat(journal.file()).exists();
        assertThat(Files.readAllBytes(journal.file())).isEqualTo(evidence);
        assertThat(target.stopped()).isFalse();
    }
}
