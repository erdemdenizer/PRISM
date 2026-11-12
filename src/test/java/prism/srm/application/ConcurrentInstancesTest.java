package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * The two-instance scenario of the final multi-instance audit, reproduced with the real recovery:
 * instance B starts and looks at the folder before instance A has set any native limit; A then caps
 * the target natively (its ledger record on disk, its job holding the process); B, asked for a CPU
 * limit on the same process, finds it in a job B does not hold (what {@code WindowsJobLimiter}
 * answers, see {@code WindowsJobLimiterTest} and {@code WindowsJobIntegrationTest}), and offers the
 * emulated limit. This test shows whether a second controller can then start. It documents the
 * reason for the single-instance rule; it does not run two applications.
 */
class ConcurrentInstancesTest {

    private static final ProcessIdentity TARGET = new ProcessIdentity(4_242, Instant.parse("2026-10-10T10:00:00Z"));
    private static final String A = "7001-1791619200000";
    private static final String B = "7002-1791619260000";

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final VirtualScheduler scheduler = new VirtualScheduler();
    private final FakeProcessController controller = new FakeProcessController();
    private final FakeProcessSource source = new FakeProcessSource();
    private final ActivityLog activity = new ActivityLog(200, () -> Instant.EPOCH);
    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiterOfB = new FakeResourceLimiter();
    private final List<LimitResult> results = new ArrayList<>();
    private final List<LimitsSnapshot> snapshots = new ArrayList<>();
    private SuspensionJournal journalOfB;
    private SuspensionCoordinator coordinatorOfB;
    private NativeRecovery recoveryOfB;
    private LimitService serviceOfB;

    @BeforeEach
    void setUp() {
        journalOfB = SuspensionJournal.open(folder.path(), B, files, millis -> {});
        coordinatorOfB = new SuspensionCoordinator(
                controller, activity, new QueuedExecutor(), () -> Instant.EPOCH, Optional.of(journalOfB));
        NativeLimitLedger ledgerOfB = new NativeLimitLedger(Optional.of(folder.path()), B, files, Runnable::run);
        // A runs: B's recovery must leave A alone; nothing of A is on disk yet.
        recoveryOfB = new NativeRecovery(
                limiterOfB, ledgerOfB, Optional.of(folder.path()), files, activity, instance -> instance.equals(A));
        recoveryOfB.lookAtStartUp();
        serviceOfB = new LimitService(
                controller,
                new ProtectionRules(1),
                coordinatorOfB,
                source,
                activity,
                scheduler,
                scheduler.clock(),
                16,
                DutyCycleController.Tuning.DEFAULTS,
                new NativeLimitController(limiterOfB, ledgerOfB, recoveryOfB),
                results::add,
                snapshots::add);
        coordinatorOfB.setJournalListener(serviceOfB::journalFailed);
        new SimulatedTarget(scheduler, 16, 1.0).wire(controller, source);
    }

    @AfterEach
    void closeJournal() {
        journalOfB.close();
    }

    private LimitResult ask(LimitRequest request) {
        int before = results.size();
        serviceOfB.request(request);
        scheduler.advance(Duration.ofMillis(1));
        assertThat(results).hasSize(before + 1);
        return results.get(results.size() - 1);
    }

    /** A, a running instance, caps the target natively after B looked: its record, then its job. */
    private void instanceACapsTheTarget() {
        NativeLimitLedger ledgerOfA = new NativeLimitLedger(Optional.of(folder.path()), A, files, Runnable::run);
        EffectiveLimits cap = EffectiveLimits.of(OptionalInt.of(2000), OptionalLong.empty());
        assertThat(ledgerOfA.putDurably(
                        LimitRecord.intended(TARGET, "demo", cap, A).applied(cap)))
                .isTrue();
        // From B's side, the target is now in a job B does not hold: refused, fallback eligible.
        limiterOfB.apply = FakeResourceLimiter.notAssigned(
                OperationResult.UNSUPPORTED, "it is in a Job Object Prism does not hold", true);
    }

    /**
     * Characterization, reproduced before the single-instance rule: with two Prism instances on one
     * profile, B offers the emulated limit, B's user accepts it, and B's emulated controller acts on a
     * process A caps natively. Kept as the evidence the rule rests on: within one limit service
     * nothing tells B of A's later ledger, and B must not read files on its limiter thread. The rule
     * (app.InstanceLock, tested in InstanceLockTest) keeps B from ever being built: a second launch is
     * refused before monitoring, recovery or any limit service exists.
     */
    @Test
    void withoutTheSingleInstanceRuleASecondInstanceWouldRunAnEmulatedLimitOverTheFirstsNativeCap() {
        assertThat(recoveryOfB.pending())
                .as("nothing of A on disk when B looked")
                .isEmpty();
        assertThat(ask(LimitRequest.of(LimitAction.MANAGE, TARGET, "demo"))
                        .outcome()
                        .succeeded())
                .isTrue();
        instanceACapsTheTarget();

        LimitResult set = ask(LimitRequest.cpuLimit(TARGET, "demo", 20));
        LimitResult accept = ask(LimitRequest.of(LimitAction.ACCEPT_EMULATED_CPU, TARGET, "demo"));
        scheduler.advance(Duration.ofSeconds(5));

        assertThat(set.fallback()).as("offered").isPresent();
        assertThat(accept.outcome().succeeded()).as("approved").isTrue();
        assertThat(serviceOfB.limiting(TARGET))
                .as("a second controller is active")
                .isTrue();
        assertThat(controller.calls.stream().filter("suspend 4242"::equals).count())
                .as("and suspends the process A caps")
                .isPositive();
    }
}
