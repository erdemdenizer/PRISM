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
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LedgerState;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsNotice;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.ScopeProvenance;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ReleaseResult;

/**
 * The limit service with a native limiter (milestone 10, plan §9): native first, the emulated limit
 * only after a refused assignment and the user's consent, never two controllers on one process,
 * the ledger before every native step, and what the Limits page is told.
 */
class LimitServiceNativeTest {

    private static final long OWN_PID = 1;
    private static final ProcessIdentity DEMO = new ProcessIdentity(4_242, Instant.parse("2026-10-10T10:00:00Z"));
    private static final int PROCESSORS = 16;
    private static final long MIB = 1L << 20;

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final VirtualScheduler scheduler = new VirtualScheduler();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeProcessSource source = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(200, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();
    private final List<LimitResult> results = new ArrayList<>();
    private final List<LimitsSnapshot> snapshots = new ArrayList<>();
    private SuspensionJournal journal;
    private SuspensionCoordinator coordinator;
    private NativeLimitLedger ledger;
    private LimitService service;

    @BeforeEach
    void setUp() {
        journal = SuspensionJournal.open(journalFolder.path(), "1-1", files, millis -> {});
        coordinator = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journal));
        ledger = new NativeLimitLedger(Optional.of(journalFolder.path()), "1-1", files, Runnable::run);
        build(ledger);
        new SimulatedTarget(scheduler, PROCESSORS, 1.0).wire(controller, source);
    }

    /** What recovery has not settled, as the test sets it (audit item 2). */
    private volatile Optional<String> unsettled = Optional.empty();

    /** What recovery says of the process itself; by default the same as what is unsettled. */
    private volatile java.util.function.Function<ProcessIdentity, Optional<String>> capsTheProcess =
            identity -> unsettled;

    private final EarlierLimits earlier = new EarlierLimits() {
        @Override
        public Optional<String> mayStillCap(ProcessIdentity identity) {
            return capsTheProcess.apply(identity);
        }

        @Override
        public Optional<String> pending() {
            return unsettled;
        }
    };

    private void build(NativeLimitLedger theLedger) {
        service = new LimitService(
                controller,
                new ProtectionRules(OWN_PID),
                coordinator,
                source,
                activity,
                scheduler,
                scheduler.clock(),
                PROCESSORS,
                DutyCycleController.Tuning.DEFAULTS,
                new NativeLimitController(limiter, theLedger, earlier),
                results::add,
                snapshots::add);
        coordinator.setJournalListener(service::journalFailed);
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

    private LimitResult memory(long mib) {
        return ask(LimitRequest.memoryLimit(DEMO, "demo", mib * MIB));
    }

    private ManagedProcessView view() {
        return service.snapshot().find(DEMO).orElseThrow();
    }

    private long suspends() {
        return controller.calls.stream().filter("suspend 4242"::equals).count();
    }

    private long applies() {
        return limiter.calls.stream().filter(call -> call.startsWith("apply")).count();
    }

    private void managed() {
        assertThat(ask(LimitAction.MANAGE).outcome().succeeded()).isTrue();
    }

    // ----- Native first -----

    @Test
    void aCpuLimitIsNativeFirstEnforcedByTheSystemAndNeverCalledApproximate() {
        managed();
        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.text())
                .contains("limited to 20 %")
                .contains("Enforced by the system")
                .doesNotContain("Approximate");
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
        assertThat(view().cpu().inEffect().mechanism()).contains(ControlMechanism.JOB_OBJECT);
        assertThat(view().cpu().inEffect().level()).isEqualTo(CapabilityLevel.NATIVE_HARD);
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).as("a native limit suspends nothing").isZero();
        assertThat(ledger.find(DEMO).orElseThrow().state()).isEqualTo(LedgerState.APPLIED);
        assertThat(ledger.find(DEMO).orElseThrow().values().cpuRate()).hasValue(2000);
    }

    /** Plan Rev 2.2 A6: a scope's ownership evidence is on disk with the record before any limit is set. */
    @Test
    void aScopesOwnershipEvidenceIsOnDiskBeforeAnyLimitAndStaysWithTheRecord() {
        ScopeProvenance scope = new ScopeProvenance(
                "prism-srm-limit-4242-1.scope",
                Optional.of("0123456789abcdef0123456789abcdef"),
                Optional.of("/user.slice/user-1000.slice/user@1000.service/app.slice/prism-srm-limit-4242-1.scope"));
        limiter.provenance = Optional.of(scope);
        List<Optional<ScopeProvenance>> onDisk = new ArrayList<>();
        limiter.apply = (target, spec) -> {
            onDisk.add(NativeLimitLedger.readDead(journalFolder.path(), "1-1", files)
                    .get(0)
                    .provenance());
            limiter.holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(spec.effective())));
            return new ApplyResult(
                    ApplyResult.Phase.ENFORCED, ControlOutcome.success(), Optional.of(spec.effective()), false);
        };
        managed();

        assertThat(limit(20).outcome().succeeded()).isTrue();

        assertThat(onDisk)
                .as("the evidence was durable when the limits were set")
                .containsExactly(Optional.of(scope));
        assertThat(ledger.find(DEMO).orElseThrow().provenance()).contains(scope);
        assertThat(ledger.find(DEMO).orElseThrow().state()).isEqualTo(LedgerState.APPLIED);
    }

    @Test
    void ownershipEvidenceThatCannotBeWrittenSetsNoLimit() {
        limiter.provenance = Optional.of(new ScopeProvenance(
                "prism-srm-limit-4242-1.scope", Optional.of("0123456789abcdef0123456789abcdef"), Optional.empty()));
        limiter.beforeRecord = () -> files.diskFull = true;
        managed();

        LimitResult set = limit(20);
        files.diskFull = false;

        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(limiter.calls).contains("record 4242 false").noneMatch(c -> c.startsWith("apply "));
        assertThat(service.limiting(DEMO)).as("nothing emulated starts either").isFalse();
    }

    @Test
    void theRecordIsOnDiskBeforeTheProcessIsAssigned() {
        managed();
        List<LedgerState> seen = new ArrayList<>();
        limiter.apply = (target, spec) -> {
            seen.add(ledger.find(target).orElseThrow().state());
            seen.add(NativeLimitLedger.readDead(journalFolder.path(), "1-1", files)
                    .get(0)
                    .state());
            limiter.holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(spec.effective())));
            return new ApplyResult(
                    ApplyResult.Phase.ENFORCED, ControlOutcome.success(), Optional.of(spec.effective()), false);
        };
        limit(20);
        assertThat(seen).containsExactly(LedgerState.INTENDED, LedgerState.INTENDED);
    }

    @Test
    void aSecondLimitChangesTheJobPrismHoldsAndNeverAssignsAgain() {
        managed();
        limit(20);
        LimitResult changed = limit(35);
        assertThat(changed.outcome().succeeded()).isTrue();
        assertThat(applies()).isEqualTo(1);
        assertThat(limiter.calls).contains("change 4242 CPU 35 %");
        assertThat(view().cpu().requestedPercent()).hasValue(35);
    }

    // ----- The fallback -----

    @Test
    void aRefusedAssignmentOffersTheEmulatedLimitWithItsCostAndStartsNothing() {
        limiter.apply =
                FakeResourceLimiter.notAssigned(OperationResult.UNSUPPORTED, "in a job Prism does not hold", true);
        managed();
        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.fallback()).hasValueSatisfying(offer -> {
            assertThat(offer.reason()).contains("in a job");
            assertThat(offer.perThreadSuspension()).isTrue();
            assertThat(offer.mostLimits()).isEqualTo(4);
        });
        assertThat(suspends()).as("no emulated limit without consent").isZero();
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
        assertThat(view().cpu().note()).isEqualTo(LimitNote.FALLBACK_OFFERED);
        assertThat(ledger.find(DEMO)).as("nothing native was left").isEmpty();
    }

    @Test
    void theEmulatedLimitRunsOnlyOnceTheUserAcceptsIt() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);

        LimitResult accepted = ask(LimitAction.ACCEPT_EMULATED_CPU);
        scheduler.advance(Duration.ofSeconds(10));

        assertThat(accepted.outcome().succeeded()).isTrue();
        assertThat(accepted.text()).contains("emulated, your choice").contains("Approximate");
        assertThat(service.limiting(DEMO)).isTrue();
        assertThat(suspends()).isPositive();
        assertThat(view().cpu().inEffect().level()).isEqualTo(CapabilityLevel.EMULATED);
        assertThat(view().process().settings().emulatedCpuApproved()).isTrue();
    }

    // ----- Audit item 2: an earlier session's ledger not settled yet -----

    // ----- Final audit: the barrier does not depend on today's native CPU capability -----

    /** Native CPU unavailable today (Dynamic Fair Share Scheduling, a failed probe), memory still native. */
    private void nativeCpuUnavailableToday() {
        limiter.system = java.util.Map.of(
                LimitKind.CPU,
                LimitCapability.unavailable("CPU rate control is not available on this system"),
                LimitKind.MEMORY,
                FakeResourceLimiter.NATIVE);
    }

    /** No native limiter at all, as on Linux and macOS or a 32-bit JVM: the milestone 9 path. */
    private void noNativeLimiterToday() {
        LimitCapability none = LimitCapability.unavailable("no native limiter on this system");
        limiter.system = java.util.Map.of(LimitKind.CPU, none, LimitKind.MEMORY, none);
    }

    private static final String PENDING =
            "1 native-limit ledger(s) of earlier Prism sessions are not settled yet (1-1)";

    /**
     * The reproduced gap: today's probe finds no native CPU limit, but an earlier session's ledger is
     * not settled; that session's kernel cap may still be on the process. No emulated limit starts.
     */
    @Test
    void withNativeCpuUnavailableTodayAnUnsettledEarlierLedgerStillBlocksTheEmulatedLimit() {
        nativeCpuUnavailableToday();
        unsettled = Optional.of(PENDING);
        managed();

        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(set.fallback()).isEmpty();
        assertThat(set.text())
                .contains("No emulated limit is offered for it yet")
                .contains("1-1");
        assertThat(view().cpu().status()).as("the row says it failed, and why").isEqualTo(LimitStatus.FAILED);
        assertThat(view().cpu().note()).isEqualTo(LimitNote.PRIOR_SESSION);
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).as("no second CPU controller").isZero();
        assertThat(applies())
                .as("nothing native is tried where native CPU is unavailable")
                .isZero();
        assertThat(view().cpu().status())
                .as("nothing claims the limit is at work")
                .isNotIn(
                        LimitStatus.ACTIVE_NOT_THROTTLING,
                        LimitStatus.ADJUSTING,
                        LimitStatus.ACTIVE_THROTTLING,
                        LimitStatus.ACTIVE_ABOVE_TARGET,
                        LimitStatus.ACTIVE_ENFORCED);
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.RECOVERY_PENDING);
    }

    /** The same on a system with no native limiter at all, through Enable. */
    @Test
    void withNoNativeLimiterTodayAnUnsettledEarlierLedgerStillBlocksEnable() {
        noNativeLimiterToday();
        managed();
        assertThat(ask(LimitAction.DISABLE).outcome().succeeded()).isTrue();
        limit(20);
        unsettled = Optional.of(PENDING);

        LimitResult enable = ask(LimitAction.ENABLE);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(enable.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(enable.text())
                .contains("No emulated limit is offered for it yet")
                .contains("1-1");
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
        assertThat(view().cpu().note()).isEqualTo(LimitNote.PRIOR_SESSION);
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.RECOVERY_PENDING);
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).isZero();
        assertThat(view().cpu().status())
                .isNotIn(
                        LimitStatus.ACTIVE_NOT_THROTTLING,
                        LimitStatus.ADJUSTING,
                        LimitStatus.ACTIVE_THROTTLING,
                        LimitStatus.ACTIVE_ABOVE_TARGET,
                        LimitStatus.ACTIVE_ENFORCED);
    }

    /**
     * A known earlier cap blocks the emulated limit whatever today's probe says. A regression guard:
     * this held before the final audit too, as the known cap was never gated on today's capability.
     */
    @Test
    void withNativeCpuUnavailableTodayAKnownEarlierCapBlocksTheEmulatedLimit() {
        nativeCpuUnavailableToday();
        managed();
        LimitRecord earlierCap = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()), "9-9")
                .applied(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(earlierCap, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofMillis(1));

        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(set.text()).contains("earlier Prism session");
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).isZero();
    }

    /**
     * Settled, with nothing earlier on the process: the milestone 9 emulated limit runs as before.
     * The negative control of the two reproducing tests above.
     */
    @Test
    void withNativeCpuUnavailableTodayAndRecoverySettledTheEmulatedLimitRunsAsInMilestone9() {
        nativeCpuUnavailableToday();
        unsettled = Optional.empty();
        managed();

        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().succeeded()).as(set.text()).isTrue();
        assertThat(service.limiting(DEMO)).isTrue();
        assertThat(suspends()).isPositive();
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .doesNotContain(LimitsNotice.Kind.RECOVERY_PENDING);
    }

    /**
     * Found late while an emulated limit runs on a system without native CPU: it stops through its own
     * release, and a suspension the user made stays, with no resume asked of the system.
     */
    @Test
    void withNoNativeLimiterTodayAnEarlierCapFoundLateStopsTheEmulatedLimit() {
        noNativeLimiterToday();
        managed();
        limit(20);
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(service.limiting(DEMO)).isTrue();
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.USER)
                        .outcome()
                        .succeeded())
                .isTrue();
        int callsBefore = controller.calls.size();

        LimitRecord earlierCap = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()), "9-9")
                .applied(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(earlierCap, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(coordinator.heldFor(DEMO)).containsExactly(SuspensionReason.USER);
        assertThat(controller.calls.subList(callsBefore, controller.calls.size()))
                .noneMatch(call -> call.startsWith("resume"));
    }

    /**
     * T-D27 (plan Rev 2.2 section 4.5 b): a process inside a scope a Prism session created gets no emulated limit, whatever
     * recovery knows: the scope-membership rule stands apart from any record.
     */
    @Test
    void aProcessInsideAPrismScopeGetsNoEmulatedLimitWhateverTheRecordsSay() {
        noNativeLimiterToday();
        limiter.scopeBarrier = identity -> identity.equals(DEMO)
                ? Optional.of("it runs in prism-srm-limit-4242-1.scope, a scope a Prism session created")
                : Optional.empty();
        managed();

        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.text()).contains("prism-srm-limit-4242-1.scope");
        assertThat(set.text())
                .as("said as the scope rule, not as an unsettled ledger")
                .contains("Prism changes nothing in that scope")
                .doesNotContain("ledger");
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).as("no emulated controller").isZero();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);

        limiter.scopeBarrier = identity -> Optional.empty();
        assertThat(limit(20).outcome().succeeded())
                .as("outside any Prism scope it runs")
                .isTrue();
    }

    /** The barrier is per process: a pending ledger blocks only what it may name; it is still said. */
    @Test
    void withNativeCpuUnavailableTodayAPendingLedgerThatCannotNameTheProcessBlocksNothing() {
        nativeCpuUnavailableToday();
        unsettled = Optional.of(PENDING);
        capsTheProcess = identity -> Optional.empty();
        managed();

        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.outcome().succeeded()).as(set.text()).isTrue();
        assertThat(service.limiting(DEMO)).isTrue();
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.RECOVERY_PENDING);
    }

    /** A known earlier cap blocks Enable and acceptance too while native CPU is unavailable. */
    @Test
    void withNoNativeLimiterTodayAKnownEarlierCapBlocksEnable() {
        noNativeLimiterToday();
        managed();
        assertThat(ask(LimitAction.DISABLE).outcome().succeeded()).isTrue();
        limit(20);
        LimitRecord earlierCap = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()), "9-9")
                .applied(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(earlierCap, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofMillis(1));

        LimitResult enable = ask(LimitAction.ENABLE);
        LimitResult accept = ask(LimitAction.ACCEPT_EMULATED_CPU);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(enable.outcome().succeeded()).isFalse();
        assertThat(accept.outcome().succeeded()).isFalse();
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).isZero();
    }

    /** Before the earlier ledger is read, nothing emulated is offered, accepted or started. */
    @Test
    void whileAnEarlierLedgerIsUnsettledNoEmulatedLimitIsOfferedAcceptedOrStarted() {
        unsettled = Optional.of("1 native-limit ledger(s) of earlier Prism sessions are not settled yet (1-1)");
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();

        LimitResult set = limit(20);

        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.fallback()).as("no offer").isEmpty();
        assertThat(set.text())
                .contains("No emulated limit is offered for it yet")
                .contains("1-1");
        assertThat(ask(LimitAction.ACCEPT_EMULATED_CPU).outcome().succeeded())
                .as("nothing to accept")
                .isFalse();
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).isZero();
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.RECOVERY_PENDING);

        // Settled, and the earlier session named nothing here: the supported fallback is offered again.
        unsettled = Optional.empty();
        LimitResult again = limit(20);
        assertThat(again.fallback()).isPresent();
        assertThat(ask(LimitAction.ACCEPT_EMULATED_CPU).outcome().succeeded()).isTrue();
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(service.limiting(DEMO)).isTrue();
    }

    /**
     * Pre-discovery acceptance (test review): an offer made before an earlier ledger became known
     * cannot be accepted while it is unsettled; the approval is not kept and nothing is suspended.
     */
    @Test
    void anOfferMadeBeforeTheBarrierCannotBeAcceptedWhileItHolds() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        assertThat(limit(20).fallback()).as("offered: nothing is unsettled yet").isPresent();
        unsettled = Optional.of("1 native-limit ledger(s) of earlier Prism sessions are not settled yet (1-1)");

        LimitResult accept = ask(LimitAction.ACCEPT_EMULATED_CPU);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(accept.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(accept.text()).contains("No emulated limit is offered for it yet");
        assertThat(view().process().settings().emulatedCpuApproved())
                .as("the approval is not kept")
                .isFalse();
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).isZero();
    }

    /** An approval given before the barrier does not start the emulated limit while it holds. */
    @Test
    void anApprovedEmulatedLimitDoesNotStartWhileAnEarlierLedgerIsUnsettled() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);
        assertThat(ask(LimitAction.ACCEPT_EMULATED_CPU).outcome().succeeded()).isTrue();
        assertThat(ask(LimitAction.DISABLE).outcome().succeeded()).isTrue();
        unsettled = Optional.of("an earlier ledger is not settled yet");
        long before = suspends();

        LimitResult enable = ask(LimitAction.ENABLE);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(enable.text())
                .as("refused by the barrier, not by chance")
                .contains("No emulated limit is offered for it yet");

        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).as("no suspension after the barrier").isEqualTo(before);
    }

    /**
     * Found late: an emulated limit already at work stops through its own path, which releases what
     * the limiter holds and nothing else; no second limiter is started and nothing native is tried.
     */
    @Test
    void anEarlierCapFoundLateStopsTheEmulatedLimitThroughItsOwnRelease() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);
        ask(LimitAction.ACCEPT_EMULATED_CPU);
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(service.limiting(DEMO)).isTrue();
        long applies = applies();

        LimitRecord found = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(1500), OptionalLong.empty()), "1-1")
                .applied(EffectiveLimits.of(OptionalInt.of(1500), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(found, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(service.limiting(DEMO)).as("the emulated limit stopped").isFalse();
        assertThat(coordinator.heldFor(DEMO))
                .as("released through the coordinator, as milestone 9 releases")
                .doesNotContain(SuspensionReason.CPU_LIMITER);
        assertThat(applies()).as("nothing native was tried instead").isEqualTo(applies);
        assertThat(view().cpu().note()).isEqualTo(LimitNote.PRIOR_SESSION);
        assertThat(activity.entries())
                .anySatisfy(entry ->
                        assertThat(entry.message()).contains("emulated CPU limit of demo (PID 4242) was stopped"));
        LimitResult again = ask(LimitAction.ACCEPT_EMULATED_CPU);
        assertThat(again.outcome().succeeded()).isFalse();
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(service.limiting(DEMO)).isFalse();
    }

    @Test
    void anEarlierCapFoundLateLeavesASuspensionTheUserMadeInPlace() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);
        ask(LimitAction.ACCEPT_EMULATED_CPU);
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(coordinator
                        .suspend(DEMO, "demo", SuspensionReason.USER)
                        .outcome()
                        .succeeded())
                .isTrue();
        int callsBefore = controller.calls.size();

        LimitRecord found = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(1500), OptionalLong.empty()), "1-1")
                .applied(EffectiveLimits.of(OptionalInt.of(1500), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(found, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(coordinator.heldFor(DEMO))
                .as("the user's suspension is not resumed")
                .containsExactly(SuspensionReason.USER);
        assertThat(controller.calls.subList(callsBefore, controller.calls.size()))
                .as("nothing resumes the process on the system")
                .noneMatch(call -> call.startsWith("resume"));
    }

    @Test
    void noFallbackIsOfferedForAProcessThatExitedNorAcceptedWithoutAnOffer() {
        managed();
        assertThat(ask(LimitAction.ACCEPT_EMULATED_CPU).outcome().result()).isEqualTo(OperationResult.INVALID_ARGUMENT);
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.PROCESS_NOT_FOUND, "exited", false);
        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        LimitResult set = limit(20);
        assertThat(set.fallback()).isEmpty();
        assertThat(service.snapshot().find(DEMO)).isEmpty();
    }

    @Test
    void anApprovedEmulatedLimitGivesWayToTheNativeOneOnceTheNativeOneCanBeApplied() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);
        ask(LimitAction.ACCEPT_EMULATED_CPU);
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(service.limiting(DEMO)).isTrue();

        limiter.apply = (target, spec) -> {
            // The emulated limit is stopped and has released everything before the assignment.
            assertThat(service.limiting(DEMO)).isFalse();
            assertThat(coordinator.heldFor(DEMO)).isEmpty();
            limiter.holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(spec.effective())));
            return new ApplyResult(
                    ApplyResult.Phase.ENFORCED, ControlOutcome.success(), Optional.of(spec.effective()), false);
        };
        LimitResult native30 = limit(30);
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(native30.outcome().succeeded()).isTrue();
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
    }

    /** Plan D11: where the system has no native CPU limit, the emulated one runs as in milestone 9, without asking. */
    @Test
    void withoutANativeCpuLimitOnTheSystemTheEmulatedLimitRunsWithoutAnOffer() {
        limiter.system = Map.of(
                LimitKind.CPU,
                LimitCapability.unavailable("Dynamic Fair Share Scheduling"),
                LimitKind.MEMORY,
                FakeResourceLimiter.NATIVE);
        managed();
        LimitResult set = limit(20);
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.fallback()).isEmpty();
        assertThat(service.limiting(DEMO)).isTrue();
        assertThat(applies()).isZero();
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.NATIVE_UNAVAILABLE);
    }

    // ----- Never two controllers -----

    @Test
    void anUncertainAssignmentOffersNoFallbackAndBlocksTheEmulatedLimit() {
        limiter.apply = limiter.uncertain();
        managed();
        LimitResult set = limit(20);
        assertThat(set.fallback()).isEmpty();
        assertThat(set.text()).contains("cannot tell");
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.FAILED);
        assertThat(view().cpu().note()).isEqualTo(LimitNote.NATIVE_UNSETTLED);
        assertThat(ask(LimitAction.ACCEPT_EMULATED_CPU).outcome().succeeded()).isFalse();
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(ledger.find(DEMO).orElseThrow().flags()).contains(LimitRecord.Flag.UNCERTAIN);
    }

    @Test
    void aChangeThatCannotBeReadBackLeavesTheLimitUnknownAndNothingIsTightenedOverIt() {
        managed();
        limit(20);
        limiter.change = limiter.unverified();
        LimitResult set = limit(30);
        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.text()).contains("could not read back");
        assertThat(view().process().settings().cpuLimitPercent())
                .as("the setting follows only what was read back")
                .hasValue(20);
        assertThat(ledger.find(DEMO).orElseThrow().state()).isEqualTo(LedgerState.PENDING_CHANGE);

        LimitResult again = limit(40);
        assertThat(again.outcome().succeeded()).isFalse();
        assertThat(limiter.calls.stream()
                        .filter(call -> call.startsWith("change"))
                        .count())
                .as("no change over limits in force that are unknown")
                .isEqualTo(1);
        assertThat(service.limiting(DEMO)).isFalse();

        limiter.change = new FakeResourceLimiter().change;
        limiter.change = (target, spec) -> {
            limiter.holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(spec.effective())));
            return new prism.srm.platform.ChangeResult(
                    prism.srm.platform.ChangeResult.Phase.APPLIED,
                    ControlOutcome.success(),
                    Optional.of(spec.effective()));
        };
        assertThat(ask(LimitAction.REMOVE_CPU_LIMIT).outcome().succeeded())
                .as("removing is always tried")
                .isTrue();
    }

    /** Plan D15: no native cap over threads the emulated limit (or any release) could not let go. */
    @Test
    void anIncompleteSuspensionKeepsTheNativeCapFromStarting() {
        managed();
        controller.resume = suspension -> {
            suspension.outstanding = 1;
            return ControlOutcome.of(OperationResult.NATIVE_ERROR, 5, "a thread could not be resumed");
        };
        coordinator.suspend(DEMO, "demo", SuspensionReason.USER);
        coordinator.release(DEMO, SuspensionReason.USER);
        assertThat(coordinator.incomplete(DEMO)).isTrue();

        LimitResult set = limit(20);

        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.fallback()).isEmpty();
        assertThat(applies()).isZero();
        assertThat(view().cpu().note()).isEqualTo(LimitNote.RELEASE_INCOMPLETE);

        controller.resume = suspension -> {
            suspension.outstanding = 0;
            return ControlOutcome.success();
        };
        coordinator.release(DEMO, SuspensionReason.USER);
        assertThat(coordinator.incomplete(DEMO)).isFalse();
        assertThat(limit(20).outcome().succeeded()).isTrue();
    }

    @Test
    void anEarlierSessionThatMayStillCapTheProcessBlocksEveryLimiterAndSaysSo() {
        managed();
        LimitRecord earlier = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()), "9-9")
                .applied(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(earlier, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofMillis(1));

        LimitResult set = limit(30);
        assertThat(set.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(set.fallback()).isEmpty();
        assertThat(set.text()).contains("earlier Prism session");
        assertThat(applies()).isZero();
        assertThat(memory(256).outcome().succeeded()).isFalse();
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.PRIOR_SESSION);
        assertThat(view().cpu().note()).isEqualTo(LimitNote.PRIOR_SESSION);
    }

    /** The record recovery carried into this session goes when its process exits, so the ledger can go too. */
    @Test
    void theRecordCarriedFromAnEarlierSessionGoesWhenItsProcessExits() {
        managed();
        LimitRecord earlier = LimitRecord.intended(
                        DEMO, "demo", EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()), "9-9")
                .applied(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        ledger.put(earlier.carriedInto("1-1"));
        service.recovered(List.of(new RecoveredLimit(earlier, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofMillis(1));
        assertThat(ledger.find(DEMO)).isPresent();

        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));

        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .doesNotContain(LimitsNotice.Kind.PRIOR_SESSION);
        assertThat(ledger.find(DEMO))
                .as("nothing left to say at the next start")
                .isEmpty();
    }

    // ----- Changes of a held job (plan §8.1b, test review) -----

    /** A change that leaves a limit in force is on disk as PENDING_CHANGE before the system is asked. */
    @Test
    void aTighteningChangeIsOnDiskAsPendingBeforeTheSystemIsAsked() {
        managed();
        limit(20);
        List<LedgerState> seen = new ArrayList<>();
        BiFunction<ProcessIdentity, LimitSpec, ChangeResult> applied = limiter.change;
        limiter.change = (target, spec) -> {
            seen.add(NativeLimitLedger.readDead(journalFolder.path(), "1-1", files)
                    .get(0)
                    .state());
            return applied.apply(target, spec);
        };

        assertThat(limit(30).outcome().succeeded()).isTrue();

        assertThat(seen).containsExactly(LedgerState.PENDING_CHANGE);
        assertThat(ledger.find(DEMO).orElseThrow().state()).isEqualTo(LedgerState.APPLIED);
    }

    @Test
    void aChangeTheLedgerCannotRecordIsNotMadeAndTheLimitStaysAsItWas() {
        managed();
        limit(20);
        files.diskFull = true;

        LimitResult refused = limit(30);

        files.diskFull = false;
        assertThat(refused.outcome().succeeded()).isFalse();
        assertThat(limiter.calls).noneMatch(call -> call.startsWith("change 4242 CPU 30 %"));
        assertThat(limiter.holds.get(DEMO).inForce().orElseThrow().cpuPercent()).hasValue(20);
        assertThat(view().process().settings().cpuLimitPercent()).hasValue(20);
    }

    /** With the limits in force unknown, nothing is tightened and Enable is refused (plan §9, V). */
    @Test
    void enableIsRefusedWhileTheLimitsInForceAreUnknown() {
        managed();
        limit(20);
        assertThat(ask(LimitAction.DISABLE).outcome().succeeded()).isTrue();
        limiter.holds.put(DEMO, new NativeHold(NativeHold.State.ASSIGNED, Optional.empty()));
        int changes = (int)
                limiter.calls.stream().filter(call -> call.startsWith("change")).count();

        LimitResult enable = ask(LimitAction.ENABLE);

        assertThat(enable.outcome().succeeded()).isFalse();
        assertThat(view().process().settings().enabled()).isFalse();
        assertThat(limiter.calls.stream()
                        .filter(call -> call.startsWith("change"))
                        .count())
                .as("the system is not asked to tighten a job whose limits are unknown")
                .isEqualTo(changes);
        assertThat(service.limiting(DEMO)).isFalse();
    }

    @Test
    void theEmulatedLimitIsNotAcceptedWhileANativeLimitIsEnforced() {
        managed();
        limit(20);

        LimitResult accept = ask(LimitAction.ACCEPT_EMULATED_CPU);

        assertThat(accept.outcome().succeeded()).isFalse();
        assertThat(service.limiting(DEMO)).isFalse();
        assertThat(suspends()).isZero();
    }

    /** Only the exact identity is blocked: the same PID with another start time is another process. */
    @Test
    void anEarlierSessionsCapOnAnotherProcessWithTheSamePidBlocksNothing() {
        managed();
        ProcessIdentity before =
                new ProcessIdentity(DEMO.pid(), DEMO.startTime().minusSeconds(60));
        LimitRecord earlier = LimitRecord.intended(
                        before, "demo", EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()), "9-9")
                .applied(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        service.recovered(List.of(new RecoveredLimit(earlier, RecoveredLimit.Verdict.UNREACHABLE, "unreachable")));
        scheduler.advance(Duration.ofMillis(1));

        assertThat(limit(30).outcome().succeeded()).isTrue();
        assertThat(applies()).isEqualTo(1);
    }

    // ----- Memory -----

    @Test
    void aMemoryLimitIsNativeAndSaysWhatReachingItMeans() {
        managed();
        LimitResult set = memory(256);
        assertThat(set.outcome().succeeded()).isTrue();
        assertThat(set.text()).contains("may commit at most 256 MiB").contains("may fail or crash");
        assertThat(view().memory().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
        assertThat(view().memory().requestedBytes()).hasValue(256 * MIB);
        limit(20);
        assertThat(limiter.calls).contains("change 4242 CPU 20 %, memory 256 MiB");
    }

    @Test
    void aMemoryLimitThatCannotBeAppliedHasNoFallback() {
        limiter.apply =
                FakeResourceLimiter.notAssigned(OperationResult.UNSUPPORTED, "in a job Prism does not hold", true);
        managed();
        LimitResult set = memory(256);
        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.fallback()).isEmpty();
        assertThat(set.text()).contains("not available for this process");
    }

    @Test
    void anEmulatedCpuLimitAndANativeMemoryLimitRunSideBySideAndTheJobCapsNoCpu() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);
        ask(LimitAction.ACCEPT_EMULATED_CPU);
        FakeResourceLimiter fresh = new FakeResourceLimiter();
        limiter.apply = (target, spec) -> {
            assertThat(spec.cpuPercent())
                    .as("the job caps no CPU while the emulated limit holds it")
                    .isEmpty();
            return fresh.apply
                    .andThen(result -> {
                        limiter.holds.putAll(fresh.holds);
                        return result;
                    })
                    .apply(target, spec);
        };
        assertThat(memory(256).outcome().succeeded()).isTrue();
        assertThat(service.limiting(DEMO)).isTrue();
    }

    // ----- Disable, Remove, Unmanage, exit, shutdown -----

    @Test
    void disableClearsTheNativeLimitsAndKeepsTheJobAndEnableAppliesThemAgainOnTheSameJob() {
        managed();
        limit(20);
        LimitResult disabled = ask(LimitAction.DISABLE);
        assertThat(disabled.outcome().succeeded()).isTrue();
        assertThat(limiter.holds.get(DEMO).inForce()).contains(EffectiveLimits.NONE);
        assertThat(ledger.find(DEMO).orElseThrow().state()).isEqualTo(LedgerState.CLEARED);

        assertThat(ask(LimitAction.ENABLE).outcome().succeeded()).isTrue();
        assertThat(applies()).isEqualTo(1);
        assertThat(view().cpu().status()).isEqualTo(LimitStatus.ACTIVE_ENFORCED);
    }

    /** Plan D19: a clearing that cannot be verified leaves the limits visible. */
    @Test
    void disableAndUnmanageThatCannotClearChangeNothingAndSaySo() {
        managed();
        limit(20);
        limiter.change = limiter.partial(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty()));
        LimitResult disabled = ask(LimitAction.DISABLE);
        assertThat(disabled.outcome().succeeded()).isFalse();
        assertThat(view().process().settings().enabled()).isTrue();

        LimitResult unmanaged = ask(LimitAction.UNMANAGE);
        assertThat(unmanaged.outcome().succeeded()).isFalse();
        assertThat(service.snapshot().find(DEMO)).isPresent();
    }

    @Test
    void unmanageClearsTheLimitsAndKeepsTheJobUntilTheProcessExits() {
        managed();
        limit(20);
        assertThat(ask(LimitAction.UNMANAGE).outcome().succeeded()).isTrue();
        assertThat(service.snapshot().find(DEMO)).isEmpty();
        assertThat(limiter.holds).containsKey(DEMO);

        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));
        assertThat(limiter.calls).contains("forget 4242");
        assertThat(limiter.holds).doesNotContainKey(DEMO);
    }

    @Test
    void anExitedProcessWhoseJobStillCarriesLimitsStaysAResidueAndIsRetried() {
        managed();
        limit(20);
        limiter.forget = target -> new ForgetResult(
                ForgetResult.Outcome.RESIDUE,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "could not clear"),
                2,
                Optional.of(EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty())));
        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));

        assertThat(service.snapshot().find(DEMO)).isEmpty();
        assertThat(service.snapshot().notices()).extracting(LimitsNotice::kind).contains(LimitsNotice.Kind.RESIDUE);
        assertThat(ledger.find(DEMO).orElseThrow().flags()).contains(LimitRecord.Flag.TARGET_EXITED);

        limiter.forget = new FakeResourceLimiter().forget;
        limiter.forget = target -> {
            limiter.holds.remove(target);
            return new ForgetResult(
                    ForgetResult.Outcome.RELEASED, ControlOutcome.success(), 2, Optional.of(EffectiveLimits.NONE));
        };
        service.listed(Set.of());
        scheduler.advance(Duration.ofMillis(1));
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .doesNotContain(LimitsNotice.Kind.RESIDUE);
        assertThat(ledger.find(DEMO)).isEmpty();
    }

    @Test
    void theOneExitPathOfAnEmulatedLimitAlsoLetsTheNativeMemoryLimitsJobGo() {
        limiter.apply = FakeResourceLimiter.notAssigned(OperationResult.ACCESS_DENIED, "refused", true);
        managed();
        limit(20);
        ask(LimitAction.ACCEPT_EMULATED_CPU);
        limiter.holds.put(
                DEMO,
                new NativeHold(
                        NativeHold.State.ASSIGNED,
                        Optional.of(EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(256 * MIB)))));
        controller.liveness = target -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        source.onCpuTime = target -> prism.srm.domain.CpuTimeReading.GONE;
        scheduler.advance(Duration.ofSeconds(5));
        assertThat(limiter.calls).contains("forget 4242");
        assertThat(limiter.holds).doesNotContainKey(DEMO);
    }

    @Test
    void theShutdownClearsEveryNativeLimitAndRemovesAnEmptyLedger() throws Exception {
        managed();
        limit(20);
        assertThat(NativeLimitLedger.readDead(journalFolder.path(), "1-1", files))
                .hasSize(1);
        assertThat(service.releaseNativeLimits(Duration.ofSeconds(1)).notCleared())
                .isZero();
        assertThat(limiter.calls).contains("releaseAll");
        assertThat(ledger.find(DEMO))
                .as("a limit verified as cleared leaves the ledger")
                .isEmpty();
        assertThat(NativeLimitLedger.listInstances(journalFolder.path(), files))
                .as("the empty ledger goes")
                .doesNotContain("1-1");
        assertThat(limit(30).outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
    }

    /**
     * Audit item 3: a shutdown that finds the limiter busy past its bound clears nothing, says so in
     * the activity log, never reports zero, and keeps every record for the next start.
     */
    @Test
    void aShutdownThatFindsTheLimiterBusyKeepsTheLedgerAndSaysLimitsMayStay() {
        managed();
        limit(20);
        limiter.busy = true;

        ReleaseResult result = service.releaseNativeLimits(Duration.ofSeconds(1));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(result.notCleared()).isEqualTo(1);
        assertThat(ledger.find(DEMO)).as("the record stays").isPresent();
        assertThat(NativeLimitLedger.readDead(journalFolder.path(), "1-1", files))
                .as("and the file, for the next start")
                .hasSize(1);
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message())
                        .contains("the limiter was busy past the time bound")
                        .contains("possibly more"));
        int afterRelease = limiter.calls.indexOf("releaseAll");
        assertThat(limit(30).outcome().succeeded())
                .as("nothing new after the shutdown began")
                .isFalse();
        assertThat(limiter.calls.subList(afterRelease + 1, limiter.calls.size()))
                .as("no assignment or change is asked of the system after it")
                .noneMatch(call -> call.startsWith("apply") || call.startsWith("change"));
    }

    /** Never "nothing left" from a clearing that could not begin, even with no hold known then. */
    @Test
    void aBusyLimiterWithNoKnownHoldIsStillReportedAsPossiblyLeavingLimits() {
        managed();
        limiter.busy = true;

        ReleaseResult result = service.releaseNativeLimits(Duration.ofSeconds(1));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.message()).contains("possibly more"));
    }

    /** A limit that cannot be verified as cleared at shutdown stays in the ledger for the next start. */
    @Test
    void aLimitNotVerifiedAsClearedAtShutdownStaysInTheLedger() {
        managed();
        limit(20);
        limiter.clears = identity -> false;

        assertThat(service.releaseNativeLimits(Duration.ofSeconds(1)).notCleared())
                .isEqualTo(1);

        assertThat(ledger.find(DEMO))
                .hasValueSatisfying(record -> assertThat(record.state()).isNotEqualTo(LedgerState.CLEARED));
        assertThat(NativeLimitLedger.readDead(journalFolder.path(), "1-1", files))
                .as("the ledger stays on disk for the next start")
                .singleElement()
                .satisfies(record -> assertThat(record.identity()).isEqualTo(DEMO));
    }

    @Test
    void aLedgerThatCannotRecordRefusesNewNativeLimitsBeforeAnyNativeCall() {
        build(new NativeLimitLedger(Optional.empty(), "1-1", files, Runnable::run));
        managed();
        LimitResult set = limit(20);
        assertThat(set.outcome().succeeded()).isFalse();
        assertThat(set.fallback()).isEmpty();
        assertThat(set.text()).contains("cannot record the native limit");
        assertThat(applies()).isZero();
        assertThat(service.snapshot().notices())
                .extracting(LimitsNotice::kind)
                .contains(LimitsNotice.Kind.LEDGER_FAILED);
    }
}
