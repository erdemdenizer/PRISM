package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The records of milestone 10: limits, the ledger record and its admissible values (plan revision 4.1). */
class NativeLimitRecordsTest {

    private static final ProcessIdentity DEMO = new ProcessIdentity(4242, Instant.ofEpochMilli(1_700_000_000_123L));
    private static final long MIB = 1L << 20;

    private static EffectiveLimits limits(int percent, long mib) {
        return EffectiveLimits.of(OptionalInt.of(percent * 100), OptionalLong.of(mib * MIB));
    }

    @Test
    void aSpecBecomesTheRateOfHundredthsOfAPercent() {
        assertThat(new LimitSpec(OptionalInt.of(20), OptionalLong.empty())
                        .effective()
                        .cpuRate())
                .hasValue(2000);
        assertThat(new LimitSpec(OptionalInt.of(1), OptionalLong.empty())
                        .effective()
                        .cpuRate())
                .hasValue(100);
        assertThat(new LimitSpec(OptionalInt.of(100), OptionalLong.empty())
                        .effective()
                        .cpuRate())
                .hasValue(10_000);
        assertThat(LimitSpec.NONE.effective()).isEqualTo(EffectiveLimits.NONE);
        assertThatThrownBy(() -> new LimitSpec(OptionalInt.of(0), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitSpec(OptionalInt.of(101), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitSpec(OptionalInt.empty(), OptionalLong.of(MIB - 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsAreDescribedWithoutTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(limits(20, 512).describe()).isEqualTo("CPU 20 %, memory 512 MiB");
            assertThat(EffectiveLimits.of(OptionalInt.of(2050), OptionalLong.empty())
                            .describe())
                    .isEqualTo("CPU 20.50 %");
            assertThat(EffectiveLimits.NONE.describe()).isEqualTo("no limit");
            assertThat(EffectiveLimits.bytes(2L << 30)).isEqualTo("2 GiB");
        } finally {
            Locale.setDefault(before);
        }
    }

    /** The example of the external review of revision 4: a crash after the CPU call, before the memory call. */
    @Test
    void aChangeUnderWayAdmitsEveryPerKindCombinationOfItsTwoEnds() {
        LimitRecord record = LimitRecord.intended(DEMO, "demo", limits(20, 512), "1-1")
                .applied(limits(20, 512))
                .changing(limits(30, 256));

        assertThat(record.admits(limits(20, 512))).isTrue();
        assertThat(record.admits(limits(30, 256))).isTrue();
        assertThat(record.admits(limits(30, 512))).as("CPU set, memory not").isTrue();
        assertThat(record.admits(limits(20, 256)))
                .as("memory set first, CPU not")
                .isTrue();
        assertThat(record.admits(limits(40, 512))).as("a value Prism never set").isFalse();
        assertThat(record.admits(limits(30, 128))).isFalse();
        assertThat(record.admits(EffectiveLimits.NONE)).isFalse();
        assertThat(record.admits(new EffectiveLimits(
                        OptionalInt.of(2000), OptionalLong.of(512 * MIB), Optional.of("weight-based"))))
                .as("anything foreign is never Prism's")
                .isFalse();
        assertThat(record.describe()).isEqualTo("CPU 20 % or CPU 30 %; memory 512 MiB or memory 256 MiB");
    }

    @Test
    void aChangeOfOneKindAdmitsOnlyTheOtherKindAsItWas() {
        LimitRecord record = LimitRecord.intended(DEMO, "demo", limits(20, 512), "1-1")
                .applied(limits(20, 512))
                .changing(EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(512 * MIB)));

        assertThat(record.admissible(LimitKind.MEMORY)).containsExactly(OptionalLong.of(512 * MIB));
        assertThat(record.admits(EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(512 * MIB))))
                .isTrue();
        assertThat(record.admits(limits(20, 512))).isTrue();
        assertThat(record.admits(EffectiveLimits.NONE)).isFalse();
    }

    @Test
    void aRecordWithoutAChangeAdmitsExactlyItsValues() {
        LimitRecord record = LimitRecord.intended(DEMO, "demo", limits(20, 512), "1-1");

        assertThat(record.admits(limits(20, 512))).isTrue();
        assertThat(record.admits(limits(30, 512))).isFalse();
        assertThat(record.state()).isEqualTo(LedgerState.INTENDED);
        assertThat(record.cleared().state()).isEqualTo(LedgerState.CLEARED);
        assertThat(record.cleared().admits(EffectiveLimits.NONE)).isTrue();
    }

    @Test
    void aRecordStatesAChangeExactlyWhenItIsUnderWayAndHoldsNothingForeign() {
        assertThatThrownBy(() -> new LimitRecord(
                        DEMO,
                        "demo",
                        limits(20, 512),
                        Optional.empty(),
                        java.util.Set.of(),
                        LedgerState.PENDING_CHANGE,
                        "1-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LimitRecord.intended(
                        DEMO,
                        "demo",
                        new EffectiveLimits(OptionalInt.empty(), OptionalLong.empty(), Optional.of("x")),
                        "1-1"))
                .isInstanceOf(IllegalArgumentException.class);
        LimitRecord flagged =
                LimitRecord.intended(DEMO, "demo", limits(20, 512), "1-1").flagged(LimitRecord.Flag.UNCERTAIN);
        assertThat(flagged.flags()).containsExactly(LimitRecord.Flag.UNCERTAIN);
        assertThat(flagged.applied(limits(20, 512)).flags()).isEmpty();
    }

    @Test
    void aRecoveredLimitWarnsAndBlocksExactlyWhereALimitMayStillBeInForce() {
        LimitRecord record = LimitRecord.intended(DEMO, "demo", limits(20, 512), "1-1");
        for (RecoveredLimit.Verdict verdict : RecoveredLimit.Verdict.values()) {
            RecoveredLimit recovered = new RecoveredLimit(record, verdict, "");
            boolean warns = switch (verdict) {
                case GONE_CHILDREN_UNKNOWN, UNREACHABLE, ACCESS_DENIED, VALUES_MISMATCH, FAILED, HELD_ELSEWHERE -> true;
                // Linux verdicts (milestone 12): every one but a verified clearing may leave a limit in force.
                case OWNED_LIMITED, OWNED_CHILDREN_ONLY, CLEAR_FAILED, UNPROVEN_SCOPE, FOREIGN_MEMBER -> true;
                default -> false;
            };
            assertThat(recovered.maybeStillLimited()).as(verdict.name()).isEqualTo(warns);
            if (recovered.blocksAnotherLimiter()) {
                assertThat(recovered.maybeStillLimited()).isTrue();
            }
        }
        assertThat(new RecoveredLimit(record, RecoveredLimit.Verdict.GONE_CHILDREN_UNKNOWN, "").blocksAnotherLimiter())
                .as("its process is gone: no identity to block")
                .isFalse();
        assertThat(new RecoveredLimit(record, RecoveredLimit.Verdict.HELD_ELSEWHERE, "").blocksAnotherLimiter())
                .as("a running program holds a job of Prism's name on it: no second limiter")
                .isTrue();
    }

    @Test
    void settingsKeepTheMilestoneNineShapeAndAddTheMemoryLimit() {
        ManagedSettings settings = new ManagedSettings(OptionalInt.of(20), true);
        assertThat(settings.memoryLimitBytes()).isEmpty();
        assertThat(settings.emulatedCpuApproved()).isFalse();
        ManagedSettings changed =
                settings.withMemoryLimit(OptionalLong.of(256 * MIB)).withEmulatedCpuApproved(true);
        assertThat(changed.nativeSpec()).isEqualTo(new LimitSpec(OptionalInt.of(20), OptionalLong.of(256 * MIB)));
        assertThat(changed.withCpuLimit(OptionalInt.empty()).emulatedCpuApproved())
                .isTrue();
        assertThat(ManagedSettings.validMemoryBytes(MIB)).isTrue();
        assertThat(ManagedSettings.validMemoryBytes(MIB - 1)).isFalse();
    }

    @Test
    void aRequestCarriesBytesExactlyForAMemoryLimit() {
        assertThat(LimitRequest.memoryLimit(DEMO, "demo", 256 * MIB).bytes()).hasValue(256 * MIB);
        assertThatThrownBy(() -> new LimitRequest(
                        LimitAction.SET_MEMORY_LIMIT, DEMO, "demo", OptionalInt.empty(), OptionalLong.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitRequest(
                        LimitAction.SET_CPU_LIMIT, DEMO, "demo", OptionalInt.of(5), OptionalLong.of(MIB)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFallbackIsOfferedOnlyForALimitThatWasNotApplied() {
        LimitRequest request = LimitRequest.cpuLimit(DEMO, "demo", 20);
        FallbackOffer offer = new FallbackOffer("in a job", 4, 16, true);
        assertThatThrownBy(() -> new LimitResult(request, ControlOutcome.success(), "ok", "", Optional.of(offer)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LimitResult(
                                request,
                                ControlOutcome.of(OperationResult.UNSUPPORTED, "in a job"),
                                "not applied",
                                "",
                                Optional.of(offer))
                        .fallback())
                .contains(offer);
    }
}
