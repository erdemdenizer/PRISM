package prism.srm.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import prism.srm.application.OomStateMachine.Transition;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.Listed;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.MemorySample;
import prism.srm.domain.OomCandidateView;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.OomView;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;
import prism.srm.domain.VersionedSettings;

/**
 * OOM Guard on a virtual clock (plan A15.9): a fake controller that records every call, the real
 * suspension coordinator with a real journal in a folder of the test's own, settings published with
 * their versions as the runtime does, and a real state machine for tests that drive whole samples.
 * Every evaluation runs when the test advances the clock; nothing runs on a thread of its own.
 */
final class OomRig implements AutoCloseable {

    static final long TOTAL = 100_000_000_000L;
    static final long MIB = 1L << 20;
    static final Instant START = Instant.parse("2026-10-12T10:00:00Z");

    final VirtualScheduler scheduler = new VirtualScheduler();
    final FakeProcessController controller = new FakeProcessController();
    final ActivityLog activity = new ActivityLog(1_000, () -> START);
    final FaultyJournalFiles files = new FaultyJournalFiles();
    final SuspensionJournal journal;
    final SuspensionCoordinator coordinator;
    final List<OomView> views = new ArrayList<>();
    final AtomicInteger settingsReads = new AtomicInteger();
    /** Runs right after each read of the settings by OOM Guard, the value already taken: a test seam. */
    volatile Runnable afterSettingsRead = () -> {};

    final OomStateMachine machine = new OomStateMachine(OomStateMachine.Tuning.DEFAULTS);
    private final AtomicReference<VersionedSettings> settings =
            new AtomicReference<>(new VersionedSettings(1, OomSettings.DEFAULTS));
    private final Map<ProcessIdentity, ManagedProcess> managed = new java.util.LinkedHashMap<>();
    private final Map<ProcessIdentity, OptionalLong> watched = new HashMap<>();
    private final Map<ProcessIdentity, Long> generations = new HashMap<>();
    final OomGuard guard;

    OomRig(Path folder) {
        this(folder, Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
    }

    OomRig(Path folder, Restorability restorability, OomSignalStatus signal) {
        controller.restorability = restorability;
        journal = SuspensionJournal.open(folder, "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> START, Optional.of(journal));
        guard = new OomGuard(
                controller,
                new ProtectionRules(1),
                coordinator,
                activity,
                scheduler,
                scheduler.clock(),
                () -> START,
                () -> {
                    settingsReads.incrementAndGet();
                    VersionedSettings read = settings.get();
                    afterSettingsRead.run();
                    return read;
                },
                views::add,
                OomGuard.Tuning.DEFAULTS,
                signal);
    }

    static ProcessIdentity process(long pid) {
        return new ProcessIdentity(pid, START.minusSeconds(pid));
    }

    static OomManagement oom(EmergencyAction action) {
        return new OomManagement(true, OomPriority.NORMAL, action);
    }

    // ----- Settings, as the runtime publishes them -----

    VersionedSettings settings() {
        return settings.get();
    }

    /** Publishes changed settings, then tells the guard, as the runtime does: set first, then notify. */
    long publish(OomSettings next) {
        VersionedSettings now = guard.publishSettings(() -> settings.updateAndGet(previous ->
                previous.settings().equals(next) ? previous : new VersionedSettings(previous.version() + 1, next)));
        guard.settingsChanged();
        return now.version();
    }

    long switches(boolean priority, boolean suspend, boolean terminate) {
        return publish(settings.get().settings().withSwitches(new OomSwitches(true, priority, suspend, terminate)));
    }

    long monitoring(boolean on) {
        OomSettings current = settings.get().settings();
        return publish(current.withSwitches(current.switches().withMonitoring(on)));
    }

    long thresholds(OomThresholds thresholds) {
        return publish(settings.get().settings().withThresholds(thresholds));
    }

    // ----- The managed processes, as the limit service pushes them -----

    void manage(ProcessIdentity identity, OomManagement oom) {
        String name = "p" + identity.pid();
        managed.put(identity, new ManagedProcess(identity, name, ManagedSettings.NONE.withOom(oom)));
        push();
    }

    void watch(ProcessIdentity identity, long limit) {
        watched.put(identity, OptionalLong.of(limit));
        generations.merge(identity, 1L, Long::sum);
        push();
    }

    void unwatch(ProcessIdentity identity) {
        watched.remove(identity);
        push();
    }

    void unmanage(ProcessIdentity identity) {
        managed.remove(identity);
        watched.remove(identity);
        push();
    }

    void push() {
        List<OomGuard.Managed> list = new ArrayList<>();
        for (ManagedProcess process : managed.values()) {
            list.add(new OomGuard.Managed(
                    process,
                    watched.getOrDefault(process.identity(), OptionalLong.empty()),
                    generations.getOrDefault(process.identity(), 0L)));
        }
        guard.managedChanged(list);
    }

    // ----- Observations -----

    /** One sample a second later through the real state machine, with this share available; evaluated. */
    Transition sample(double percentAvailable) {
        scheduler.elapse(Duration.ofSeconds(1));
        long available = Math.round(TOTAL * percentAvailable / 100);
        return feed(Optional.of(new MemorySample(TOTAL, available, Optional.empty())));
    }

    /** A sample that could not be read, a second later; evaluated. */
    Transition missing() {
        scheduler.elapse(Duration.ofSeconds(1));
        return feed(Optional.empty());
    }

    private Transition feed(Optional<MemorySample> sample) {
        VersionedSettings v = settings.get();
        Transition t = machine.observe(sample, scheduler.now(), v.settings());
        guard.observed(OomObservation.of(t, v, scheduler.now()));
        run();
        return t;
    }

    /** These samples, one a second; returns the last transition. */
    Transition samples(double... percents) {
        Transition last = null;
        for (double percent : percents) {
            last = sample(percent);
        }
        return last;
    }

    /** An observation made by hand, now, under the version given; not evaluated yet. */
    void observed(OomState state, double percentAvailable, Transition.Kind kind, long version) {
        guard.observed(observation(state, percentAvailable, kind, version, scheduler.now()));
    }

    OomObservation observation(OomState state, double percentAvailable, Transition.Kind kind, long version, long at) {
        long available = Math.round(TOTAL * percentAvailable / 100);
        Optional<OomReading> reading = kind == Transition.Kind.HOLD || kind == Transition.Kind.OFF
                ? Optional.empty()
                : Optional.of(new OomReading(available, TOTAL, at));
        return new OomObservation(state, reading, kind, kind != Transition.Kind.OFF, 1, version, at);
    }

    // ----- Listings -----

    /** A listing of these processes, each with its resident memory and its listed priority number. */
    void listed(Object... identityResidentPriority) {
        Map<ProcessIdentity, Listed> map = new HashMap<>();
        for (int i = 0; i < identityResidentPriority.length; i += 3) {
            ProcessIdentity identity = (ProcessIdentity) identityResidentPriority[i];
            long resident = ((Number) identityResidentPriority[i + 1]).longValue();
            int priority = ((Number) identityResidentPriority[i + 2]).intValue();
            map.put(identity, new Listed(OptionalLong.of(resident), OptionalInt.of(priority)));
        }
        guard.listed(map);
    }

    /** Runs whatever is due now. */
    void run() {
        scheduler.advance(Duration.ZERO);
    }

    // ----- What came of it -----

    OomView view() {
        return views.get(views.size() - 1);
    }

    OomCandidateView candidate(ProcessIdentity identity) {
        return view().candidates().stream()
                .filter(candidate -> candidate.identity().equals(identity))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not a candidate: " + identity));
    }

    OomHold hold(ProcessIdentity identity) {
        return candidate(identity).hold();
    }

    Optional<String> notNow(ProcessIdentity identity) {
        return candidate(identity).notNow();
    }

    /** The calls that change a process, in order: lowering, suspension, termination and resumption. */
    List<String> actions() {
        return controller.calls.stream()
                .filter(call -> call.startsWith("priority ")
                        || call.startsWith("suspend ")
                        || call.startsWith("terminate ")
                        || call.startsWith("resume "))
                .toList();
    }

    /** Every call, the reads of protection facts included. */
    List<String> calls() {
        return List.copyOf(controller.calls);
    }

    long count(String call) {
        return controller.calls.stream().filter(call::equals).count();
    }

    List<ActivityEntry> log(ActivitySeverity severity) {
        return activity.entries().stream()
                .filter(entry -> entry.severity() == severity)
                .toList();
    }

    List<JournalEntry> journaled() throws Exception {
        if (!Files.exists(journal.file())) {
            return List.of();
        }
        return SuspensionJournal.decode(Files.readAllBytes(journal.file())).entries();
    }

    @Override
    public void close() {
        journal.close();
    }
}
