package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The memory boundary and the scope provenance milestone 12 adds to the native-limit records (S1): a hard limit stays
 * what milestone 10 had, byte for byte in its value and its words; a throttling limit is never taken for a hard one
 * of the same size; the provenance goes with a record through every step and never appears on its own.
 */
class MemoryBoundaryRecordsTest {

    private static final long MIB = 1L << 20;
    private static final ProcessIdentity DEMO = new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L));
    private static final ScopeProvenance SCOPE = new ScopeProvenance(
            "prism-srm-limit-2071-1791970282990.scope",
            Optional.of("86f1083782564429bf3a1a34ede0c182"),
            Optional.of(
                    "/user.slice/user-1001.slice/user@1001.service/app.slice/prism-srm-limit-2071-1791970282990.scope"));

    private static EffectiveLimits memory(long mib, MemoryBoundary boundary) {
        return EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(mib * MIB), boundary);
    }

    @Test
    void aLimitWithoutMemoryIsAlwaysHardSoTwoSuchLimitsAreEqual() {
        EffectiveLimits cpuOnly =
                EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty(), MemoryBoundary.THROTTLE);
        assertThat(cpuOnly.memoryBoundary()).isEqualTo(MemoryBoundary.HARD);
        assertThat(cpuOnly).isEqualTo(EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty()));
        assertThat(EffectiveLimits.NONE.withMemoryBoundary(MemoryBoundary.THROTTLE))
                .isEqualTo(EffectiveLimits.NONE);
        assertThat(memory(512, MemoryBoundary.THROTTLE)
                        .withMemoryBytes(OptionalLong.empty())
                        .memoryBoundary())
                .isEqualTo(MemoryBoundary.HARD);
    }

    @Test
    void theMilestoneTenConstructorsMeanAHardLimit() {
        EffectiveLimits old = new EffectiveLimits(OptionalInt.of(2_000), OptionalLong.of(512 * MIB), Optional.empty());
        assertThat(old.memoryBoundary()).isEqualTo(MemoryBoundary.HARD);
        assertThat(EffectiveLimits.of(OptionalInt.empty(), OptionalLong.of(512 * MIB))
                        .memoryBoundary())
                .isEqualTo(MemoryBoundary.HARD);
        assertThat(new LimitSpec(OptionalInt.of(20), OptionalLong.of(512 * MIB)).memoryBoundary())
                .isEqualTo(MemoryBoundary.HARD);
        assertThat(LimitSpec.NONE.memoryBoundary()).isEqualTo(MemoryBoundary.HARD);
    }

    @Test
    void aHardLimitKeepsItsMilestoneTenValueAndWords() {
        EffectiveLimits hard = memory(512, MemoryBoundary.HARD);
        assertThat(hard.value(LimitKind.MEMORY)).isEqualTo(OptionalLong.of(512 * MIB));
        assertThat(hard.describe()).isEqualTo("memory 512 MiB");
        assertThat(memory(512, MemoryBoundary.THROTTLE).describe()).isEqualTo("memory throttle 512 MiB");
    }

    @Test
    void aThrottlingLimitIsNeverTakenForAHardOneOfTheSameSize() {
        EffectiveLimits hard = memory(512, MemoryBoundary.HARD);
        EffectiveLimits throttle = memory(512, MemoryBoundary.THROTTLE);
        assertThat(throttle).isNotEqualTo(hard);
        assertThat(throttle.value(LimitKind.MEMORY)).isNotEqualTo(hard.value(LimitKind.MEMORY));
        LimitRecord record = LimitRecord.intended(DEMO, "demo", throttle, "1-1");
        assertThat(record.admits(throttle)).isTrue();
        assertThat(record.admits(hard)).as("the same bytes as a hard limit").isFalse();
        assertThat(record.admits(EffectiveLimits.NONE)).isFalse();
    }

    @Test
    void aChangeFromThrottleToHardAdmitsEachKindsOldOrNewValue() {
        LimitRecord record = LimitRecord.intended(DEMO, "demo", memory(512, MemoryBoundary.THROTTLE), "1-1")
                .applied(memory(512, MemoryBoundary.THROTTLE))
                .changing(memory(256, MemoryBoundary.HARD));
        assertThat(record.admits(memory(512, MemoryBoundary.THROTTLE))).isTrue();
        assertThat(record.admits(memory(256, MemoryBoundary.HARD))).isTrue();
        assertThat(record.admits(memory(256, MemoryBoundary.THROTTLE))).isFalse();
        assertThat(record.admits(memory(512, MemoryBoundary.HARD))).isFalse();
        assertThat(record.describe()).contains("memory throttle 512 MiB or memory 256 MiB");
    }

    @Test
    void aSpecCarriesItsBoundaryIntoWhatTheSystemShouldHoldAndThroughItsChanges() {
        LimitSpec spec = new LimitSpec(OptionalInt.of(20), OptionalLong.of(512 * MIB), MemoryBoundary.THROTTLE);
        assertThat(spec.effective())
                .isEqualTo(
                        EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.of(512 * MIB), MemoryBoundary.THROTTLE));
        assertThat(spec.withCpuPercent(OptionalInt.empty()).memoryBoundary()).isEqualTo(MemoryBoundary.THROTTLE);
        assertThat(spec.withMemoryBytes(OptionalLong.of(256 * MIB)).memoryBoundary())
                .isEqualTo(MemoryBoundary.THROTTLE);
        assertThat(spec.withMemoryBytes(OptionalLong.empty()).memoryBoundary()).isEqualTo(MemoryBoundary.HARD);
        assertThat(spec.withMemoryBoundary(MemoryBoundary.HARD).effective().memoryBoundary())
                .isEqualTo(MemoryBoundary.HARD);
    }

    @Test
    void theProvenanceGoesWithTheRecordThroughEveryStep() {
        LimitRecord record = LimitRecord.intended(DEMO, "demo", memory(512, MemoryBoundary.THROTTLE), "1-1")
                .withProvenance(SCOPE);
        assertThat(record.applied(memory(512, MemoryBoundary.THROTTLE)).provenance())
                .contains(SCOPE);
        assertThat(record.changing(EffectiveLimits.NONE).provenance()).contains(SCOPE);
        assertThat(record.flagged(LimitRecord.Flag.UNVERIFIED).provenance()).contains(SCOPE);
        assertThat(record.carriedInto("2-2").provenance()).contains(SCOPE);
        assertThat(record.cleared().provenance())
                .as("a cleared limit leaves the process in its scope")
                .contains(SCOPE);
    }

    @Test
    void aMilestoneTenRecordHasNoProvenance() {
        LimitRecord record = new LimitRecord(
                DEMO, "demo", memory(512, MemoryBoundary.HARD), Optional.empty(), Set.of(), LedgerState.APPLIED, "1-1");
        assertThat(record.provenance()).isEmpty();
        assertThat(LimitRecord.intended(DEMO, "demo", EffectiveLimits.NONE, "1-1")
                        .provenance())
                .isEmpty();
    }
}
