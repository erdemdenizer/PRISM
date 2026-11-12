package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.ScopeProvenance;
import prism.srm.platform.ClearResult;

/**
 * T-D26 (plan Rev 2.2 §4.5 e): the lifecycle of a proven scope whose target exited and whose descendants survive, over
 * several sessions, with the real ledger, recovery and native-limit controller. The limiter's verdicts come from a small
 * model of the scope (unit, invocation id, members) that follows the R table the Linux scope limiter implements (its own
 * tests prove that table against fake systemd and cgroup files).
 *
 * <p>Invariant after every step: while the unit exists and holds processes and no clearing was verified, its record is
 * in the newest ledger. The exited identity and a process that reuses its PID are never blocked.
 */
class SurvivingDescendantLifecycleTest {

    private static final ProcessIdentity TARGET = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L));
    private static final ProcessIdentity REUSED = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_400_000L));
    private static final int CHILD = 2_080;
    private static final String UNIT = "prism-srm-limit-2071-1791970282990.scope";
    private static final String INV = "86f1083782564429bf3a1a34ede0c182";
    private static final String GROUP = "/user.slice/user-1001.slice/user@1001.service/app.slice/" + UNIT;

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();

    /** The scope as the system shows it. */
    private boolean unitExists = true;

    private String liveInvocation = INV;
    private final Set<Integer> members = new HashSet<>(Set.of(CHILD));
    private boolean targetAlive;
    private boolean limitsCleared;
    private int session = 1;

    private NativeLimitLedger ledger(String instance) {
        return new NativeLimitLedger(Optional.of(folder.path()), instance, files, Runnable::run);
    }

    private static LimitRecord applied() {
        EffectiveLimits values =
                EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.of(512L << 20), MemoryBoundary.THROTTLE);
        return LimitRecord.intended(TARGET, "demo", values, "1-1")
                .applied(values)
                .withProvenance(new ScopeProvenance(UNIT, Optional.of(INV), Optional.of(GROUP)));
    }

    /** The R-table verdict for the model, as LinuxScopeLimiter.recover gives it. */
    private RecoveredLimit classify(LimitRecord record) {
        if (!unitExists) {
            return new RecoveredLimit(
                    record, targetAlive ? RecoveredLimit.Verdict.NOT_IN_JOB : RecoveredLimit.Verdict.GONE, "no unit");
        }
        if (!record.provenance().flatMap(ScopeProvenance::invocationId).equals(Optional.of(liveInvocation))) {
            return new RecoveredLimit(record, RecoveredLimit.Verdict.UNPROVEN_SCOPE, "another invocation id");
        }
        if (members.isEmpty()) {
            return new RecoveredLimit(record, RecoveredLimit.Verdict.GONE, "empty scope");
        }
        if (targetAlive) {
            return new RecoveredLimit(record, RecoveredLimit.Verdict.OWNED_LIMITED, "holds the target");
        }
        return new RecoveredLimit(record, RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY, "children remain");
    }

    /** One start of Prism: recovery over the earlier ledgers, into this session's own. */
    private Session start() {
        session++;
        NativeLimitLedger own = ledger(session + "-" + session);
        ActivityLog activity = new ActivityLog(100, () -> Instant.EPOCH);
        NativeRecovery recovery =
                new NativeRecovery(limiter, own, Optional.of(folder.path()), files, activity, instance -> false);
        List<RecoveredLimit> found = recovery.lookAtOrphans();
        return new Session(own, recovery, found);
    }

    private record Session(NativeLimitLedger own, NativeRecovery recovery, List<RecoveredLimit> found) {
        Optional<LimitRecord> record() {
            return own.find(TARGET);
        }
    }

    private void invariant(Session s) {
        if (unitExists && !members.isEmpty() && !limitsCleared) {
            assertThat(s.record())
                    .as("a proven scope that may still limit processes keeps its evidence")
                    .hasValueSatisfying(r -> assertThat(r.provenance()).isPresent());
        }
        assertThat(s.recovery().mayStillCap(REUSED))
                .as("a process reusing the PID is never blocked")
                .isEmpty();
    }

    @Test
    void theEvidenceOutlivesTheTargetAndGoesOnlyWhenTheScopeIsGone() {
        limiter.recover = this::classify;
        assertThat(ledger("1-1").putDurably(applied())).isTrue();

        // Step 1: session 2; the limiter does not clear.
        Session second = start();
        assertThat(second.found())
                .singleElement()
                .satisfies(f -> assertThat(f.verdict()).isEqualTo(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY));
        assertThat(second.record())
                .hasValueSatisfying(r -> assertThat(r.provenance())
                        .contains(new ScopeProvenance(UNIT, Optional.of(INV), Optional.of(GROUP))));
        assertThat(Files.exists(NativeLimitLedger.file(folder.path(), "1-1"))).isFalse();
        assertThat(second.recovery().mayStillCap(TARGET))
                .as("the exited identity is not blocked")
                .isEmpty();
        assertThat(limiter.calls).noneMatch(c -> c.startsWith("clearOwned"));
        invariant(second);

        // Step 2: the exit sweep of LimitService forgets what was carried for the exited identity.
        new NativeLimitController(limiter, second.own(), second.recovery()).forgetCarried(TARGET);
        assertThat(second.record())
                .as("the evidence is not the identity's to forget")
                .isPresent();
        invariant(second);

        // Step 3: session 2 crashes; session 3 still proves the scope.
        Session third = start();
        assertThat(third.found())
                .singleElement()
                .satisfies(f -> assertThat(f.verdict())
                        .isEqualTo(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY)
                        .isNotEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE));
        invariant(third);

        // Step 4: the child exits, the scope empties and goes.
        members.clear();
        unitExists = false;
        Session fourth = start();
        assertThat(fourth.found())
                .singleElement()
                .satisfies(f -> assertThat(f.verdict()).isEqualTo(RecoveredLimit.Verdict.GONE));
        assertThat(fourth.record()).as("nothing left to prove").isEmpty();
        invariant(fourth);
    }

    @Test
    void variantA_aVerifiedClearingEndsTheRecordAndNothingElseDoes() {
        limiter.recover = this::classify;
        limiter.clearsAfterRecovery = true;
        limiter.clearOwned = record -> {
            limitsCleared = true;
            return new ClearResult(
                    ClearResult.Outcome.CLEARED, ControlOutcome.success(), Optional.of(EffectiveLimits.NONE));
        };
        assertThat(ledger("1-1").putDurably(applied())).isTrue();

        Session second = start();

        assertThat(second.found())
                .singleElement()
                .satisfies(f -> assertThat(f.verdict()).isEqualTo(RecoveredLimit.Verdict.CLEARED));
        assertThat(second.record()).as("cleared and read back: the record goes").isEmpty();
        invariant(second);
    }

    @Test
    void variantB_aPartialClearingKeepsTheRecordAndIsRetriedAtTheNextStart() {
        limiter.recover = this::classify;
        limiter.clearsAfterRecovery = true;
        limiter.clearOwned = record -> new ClearResult(
                ClearResult.Outcome.PARTIAL,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "MemoryHigh refused"),
                Optional.of(
                        EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(512L << 20), MemoryBoundary.THROTTLE)));
        assertThat(ledger("1-1").putDurably(applied())).isTrue();

        Session second = start();
        assertThat(second.found())
                .singleElement()
                .satisfies(f -> assertThat(f.verdict()).isEqualTo(RecoveredLimit.Verdict.CLEAR_FAILED));
        invariant(second);

        Session third = start();
        assertThat(limiter.calls.stream()
                        .filter(c -> c.startsWith("clearOwned"))
                        .count())
                .as("retried once per start, never in a loop")
                .isEqualTo(2);
        invariant(third);
    }

    @Test
    void variantC_aScopeWithAnotherInvocationIdIsNeverClearedAndKeepsItsRecordUnchanged() {
        limiter.recover = this::classify;
        limiter.clearsAfterRecovery = true;
        liveInvocation = "fedcba9876543210fedcba9876543210";
        assertThat(ledger("1-1").putDurably(applied())).isTrue();

        Session second = start();

        assertThat(second.found())
                .singleElement()
                .satisfies(f -> assertThat(f.verdict()).isEqualTo(RecoveredLimit.Verdict.UNPROVEN_SCOPE));
        assertThat(limiter.calls).noneMatch(c -> c.startsWith("clearOwned"));
        assertThat(second.record())
                .hasValueSatisfying(r -> assertThat(r.provenance())
                        .flatMap(ScopeProvenance::invocationId)
                        .contains(INV));
    }

    @Test
    void variantD_withTheLedgerLostNothingIsClearedAndOnlyTheMembershipRuleProtects() {
        limiter.recover = this::classify;
        limiter.clearsAfterRecovery = true;
        limiter.scopeBarrier = identity -> identity.pid() == CHILD ? Optional.of("in " + UNIT) : Optional.empty();

        Session second = start();

        assertThat(second.found()).isEmpty();
        assertThat(limiter.calls).noneMatch(c -> c.startsWith("clearOwned"));
        assertThat(limiter.scopeBarrier(new ProcessIdentity(CHILD, Instant.EPOCH)))
                .isPresent();
    }

    @Test
    void windowsControl_aGoneTargetWithUnknownChildrenIsNeitherCarriedNorBlocking() {
        limiter.recover = record -> new RecoveredLimit(record, RecoveredLimit.Verdict.GONE_CHILDREN_UNKNOWN, "job");
        EffectiveLimits values = EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty());
        assertThat(ledger("1-1")
                        .putDurably(LimitRecord.intended(TARGET, "demo", values, "1-1")
                                .applied(values)))
                .isTrue();

        Session second = start();

        assertThat(second.record()).isEmpty();
        assertThat(second.recovery().mayStillCap(TARGET)).isEmpty();
    }
}
