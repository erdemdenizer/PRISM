package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.OomStateMachine.Transition.Kind;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.OomCandidateView;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.VersionedSettings;

/**
 * The limit service with OOM Guard (milestone 11): the OOM settings of a managed process, what it
 * pushes to the guard, and the memory limit that is watched, never capped, where the system has no
 * native one or the user chose it after the native one failed (LIM-9, O9, CAP-3, O17b).
 */
class LimitServiceOomTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-12T10:00:00Z"));
    private static final long MIB = 1L << 20;
    private static final LimitCapability NO_NATIVE = LimitCapability.unavailable("no native limiter on this system");

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final VirtualScheduler scheduler = new VirtualScheduler();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeProcessSource source = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(500, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();
    private final List<LimitResult> results = new ArrayList<>();
    private final List<LimitsSnapshot> snapshots = new ArrayList<>();
    private final List<OomView> views = new ArrayList<>();
    private final AtomicReference<VersionedSettings> settings =
            new AtomicReference<>(new VersionedSettings(1, OomSettings.DEFAULTS));
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private OomGuard guard;
    private LimitService service;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
        new SimulatedTarget(scheduler, 8, 1.0).wire(controller, source);
    }

    @AfterEach
    void closeJournal() {
        journal.close();
    }

    /** A system without native limits (Linux, macOS in this version), or with Job Objects (Windows). */
    private void build(boolean nativeLimits, OomSignalStatus signal) {
        if (!nativeLimits) {
            limiter.system = Map.of(LimitKind.CPU, NO_NATIVE, LimitKind.MEMORY, NO_NATIVE);
            limiter.leftLimits = false;
        }
        guard = new OomGuard(
                controller,
                new ProtectionRules(1),
                coordinator,
                activity,
                scheduler,
                scheduler.clock(),
                () -> Instant.EPOCH,
                settings::get,
                views::add,
                OomGuard.Tuning.DEFAULTS,
                signal);
        service = new LimitService(
                controller,
                new ProtectionRules(1),
                coordinator,
                source,
                activity,
                scheduler,
                scheduler.clock(),
                8,
                DutyCycleController.Tuning.DEFAULTS,
                new NativeLimitController(
                        limiter,
                        nativeLimits
                                ? new NativeLimitLedger(Optional.of(journalFolder.path()), "1-1", files, Runnable::run)
                                : NativeLimitLedger.none(),
                        EarlierLimits.NONE),
                Optional.of(guard),
                results::add,
                snapshots::add);
    }

    private LimitResult ask(LimitRequest request) {
        int before = results.size();
        service.request(request);
        scheduler.advance(Duration.ofMillis(1));
        assertThat(results).hasSize(before + 1);
        return results.get(results.size() - 1);
    }

    private LimitResult manage() {
        return ask(LimitRequest.of(LimitAction.MANAGE, DEMO, "demo"));
    }

    private ManagedProcessView view() {
        return service.snapshot().find(DEMO).orElseThrow();
    }

    private Optional<OomCandidateView> candidate() {
        return views.get(views.size() - 1).candidates().stream()
                .filter(candidate -> candidate.identity().equals(DEMO))
                .findFirst();
    }

    private void switches(boolean priority, boolean suspend, boolean terminate) {
        settings.updateAndGet(previous -> new VersionedSettings(
                previous.version() + 1,
                previous.settings().withSwitches(new OomSwitches(true, priority, suspend, terminate))));
        guard.settingsChanged();
        scheduler.advance(Duration.ZERO);
    }

    // ----- OOM settings of a process -----

    @Test
    void theOomSettingsOfAManagedProcessAreKeptAndHandedToOomGuard() {
        build(true, OomSignalStatus.VALIDATED);
        manage();
        OomManagement chosen = new OomManagement(true, OomPriority.FIRST, EmergencyAction.NOTIFY);

        LimitResult set = ask(LimitRequest.oom(DEMO, "demo", chosen));

        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.text()).contains("demo (PID 4242) is under OOM management: Notify only, first priority.");
        assertThat(view().process().settings().oom()).isEqualTo(chosen);
        assertThat(candidate().orElseThrow().oom()).isEqualTo(chosen);
    }

    @Test
    void terminateChosenWhileTheSwitchIsOffIsKeptAndSaidToBeNotInEffect() {
        build(true, OomSignalStatus.VALIDATED);
        manage();

        LimitResult set = ask(
                LimitRequest.oom(DEMO, "demo", new OomManagement(true, OomPriority.NORMAL, EmergencyAction.TERMINATE)));

        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.text()).contains("Terminate (force kill)", "Not in effect: emergency termination is off");
        assertThat(view().process().settings().oom().action()).isEqualTo(EmergencyAction.TERMINATE);
        assertThat(candidate().orElseThrow().effective().action()).isEqualTo(EmergencyAction.NOTIFY);
    }

    @Test
    void oomSettingsOfAProcessNotManagedAreRefused() {
        build(true, OomSignalStatus.VALIDATED);

        LimitResult set = ask(LimitRequest.oom(DEMO, "demo", OomManagement.ON));

        assertThat(set.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(service.snapshot().processes()).isEmpty();
    }

    @Test
    void aProtectedProcessCannotBeManagedSoOomGuardNeverSeesIt() {
        build(true, OomSignalStatus.VALIDATED);
        controller.facts = identity -> new ProtectionFacts(false, Optional.of(true), false);

        assertThat(manage().outcome().result()).isEqualTo(OperationResult.ACCESS_DENIED);
        assertThat(ask(LimitRequest.oom(DEMO, "demo", OomManagement.ON))
                        .outcome()
                        .succeeded())
                .isFalse();
        scheduler.advance(Duration.ZERO);
        assertThat(views).allSatisfy(view -> assertThat(view.candidates()).isEmpty());
    }

    @Test
    void unmanagingAndExitingTakeTheProcessAwayFromOomGuard() {
        build(true, OomSignalStatus.VALIDATED);
        manage();
        assertThat(candidate()).isPresent();
        ask(LimitRequest.of(LimitAction.UNMANAGE, DEMO, "demo"));
        assertThat(candidate()).isEmpty();

        manage();
        assertThat(candidate()).isPresent();
        controller.liveness = identity -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        service.listed(java.util.Set.of());
        scheduler.advance(Duration.ofMillis(1));
        assertThat(candidate()).isEmpty();
    }

    // ----- The watched memory limit (LIM-9) -----

    @Test
    void withoutANativeMemoryLimitTheLimitIsWatchedNeverCapped() {
        build(false, OomSignalStatus.VALIDATED);
        assertThat(service.memoryCapability())
                .isEqualTo(LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.RESIDENT_POLICY));
        manage();

        LimitResult set = ask(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB));

        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.text()).contains("watched against 256 MiB", "Not a cap").doesNotContain("may commit");
        assertThat(view().memory().inEffect().mechanism()).contains(ControlMechanism.RESIDENT_POLICY);
        assertThat(view().memory().inEffect().level()).isEqualTo(CapabilityLevel.EMULATED);
        assertThat(view().memory().status()).isEqualTo(LimitStatus.ACTIVE_NOT_THROTTLING);
        assertThat(view().memory().detail().orElseThrow()).startsWith("watched, not a cap");
        assertThat(candidate().orElseThrow().memoryLimitBytes()).hasValue(256 * MIB);
    }

    @Test
    void aMemoryLimitIsUnsupportedOnAnUnvalidatedSystem() {
        build(false, OomSignalStatus.UNVALIDATED);
        assertThat(service.memoryCapability().available()).isFalse();
        assertThat(service.memoryCapability().reason())
                .contains("not available on this system: memory signals are not validated");
        manage();

        LimitResult set = ask(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB));

        assertThat(set.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(set.text()).contains("memory signals are not validated");
        assertThat(view().process().settings().memoryLimitBytes()).isEmpty();
        assertThat(candidate().orElseThrow().memoryLimitBytes()).isEmpty();
    }

    @Test
    void disablingStopsTheWatchAndEnablingStartsANewOneAndRemovingEndsIt() {
        build(false, OomSignalStatus.VALIDATED);
        manage();
        ask(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB));

        ask(LimitRequest.of(LimitAction.DISABLE, DEMO, "demo"));
        assertThat(candidate().orElseThrow().memoryLimitBytes()).isEmpty();
        assertThat(view().memory().status()).isEqualTo(LimitStatus.INACTIVE);
        ask(LimitRequest.of(LimitAction.ENABLE, DEMO, "demo"));
        assertThat(candidate().orElseThrow().memoryLimitBytes()).hasValue(256 * MIB);

        LimitResult removed = ask(LimitRequest.of(LimitAction.REMOVE_MEMORY_LIMIT, DEMO, "demo"));
        assertThat(removed.text()).contains("no longer watched");
        assertThat(candidate().orElseThrow().memoryLimitBytes()).isEmpty();
    }

    @Test
    void aWatchedLimitAboveWhichTheProcessIsListedIsShownExceededAndHeld() {
        build(false, OomSignalStatus.VALIDATED);
        manage();
        ask(LimitRequest.oom(DEMO, "demo", new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND)));
        switches(false, true, false);
        ask(LimitRequest.memoryLimit(DEMO, "demo", 100 * MIB));
        guard.observed(new OomObservation(
                OomState.NORMAL,
                Optional.of(new OomReading(50, 100, scheduler.now())),
                Kind.NONE,
                true,
                1,
                settings.get().version(),
                scheduler.now()));
        guard.listed(Map.of(
                DEMO, new prism.srm.domain.Listed(java.util.OptionalLong.of(101 * MIB), java.util.OptionalInt.of(20))));
        scheduler.advance(Duration.ZERO);
        service.listed(java.util.Set.of(DEMO));
        scheduler.advance(Duration.ofMillis(1));

        assertThat(controller.calls).contains("suspend 4242");
        assertThat(view().memory().status()).isEqualTo(LimitStatus.ACTIVE_ABOVE_TARGET);
        assertThat(view().memory().note()).isEqualTo(LimitNote.POLICY_HOLD_USER_ENDS);
    }

    @Test
    void whereTheNativeMemoryLimitIsNotAppliedTheWatchIsOfferedAndRunsOnlyAfterConsent() {
        build(true, OomSignalStatus.VALIDATED);
        manage();
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "in another job", true);

        LimitResult offered = ask(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB));

        assertThat(offered.outcome().succeeded()).isFalse();
        assertThat(offered.text()).contains("not applied", "offers to watch", "unless you accept");
        assertThat(view().residentOffer()).contains("in another job");
        assertThat(view().memory().note()).isEqualTo(LimitNote.FALLBACK_OFFERED);
        assertThat(candidate().orElseThrow().memoryLimitBytes())
                .as("nothing watched yet")
                .isEmpty();

        LimitResult accepted = ask(LimitRequest.of(LimitAction.ACCEPT_RESIDENT_POLICY, DEMO, "demo"));

        assertThat(accepted.outcome().succeeded()).isTrue();
        assertThat(accepted.text()).contains("watched against 256 MiB");
        assertThat(view().residentOffer()).isEmpty();
        assertThat(candidate().orElseThrow().memoryLimitBytes()).hasValue(256 * MIB);
        assertThat(view().memory().inEffect().mechanism()).contains(ControlMechanism.RESIDENT_POLICY);
    }

    @Test
    void acceptingWithoutAnOfferIsRefused() {
        build(true, OomSignalStatus.VALIDATED);
        manage();

        LimitResult accepted = ask(LimitRequest.of(LimitAction.ACCEPT_RESIDENT_POLICY, DEMO, "demo"));

        assertThat(accepted.outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(candidate().orElseThrow().memoryLimitBytes()).isEmpty();
    }

    @Test
    void aNativeMemoryLimitInForceIsNeverWatched() {
        build(true, OomSignalStatus.VALIDATED);
        manage();

        LimitResult set = ask(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB));

        assertThat(set.text()).contains("may commit at most 256 MiB");
        assertThat(view().memory().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
        assertThat(candidate().orElseThrow().memoryLimitBytes()).isEmpty();
    }

    @Test
    void withoutOomGuardTheMilestone10AnswerStands() {
        limiter.system = Map.of(LimitKind.CPU, NO_NATIVE, LimitKind.MEMORY, NO_NATIVE);
        service = new LimitService(
                controller,
                new ProtectionRules(1),
                coordinator,
                source,
                activity,
                scheduler,
                scheduler.clock(),
                8,
                results::add,
                snapshots::add);
        manage();

        assertThat(ask(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB))
                        .outcome()
                        .result())
                .isEqualTo(OperationResult.UNSUPPORTED);
    }

    @Test
    void anEmulatedCpuLimitWaitingForOomGuardSaysWhoHoldsTheProcess() {
        build(false, OomSignalStatus.VALIDATED);
        manage();
        ask(LimitRequest.oom(DEMO, "demo", new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND)));
        switches(false, true, false);
        ask(LimitRequest.cpuLimit(DEMO, "demo", 50));
        guard.listed(Map.of(
                DEMO, new prism.srm.domain.Listed(java.util.OptionalLong.of(10 * MIB), java.util.OptionalInt.of(20))));
        guard.observed(new OomObservation(
                OomState.EMERGENCY,
                Optional.of(new OomReading(4, 100, scheduler.now())),
                Kind.ENTERED,
                true,
                1,
                settings.get().version(),
                scheduler.now()));
        scheduler.advance(Duration.ZERO);
        assertThat(coordinator.heldFor(DEMO)).contains(prism.srm.domain.SuspensionReason.OOM_GUARD);

        scheduler.advance(Duration.ofSeconds(3));

        assertThat(view().cpu().status()).isEqualTo(LimitStatus.HELD_BY_OTHER);
        assertThat(view().cpu().detail().orElseThrow()).contains("suspended by OOM Guard");
    }
}
