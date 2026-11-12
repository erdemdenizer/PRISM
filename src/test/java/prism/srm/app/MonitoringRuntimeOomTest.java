package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.ActivityLog;
import prism.srm.application.FakeProcessController;
import prism.srm.application.FakeProcessSource;
import prism.srm.application.FakeSystemMetricsSource;
import prism.srm.application.JournalFolder;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.MemorySample;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomView;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;
import prism.srm.platform.UnavailableResourceLimiter;

/**
 * OOM Guard in the runtime, with its real executors against fake sources (plan A15.5): every memory
 * sample reaches the state machine, every action runs on the limiter thread, the settings are
 * published with their versions, and the shutdown and the exit hook end what OOM Guard holds as
 * decided (plan O7).
 */
class MonitoringRuntimeOomTest {

    private static final long PATIENCE_SECONDS = 15;
    private static final long GIB = 1L << 30;
    private static final ProcessIdentity DEMO = FakeProcessSource.identity(4_242, 10);
    private static final ProcessIdentity OTHER = FakeProcessSource.identity(5_555, 20);

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeSystemMetricsSource system = new FakeSystemMetricsSource();
    private final FakeProcessSource processes = new FakeProcessSource();
    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(1_000, () -> Instant.EPOCH);
    private final List<LimitResult> limitResults = new CopyOnWriteArrayList<>();
    private final List<ProcessControlResult> controlResults = new CopyOnWriteArrayList<>();
    private final List<OomView> views = new CopyOnWriteArrayList<>();
    private final List<String> actingThreads = new CopyOnWriteArrayList<>();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private MonitoringRuntime runtime;

    MonitoringRuntimeOomTest() {
        processes.list(List.of(
                FakeProcessSource.listedReading(DEMO, 100, 200L << 20, 8),
                FakeProcessSource.listedReading(OTHER, 100, 100L << 20, 8)));
        controller.restorability = Restorability.RESTORABLE;
        java.util.function.Function<ProcessIdentity, ControlOutcome> terminate = controller.terminate;
        controller.terminate = target -> {
            actingThreads.add(Thread.currentThread().getName());
            return terminate.apply(target);
        };
        java.util.function.BiFunction<ProcessIdentity, prism.srm.domain.PriorityLevel, ControlOutcome> priority =
                controller.priority;
        controller.priority = (target, level) -> {
            actingThreads.add(Thread.currentThread().getName());
            return priority.apply(target, level);
        };
    }

    @AfterEach
    void shutDown() throws InterruptedException {
        if (runtime != null) {
            runtime.shutdown();
            assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(uncaught).isEmpty();
    }

    private void start(OomSignalStatus signal, OomSettings settings) {
        start(signal, settings, System::nanoTime);
    }

    private void start(OomSignalStatus signal, OomSettings settings, LongSupplier nanoClock) {
        runtime = new MonitoringRuntime(
                () -> new PlatformSources(
                        system,
                        processes,
                        controller,
                        1,
                        Optional.of(journalFolder.path()),
                        "9-9",
                        UnavailableResourceLimiter.here(),
                        signal),
                nanoClock,
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
                        recovered -> {},
                        check -> {},
                        result -> {},
                        views::add),
                Duration.ofSeconds(5),
                (thread, failure) -> uncaught.add(failure),
                MonitoringRuntime.RECOVERY_RETRIES,
                settings);
        runtime.start();
    }

    private static OomSettings switches(boolean priority, boolean suspend, boolean terminate) {
        return OomSettings.DEFAULTS.withSwitches(new OomSwitches(true, priority, suspend, terminate));
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

    /** Manages the process with these OOM settings, once process monitoring runs. */
    private void manage(ProcessIdentity identity, EmergencyAction action) throws InterruptedException {
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
        assertThat(ask(LimitRequest.oom(identity, "demo", new OomManagement(true, OomPriority.NORMAL, action)))
                        .outcome()
                        .succeeded())
                .isTrue();
    }

    private void available(double percent) {
        system.memory(new MemorySample(16 * GIB, Math.round(16 * GIB * percent / 100), Optional.empty()));
    }

    private boolean logged(String text) {
        return activity.entries().stream().map(ActivityEntry::message).anyMatch(message -> message.contains(text));
    }

    private Set<String> prismThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("srm-"))
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void everyActionRunsOnTheLimiterThreadAndTheStatesAreLogged() throws Exception {
        start(OomSignalStatus.VALIDATED, switches(true, false, true));
        manage(DEMO, EmergencyAction.TERMINATE);

        available(7);
        await(() -> controller.calls.contains("priority 4242 LOW"));
        available(3);
        await(() -> controller.calls.contains("terminate 4242"));

        assertThat(actingThreads).isNotEmpty().allMatch("srm-limiter"::equals);
        assertThat(logged("Memory pressure critical")).isTrue();
        assertThat(logged("Memory pressure emergency")).isTrue();
        assertThat(views.get(views.size() - 1).state()).isEqualTo(prism.srm.domain.OomState.EMERGENCY);
    }

    @Test
    void theStateMachineSeesTheSamplesWhateverTheUserInterfaceDoesWithThem() throws Exception {
        start(OomSignalStatus.VALIDATED, OomSettings.DEFAULTS);
        await(() -> !views.isEmpty());

        available(14);

        await(() -> logged("Memory pressure warning"));
    }

    @Test
    void aMissingReadingIsLoggedOnceAndItsReturnOnce() throws Exception {
        start(OomSignalStatus.VALIDATED, OomSettings.DEFAULTS);
        await(() -> !views.isEmpty());

        system.fail(FakeSystemMetricsSource.Metric.MEMORY, new IllegalStateException("cannot read"));
        int reads = system.reads(FakeSystemMetricsSource.Metric.MEMORY);
        await(() -> system.reads(FakeSystemMetricsSource.Metric.MEMORY) >= reads + 3);
        system.recover(FakeSystemMetricsSource.Metric.MEMORY);
        await(() -> logged("has a memory reading again"));

        assertThat(activity.entries().stream().filter(entry -> entry.message().contains("has no memory reading")))
                .hasSize(1);
    }

    @Test
    void publishingEqualSettingsDoesNotChangeTheVersion() throws Exception {
        start(OomSignalStatus.VALIDATED, OomSettings.DEFAULTS);

        assertThat(runtime.oomSettings().version()).isEqualTo(1);
        assertThat(runtime.publishOomSettings(
                        new OomSettings(OomSettings.DEFAULTS.thresholds(), new OomSwitches(true, false, false, false))))
                .isEqualTo(1);
        assertThat(runtime.publishOomSettings(switches(false, true, false))).isEqualTo(2);
        assertThat(runtime.publishOomSettings(switches(false, true, false))).isEqualTo(2);
        assertThat(activity.entries().stream()
                        .filter(entry -> entry.message().startsWith("OOM Guard settings changed")))
                .hasSize(1);
    }

    @Test
    void allSwitchesOnAnUnvalidatedSystemMakeNoNativeCall() throws Exception {
        start(OomSignalStatus.UNVALIDATED, switches(true, true, true));
        manage(DEMO, EmergencyAction.SUSPEND);
        manage(OTHER, EmergencyAction.TERMINATE);

        available(1);
        await(() -> views.stream()
                .anyMatch(view -> view.state() == prism.srm.domain.OomState.EMERGENCY
                        && view.candidates().size() == 2));
        Thread.sleep(1_500);

        assertThat(controller.calls)
                .noneMatch(call ->
                        call.startsWith("suspend") || call.startsWith("terminate") || call.startsWith("priority"));
        assertThat(views.get(views.size() - 1).candidates())
                .allSatisfy(candidate -> assertThat(candidate.effective().notInEffect())
                        .contains("not available on this system: memory signals are not validated"));
    }

    @Test
    void shutdownReleasesOomHoldsButNotTheUsersAndRestoresPriorities() throws Exception {
        start(OomSignalStatus.VALIDATED, switches(true, true, false));
        manage(DEMO, EmergencyAction.SUSPEND);
        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, OTHER, "other"));
        await(() -> controller.calls.contains("suspend 5555"));

        available(7);
        await(() -> controller.calls.contains("priority 4242 LOW"));
        processes.list(List.of(
                FakeProcessSource.listedReading(DEMO, 100, 200L << 20, 4),
                FakeProcessSource.listedReading(OTHER, 100, 100L << 20, 8)));
        available(3);
        await(() -> controller.calls.contains("suspend 4242"));

        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(controller.calls).contains("resume 4242", "priority 4242 NORMAL");
        assertThat(controller.calls).doesNotContain("resume 5555");
        int resumed = controller.calls.indexOf("resume 4242");
        int restored = controller.calls.indexOf("priority 4242 NORMAL");
        assertThat(resumed).as("resume, then restore").isLessThan(restored);
        runtime = null;
    }

    @Test
    void shutdownRestoresPrioritiesWithinItsBoundAndLogsWhatItCouldNot() throws Exception {
        start(OomSignalStatus.VALIDATED, switches(true, false, false));
        manage(DEMO, EmergencyAction.LOWER_PRIORITY);
        manage(OTHER, EmergencyAction.LOWER_PRIORITY);
        available(7);
        await(() -> controller.calls.contains("priority 4242 LOW") && controller.calls.contains("priority 5555 LOW"));
        processes.list(List.of(
                FakeProcessSource.listedReading(DEMO, 100, 200L << 20, 4),
                FakeProcessSource.listedReading(OTHER, 100, 100L << 20, 4)));
        int listings = processes.listings();
        await(() -> processes.listings() >= listings + 2);
        controller.priority = (target, level) -> {
            actingThreads.add(Thread.currentThread().getName());
            try {
                // Longer than the bound of the restore: the second process is left lowered.
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ControlOutcome.success("late");
        };
        long started = System.nanoTime();

        runtime.shutdown();
        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(controller.calls.stream().filter(call -> call.endsWith(" NORMAL")))
                .hasSize(1);
        assertThat(logged("left the priority of demo (PID")).isTrue();
        assertThat(actingThreads.get(actingThreads.size() - 1)).isEqualTo("srm-shutdown");
        runtime = null;
    }

    @Test
    void theExitHookStopsOomGuardAndDoesNotRestorePriorities() throws Exception {
        start(OomSignalStatus.VALIDATED, switches(true, true, false));
        manage(DEMO, EmergencyAction.SUSPEND);
        available(7);
        await(() -> controller.calls.contains("priority 4242 LOW"));
        available(3);
        await(() -> controller.calls.contains("suspend 4242"));

        runtime.releaseForExit();

        assertThat(controller.calls).contains("resume 4242").doesNotContain("priority 4242 NORMAL");
        int calls = controller.calls.size();
        Thread.sleep(1_500);
        assertThat(controller.calls).as("nothing after the hook").hasSize(calls);
    }

    @Test
    void whenSystemMonitoringEndsTheViewSaysSoAndTheHoldsStay() throws Exception {
        start(OomSignalStatus.VALIDATED, switches(false, true, false));
        manage(DEMO, EmergencyAction.SUSPEND);
        available(3);
        await(() -> controller.calls.contains("suspend 4242"));

        system.fail(FakeSystemMetricsSource.Metric.CPU, new AssertionError("an Error ends the loop"));
        await(() -> views.stream().anyMatch(OomView::monitorEnded));

        assertThat(controller.calls).doesNotContain("resume 4242");
        uncaught.clear();
    }

    @Test
    void noNewThreadIsStarted() throws Exception {
        start(OomSignalStatus.VALIDATED, switches(true, true, true));
        manage(DEMO, EmergencyAction.SUSPEND);
        available(3);
        await(() -> controller.calls.contains("suspend 4242"));

        assertThat(prismThreads())
                .allMatch(name -> Set.of(
                                "srm-startup",
                                "srm-system-monitor",
                                "srm-process-monitor",
                                "srm-process-details",
                                "srm-process-control",
                                "srm-limiter",
                                "srm-journal",
                                "srm-recovery",
                                "srm-shutdown")
                        .contains(name));
    }

    @Test
    void theSettingsFileIsWrittenOnTheJournalThread() throws Exception {
        start(OomSignalStatus.VALIDATED, OomSettings.DEFAULTS);
        await(() -> !views.isEmpty());
        Path folder = journalFolder.path().resolve("settings");
        prism.srm.application.SettingsStore store = new prism.srm.application.SettingsStore(
                folder,
                prism.srm.application.SuspensionJournal.onDisk(),
                activity,
                Instant::now,
                runtime::executeOnJournalThread);
        List<String> writer = new CopyOnWriteArrayList<>();
        runtime.executeOnJournalThread(() -> writer.add(Thread.currentThread().getName()));

        store.save(prism.srm.domain.AppSettings.DEFAULTS);
        await(() -> Files.exists(folder.resolve("prism.settings")));

        assertThat(writer).containsExactly("srm-journal");
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(Path::getFileName).map(Path::toString)).containsExactly("prism.settings");
        }
        assertThat(activity.entries())
                .noneMatch(entry -> entry.severity() == ActivitySeverity.WARNING
                        && entry.message().contains("settings"));
    }

    /** O14 (a) end to end: the journal fails while OOM Guard holds a process; the hold is released. */
    @Test
    void aJournalFailureReleasesWhatOomGuardHolds() throws Exception {
        long begun = System.nanoTime();
        processes.onCpuTime =
                identity -> new prism.srm.domain.CpuTimeReading.Read((System.nanoTime() - begun) / 1_000_000 * 2);
        start(OomSignalStatus.VALIDATED, switches(false, true, false));
        manage(DEMO, EmergencyAction.SUSPEND);
        available(3);
        await(() -> controller.calls.contains("suspend 4242"));
        // An emulated CPU limit makes Prism check the journal on disk about once a second.
        assertThat(ask(LimitRequest.of(LimitAction.MANAGE, OTHER, "other"))
                        .outcome()
                        .succeeded())
                .isTrue();
        assertThat(ask(LimitRequest.cpuLimit(OTHER, "other", 50)).outcome().succeeded())
                .isTrue();

        Path journal = journalFolder.path().resolve("suspensions-9-9.journal");
        Files.writeString(journal, "not what Prism wrote");

        await(() -> controller.calls.contains("resume 4242"));
        await(() -> logged("The suspension journal cannot be written"));
        // OOM Guard publishes the view after the release and the log line, on its own thread.
        await(() -> views.get(views.size() - 1).availability().journal().isPresent());
    }

    /**
     * Plan A15.9 M10 pin test (ADR-0015 §2, gate item 0b): a memory sample is judged and handed to OOM
     * Guard under ONE settings snapshot, even when the settings change while the sample is being judged.
     *
     * <p>The settings are published by another thread (as the JavaFX thread would) right after the sample
     * took its snapshot, through the clock the sample reads next, and before the sample is judged. The
     * observation must still carry the version it was judged under (suspend off). OOM Guard then refuses
     * it as "settings changed; waiting for a sample" instead of treating an old judgment as one made
     * under the new settings. The next sample, judged under the new settings (suspend on), acts. A
     * listener that read the settings a second time for the version would tag the old judgment with
     * the new version: the refusal would never be shown, and the suspend would follow the old sample.
     */
    @Test
    void theListenerReadsSettingsOncePerSample() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger published = new AtomicInteger();
        List<Throwable> writerFailed = new CopyOnWriteArrayList<>();
        LongSupplier clock = () -> {
            if (armed.get()
                    && Thread.currentThread().getName().equals("srm-system-monitor")
                    && StackWalker.getInstance()
                            .walk(frames -> frames.anyMatch(
                                    frame -> frame.getMethodName().equals("oomSample")
                                            && frame.getClassName().equals(MonitoringRuntime.class.getName())))
                    && armed.compareAndSet(true, false)) {
                Thread writer = new Thread(
                        () -> {
                            try {
                                runtime.publishOomSettings(switches(false, true, false));
                            } catch (RuntimeException failure) {
                                writerFailed.add(failure);
                            }
                        },
                        "test-settings-writer");
                writer.start();
                try {
                    writer.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                published.incrementAndGet();
            }
            return System.nanoTime();
        };
        start(OomSignalStatus.VALIDATED, switches(false, false, false), clock);
        manage(DEMO, EmergencyAction.SUSPEND);
        available(4);
        await(() -> views.stream()
                .anyMatch(view -> view.state() == prism.srm.domain.OomState.EMERGENCY
                        && view.candidates().stream()
                                .anyMatch(candidate -> candidate
                                        .notNow()
                                        .filter(text -> text.contains("automatic suspend is off"))
                                        .isPresent())));
        long versionBefore = runtime.oomSettings().version();
        assertThat(controller.calls).noneMatch(call -> call.startsWith("suspend"));

        armed.set(true);
        await(() -> published.get() == 1);
        assertThat(writerFailed).isEmpty();
        assertThat(runtime.oomSettings().version()).isEqualTo(versionBefore + 1);

        // The sample that took its snapshot before the publication is refused for that reason.
        await(() -> views.stream()
                .flatMap(view -> view.candidates().stream())
                .anyMatch(candidate -> candidate.identity().equals(DEMO)
                        && candidate
                                .notNow()
                                .filter(text -> text.equals("not now: settings changed; waiting for a sample"))
                                .isPresent()));
        // The next sample is judged under the new settings and acts.
        await(() -> controller.calls.contains("suspend 4242"));
        assertThat(controller.calls.stream().filter(call -> call.startsWith("suspend")))
                .hasSize(1);
        assertThat(published.get())
                .as("the settings were published inside exactly one sample")
                .isEqualTo(1);
    }
}
