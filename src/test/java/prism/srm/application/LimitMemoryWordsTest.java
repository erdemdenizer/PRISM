package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.LimitCapability;

/**
 * The memory limit in force is described in the words of its mechanism: "committed" is Windows' Job Object; a
 * systemd scope's memory.high only holds the process near the amount (found on the Linux desktop, 2026-11-28).
 */
class LimitMemoryWordsTest {

    private static final long MIB_256 = 256L << 20;

    @Test
    void aJobObjectLimitsCommittedMemory() {
        LimitCapability job = LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT);

        assertThat(LimitService.memoryInForce(job, MIB_256)).isEqualTo("at most 256 MiB committed");
    }

    @Test
    void aScopesThrottleHoldsTheProcessNearTheAmountAndSaysNothingOfCommit() {
        LimitCapability high = LimitCapability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.SYSTEMD_SCOPE);

        assertThat(LimitService.memoryInForce(high, MIB_256))
                .isEqualTo("held near 256 MiB")
                .doesNotContain("committed");
    }

    @Test
    void aScopesHardLimitCapsTheMemory() {
        LimitCapability max = LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.SYSTEMD_SCOPE);

        assertThat(LimitService.memoryInForce(max, MIB_256)).isEqualTo("at most 256 MiB");
    }
}
