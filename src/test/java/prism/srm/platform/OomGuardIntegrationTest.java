package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.application.ActivityLog;
import prism.srm.application.EarlierLimits;
import prism.srm.application.JournalEntry;
import prism.srm.application.JournalFolder;
import prism.srm.application.LimitService;
import prism.srm.application.LimiterScheduler;
import prism.srm.application.NativeLimitLedger;
import prism.srm.application.OomGuard;
import prism.srm.application.OomObservation;
import prism.srm.application.OomStateMachine.Transition;
import prism.srm.application.ProtectionRules;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.Listed;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomView;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;
import prism.srm.domain.SuspensionReason;
import prism.srm.domain.VersionedSettings;

/**
 * OOM Guard on the real system (plan A15.4), on the demo workload only (SAFE-6): the controller,
 * the process listing, the suspension coordinator with its journal on disk and the limit service of
 * this operating system, OOM Guard on a limiter thread of its own. The state machine is pure and
 * tested on its own, so the observations are handed to OOM Guard directly: no real memory pressure
 * is made (DEMO-2), and no state needs half a minute of real time to settle.
 *
 * <p>The memory signal is given as validated on every system: what is tested here is that the
 * actions work on the process, not the gate of the product, which {@code MonitoringRuntimeOomTest}
 * tests (on macOS the product offers none of them, ADR-0015).
 *
 * <p>Runs on Windows, Linux and macOS; what was run where is in the milestone report.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX, OS.MAC})
class OomGuardIntegrationTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final HostPlatform host = HostPlatform.create();
    private final Recording controller = new Recording(host.processController());
    private final ActivityLog activity = new ActivityLog(500, InstantSource.system());
    private final List<LimitResult> results = new CopyOnWriteArrayList<>();
    private final List<OomView> views = new CopyOnWriteArrayList<>();
    private final AtomicReference<VersionedSettings> settings =
            new AtomicReference<>(new VersionedSettings(1, OomSettings.DEFAULTS));
    private final ScheduledThreadPoolExecutor limiterThread = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "test-limiter");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1);
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private OomGuard guard;
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
        ProtectionRules rules = new ProtectionRules(ProcessHandle.current().pid());
        guard = new OomGuard(
                controller,
                rules,
                coordinator,
                activity,
                scheduler,
                System::nanoTime,
                InstantSource.system(),
                settings::get,
                views::add,
                OomGuard.Tuning.DEFAULTS,
                OomSignalStatus.VALIDATED);
        limits = new LimitService(
                controller,
                rules,
                coordinator,
                host.processSource(),
                activity,
                scheduler,
                System::nanoTime,
                Runtime.getRuntime().availableProcessors(),
                UnavailableResourceLimiter.here(),
                NativeLimitLedger.none(),
                EarlierLimits.NONE,
                guard,
                results::add,
                snapshot -> {});
        coordinator.setJournalListener(problem -> {
            limits.journalFailed(problem);
            guard.journalFailed(problem);
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // The order of the shutdown sequence: refuse, stop the guard and the limits and their thread, then release.
        coordinator.refuseFurtherSuspensions();
        guard.stop();
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

    // ----- How the test drives OOM Guard -----

    /** Publishes these switches as the runtime does: set first, then tell the guard. */
    private void switches(boolean priority, boolean suspend, boolean terminate) {
        settings.updateAndGet(previous -> new VersionedSettings(
                previous.version() + 1,
                previous.settings().withSwitches(new OomSwitches(true, priority, suspend, terminate))));
        guard.settingsChanged();
    }

    /** Puts the demo under OOM management through the limit service, as the Processes page does. */
    private void manage(EmergencyAction action) throws InterruptedException {
        assertThat(ask(LimitRequest.of(LimitAction.MANAGE, demo.identity(), "demo"))
                        .outcome()
                        .succeeded())
                .isTrue();
        LimitResult set =
                ask(LimitRequest.oom(demo.identity(), "demo", new OomManagement(true, OomPriority.NORMAL, action)));
        assertThat(set.outcome().succeeded()).as(set.text()).isTrue();
    }

    private LimitResult ask(LimitRequest request) throws InterruptedException {
        int before = results.size();
        limits.request(request);
        await(() -> results.size() > before, BOUND);
        return results.get(before);
    }

    /** The real listing, as the process monitor hands it to OOM Guard; the demo's reading, if listed. */
    private Optional<ProcessReading> list() {
        Map<ProcessIdentity, Listed> listed = new HashMap<>();
        Optional<ProcessReading> demoReading = Optional.empty();
        for (ProcessReading reading : host.processSource().processes()) {
            listed.put(reading.identity(), new Listed(reading.residentBytes(), reading.priority()));
            if (demo != null && reading.identity().equals(demo.identity())) {
                demoReading = Optional.of(reading);
            }
        }
        guard.listed(listed);
        return demoReading;
    }

    /** An observation made now, as the state machine would hand it over after a change of state. */
    private void observed(OomState state, double percentAvailable) {
        long now = System.nanoTime();
        long total = 16L << 30;
        long available = Math.round(total * percentAvailable / 100);
        VersionedSettings current = settings.get();
        guard.observed(new OomObservation(
                state,
                Optional.of(new OomReading(available, total, now)),
                state == OomState.NORMAL ? Transition.Kind.LEFT : Transition.Kind.ENTERED,
                true,
                1,
                current.version(),
                now));
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

    /** Whether the demo used no CPU time over the window, measured by the JDK (the listing on macOS). */
    private boolean stoppedOver(Duration window) throws InterruptedException {
        Duration before = demo.cpu();
        Thread.sleep(window.toMillis());
        return demo.cpu().equals(before);
    }

    private boolean ranOver(Duration window) throws InterruptedException {
        Duration before = demo.cpu();
        Thread.sleep(window.toMillis());
        return demo.cpu().compareTo(before) > 0;
    }

    private List<OomActionRecord> records() {
        return views.isEmpty() ? List.of() : views.get(views.size() - 1).actions();
    }

    private Optional<JournalEntry> journaled(ProcessIdentity identity) {
        assertThat(journal.health().healthy()).isTrue();
        return journal.entries().stream()
                .filter(entry -> entry.identity().equals(identity))
                .findFirst();
    }

    private static DemoProcess busyDemo() throws Exception {
        return DemoProcess.start("--cpu", DemoProcess.oneWorkerOfCpu(), "--memory", "64MiB", "--duration", "2m");
    }

    // ----- The tests -----

    @Test
    void anEmergencySuspendsTheDemoAfterDeclaringItAndNormalResumesIt() throws Exception {
        demo = busyDemo();
        assertThat(controller
                        .capabilities()
                        .getOrDefault(ControlAction.SUSPEND, Capability.UNAVAILABLE)
                        .available())
                .as("suspension on this system")
                .isTrue();
        manage(EmergencyAction.SUSPEND);
        switches(false, true, false);
        assertThat(list()).isPresent();

        observed(OomState.EMERGENCY, 4);
        await(() -> coordinator.heldFor(demo.identity()).contains(SuspensionReason.OOM_GUARD), BOUND);

        assertThat(journaled(demo.identity()))
                .as("declared in the journal on disk")
                .hasValueSatisfying(entry -> assertThat(entry.reasons()).contains(SuspensionReason.OOM_GUARD));
        assertThat(stoppedOver(Duration.ofSeconds(1)))
                .as("no CPU time while suspended")
                .isTrue();
        assertThat(controller.calls())
                .filteredOn(call -> call.startsWith("suspend"))
                .hasSize(1);

        list();
        observed(OomState.NORMAL, 40);
        await(() -> coordinator.heldFor(demo.identity()).isEmpty(), BOUND);

        await(() -> journaled(demo.identity()).isEmpty(), BOUND);
        long end = System.nanoTime() + BOUND.toNanos();
        boolean ran = false;
        while (!ran && System.nanoTime() < end) {
            ran = ranOver(Duration.ofMillis(500));
        }
        assertThat(ran).as("CPU time again after the resume").isTrue();
        assertThat(controller.calls())
                .filteredOn(call -> call.startsWith("resume"))
                .hasSize(1);
        assertThat(records())
                .extracting(OomActionRecord::kind)
                .contains(OomActionRecord.Kind.SUSPEND, OomActionRecord.Kind.RESUME);
    }

    @Test
    void criticalLowersThePriorityOnlyWhereItCanBeRaisedBackAndNormalRestoresTheLevelThatWasRead() throws Exception {
        demo = busyDemo();
        manage(EmergencyAction.LOWER_PRIORITY);
        switches(true, false, false);
        ProcessReading before = list().orElseThrow();
        Map<Integer, PriorityLevel> levels = controller.listedPriorities();
        Optional<PriorityLevel> was = levelOf(before.priority(), levels);
        System.out.println("oom-priority: before " + before.priority() + " = " + was + ", restorability "
                + controller.priorityRestorability());

        observed(OomState.CRITICAL, 7);

        if (controller.priorityRestorability() != Restorability.RESTORABLE) {
            // O16: where a lowered priority cannot be raised back, OOM Guard does not lower it.
            await(() -> views.stream().anyMatch(view -> view.state() == OomState.CRITICAL), BOUND);
            Thread.sleep(500);
            list();
            assertThat(controller.calls())
                    .filteredOn(call -> call.startsWith("priority"))
                    .isEmpty();
            assertThat(levelOf(list().orElseThrow().priority(), levels)).isEqualTo(was);
            return;
        }
        await(() -> controller.calls().stream().anyMatch(call -> call.startsWith("priority")), BOUND);
        await(() -> levelOf(list().orElseThrow().priority(), levels).equals(Optional.of(PriorityLevel.LOW)), BOUND);

        observed(OomState.NORMAL, 40);
        await(
                () -> controller.calls().stream()
                                .filter(call -> call.startsWith("priority"))
                                .count()
                        == 2,
                BOUND);
        await(() -> levelOf(list().orElseThrow().priority(), levels).equals(was), BOUND);
        assertThat(controller.calls())
                .filteredOn(call -> call.startsWith("priority"))
                .containsExactly("priority " + PriorityLevel.LOW, "priority " + was.orElseThrow());
    }

    @Test
    void anEmergencyTerminatesTheDemoOnceWithBothOptIns() throws Exception {
        demo = busyDemo();
        manage(EmergencyAction.TERMINATE);
        switches(false, false, true);
        list();

        observed(OomState.EMERGENCY, 4);

        assertThat(demo.exitsWithin(BOUND)).as("the demo ended").isTrue();
        await(() -> controller.calls().stream().anyMatch(call -> call.startsWith("terminate")), BOUND);
        // That it is never sent twice to one identity is the unit tests' (OomGuardLedgerTest): the
        // exited demo leaves the listing, so a second incident here could not show it.
        assertThat(controller.calls())
                .filteredOn(call -> call.startsWith("terminate"))
                .hasSize(1);
        assertThat(list()).as("gone from the listing").isEmpty();
    }

    /**
     * A process whose PID came to another start time: the listing names it, OOM Guard acts on it, and
     * the controller refuses every action because the start time is not the demo's (SI-4): the demo is
     * neither suspended, nor ended, nor lowered.
     */
    @ParameterizedTest
    @EnumSource(
            value = EmergencyAction.class,
            names = {"SUSPEND", "TERMINATE", "LOWER_PRIORITY"})
    void anIdentityWithAnotherStartTimeIsNeverActedOn(EmergencyAction action) throws Exception {
        demo = busyDemo();
        ProcessReading before = list().orElseThrow();
        ProcessIdentity stale = new ProcessIdentity(
                demo.identity().pid(), demo.identity().startTime().minusSeconds(3_600));
        switches(true, true, true);
        guard.managedChanged(List.of(new OomGuard.Managed(
                new ManagedProcess(
                        stale,
                        "demo",
                        ManagedSettings.NONE.withOom(new OomManagement(true, OomPriority.NORMAL, action))),
                java.util.OptionalLong.empty(),
                0)));
        Map<ProcessIdentity, Listed> listing = new HashMap<>();
        listing.put(stale, new Listed(java.util.OptionalLong.of(64L << 20), before.priority()));
        guard.listed(listing);

        OomState state = action == EmergencyAction.LOWER_PRIORITY ? OomState.CRITICAL : OomState.EMERGENCY;
        observed(state, action == EmergencyAction.LOWER_PRIORITY ? 7 : 4);
        await(() -> views.stream().anyMatch(view -> view.state() == state), BOUND);
        await(
                () -> !records().isEmpty()
                        || !controller.calls().isEmpty()
                        || views.get(views.size() - 1).candidates().stream()
                                .anyMatch(candidate -> candidate.notNow().isPresent()
                                        || candidate.hold().failed().isPresent()),
                BOUND);
        System.out.println("oom-stale " + action + ": calls " + controller.calls() + ", records "
                + records().stream().map(OomActionRecord::text).toList());

        assertThat(coordinator.held()).isEmpty();
        assertThat(demo.process().isAlive()).as("not ended").isTrue();
        assertThat(controller.stopState(demo.identity())).isNotEqualTo(StopState.STOPPED);
        assertThat(list().orElseThrow().priority()).as("not lowered").isEqualTo(before.priority());
        boolean ran = false;
        long end = System.nanoTime() + BOUND.toNanos();
        while (!ran && System.nanoTime() < end) {
            ran = ranOver(Duration.ofMillis(500));
        }
        assertThat(ran).as("the demo keeps running").isTrue();
    }

    /** OOM-5: with the switches as they come, an Emergency changes nothing on a managed process. */
    @Test
    void withTheDefaultSwitchesNothingIsDone() throws Exception {
        demo = busyDemo();
        manage(EmergencyAction.TERMINATE);
        ProcessReading before = list().orElseThrow();

        observed(OomState.CRITICAL, 7);
        observed(OomState.EMERGENCY, 4);
        await(() -> views.stream().anyMatch(view -> view.state() == OomState.EMERGENCY), BOUND);
        Thread.sleep(500);

        assertThat(controller.calls())
                .filteredOn(call ->
                        call.startsWith("suspend") || call.startsWith("terminate") || call.startsWith("priority"))
                .isEmpty();
        assertThat(coordinator.held()).isEmpty();
        assertThat(controller.stopState(demo.identity())).isNotEqualTo(StopState.STOPPED);
        assertThat(list().orElseThrow().priority()).isEqualTo(before.priority());
        assertThat(demo.process().isAlive()).isTrue();
    }

    private static Optional<PriorityLevel> levelOf(OptionalInt listed, Map<Integer, PriorityLevel> levels) {
        return listed.isPresent() ? Optional.ofNullable(levels.get(listed.getAsInt())) : Optional.empty();
    }

    /** The controller of this system, with each call that changes a process written down. */
    private static final class Recording implements ProcessController {
        private final ProcessController real;
        private final List<String> calls = new CopyOnWriteArrayList<>();

        Recording(ProcessController real) {
            this.real = real;
        }

        List<String> calls() {
            return new ArrayList<>(calls);
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
            calls.add("close");
            return real.requestClose(target);
        }

        @Override
        public ControlOutcome terminate(ProcessIdentity target) {
            calls.add("terminate");
            return real.terminate(target);
        }

        @Override
        public SuspendResult suspend(ProcessIdentity target) {
            calls.add("suspend");
            return real.suspend(target);
        }

        @Override
        public ControlOutcome resume(Suspension suspension) {
            calls.add("resume");
            return real.resume(suspension);
        }

        @Override
        public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
            calls.add("priority " + level);
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
