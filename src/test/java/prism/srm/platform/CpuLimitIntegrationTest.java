package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.ActivityLog;
import prism.srm.application.JournalEntry;
import prism.srm.application.JournalFolder;
import prism.srm.application.LimitService;
import prism.srm.application.LimiterScheduler;
import prism.srm.application.ProtectionRules;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryState;
import prism.srm.domain.Restorability;
import prism.srm.domain.SuspensionReason;

/**
 * The emulated CPU limit on the real system (LIM-8, ADR-0011), with the controller and the source
 * of this operating system, on the demo workload only (SAFE-6): what it holds a busy process to,
 * that it leaves one below its limit alone, that every way it ends lets the process run, and what
 * the next start does after Prism was ended without a controlled shutdown. The independent measure
 * of the CPU time is the JDK's (the process listing on macOS), never the source under test.
 *
 * <p>Runs on Windows, Linux and macOS; what was run where is in the milestone report.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX, OS.MAC})
class CpuLimitIntegrationTest {

    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final HostPlatform host = HostPlatform.create();
    private final CountingController controller = new CountingController(host.processController());
    private final ActivityLog activity = new ActivityLog(500, InstantSource.system());
    private final List<LimitResult> results = new CopyOnWriteArrayList<>();
    private final ScheduledThreadPoolExecutor limiterThread = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "test-limiter");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1);
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private LimitService limits;
    private DemoProcess demo;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", SuspensionJournal.onDisk());
        coordinator =
                new SuspensionCoordinator(controller, activity, checks, InstantSource.system(), Optional.of(journal));
        LimiterScheduler scheduler = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                limiterThread.execute(task);
            }

            @Override
            public Future<?> schedule(Runnable task, Duration delay) {
                return limiterThread.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            }
        };
        limits = new LimitService(
                controller,
                new ProtectionRules(ProcessHandle.current().pid()),
                coordinator,
                host.processSource(),
                activity,
                scheduler,
                System::nanoTime,
                PROCESSORS,
                results::add,
                snapshot -> {});
        coordinator.setJournalListener(limits::journalFailed);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // The order of the shutdown sequence: refuse, stop the limits and their thread, then release.
        coordinator.refuseFurtherSuspensions();
        limits.refuseFurtherLimits();
        limits.stopForShutdown();
        limiterThread.shutdown();
        assertThat(limiterThread.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        coordinator.releaseAutomaticReasons(Duration.ofSeconds(2));
        coordinator.finalizeForShutdown(Duration.ofSeconds(2));
        journal.close();
        checks.shutdownNow();
        if (demo != null) {
            demo.close();
        }
    }

    /** Busy workers: up to four logical processors' worth of the machine, so that a limit has room on any machine. */
    private static DemoProcess busy(int workers, String duration) throws Exception {
        double percent = Math.floor(100_000.0 * workers / PROCESSORS) / 1000;
        return DemoProcess.start(
                "--cpu",
                String.format(Locale.ROOT, "%.3f", percent),
                "--workers",
                Integer.toString(workers),
                "--duration",
                duration);
    }

    /** The share of the whole machine the workload used over the window, in percent, measured by the JDK. */
    private double useOver(Duration window) throws InterruptedException {
        Duration before = demo.cpu();
        long start = System.nanoTime();
        Thread.sleep(window.toMillis());
        Duration used = demo.cpu().minus(before);
        long elapsed = System.nanoTime() - start;
        return used.toNanos() * 100.0 / ((double) elapsed * PROCESSORS);
    }

    private LimitResult ask(LimitRequest request) throws InterruptedException {
        int before = results.size();
        limits.request(request);
        await(() -> results.size() > before, Duration.ofSeconds(10));
        return results.get(before);
    }

    private static void await(BooleanSupplier condition, Duration bound) throws InterruptedException {
        long end = System.nanoTime() + bound.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("Not reached within " + bound);
            }
            Thread.sleep(20);
        }
    }

    private Optional<LimitStatus> status() {
        return limits.snapshot().find(demo.identity()).map(view -> view.cpu().status());
    }

    private List<JournalEntry> journaled() {
        assertThat(journal.health().healthy()).isTrue();
        return journal.entries();
    }

    private void manageAndLimit(int percent) throws InterruptedException {
        assertThat(limits.cpuCapability().available())
                .as("an emulated CPU limit on this system: %s", limits.cpuCapability())
                .isTrue();
        assertThat(ask(LimitRequest.of(LimitAction.MANAGE, demo.identity(), "demo"))
                        .outcome()
                        .succeeded())
                .isTrue();
        LimitResult set = ask(LimitRequest.cpuLimit(demo.identity(), "demo", percent));
        assertThat(set.outcome().succeeded()).as(set.text()).isTrue();
    }

    /**
     * The window band of plan §8.2, in points of the machine: the room the other processes must
     * leave beyond what is measured. The unlimited use is the workload's demand only while they take
     * no more than this; a window of the limit says something of the limiter only while they leave
     * the limit and this much more. Otherwise the target may not have got the time it was let run
     * for, and after such a phase the duty cycle needs a few intervals to tighten again (failures A
     * and C of the milestone 10 report: the Windows runner of 2026-10-10, and the two-processor
     * stress runs, where the target fell to 20 % while others took 70 % and rose to 69 % after).
     */
    private static final double BAND = 15.0;

    /** The most windows measured for three that count. */
    private static final int MOST_WINDOWS = 12;

    /**
     * One measured window, as shares of the machine in percent: the target's use (the JDK's
     * reading), and what the other processes used of the processors the test runs on (the system's
     * busy time, less the target and this JVM, whose limiter is part of what is under test).
     */
    private record Window(long start, long end, double target, double others) {
        /** Whether the other processes left the target less than this share of the machine and the band. */
        boolean disturbedFor(double needed) {
            return others > 100.0 - needed - BAND;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.2f(others %.2f)", target, others);
        }
    }

    private final SystemMetricsSource metrics = host.systemMetricsSource();

    /**
     * Busy and all time of the processors the test runs on: the whole machine, or, when the stress
     * harness of the milestone 10 report pinned the test to its first processors and says so
     * ({@code -Dprism.test.pinnedToFirstProcessors=true}), those. A container's processor quota
     * lowers the processor count without pinning, so fewer processors alone is no evidence of it.
     */
    private long[] busyAndTotal() {
        prism.srm.domain.CpuSample sample = metrics.cpuTicks();
        List<prism.srm.domain.CpuTicks> ticks = Boolean.getBoolean("prism.test.pinnedToFirstProcessors")
                        && sample.perProcessor().size() > PROCESSORS
                ? sample.perProcessor().subList(0, PROCESSORS)
                : List.of(sample.total());
        long busy = 0;
        long total = 0;
        for (prism.srm.domain.CpuTicks each : ticks) {
            busy += each.total() - each.idle();
            total += each.total();
        }
        return new long[] {busy, total};
    }

    private static long thisJvmCpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean)
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    private Window windowOver(Duration window) throws InterruptedException {
        long[] ticksBefore = busyAndTotal();
        Duration before = demo.cpu();
        long selfBefore = thisJvmCpuNanos();
        long start = System.nanoTime();
        Thread.sleep(window.toMillis());
        long end = System.nanoTime();
        Duration used = demo.cpu().minus(before);
        long self = thisJvmCpuNanos() - selfBefore;
        long[] ticksAfter = busyAndTotal();
        double capacity = (double) (end - start) * PROCESSORS;
        double target = used.toNanos() * 100.0 / capacity;
        double ownJvm = self * 100.0 / capacity;
        long totalTicks = ticksAfter[1] - ticksBefore[1];
        double busy = totalTicks > 0 ? (ticksAfter[0] - ticksBefore[0]) * 100.0 / totalTicks : 0;
        return new Window(start, end, target, Math.max(0, busy - target - ownJvm));
    }

    /**
     * Held near its limit, measured only where the machine let it be: a window counts when neither
     * it nor the one before it was disturbed by other processes. The bounds are those of the plan;
     * a machine that is never quiet enough fails the test with what it measured.
     */
    @Test
    void aBusyProcessIsHeldNearItsLimitAndTheLimitersHoldsAreNotShownAsSuspensions() throws Exception {
        int workers = Math.min(4, PROCESSORS);
        demo = busy(workers, "150s");
        // The workers reach their steady load before it is measured.
        Thread.sleep(2_000);
        List<Window> baselines = new java.util.ArrayList<>();
        Window baseline;
        do {
            baseline = windowOver(Duration.ofSeconds(4));
            baselines.add(baseline);
        } while (baseline.others() > BAND && baselines.size() < 5);
        assertThat(baseline.others() > BAND)
                .as("the machine was too busy to measure the workload unlimited: %s", baselines)
                .isFalse();
        double unlimited = baseline.target();
        int limit = (int) Math.max(1, Math.round(unlimited / 2));
        manageAndLimit(limit);
        AtomicInteger shownHeld = new AtomicInteger();
        List<Map.Entry<Long, LimitStatus>> shown = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> samplerFailed =
                new java.util.concurrent.atomic.AtomicReference<>();
        ScheduledThreadPoolExecutor watcher = new ScheduledThreadPoolExecutor(1);
        watcher.scheduleAtFixedRate(
                () -> {
                    // A task of a fixed-rate schedule that throws is never run again, silently.
                    try {
                        if (coordinator.held().contains(demo.identity())) {
                            shownHeld.incrementAndGet();
                        }
                        status().ifPresent(status -> shown.add(Map.entry(System.nanoTime(), status)));
                    } catch (RuntimeException e) {
                        samplerFailed.compareAndSet(null, e);
                        throw e;
                    }
                },
                0,
                50,
                TimeUnit.MILLISECONDS);
        try {
            Thread.sleep(8_000);
            List<Window> measured = new java.util.ArrayList<>();
            List<Window> counted = new java.util.ArrayList<>();
            measured.add(windowOver(Duration.ofSeconds(4)));
            while (counted.size() < 3 && measured.size() < MOST_WINDOWS) {
                Window window = windowOver(Duration.ofSeconds(4));
                if (!window.disturbedFor(limit) && !measured.getLast().disturbedFor(limit)) {
                    counted.add(window);
                }
                measured.add(window);
            }
            System.out.printf(
                    Locale.ROOT,
                    "cpu-limit: processors=%d workers=%d unlimited=%.2f%% limit=%d%% baselines=%s windows=%s counted=%s"
                            + " status=%s suspends=%d%n",
                    PROCESSORS,
                    workers,
                    unlimited,
                    limit,
                    baselines,
                    measured,
                    counted,
                    status().orElse(null),
                    controller.suspends.get());
            assertThat(counted)
                    .as("the machine was too busy to measure the limit: %s", measured)
                    .hasSize(3);
            double mean = counted.stream().mapToDouble(Window::target).average().orElseThrow();

            // Plan §8.2: the mean within 8 points of the limit and every window within 15. On a small
            // limit the mean must also be within a quarter of it (2 points at least), or a limit of 4 %
            // held at 11 % would pass.
            double tolerance = Math.min(8.0, Math.max(0.25 * limit, 2.0));
            assertThat(mean)
                    .as("mean of three windows against limit %d %%: %s", limit, counted)
                    .isBetween(limit - tolerance, limit + tolerance);
            for (Window window : counted) {
                assertThat(window.target()).isBetween(limit - 15.0, limit + 15.0);
            }
            assertThat(mean).as("the limit acts: less than the unlimited use").isLessThan(unlimited * 0.75);
            assertThat(shownHeld.get())
                    .as("the limiter's holds are never Suspended by Prism")
                    .isZero();
            // A target that demands more than its limit is throttled while it is held near it. "Not
            // throttling" is true for an interval in which it used less (a burst of another process
            // within a window can do that), never for most of the time it was measured held
            // (milestone 10 report, failure A).
            List<LimitStatus> whileCounted = shown.stream()
                    .filter(sample -> counted.stream()
                            .anyMatch(window -> sample.getKey() >= window.start() && sample.getKey() <= window.end()))
                    .map(Map.Entry::getValue)
                    .toList();
            long notThrottling = whileCounted.stream()
                    .filter(LimitStatus.ACTIVE_NOT_THROTTLING::equals)
                    .count();
            System.out.printf(
                    Locale.ROOT,
                    "cpu-limit: status samples while counted=%d, not throttling=%d%n",
                    whileCounted.size(),
                    notThrottling);
            assertThat(samplerFailed.get()).as("the status sampler").isNull();
            // Three 4 s windows sampled every 50 ms: at least half of the 240 samples, however slow the machine.
            assertThat(whileCounted.size())
                    .as("the status was read while the limit was measured")
                    .isGreaterThanOrEqualTo(120);
            assertThat(notThrottling * 2)
                    .as(
                            "shown as not throttling for most of the time it was held near its limit: %d of %d samples",
                            notThrottling, whileCounted.size())
                    .isLessThanOrEqualTo(whileCounted.size());
            // After a phase in which the target used less, the duty cycle tightens again within a few
            // one-second intervals; the status is read once that may have happened, not at an instant.
            await(
                    () -> status().filter(status ->
                                    status == LimitStatus.ACTIVE_THROTTLING || status == LimitStatus.ADJUSTING)
                            .isPresent(),
                    Duration.ofSeconds(5));
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void aProcessBelowItsLimitIsNeverSuspended() throws Exception {
        demo = DemoProcess.start("--cpu", DemoProcess.oneWorkerOfCpu(), "--duration", "60s");
        Thread.sleep(2_000);
        double unlimited = useOver(Duration.ofSeconds(2));
        int limit = (int) Math.min(100, Math.ceil(unlimited * 2) + 2);

        manageAndLimit(limit);
        Thread.sleep(6_000);

        assertThat(controller.suspends.get()).isZero();
        assertThat(status()).hasValue(LimitStatus.ACTIVE_NOT_THROTTLING);
    }

    /**
     * Whether the system reports the workload held: on Windows a thread waiting for the reason
     * Suspended (WMI, read without touching it), elsewhere the process stopped (the kernel's
     * state). Independent of Prism's record of what it holds.
     */
    private boolean heldBySystem() {
        if (org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs()) {
            return prism.srm.platform.windows.SuspendedThreads.of(
                            demo.identity().pid())
                    > 0;
        }
        return host.processController().stopState(demo.identity()) == StopState.STOPPED;
    }

    /**
     * Removing a limit releases the whole workload and lets it run as before. What Prism records
     * ({@code heldFor}) is not taken as the evidence: the system's own report of the threads is, once
     * it has been seen to report the limiter's stops; and the workload must then reach its earlier
     * use. A one-second window over 80 % of it within ten seconds: a neighbour on a busy machine may
     * take a moment of it, a worker left suspended keeps every window near half (milestone 9, the
     * main CI run 37947123315).
     */
    @Test
    void removingTheLimitLetsTheProcessRunInFullAndTakesItsEntryOut() throws Exception {
        int workers = Math.min(2, PROCESSORS);
        demo = busy(workers, "60s");
        Thread.sleep(2_000);
        double unlimited = useOver(Duration.ofSeconds(2));
        manageAndLimit((int) Math.max(1, Math.round(unlimited / 3)));
        Thread.sleep(5_000);
        assertThat(journaled()).hasSize(1);
        // The reading can tell: it reports the limiter's stops while the limit runs.
        await(this::heldBySystem, Duration.ofSeconds(5));

        assertThat(ask(LimitRequest.of(LimitAction.REMOVE_CPU_LIMIT, demo.identity(), "demo"))
                        .outcome()
                        .succeeded())
                .isTrue();

        assertThat(coordinator.heldFor(demo.identity())).isEmpty();
        // Bounded, not instant: one reading of the system can take a while on a busy two-processor
        // runner; a suspension left behind stays, so the bound does not hide it.
        await(() -> !heldBySystem(), Duration.ofSeconds(5));
        List<Double> windows = new java.util.ArrayList<>();
        for (int window = 0; window < 10 && (windows.isEmpty() || windows.getLast() <= unlimited * 0.8); window++) {
            windows.add(useOver(Duration.ofSeconds(1)));
        }
        assertThat(windows.getLast())
                .as("one-second windows after the removal, against %.1f %% unlimited: %s", unlimited, windows)
                .isGreaterThan(unlimited * 0.8);
        assertThat(heldBySystem()).as("still released").isFalse();
        assertThat(journaled()).isEmpty();
    }

    @Test
    void aProcessThatExitsDuringItsLimitLeavesTheManagedListAndNothingIsHeld() throws Exception {
        demo = busy(Math.min(2, PROCESSORS), "4s");
        manageAndLimit(5);

        assertThat(demo.exitsWithin(Duration.ofSeconds(15))).isTrue();
        await(() -> limits.snapshot().processes().isEmpty(), Duration.ofSeconds(10));

        assertThat(coordinator.heldFor(demo.identity())).isEmpty();
        assertThat(journaled()).isEmpty();
        // An exit, as an exit: a thread that refused a suspend as the process exited is no failure
        // to report (the Windows runner of 2026-10-10 reported "SuspendThread failed: 5").
        assertThat(activity.entries())
                .as("the activity log")
                .anySatisfy(entry -> assertThat(entry.message()).contains("has exited and is no longer managed"))
                .noneSatisfy(entry -> assertThat(entry.severity()).isEqualTo(prism.srm.domain.ActivitySeverity.ERROR));
    }

    /**
     * ADR-0011: after Prism ended without a controlled shutdown, the next start resumes nothing by
     * itself; it lists what the journal holds, and resumes it only on the user's request, after the
     * controller's check.
     */
    @Test
    void afterACrashTheNextStartListsTheUsersSuspensionAndResumesItOnlyOnRequest() throws Exception {
        demo = busy(1, "60s");
        // The user's suspension must take hold: a failed suspend is recorded too (as nothing) and left
        // the next session nothing to find, which failed this test later and silently (full gate of
        // 2026-10-11). A failure the platform calls transient may be asked again, as a user would.
        SuspensionCoordinator.Outcome suspended = coordinator.suspend(demo.identity(), "demo", SuspensionReason.USER);
        for (int attempt = 1; attempt < 3 && suspended.retryable(); attempt++) {
            System.out.println("after-crash: transient suspend failure, asked again: " + suspended);
            suspended = coordinator.suspend(demo.identity(), "demo", SuspensionReason.USER);
        }
        assertThat(suspended.change())
                .as("the user's suspension: %s", suspended)
                .isEqualTo(SuspensionCoordinator.Change.SUSPENDED);
        assertThat(suspended.recorded()).isTrue();
        // What a kill of Prism leaves: the entry on disk, the process stopped, nothing held by a live session.
        limits.stopForShutdown();
        coordinator.refuseFurtherSuspensions();
        coordinator.finalizeForShutdown(Duration.ofSeconds(2));
        journal.close();

        try (SuspensionJournal next = SuspensionJournal.open(journalFolder.path(), "2-2", SuspensionJournal.onDisk())) {
            SuspensionCoordinator later = new SuspensionCoordinator(
                    host.processController(), activity, checks, InstantSource.system(), Optional.of(next));
            List<SuspensionJournal.Dead> dead = next.deadJournals();
            assertThat(dead).hasSize(1);
            assertThat(later.recover(dead.get(0).entries())).isTrue();
            next.finish(dead.get(0));

            RecoveredSuspension listed = later.recovered().get(0);
            assertThat(listed.identity()).isEqualTo(demo.identity());
            assertThat(listed.state()).isEqualTo(RecoveryState.POSSIBLY_SUSPENDED);
            assertThat(useOver(Duration.ofMillis(800)))
                    .as("nothing was resumed by itself")
                    .isLessThan(1.0);

            SuspensionCoordinator.RecoveryOutcome resumed = later.resumeRecovered(demo.identity());

            assertThat(resumed.outcome().succeeded())
                    .as(resumed.outcome().toString())
                    .isTrue();
            assertThat(useOver(Duration.ofSeconds(1))).as("running again").isGreaterThan(50.0 / PROCESSORS);
            assertThat(later.recovered()).isEmpty();
            later.refuseFurtherSuspensions();
            later.finalizeForShutdown(Duration.ofSeconds(2));
        }
    }

    /**
     * ADR-0011 against a real hard kill: a session limiting the demo workload is killed as Task
     * Manager or {@code kill -9} would kill it. Its journal names the limit; the next session lists
     * the entry, or closes it when the process runs, and resumes nothing by itself.
     */
    @Test
    void afterAHardKillDuringALimitTheNextStartFindsTheEntryAndResumesNothingByItself() throws Exception {
        demo = busy(Math.min(2, PROCESSORS), "90s");
        java.nio.file.Path childJournal;
        String javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        ProcessBuilder builder = new ProcessBuilder(
                        javaBinary,
                        "-cp",
                        System.getProperty("java.class.path"),
                        LimitingChild.class.getName(),
                        journalFolder.path().toString(),
                        Long.toString(demo.identity().pid()),
                        "5")
                .redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        Process child = builder.start();
        try (java.io.BufferedReader output = new java.io.BufferedReader(
                new java.io.InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            List<String> lines = new java.util.ArrayList<>();
            String line;
            while ((line = output.readLine()) != null && !line.equals("limiting")) {
                lines.add(line);
            }
            assertThat(line).as("the child limits the demo: %s", lines).isEqualTo("limiting");
            // Across processes: the journal of a session that runs is never taken for a dead one.
            assertThat(journal.deadJournals()).as("the child's lock is held").isEmpty();
            childJournal = journalOfChild(child);
            // From outside: the limit's entry is on disk, and the limiter does stop the workload.
            await(this::heldBySystem, Duration.ofSeconds(10));
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            child.destroyForcibly();
        }

        CountingController next = new CountingController(host.processController());
        try (SuspensionJournal own = SuspensionJournal.open(journalFolder.path(), "3-3", SuspensionJournal.onDisk())) {
            List<SuspensionJournal.Dead> dead = List.of(awaitDeadJournal(own, childJournal));
            assertThat(dead.get(0).entries()).singleElement().satisfies(entry -> {
                assertThat(entry.identity()).isEqualTo(demo.identity());
                assertThat(entry.reasons()).containsExactly(SuspensionReason.CPU_LIMITER);
            });
            SuspensionCoordinator later =
                    new SuspensionCoordinator(next, activity, checks, InstantSource.system(), Optional.of(own));
            StopState found = host.processController().stopState(demo.identity());
            later.recover(dead.get(0).entries());
            own.finish(dead.get(0));

            assertThat(next.resumes.get()).as("nothing is resumed by itself").isZero();
            assertThat(next.suspends.get()).isZero();
            List<RecoveredSuspension> listed = later.recovered();
            System.out.println("hard-kill: listed=" + listed);
            boolean perThread = host.processController()
                    .capabilities()
                    .get(ControlAction.RESUME)
                    .mechanism()
                    .filter(prism.srm.domain.ControlMechanism.PER_THREAD_SUSPENSION::equals)
                    .isPresent();
            if (perThread) {
                // Windows: the limiter keeps no record of its threads, so only the user can act, outside Prism.
                assertThat(listed)
                        .singleElement()
                        .satisfies(entry -> assertThat(entry.state()).isEqualTo(RecoveryState.MANUAL_INTERVENTION));
            } else {
                // Linux and macOS: listed exactly when the kill left it stopped, closed when it fell in a run phase.
                if (found == StopState.STOPPED) {
                    assertThat(listed)
                            .singleElement()
                            .satisfies(entry -> assertThat(entry.state()).isEqualTo(RecoveryState.POSSIBLY_SUSPENDED));
                } else {
                    assertThat(listed).as("found %s", found).isEmpty();
                }
            }
            later.refuseFurtherSuspensions();
            later.finalizeForShutdown(Duration.ofSeconds(2));
        }
    }

    /**
     * ADR-0011 against a hard kill in a stop phase, made deterministic by the controlling side: a
     * child limits the demo workload with the application's journal writer, and once its coordinator
     * holds the workload for the CPU limiter it runs no further step, so nothing will continue the
     * workload ("held"). The test checks from outside that the workload is stopped (the kernel's
     * state on Linux and macOS, and no processor time on every system), kills the child, and starts
     * a recovery: the workload stays stopped and is listed; an entry for the same PID with another
     * start time is closed; nothing is resumed until the user asks. Windows keeps no record of the
     * limiter's threads, so there the entry is left to the user outside Prism and Dismiss touches
     * nothing.
     */
    @Test
    void aHardKillInAVerifiedStopPhaseLeavesTheTargetStoppedAndListedUntilTheUserActs() throws Exception {
        demo = busy(Math.min(2, PROCESSORS), "120s");
        boolean perThread = host.processController()
                .capabilities()
                .get(ControlAction.RESUME)
                .mechanism()
                .filter(prism.srm.domain.ControlMechanism.PER_THREAD_SUSPENSION::equals)
                .isPresent();
        Process child = limitingChild(demo.identity().pid(), 5, "hold");
        java.nio.file.Path childJournal;
        java.util.concurrent.BlockingQueue<String> lines = new java.util.concurrent.LinkedBlockingQueue<>();
        Thread reader = new Thread(
                () -> {
                    try (java.io.BufferedReader output = new java.io.BufferedReader(new java.io.InputStreamReader(
                            child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        for (String line = output.readLine(); line != null; line = output.readLine()) {
                            lines.add(line);
                        }
                    } catch (java.io.IOException ended) {
                        // The child was killed.
                    }
                },
                "test-child-output");
        reader.setDaemon(true);
        reader.start();
        try {
            List<String> seen = new java.util.ArrayList<>();
            String line = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!"held".equals(line) && System.nanoTime() < end) {
                line = lines.poll(200, TimeUnit.MILLISECONDS);
                if (line != null) {
                    seen.add(line);
                }
            }
            assertThat(line)
                    .as("the child's limiter holds the workload: %s", seen)
                    .isEqualTo("held");
            // From outside: the workload is stopped, by the only thing that suspends it, the child's limiter.
            if (!perThread) {
                assertThat(host.processController().stopState(demo.identity()))
                        .as("the kernel's state of the workload")
                        .isEqualTo(StopState.STOPPED);
            }
            assertThat(useOver(Duration.ofSeconds(1)))
                    .as("suspended before the kill")
                    .isLessThan(1.0);
            assertThat(journal.deadJournals()).as("the child's lock is held").isEmpty();
            childJournal = journalOfChild(child);
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            child.destroyForcibly();
        }

        if (!perThread) {
            assertThat(host.processController().stopState(demo.identity()))
                    .as("still stopped after the kill")
                    .isEqualTo(StopState.STOPPED);
        }
        assertThat(useOver(Duration.ofMillis(800))).as("nothing continued it").isLessThan(1.0);

        CountingController next = new CountingController(host.processController());
        java.util.concurrent.ThreadPoolExecutor journalThread = new java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(64),
                task -> {
                    Thread writer = new Thread(task, "srm-journal");
                    writer.setDaemon(true);
                    return writer;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        try (SuspensionJournal own = SuspensionJournal.open(journalFolder.path(), "4-4", SuspensionJournal.onDisk())) {
            List<SuspensionJournal.Dead> dead = List.of(awaitDeadJournal(own, childJournal));
            JournalEntry entry = dead.get(0).entries().get(0);
            assertThat(dead.get(0).entries())
                    .as("written before the first suspend")
                    .containsExactly(entry);
            assertThat(entry.identity()).isEqualTo(demo.identity());
            assertThat(entry.reasons()).containsExactly(SuspensionReason.CPU_LIMITER);
            ProcessIdentity otherStart = new ProcessIdentity(
                    demo.identity().pid(), demo.identity().startTime().minusSeconds(1));
            JournalEntry stale = new JournalEntry(
                    otherStart,
                    entry.name(),
                    entry.reasons(),
                    entry.memento(),
                    entry.cause(),
                    entry.writer(),
                    entry.created(),
                    entry.updated(),
                    entry.incomplete(),
                    entry.carried());
            SuspensionCoordinator later = new SuspensionCoordinator(
                    next, activity, checks, InstantSource.system(), Optional.of(own), journalThread);

            assertThat(later.recover(List.of(entry, stale))).isTrue();
            own.finish(dead.get(0));

            List<RecoveredSuspension> listed = later.recovered();
            System.out.println("stop-phase-kill: listed=" + listed);
            assertThat(listed)
                    .as("the same PID with another start time is not the workload")
                    .hasSize(1);
            RecoveredSuspension found = listed.get(0);
            assertThat(found.identity()).isEqualTo(demo.identity());
            assertThat(next.resumes.get()).as("nothing resumed by itself").isZero();
            assertThat(next.suspends.get()).isZero();
            assertThat(useOver(Duration.ofMillis(800)))
                    .as("still stopped after the recovery")
                    .isLessThan(1.0);
            assertThat(later.resumeRecovered(otherStart).outcome().succeeded())
                    .as("no request for another start time acts")
                    .isFalse();
            assertThat(next.resumes.get()).isZero();

            if (!perThread) {
                assertThat(found.state()).isEqualTo(RecoveryState.POSSIBLY_SUSPENDED);
                assertThat(found.resumable()).isTrue();

                SuspensionCoordinator.RecoveryOutcome resumed = later.resumeRecovered(demo.identity());

                assertThat(resumed.outcome().succeeded())
                        .as(resumed.outcome().toString())
                        .isTrue();
                assertThat(useOver(Duration.ofSeconds(1))).as("running again").isGreaterThan(50.0 / PROCESSORS);
                System.out.println("stop-phase-kill: resumed on request: " + resumed.outcome());
            } else {
                assertThat(found.state()).isEqualTo(RecoveryState.MANUAL_INTERVENTION);
                assertThat(found.resumable()).isFalse();
                assertThat(later.resumeRecovered(demo.identity()).outcome().succeeded())
                        .as("Prism offers no resume for it")
                        .isFalse();
                assertThat(later.dismissRecovered(demo.identity())).isTrue();
                assertThat(next.resumes.get()).as("Dismiss touches nothing").isZero();
                assertThat(useOver(Duration.ofMillis(800)))
                        .as("still suspended")
                        .isLessThan(1.0);
                System.out.println("stop-phase-kill: left to the user, dismissed without a call");
            }
            assertThat(later.recovered()).isEmpty();
            later.refuseFurtherSuspensions();
            later.finalizeForShutdown(Duration.ofSeconds(2));
            later.closeJournal(Duration.ofSeconds(2));
        } finally {
            journalThread.shutdownNow();
        }
        // The workload, stopped or not, ends in tearDown: a kill ends a stopped process too.
    }

    /**
     * The journal file of the limiting child, found and read from outside while the child runs: it
     * holds exactly the limit's entry, the demo workload's identity for the CPU limiter.
     */
    private java.nio.file.Path journalOfChild(Process child) throws Exception {
        String prefix = "suspensions-" + child.pid() + "-";
        List<java.nio.file.Path> found;
        try (java.util.stream.Stream<java.nio.file.Path> listed = java.nio.file.Files.list(journalFolder.path())) {
            found = listed.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".journal");
                    })
                    .toList();
        }
        assertThat(found).as("the child's journal file").hasSize(1);
        prism.srm.application.JournalReading.Read read = prism.srm.application.JournalReading.of(found.get(0));
        assertThat(read.corrupt()).isFalse();
        assertThat(read.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.identity()).isEqualTo(demo.identity());
            assertThat(entry.reasons()).containsExactly(SuspensionReason.CPU_LIMITER);
        });
        return found.get(0);
    }

    /**
     * The killed child's journal, once this session can take its lock: the system lets the locks
     * of a killed process go "depending upon available system resources" (LockFileEx), so it is
     * looked for again, for at most 15 s, with what each look found reported.
     */
    private SuspensionJournal.Dead awaitDeadJournal(SuspensionJournal own, java.nio.file.Path expected)
            throws Exception {
        long start = System.nanoTime();
        int looks = 0;
        int waiting = 0;
        while (true) {
            looks++;
            SuspensionJournal.Scan scan = own.scan();
            SuspensionJournal.Dead found = null;
            for (SuspensionJournal.Dead dead : scan.dead()) {
                if (dead.journal().equals(expected)) {
                    found = dead;
                } else {
                    own.abandon(dead);
                }
            }
            if (scan.waiting().contains(expected)) {
                waiting++;
            }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (found != null) {
                System.out.printf(
                        Locale.ROOT,
                        "hard-kill-journal: readable after %d ms, %d look(s), %d waiting for its lock%n",
                        elapsed,
                        looks,
                        waiting);
                return found;
            }
            if (elapsed > 15_000) {
                boolean exists = java.nio.file.Files.exists(expected);
                throw new AssertionError("The killed child's journal was not readable within 15 s: exists=" + exists
                        + ", looks=" + looks + ", waiting for its lock=" + waiting + ", entries="
                        + (exists
                                ? prism.srm.application.JournalReading.of(expected)
                                        .entries()
                                : "none"));
            }
            Thread.sleep(100);
        }
    }

    /** Starts {@link LimitingChild} in a JVM of its own, limiting the process with the PID. */
    private Process limitingChild(long pid, int percent, String... more) throws java.io.IOException {
        String javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                .toString();
        List<String> command = new java.util.ArrayList<>(List.of(
                javaBinary,
                "-cp",
                System.getProperty("java.class.path"),
                LimitingChild.class.getName(),
                journalFolder.path().toString(),
                Long.toString(pid),
                Integer.toString(percent)));
        command.addAll(List.of(more));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeAll(List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"));
        return builder.start();
    }

    /** ADR-0011: a stop another program makes is neither held nor ended by the limiter; it waits. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aProcessStoppedByAnotherProgramIsNeitherHeldNorContinuedByTheLimiter() throws Exception {
        demo = busy(Math.min(2, PROCESSORS), "60s");
        manageAndLimit(10);
        Thread.sleep(3_000);
        ProcessController other = HostPlatform.create().processController();
        SuspendResult outside = stopFromOutsideUntilTheLimiterWaits(other);
        try {
            int resumes = controller.resumes.get();
            Thread.sleep(3_000);

            assertThat(controller.resumes.get()).as("no continue is sent").isEqualTo(resumes);
            assertThat(useOver(Duration.ofMillis(800))).isLessThan(1.0);
        } finally {
            other.resume(outside.suspension().orElseThrow());
        }
        await(() -> status().filter(LimitStatus.PAUSED_EXTERNAL_STOP::equals).isEmpty(), Duration.ofSeconds(10));
    }

    /**
     * Stops the demo as another program would, until the limiter reports that it waits. A stop that coincides with the
     * limiter's own suspend ends with its continue, and nobody can see that (ADR-0011, residual risks): the other
     * program then stops it again. A limiter that never notices still fails here.
     */
    private SuspendResult stopFromOutsideUntilTheLimiterWaits(ProcessController other) throws InterruptedException {
        for (int attempt = 1; attempt <= 5; attempt++) {
            SuspendResult outside = other.suspend(demo.identity());
            for (int tries = 0; !outside.outcome().succeeded() && tries < 100; tries++) {
                // The limiter held it at that moment: the other program tries again a moment later.
                Thread.sleep(37);
                outside = other.suspend(demo.identity());
            }
            assertThat(outside.outcome().succeeded())
                    .as(outside.outcome().toString())
                    .isTrue();
            long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < end) {
                if (status().filter(LimitStatus.PAUSED_EXTERNAL_STOP::equals).isPresent()) {
                    return outside;
                }
                Thread.sleep(20);
            }
            System.out.println("external-stop: stop " + attempt + " was not seen by the limiter; stopping again");
            other.resume(outside.suspension().orElseThrow());
        }
        throw new AssertionError("The limiter never reported the stop of another program");
    }

    /** The controller of this system, counting what the limiter asks of it. */
    private static final class CountingController implements ProcessController {
        private final ProcessController real;
        final AtomicInteger suspends = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();

        CountingController(ProcessController real) {
            this.real = real;
        }

        @Override
        public Map<ControlAction, Capability> capabilities() {
            return real.capabilities();
        }

        @Override
        public List<String> limitations() {
            return real.limitations();
        }

        @Override
        public Map<Integer, PriorityLevel> listedPriorities() {
            return real.listedPriorities();
        }

        @Override
        public Restorability priorityRestorability() {
            return real.priorityRestorability();
        }

        @Override
        public ProtectionFacts protectionFacts(ProcessIdentity target) {
            return real.protectionFacts(target);
        }

        @Override
        public ControlOutcome liveness(ProcessIdentity target) {
            return real.liveness(target);
        }

        @Override
        public ControlOutcome requestClose(ProcessIdentity target) {
            return real.requestClose(target);
        }

        @Override
        public ControlOutcome terminate(ProcessIdentity target) {
            return real.terminate(target);
        }

        @Override
        public SuspendResult suspend(ProcessIdentity target) {
            suspends.incrementAndGet();
            return real.suspend(target);
        }

        @Override
        public ControlOutcome resume(Suspension suspension) {
            resumes.incrementAndGet();
            return real.resume(suspension);
        }

        @Override
        public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
            return real.changePriority(target, level);
        }

        @Override
        public StopState stopState(ProcessIdentity target) {
            return real.stopState(target);
        }

        @Override
        public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
            return real.adopt(target, memento);
        }
    }
}
