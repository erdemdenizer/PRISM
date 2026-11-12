package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.MemoryBoundary;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ClearResult;

/**
 * What milestone 12's contracts change for Job Objects (S1): nothing a milestone 10 limit does. A throttling memory
 * limit, which Job Objects do not have, is refused before any job is made; recovery stays read-only, and the limiter
 * never clears an earlier session's limits.
 */
class WindowsJobLimiterBoundaryTest {

    private static final int PID = 4100;
    private static final Instant START = Instant.parse("2026-10-10T08:00:00.123Z");
    private static final ProcessIdentity DEMO = new ProcessIdentity(PID, START);
    private static final long MIB = 1L << 20;

    private final FakeWin32 win32 = new FakeWin32();
    private final WindowsJobLimiter limiter = new WindowsJobLimiter(win32);

    @BeforeEach
    void demo() {
        win32.process(PID, START);
        limiter.capabilities();
        win32.calls.clear();
    }

    @Test
    void aThrottlingMemoryLimitIsRefusedBeforeAnyJobIsMade() {
        LimitSpec throttle = new LimitSpec(OptionalInt.of(20), OptionalLong.of(512 * MIB), MemoryBoundary.THROTTLE);

        ApplyResult result = limiter.apply(DEMO, throttle);

        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.NOT_ASSIGNED);
        assertThat(result.outcome().result()).isEqualTo(OperationResult.UNSUPPORTED);
        assertThat(result.outcome().detail())
                .hasValueSatisfying(d -> assertThat(d).contains("throttling"));
        assertThat(result.fallbackEligible()).isFalse();
        assertThat(limiter.hold(DEMO)).isEmpty();
        assertThat(win32.calls).as("no system call at all").isEmpty();
        assertThat(win32.openJobHandles()).isEmpty();
    }

    @Test
    void aHardMemoryLimitIsStillAppliedAsInMilestoneTen() {
        ApplyResult result =
                limiter.apply(DEMO, new LimitSpec(OptionalInt.of(20), OptionalLong.of(512 * MIB), MemoryBoundary.HARD));
        assertThat(result.phase()).isEqualTo(ApplyResult.Phase.ENFORCED);
        limiter.releaseAll(java.time.Duration.ofSeconds(1));
    }

    @Test
    void recoveryStaysReadOnlyAndTheLimiterNeverClears() {
        LimitRecord record = LimitRecord.intended(
                DEMO, "demo", new LimitSpec(OptionalInt.of(20), OptionalLong.empty()).effective(), "1-1");
        assertThat(limiter.recoveryClears()).isFalse();
        ClearResult cleared = limiter.clearOwned(record);
        assertThat(cleared.result()).isEqualTo(ClearResult.Outcome.UNSUPPORTED);
        assertThat(cleared.mayHaveChanged()).isFalse();
        assertThat(win32.calls).as("clearOwned touches nothing").isEmpty();
    }
}
