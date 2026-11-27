package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.OomStateMachine.Transition.Kind;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsNotice;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.Listed;
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
import prism.srm.domain.SuspensionReason;
import prism.srm.domain.VersionedSettings;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.SuspendResult;

/**
 * Risk D6: the native part of every native limit operation runs on a worker of its own, so a native step that waits for
 * the system (an adoption waits for the systemd user manager for seconds) holds up neither the steps of an emulated limit
 * nor OOM Guard, which share the limiter thread. The worker here runs only when the test lets it; the limiter thread is
 * the virtual one.
 */
class LimitServiceNativeWorkTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-11-20T10:00:00Z"));
    private static final ProcessIdentity OTHER = new ProcessIdentity(5_151, Instant.parse("2026-11-20T10:00:01Z"));
    private static final ProcessIdentity THIRD = new ProcessIdentity(6_262, Instant.parse("2026-11-20T10:00:02Z"));
    private static final long MIB = 1L << 20;
    private static final LimitCapability NO_NATIVE = LimitCapability.unavailable("no native memory limit here");

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final VirtualScheduler scheduler = new VirtualScheduler();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeProcessSource source = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(500, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();
    /** The native-limit worker: it runs what it was handed only when the test says so. */
    private final QueuedExecutor worker = new QueuedExecutor();

    private final List<LimitResult> results = new ArrayList<>();
    private final List<LimitsSnapshot> snapshots = new ArrayList<>();
    private final List<OomView> views = new ArrayList<>();
    private final AtomicReference<VersionedSettings> settings =
            new AtomicReference<>(new VersionedSettings(1, OomSettings.DEFAULTS));
    /** What the fake assigns when asked: the default, enforced as asked. */
    private final BiFunction<ProcessIdentity, LimitSpec, ApplyResult> enforce = limiter.apply;

    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private NativeLimitLedger ledger;
    private SimulatedTarget target;
    /** The limiter thread as OOM Guard sees it; the same one unless a test makes it fail. */
    private LimiterScheduler guardThread = scheduler;

    private final AtomicBoolean failOnce = new AtomicBoolean();
    private OomGuard guard;
    private LimitService service;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
        ledger = new NativeLimitLedger(Optional.of(journalFolder.path()), "1-1", files, Runnable::run);
        target = new SimulatedTarget(scheduler, 8, 1.0).wire(controller, source);
        // Only DEMO is the simulated process; a suspension of another one (OOM Guard's) leaves it running.
        Function<ProcessIdentity, SuspendResult> simulated = controller.suspend;
        controller.suspend = identity -> identity.equals(DEMO)
                ? simulated.apply(identity)
                : SuspendResult.suspended(ControlOutcome.success(), controller.adopted(identity));
        Function<FakeProcessController.FakeSuspension, ControlOutcome> resumed = controller.resume;
        controller.resume = suspension -> {
            if (suspension.identity().equals(DEMO)) {
                return resumed.apply(suspension);
            }
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
    }

    @AfterEach
    void closeJournal() {
        journal.close();
    }

    private void build() {
        guard = new OomGuard(
                controller,
                new ProtectionRules(1),
                coordinator,
                activity,
                guardThread,
                scheduler.clock(),
                () -> Instant.EPOCH,
                settings::get,
                views::add,
                OomGuard.Tuning.DEFAULTS,
                OomSignalStatus.VALIDATED);
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
                new NativeLimitController(limiter, ledger, EarlierLimits.NONE),
                Optional.of(guard),
                worker,
                results::add,
                snapshots::add);
    }

    /** The limiter thread and the worker, until neither has anything left to do now. */
    private void settle() {
        scheduler.advance(Duration.ofMillis(1));
        while (worker.waiting() > 0) {
            worker.runAll();
            scheduler.advance(Duration.ofMillis(1));
        }
    }

    private LimitResult ask(LimitRequest request) {
        int before = results.size();
        service.request(request);
        settle();
        assertThat(results).hasSize(before + 1);
        return results.get(results.size() - 1);
    }

    /** Hands the request over and lets the limiter thread take it; the worker does not run. */
    private void send(LimitRequest request) {
        service.request(request);
        scheduler.advance(Duration.ofMillis(1));
    }

    private void manage(ProcessIdentity identity) {
        assertThat(ask(LimitRequest.of(LimitAction.MANAGE, identity, name(identity)))
                        .outcome()
                        .succeeded())
                .isTrue();
    }

    private static String name(ProcessIdentity identity) {
        return "p" + identity.pid();
    }

    private static LimitRequest cpu(ProcessIdentity identity, int percent) {
        return LimitRequest.cpuLimit(identity, name(identity), percent);
    }

    private ManagedProcessView view(ProcessIdentity identity) {
        return service.snapshot().find(identity).orElseThrow();
    }

    private Optional<OomCandidateView> candidate(ProcessIdentity identity) {
        return views.get(views.size() - 1).candidates().stream()
                .filter(candidate -> candidate.identity().equals(identity))
                .findFirst();
    }

    private List<String> nativeCalls() {
        return limiter.calls.stream()
                .filter(call -> call.startsWith("apply ") || call.startsWith("change ") || call.startsWith("forget "))
                .toList();
    }

    private void systemWithoutNativeMemory() {
        limiter.system = Map.of(LimitKind.CPU, FakeResourceLimiter.NATIVE, LimitKind.MEMORY, NO_NATIVE);
    }

    // ----- The limiter thread stays free -----

    @Test
    void anAdoptionInFlightDelaysNeitherOomGuardNorAnEmulatedLimit() {
        systemWithoutNativeMemory();
        // DEMO cannot be capped natively, so its user chose the emulated limit; the others can.
        limiter.apply = (identity, spec) -> identity.equals(DEMO)
                ? FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "in another job", true)
                        .apply(identity, spec)
                : enforce.apply(identity, spec);
        build();
        manage(DEMO);
        assertThat(ask(cpu(DEMO, 20)).fallback()).isPresent();
        assertThat(ask(LimitRequest.of(LimitAction.ACCEPT_EMULATED_CPU, DEMO, name(DEMO)))
                        .outcome()
                        .succeeded())
                .isTrue();
        manage(OTHER);
        manage(THIRD);
        ask(LimitRequest.oom(THIRD, name(THIRD), new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND)));
        settings.updateAndGet(previous -> new VersionedSettings(
                previous.version() + 1, previous.settings().withSwitches(new OomSwitches(true, false, true, false))));
        guard.settingsChanged();
        assertThat(ask(LimitRequest.memoryLimit(THIRD, name(THIRD), 100 * MIB))
                        .outcome()
                        .succeeded())
                .isTrue();
        scheduler.advance(Duration.ofSeconds(15));
        assertThat(service.limiting(DEMO)).isTrue();

        // The assignment for OTHER waits on the worker, as an adoption waits for the manager.
        int answered = results.size();
        send(cpu(OTHER, 30));
        assertThat(worker.waiting()).isEqualTo(1);
        guard.observed(new OomObservation(
                OomState.NORMAL,
                Optional.of(new OomReading(50, 100, scheduler.now())),
                Kind.NONE,
                true,
                1,
                settings.get().version(),
                scheduler.now()));
        guard.listed(Map.of(THIRD, new Listed(OptionalLong.of(101 * MIB), OptionalInt.of(20))));

        double used = target.useOver(Duration.ofSeconds(10));

        assertThat(used).as("the emulated limit keeps its schedule").isBetween(0.17, 0.23);
        assertThat(controller.calls).as("OOM Guard intervened meanwhile").contains("suspend 6262");
        assertThat(results).as("the assignment is not answered yet").hasSize(answered);
        assertThat(worker.waiting()).isEqualTo(1);
        assertThat(limiter.calls).noneMatch(call -> call.startsWith("apply 5151"));

        settle();

        assertThat(results).hasSize(answered + 1);
        assertThat(results.get(answered).request().identity()).isEqualTo(OTHER);
        assertThat(results.get(answered).outcome().succeeded()).isTrue();
        assertThat(ledger.find(OTHER).orElseThrow().state()).isEqualTo(LedgerState.APPLIED);
        assertThat(view(OTHER).cpu().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
    }

    // ----- One native operation per process, one at a time -----

    @Test
    void requestsForAProcessWhoseNativeStepIsInFlightRunAfterItInOrder() {
        build();
        manage(DEMO);
        int answered = results.size();

        send(cpu(DEMO, 20));
        send(cpu(DEMO, 30));

        assertThat(results)
                .as("both wait: the first for the worker, the second for the first")
                .hasSize(answered);
        assertThat(worker.waiting()).isEqualTo(1);
        assertThat(nativeCalls()).isEmpty();

        settle();

        assertThat(results).hasSize(answered + 2);
        assertThat(results.get(answered).text()).contains("20 %");
        assertThat(results.get(answered + 1).text()).contains("30 %");
        assertThat(results.subList(answered, answered + 2))
                .allMatch(result -> result.outcome().succeeded());
        assertThat(nativeCalls())
                .as("the second changes the job the first made, never assigns again")
                .containsExactly("apply 4242 CPU 20 %", "change 4242 CPU 30 %");
        assertThat(view(DEMO).cpu().detail().orElseThrow()).contains("enforced: CPU 30 %");
    }

    @Test
    void requestsWaitingForANativeStepStillCountAgainstTheBound() {
        build();
        manage(DEMO);
        int answered = results.size();
        send(cpu(DEMO, 20));

        for (int i = 1; i < LimitService.MOST_PENDING; i++) {
            service.request(cpu(DEMO, 20 + i % 10));
        }
        scheduler.advance(Duration.ofMillis(1));
        assertThat(results).hasSize(answered);
        service.request(cpu(DEMO, 50));

        assertThat(results).hasSize(answered + 1);
        assertThat(results.get(answered).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        assertThat(results.get(answered).text()).contains("Too many requests are waiting");

        settle();
        assertThat(results).hasSize(answered + 1 + LimitService.MOST_PENDING);
    }

    @Test
    void aRequestForAnotherProcessIsAnsweredWhileANativeStepIsInFlight() {
        build();
        manage(DEMO);
        int answered = results.size();
        send(cpu(DEMO, 20));
        assertThat(worker.waiting()).isEqualTo(1);

        send(LimitRequest.of(LimitAction.MANAGE, OTHER, name(OTHER)));
        send(LimitRequest.oom(OTHER, name(OTHER), OomManagement.ON));

        assertThat(results).hasSize(answered + 2);
        assertThat(results.subList(answered, answered + 2)).allSatisfy(result -> {
            assertThat(result.request().identity()).isEqualTo(OTHER);
            assertThat(result.outcome().succeeded()).isTrue();
        });
        assertThat(service.snapshot().find(OTHER)).isPresent();
        assertThat(worker.waiting()).as("DEMO's step still waits on the worker").isEqualTo(1);

        settle();

        assertThat(results).hasSize(answered + 3);
        assertThat(results.get(answered + 2).request().identity()).isEqualTo(DEMO);
        assertThat(results.get(answered + 2).outcome().succeeded()).isTrue();
    }

    @Test
    void nativeStepsRunOneAtATime() {
        build();
        manage(DEMO);
        manage(OTHER);
        int answered = results.size();

        send(cpu(DEMO, 20));
        send(cpu(OTHER, 30));

        assertThat(worker.waiting())
                .as("the second waits until the first has ended")
                .isEqualTo(1);
        worker.runNext();
        assertThat(nativeCalls()).containsExactly("apply 4242 CPU 20 %");
        assertThat(worker.waiting())
                .as("not handed over before the first is concluded")
                .isZero();

        scheduler.advance(Duration.ofMillis(1));

        assertThat(results).hasSize(answered + 1);
        assertThat(results.get(answered).request().identity()).isEqualTo(DEMO);
        assertThat(worker.waiting()).isEqualTo(1);

        settle();

        assertThat(nativeCalls()).containsExactly("apply 4242 CPU 20 %", "apply 5151 CPU 30 %");
        assertThat(results).hasSize(answered + 2);
        assertThat(results.get(answered + 1).request().identity()).isEqualTo(OTHER);
    }

    // ----- Exits -----

    @Test
    void anExitSeenDuringANativeStepIsHandledAfterIt() {
        build();
        manage(DEMO);
        int answered = results.size();
        send(cpu(DEMO, 20));
        controller.liveness = identity -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        long looks = controller.calls.stream().filter("liveness 4242"::equals).count();

        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));

        assertThat(service.snapshot().find(DEMO)).as("its step ends first").isPresent();
        assertThat(controller.calls.stream().filter("liveness 4242"::equals).count())
                .as("not even looked at while in flight")
                .isEqualTo(looks);

        settle();
        assertThat(results).hasSize(answered + 1);
        assertThat(results.get(answered).outcome().succeeded()).isTrue();
        assertThat(nativeCalls()).containsExactly("apply 4242 CPU 20 %");

        service.listed(Set.of());
        settle();

        assertThat(service.snapshot().find(DEMO)).isEmpty();
        assertThat(nativeCalls()).containsExactly("apply 4242 CPU 20 %", "forget 4242");
        assertThat(limiter.holds).isEmpty();
        assertThat(ledger.find(DEMO)).as("its job let go, its record goes").isEmpty();
    }

    @Test
    void aRunThatSeesItsProcessExitDuringANativeStepLetsItsJobGoAfterIt() {
        // A CPU limit that cannot be native runs emulated beside a native memory limit (plan D11, LIM-3).
        limiter.apply = (identity, spec) -> spec.cpuPercent().isPresent()
                ? FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "in another job", true)
                        .apply(identity, spec)
                : enforce.apply(identity, spec);
        build();
        manage(DEMO);
        ask(cpu(DEMO, 20));
        ask(LimitRequest.of(LimitAction.ACCEPT_EMULATED_CPU, DEMO, name(DEMO)));
        scheduler.advance(Duration.ofSeconds(3));
        assertThat(service.limiting(DEMO)).isTrue();
        int answered = results.size();
        limiter.calls.clear();

        send(LimitRequest.memoryLimit(DEMO, name(DEMO), 256 * MIB));
        source.onCpuTime = identity -> prism.srm.domain.CpuTimeReading.GONE;
        scheduler.advance(Duration.ofSeconds(3));

        assertThat(service.limiting(DEMO)).as("the run has ended").isFalse();
        assertThat(service.snapshot().find(DEMO))
                .as("its exit waits for the step")
                .isPresent();
        assertThat(nativeCalls()).isEmpty();

        settle();

        assertThat(results).hasSize(answered + 1);
        assertThat(nativeCalls()).containsExactly("apply 4242 memory 256 MiB", "forget 4242");
        assertThat(service.snapshot().find(DEMO)).isEmpty();
        assertThat(limiter.holds).isEmpty();
    }

    @Test
    void aResidueIsRetriedOnTheWorkerOncePerProcess() {
        build();
        manage(DEMO);
        ask(LimitRequest.memoryLimit(DEMO, name(DEMO), 256 * MIB));
        limiter.forget = identity -> new ForgetResult(
                ForgetResult.Outcome.RESIDUE,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "limits are left"),
                2,
                Optional.empty());
        controller.liveness = identity -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        service.listed(Set.of());
        settle();
        assertThat(service.snapshot().notices()).extracting(LimitsNotice::kind).contains(LimitsNotice.Kind.RESIDUE);
        assertThat(forgets()).isEqualTo(1);

        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));
        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));

        assertThat(worker.waiting()).as("one retry at a time for the process").isEqualTo(1);
        limiter.forget = identity -> {
            limiter.holds.remove(identity);
            return new ForgetResult(ForgetResult.Outcome.RELEASED, ControlOutcome.success(), 2, Optional.empty());
        };
        settle();

        assertThat(forgets()).isEqualTo(2);
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .doesNotContain(LimitsNotice.Kind.RESIDUE);
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message()).contains("PRISM cleared the limits the job of p4242"));
    }

    private long forgets() {
        return limiter.calls.stream().filter("forget 4242"::equals).count();
    }

    // ----- What is shown meanwhile -----

    @Test
    void whileInFlightTheRowShowsWhatWasInForceAndOomGuardActsOnNoMemoryThatMayBeLanding() {
        // OTHER's native memory limit is refused, so its user chose the watched resident memory instead (O9).
        limiter.apply =
                (identity, spec) -> identity.equals(OTHER) && spec.cpuPercent().isEmpty()
                        ? FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "in another job", true)
                                .apply(identity, spec)
                        : enforce.apply(identity, spec);
        build();
        manage(DEMO);
        manage(OTHER);
        suspendsWhenOverItsLimit(OTHER);
        ask(cpu(DEMO, 20));
        ask(LimitRequest.memoryLimit(OTHER, name(OTHER), 256 * MIB));
        assertThat(ask(LimitRequest.of(LimitAction.ACCEPT_RESIDENT_POLICY, OTHER, name(OTHER)))
                        .outcome()
                        .succeeded())
                .isTrue();
        assertThat(candidate(OTHER).orElseThrow().memoryLimitBytes()).hasValue(256 * MIB);

        send(cpu(DEMO, 30));
        // OTHER's CPU limit carries its memory limit, which may land natively now.
        send(cpu(OTHER, 20));
        listedAt(OTHER, 300 * MIB);

        assertThat(view(DEMO).cpu().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
        assertThat(view(DEMO).cpu().detail().orElseThrow())
                .as("what is known to be in force, not what was asked")
                .contains("enforced: CPU 20 %");
        assertThat(candidate(OTHER).orElseThrow().memoryLimitBytes())
                .as("the watch stays as it stands: neither dropped nor armed again")
                .hasValue(256 * MIB);
        assertThat(crossings(OTHER))
                .as("a native memory limit may be landing: nothing crosses")
                .isZero();
        assertThat(controller.calls).doesNotContain("suspend 5151");

        settle();
        listedAt(OTHER, 300 * MIB);

        assertThat(view(DEMO).cpu().detail().orElseThrow()).contains("enforced: CPU 30 %");
        assertThat(view(OTHER).memory().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
        assertThat(candidate(OTHER).orElseThrow().memoryLimitBytes())
                .as("the native memory limit is in force now: the watch ends")
                .isEmpty();
        assertThat(crossings(OTHER)).isZero();
        assertThat(controller.calls).doesNotContain("suspend 5151");
    }

    /**
     * Review S1: a native step that sets no memory limit leaves OOM Guard's watch alone. A process over its watched
     * limit was acted on once and resumed by the user; its CPU limit then goes through the worker, and on Linux the
     * adoption shows an uncertain hold midway, while the limiter thread tells OOM Guard about another request. No
     * second warning and no second action follow: only a new crossing acts again.
     */
    @Test
    void aNativeStepThatSetsNoMemoryNeitherDropsNorArmsTheWatchAgain() {
        systemWithoutNativeMemory();
        build();
        manage(THIRD);
        suspendsWhenOverItsLimit(THIRD);
        assertThat(ask(LimitRequest.memoryLimit(THIRD, name(THIRD), 100 * MIB))
                        .outcome()
                        .succeeded())
                .isTrue();
        listedAt(THIRD, 101 * MIB);
        assertThat(suspends(THIRD)).isEqualTo(1);
        assertThat(crossings(THIRD)).isEqualTo(1);
        guard.releaseHold(THIRD);
        scheduler.advance(Duration.ofMillis(1));
        listedAt(THIRD, 101 * MIB);
        assertThat(suspends(THIRD)).as("the user resumed it; no new crossing").isEqualTo(1);
        limiter.apply = (identity, spec) -> {
            // Midway through an adoption, as the Linux limiter shows it without its lock: uncertain.
            limiter.holds.put(identity, new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
            service.request(LimitRequest.of(LimitAction.MANAGE, OTHER, name(OTHER)));
            listedAt(THIRD, 101 * MIB);
            return enforce.apply(identity, spec);
        };

        send(cpu(THIRD, 30));
        settle();
        listedAt(THIRD, 101 * MIB);
        listedAt(THIRD, 101 * MIB);

        assertThat(results.stream()
                        .filter(result -> result.request().action() == LimitAction.SET_CPU_LIMIT)
                        .findFirst()
                        .orElseThrow()
                        .outcome()
                        .succeeded())
                .isTrue();
        assertThat(service.snapshot().find(OTHER))
                .as("the request midway was carried out")
                .isPresent();
        assertThat(candidate(THIRD).orElseThrow().memoryLimitBytes()).hasValue(100 * MIB);
        assertThat(crossings(THIRD)).as("no second warning").isEqualTo(1);
        assertThat(suspends(THIRD)).as("no second action").isEqualTo(1);
    }

    /**
     * Review S2: OOM Guard suspends a process while its native CPU limit is on the worker. The barrier of plan D15 is
     * checked again once the step has ended: the cap is withdrawn, through the worker, and the request is answered
     * with the refusal, never as a success.
     */
    @Test
    void aCapThatLandsWhileOomGuardTookTheProcessIsWithdrawnAndRefused() {
        systemWithoutNativeMemory();
        build();
        manage(THIRD);
        suspendsWhenOverItsLimit(THIRD);
        ask(LimitRequest.memoryLimit(THIRD, name(THIRD), 100 * MIB));
        int answered = results.size();
        send(cpu(THIRD, 30));
        assertThat(worker.waiting()).isEqualTo(1);

        listedAt(THIRD, 101 * MIB);
        assertThat(coordinator.heldFor(THIRD)).contains(SuspensionReason.OOM_GUARD);
        settle();

        assertThat(results).hasSize(answered + 1);
        LimitResult refused = results.get(answered);
        assertThat(refused.outcome().succeeded()).isFalse();
        assertThat(refused.outcome().detail()).contains("held by OOM Guard");
        assertThat(nativeCalls())
                .as("set, then withdrawn to what was held before: nothing")
                .containsExactly("apply 6262 CPU 30 %", "change 6262 no limit");
        assertThat(limiter.holds.get(THIRD).inForce()).contains(EffectiveLimits.NONE);
        assertThat(view(THIRD).process().settings().cpuLimitPercent())
                .as("not stored as set")
                .isEmpty();
        assertThat(view(THIRD).cpu().status()).isNotEqualTo(LimitStatus.ACTIVE_ENFORCED);
    }

    /** Review S2: an assignment refused while OOM Guard took the process starts no emulated limit and offers none. */
    @Test
    void aRefusedAssignmentWhileOomGuardTookTheProcessStartsNothing() {
        systemWithoutNativeMemory();
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "in another job", true);
        build();
        manage(THIRD);
        suspendsWhenOverItsLimit(THIRD);
        ask(LimitRequest.memoryLimit(THIRD, name(THIRD), 100 * MIB));
        int answered = results.size();
        send(cpu(THIRD, 30));
        listedAt(THIRD, 101 * MIB);

        settle();

        LimitResult refused = results.get(answered);
        assertThat(refused.outcome().detail()).contains("held by OOM Guard");
        assertThat(refused.fallback()).as("no emulated limit offered").isEmpty();
        assertThat(service.limiting(THIRD)).isFalse();
        assertThat(view(THIRD).process().settings().cpuLimitPercent()).isEmpty();
    }

    /** Review C1: the journal fails while the assignment waits for the worker; nothing is recorded or asked. */
    @Test
    void aJournalThatFailsWhileTheStepWaitsKeepsItFromBeingAsked() {
        build();
        manage(DEMO);
        int answered = results.size();
        send(cpu(DEMO, 20));
        assertThat(worker.waiting()).isEqualTo(1);
        files.locksLost = true;
        assertThat(coordinator.verifyJournal()).isFalse();
        assertThat(coordinator.journalHealthy()).isFalse();

        settle();

        assertThat(results).hasSize(answered + 1);
        assertThat(results.get(answered).outcome().succeeded()).isFalse();
        assertThat(results.get(answered).outcome().detail().orElseThrow()).contains("suspension journal");
        assertThat(nativeCalls()).isEmpty();
        assertThat(ledger.find(DEMO)).isEmpty();
    }

    /**
     * Review C2: a failure while an operation is concluded (here OOM Guard's thread refusing once, unexpectedly) is
     * logged; its request is answered, what waited for it runs, and the next operation is handed over.
     */
    @Test
    void aFailureWhileConcludingLosesNoWaitingWork() {
        guardThread = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("injected");
                }
                scheduler.execute(task);
            }

            @Override
            public Future<?> schedule(Runnable task, Duration delay) {
                return scheduler.schedule(task, delay);
            }
        };
        build();
        manage(DEMO);
        manage(OTHER);
        int answered = results.size();
        send(cpu(DEMO, 20));
        send(cpu(DEMO, 30));
        send(cpu(OTHER, 40));
        worker.runNext();
        failOnce.set(true);

        settle();

        assertThat(failOnce).as("the failure was injected").isFalse();
        assertThat(results).hasSize(answered + 3);
        assertThat(results.subList(answered, answered + 3))
                .allMatch(result -> result.outcome().succeeded());
        assertThat(nativeCalls())
                .containsExactlyInAnyOrder("apply 4242 CPU 20 %", "change 4242 CPU 30 %", "apply 5151 CPU 40 %");
        assertThat(results.get(answered).request().percent()).hasValue(20);
        // Nothing leaked: as many requests as ever may wait.
        for (int i = 0; i < LimitService.MOST_PENDING; i++) {
            service.request(LimitRequest.oom(OTHER, name(OTHER), OomManagement.ON));
        }
        settle();
        assertThat(results.subList(answered + 3, results.size()))
                .hasSize(LimitService.MOST_PENDING)
                .allMatch(result -> result.outcome().succeeded());
    }

    private void suspendsWhenOverItsLimit(ProcessIdentity identity) {
        ask(LimitRequest.oom(
                identity, name(identity), new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND)));
        settings.updateAndGet(previous -> new VersionedSettings(
                previous.version() + 1, previous.settings().withSwitches(new OomSwitches(true, false, true, false))));
        guard.settingsChanged();
        scheduler.advance(Duration.ofMillis(1));
    }

    /** A memory sample in the normal state, then a listing that shows the process with this resident memory. */
    private void listedAt(ProcessIdentity identity, long resident) {
        guard.observed(new OomObservation(
                OomState.NORMAL,
                Optional.of(new OomReading(50, 100, scheduler.now())),
                Kind.NONE,
                true,
                1,
                settings.get().version(),
                scheduler.now()));
        guard.listed(Map.of(identity, new Listed(OptionalLong.of(resident), OptionalInt.of(20))));
        scheduler.advance(Duration.ofMillis(1));
    }

    private long crossings(ProcessIdentity identity) {
        return activity.entries().stream()
                .filter(entry -> entry.message().startsWith(name(identity) + " (PID " + identity.pid() + ") uses")
                        && entry.message().contains("above its watched limit"))
                .count();
    }

    private long suspends(ProcessIdentity identity) {
        return controller.calls.stream()
                .filter(("suspend " + identity.pid())::equals)
                .count();
    }

    // ----- Shutdown and failures -----

    @Test
    void atShutdownAStepNotHandedOverIsAnsweredClosingAndNothingIsAsked() {
        build();
        manage(DEMO);
        manage(OTHER);
        int answered = results.size();
        send(cpu(DEMO, 20));
        send(cpu(DEMO, 30));
        send(cpu(OTHER, 30));

        service.stopForShutdown();
        settle();

        assertThat(results).hasSize(answered + 3);
        assertThat(results.get(answered).request().percent()).hasValue(20);
        assertThat(results.get(answered).outcome().succeeded())
                .as("the step in flight ends")
                .isTrue();
        assertThat(results.subList(answered + 1, answered + 3)).allSatisfy(result -> {
            assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
            assertThat(result.outcome().detail()).contains("shutting down");
        });
        assertThat(nativeCalls()).containsExactly("apply 4242 CPU 20 %");
    }

    @Test
    void aWorkerThatRefusesLeavesNothingAskedAndAnswersEveryWaitingRequest() {
        build();
        manage(DEMO);
        int answered = results.size();
        worker.refusing = true;

        send(cpu(DEMO, 20));

        assertThat(results).hasSize(answered + 1);
        assertThat(results.get(answered).outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(nativeCalls()).isEmpty();
        assertThat(ledger.find(DEMO)).isEmpty();
        assertThat(view(DEMO).process().settings().cpuLimitPercent()).isEmpty();
    }

    @Test
    void aStepEndingAfterTheLimiterStoppedLeavesItsRecordInTheLedger() {
        build();
        manage(DEMO);
        int answered = results.size();
        send(cpu(DEMO, 20));

        service.stopForShutdown();
        scheduler.shutDown();
        worker.runAll();

        assertThat(nativeCalls()).containsExactly("apply 4242 CPU 20 %");
        assertThat(results).as("nobody is left to answer").hasSize(answered);
        assertThat(ledger.find(DEMO).orElseThrow().state())
                .as("what the step did is recorded for the release and the next start")
                .isEqualTo(LedgerState.APPLIED);
        assertThat(limiter.holds).containsKey(DEMO);

        assertThat(service.releaseNativeLimits(Duration.ofSeconds(1)).notCleared())
                .isZero();
        assertThat(limiter.holds).isEmpty();
        assertThat(ledger.find(DEMO)).isEmpty();
    }

    @Test
    void aFailureOnTheWorkerAnswersFailedAndFreesTheProcess() {
        build();
        manage(DEMO);
        AtomicInteger asked = new AtomicInteger();
        limiter.apply = (identity, spec) -> {
            if (asked.getAndIncrement() == 0) {
                throw new IllegalStateException("the system went away");
            }
            return enforce.apply(identity, spec);
        };
        int answered = results.size();
        send(cpu(DEMO, 20));
        send(cpu(DEMO, 30));

        settle();

        assertThat(results).hasSize(answered + 2);
        LimitResult failed = results.get(answered);
        assertThat(failed.outcome().result()).isEqualTo(OperationResult.NATIVE_ERROR);
        assertThat(failed.outcome().succeeded()).isFalse();
        assertThat(failed.outcome().detail().orElseThrow()).contains("the system went away");
        assertThat(results.get(answered + 1).outcome().succeeded())
                .as("the process is free again: what waited runs")
                .isTrue();
        assertThat(view(DEMO).cpu().detail().orElseThrow()).contains("enforced: CPU 30 %");
    }
}
