package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ActivitySeverity;
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
 * Recovery of a Linux record (milestone 12, S1): the limiter classifies, recovery reports and carries over what may
 * still be in force, and nothing is cleared. Recovery never asks a limiter to clear, not even one that says it could,
 * and a proven scope is reported as possibly limited, never as cleared.
 */
class NativeRecoveryLinuxVerdictsTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L));
    private static final ProcessIdentity REUSED_PID =
            new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_400_000L));
    private static final String UNIT = "prism-srm-limit-2071-1791970282990.scope";

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final FakeResourceLimiter limiter = new FakeResourceLimiter();
    private ActivityLog activity;
    private NativeLimitLedger own;
    private NativeRecovery recovery;

    @BeforeEach
    void setUp() {
        activity = new ActivityLog(100, () -> Instant.EPOCH);
        own = ledger("2-2");
        recovery = new NativeRecovery(limiter, own, Optional.of(folder.path()), files, activity, instance -> false);
    }

    private NativeLimitLedger ledger(String instance) {
        return new NativeLimitLedger(Optional.of(folder.path()), instance, files, Runnable::run);
    }

    private static LimitRecord linux(String instance) {
        EffectiveLimits values =
                EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.of(512L << 20), MemoryBoundary.THROTTLE);
        return LimitRecord.intended(DEMO, "demo", values, instance)
                .applied(values)
                .withProvenance(new ScopeProvenance(
                        UNIT,
                        Optional.of("86f1083782564429bf3a1a34ede0c182"),
                        Optional.of("/user.slice/user-1001.slice/user@1001.service/app.slice/" + UNIT)));
    }

    private RecoveredLimit recoverAs(RecoveredLimit.Verdict verdict) {
        assertThat(ledger("1-1").putDurably(linux("1-1"))).isTrue();
        limiter.recover = record -> new RecoveredLimit(record, verdict, "checked");
        List<RecoveredLimit> found = recovery.lookAtOrphans();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    /** A limiter that does not clear (recoveryClears false) is never asked to; the proven scope is carried with its evidence. */
    @Test
    void aProvenScopeOfALimiterThatDoesNotClearIsReportedAndCarriedOverWithItsProvenance() {
        RecoveredLimit found = recoverAs(RecoveredLimit.Verdict.OWNED_LIMITED);

        assertThat(found.ownershipProven()).isTrue();
        assertThat(limiter.calls).as("never asked to clear").noneMatch(call -> call.startsWith("clearOwned"));
        assertThat(own.find(DEMO))
                .as("said again by the next start, with its provenance")
                .hasValueSatisfying(r -> {
                    assertThat(r.provenance()).isPresent();
                    assertThat(r.values().memoryBoundary()).isEqualTo(MemoryBoundary.THROTTLE);
                });
        assertThat(activity.entries()).anySatisfy(entry -> {
            assertThat(entry.severity()).isEqualTo(ActivitySeverity.WARNING);
            assertThat(entry.message()).contains("systemd scope").contains("does not clear it");
        });
    }

    /**
     * Plan Rev 2.2 section 4.5 (S3B+): where the limiter clears, a proven scope is cleared during recovery, and only a
     * clearing read back as none ends its record; anything else keeps the record as CLEAR_FAILED, which blocks.
     */
    @Test
    void aProvenScopeOfALimiterThatClearsLosesItsRecordOnlyAfterAVerifiedClearing() {
        limiter.clearsAfterRecovery = true;
        limiter.clearOwned = record -> new ClearResult(
                ClearResult.Outcome.CLEARED, ControlOutcome.success(), Optional.of(EffectiveLimits.NONE));
        RecoveredLimit cleared = recoverAs(RecoveredLimit.Verdict.OWNED_LIMITED);
        assertThat(limiter.calls).contains("clearOwned 2071");
        assertThat(cleared.verdict()).isEqualTo(RecoveredLimit.Verdict.CLEARED);
        assertThat(own.find(DEMO)).as("verified as cleared: the record goes").isEmpty();
        assertThat(activity.entries())
                .anySatisfy(entry -> assertThat(entry.severity()).isEqualTo(ActivitySeverity.INFO));

        setUp();
        limiter.clearsAfterRecovery = true;
        limiter.clearOwned = record -> new ClearResult(
                ClearResult.Outcome.PARTIAL,
                ControlOutcome.of(OperationResult.NATIVE_ERROR, "MemoryHigh refused"),
                Optional.of(
                        EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(512L << 20), MemoryBoundary.THROTTLE)));
        RecoveredLimit partial = recoverAs(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY);
        assertThat(partial.verdict()).isEqualTo(RecoveredLimit.Verdict.CLEAR_FAILED);
        assertThat(partial.blocksAnotherLimiter()).isTrue();
        assertThat(own.find(DEMO))
                .as("not verified: kept as evidence")
                .hasValueSatisfying(r -> assertThat(r.provenance()).isPresent());

        setUp();
        limiter.clearsAfterRecovery = true;
        limiter.clearOwned = record -> new ClearResult(
                ClearResult.Outcome.NOT_DONE,
                ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the scope is gone"),
                Optional.empty());
        assertThat(recoverAs(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY).verdict())
                .isEqualTo(RecoveredLimit.Verdict.GONE);
        assertThat(own.find(DEMO)).isEmpty();
    }

    /**
     * Test review S9: every clearing that is not verified keeps the record as ownership evidence and blocks; a limiter
     * that does not clear leaves the verdict as found. No outcome but a verified clearing or a gone scope drops it.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"UNVERIFIED", "NOT_OWNED", "PARTIAL", "NOT_DONE_ERROR", "UNSUPPORTED", "THROWS"})
    void aClearingThatIsNotVerifiedKeepsTheEvidence(String outcome) {
        limiter.clearsAfterRecovery = true;
        limiter.clearOwned = record -> switch (outcome) {
            case "UNVERIFIED" ->
                new ClearResult(
                        ClearResult.Outcome.UNVERIFIED,
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "the limits could not be read back"),
                        Optional.empty());
            case "NOT_OWNED" ->
                new ClearResult(
                        ClearResult.Outcome.NOT_OWNED,
                        ControlOutcome.of(OperationResult.ACCESS_DENIED, "ownership is not proven now"),
                        Optional.empty());
            case "PARTIAL" ->
                new ClearResult(
                        ClearResult.Outcome.PARTIAL,
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "MemoryHigh refused"),
                        Optional.of(EffectiveLimits.of(
                                OptionalInt.empty(), OptionalLong.of(512L << 20), MemoryBoundary.THROTTLE)));
            case "NOT_DONE_ERROR" ->
                new ClearResult(
                        ClearResult.Outcome.NOT_DONE,
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "every property was refused"),
                        Optional.empty());
            case "UNSUPPORTED" -> ClearResult.unsupported("not this limiter");
            default -> throw new IllegalStateException("the manager went away");
        };

        RecoveredLimit found = recoverAs(RecoveredLimit.Verdict.OWNED_LIMITED);

        assertThat(limiter.calls).contains("clearOwned 2071");
        assertThat(found.verdict())
                .isEqualTo(
                        outcome.equals("UNSUPPORTED")
                                ? RecoveredLimit.Verdict.OWNED_LIMITED
                                : RecoveredLimit.Verdict.CLEAR_FAILED);
        assertThat(found.blocksAnotherLimiter()).isTrue();
        assertThat(own.find(DEMO))
                .as("kept as evidence")
                .hasValueSatisfying(r -> assertThat(r.provenance()).isPresent());
    }

    /**
     * Review follow-up O9, as amended by plan Rev 2.2 section 4.5 for S3B+: a proven scope that holds only the processes
     * an exited target started warns and blocks no identity (the exited identity cannot return; a new process with the
     * same PID has another start time), but its record is carried as ownership evidence until the scope is cleared,
     * empty or gone. The processes in it are protected by the scope-membership rule, not by the record.
     */
    @Test
    void aScopeHoldingOnlyTheTargetsChildrenWarnsBlocksNoIdentityAndKeepsItsEvidence() {
        RecoveredLimit found = recoverAs(RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY);

        assertThat(found.ownershipProven()).isTrue();
        assertThat(found.maybeStillLimited()).isTrue();
        assertThat(found.blocksAnotherLimiter()).isFalse();
        assertThat(found.retainsProvenance()).isTrue();
        assertThat(own.find(DEMO))
                .as("carried as ownership evidence, though the process it names has exited")
                .hasValueSatisfying(r -> assertThat(r.provenance()).isPresent());
        assertThat(Files.exists(NativeLimitLedger.file(folder.path(), "1-1")))
                .as("the earlier file goes once the evidence is carried")
                .isFalse();
        assertThat(recovery.mayStillCap(DEMO)).isEmpty();
        assertThat(recovery.mayStillCap(REUSED_PID))
                .as("a new process with the same PID")
                .isEmpty();
        assertThat(activity.entries()).anySatisfy(entry -> {
            assertThat(entry.severity()).isEqualTo(ActivitySeverity.WARNING);
            assertThat(entry.message())
                    .contains("demo (PID 2071)")
                    .contains("has exited")
                    .contains("processes it started")
                    .contains("does not clear it")
                    .doesNotContain("starts no second limiter")
                    .doesNotContain("demo (PID 2071) may still be limited");
        });
    }

    /** The contrast: a proven scope that still holds the recorded process blocks exactly that identity. */
    @Test
    void aScopeHoldingTheRecordedProcessBlocksExactlyItsIdentity() {
        RecoveredLimit found = recoverAs(RecoveredLimit.Verdict.OWNED_LIMITED);

        assertThat(found.blocksAnotherLimiter()).isTrue();
        assertThat(recovery.mayStillCap(DEMO)).isPresent();
        assertThat(recovery.mayStillCap(REUSED_PID))
                .as("same PID, another start time")
                .isEmpty();
    }

    @Test
    void everyLinuxVerdictIsReportedAndCarriedByTheIdentityOrTheProvenanceRule() {
        for (RecoveredLimit.Verdict verdict : List.of(
                RecoveredLimit.Verdict.OWNED_CHILDREN_ONLY,
                RecoveredLimit.Verdict.CLEAR_FAILED,
                RecoveredLimit.Verdict.UNPROVEN_SCOPE,
                RecoveredLimit.Verdict.FOREIGN_MEMBER,
                RecoveredLimit.Verdict.CLEARED)) {
            setUp();
            limiter.calls.clear();
            RecoveredLimit found = recoverAs(verdict);
            assertThat(limiter.calls).as(verdict.name()).noneMatch(call -> call.startsWith("clearOwned"));
            ActivitySeverity expected =
                    verdict == RecoveredLimit.Verdict.CLEARED ? ActivitySeverity.INFO : ActivitySeverity.WARNING;
            assertThat(activity.entries()).as(verdict.name()).anySatisfy(entry -> {
                assertThat(entry.severity()).isEqualTo(expected);
                assertThat(entry.message()).contains("demo (PID 2071)");
            });
            assertThat(own.find(DEMO).isPresent())
                    .as(verdict.name())
                    .isEqualTo(found.blocksAnotherLimiter() || found.retainsProvenance());
        }
    }
}
