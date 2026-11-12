package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * The explicit clearing of an earlier session's limits is kept apart from recovery (S1): its result says exactly what
 * may have changed, a success needs a read-back of no limit, and a limiter that does not clear changes nothing. No
 * limiter of this version clears.
 */
class ClearResultTest {

    private static final LimitRecord RECORD = LimitRecord.intended(
            new ProcessIdentity(2_071, Instant.ofEpochMilli(1_791_970_282_990L)),
            "demo",
            EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty()),
            "1-1");

    @Test
    void onlyAClearingReadBackAsNoLimitIsASuccess() {
        ClearResult cleared = new ClearResult(
                ClearResult.Outcome.CLEARED, ControlOutcome.success(), Optional.of(EffectiveLimits.NONE));
        assertThat(cleared.mayHaveChanged()).isTrue();
        assertThatThrownBy(
                        () -> new ClearResult(ClearResult.Outcome.CLEARED, ControlOutcome.success(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClearResult(
                        ClearResult.Outcome.CLEARED,
                        ControlOutcome.success(),
                        Optional.of(EffectiveLimits.of(OptionalInt.of(2_000), OptionalLong.empty()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClearResult(
                        ClearResult.Outcome.PARTIAL, ControlOutcome.success(), Optional.of(EffectiveLimits.NONE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClearResult(
                        ClearResult.Outcome.UNVERIFIED,
                        ControlOutcome.of(OperationResult.NATIVE_ERROR, "no read-back"),
                        Optional.of(EffectiveLimits.NONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatMayHaveChangedFollowsTheOutcome() {
        ControlOutcome failed = ControlOutcome.of(OperationResult.NATIVE_ERROR, "x");
        assertThat(new ClearResult(ClearResult.Outcome.PARTIAL, failed, Optional.empty()).mayHaveChanged())
                .isTrue();
        assertThat(new ClearResult(ClearResult.Outcome.UNVERIFIED, failed, Optional.empty()).mayHaveChanged())
                .isTrue();
        assertThat(new ClearResult(ClearResult.Outcome.NOT_OWNED, failed, Optional.empty()).mayHaveChanged())
                .isFalse();
        assertThat(new ClearResult(ClearResult.Outcome.NOT_DONE, failed, Optional.empty()).mayHaveChanged())
                .isFalse();
        assertThat(ClearResult.unsupported("x").mayHaveChanged()).isFalse();
    }

    @Test
    void theLimiterOfASystemWithoutNativeLimitsNeverClears() {
        ResourceLimiter limiter = UnavailableResourceLimiter.here();
        assertThat(limiter.recoveryClears()).isFalse();
        ClearResult result = limiter.clearOwned(RECORD);
        assertThat(result.result()).isEqualTo(ClearResult.Outcome.UNSUPPORTED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(result.mayHaveChanged()).isFalse();
    }
}
