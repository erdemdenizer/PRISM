package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * The CPU quota conversion (S2): Prism's whole-machine percentage P over N logical processors to systemd's
 * CPUQuotaPerSecUSec and the kernel's cpu.max, and back from a read-back; exact arithmetic, refused inputs instead of
 * an overflow, and a read-back Prism never writes recognised as foreign.
 */
class CpuQuotaTest {

    private static CgroupV2.CpuMax max(String text) {
        return CgroupV2.cpuMax(text).orElseThrow();
    }

    @Test
    void theExamplesOfThePlan() {
        assertThat(CpuQuota.cpuMaxText(25, 8)).isEqualTo("200000 100000");
        assertThat(CpuQuota.cpuMaxText(1, 1)).isEqualTo("1000 100000");
        assertThat(CpuQuota.cpuMaxText(1, 16)).isEqualTo("16000 100000");
        assertThat(CpuQuota.cpuMaxText(50, 16)).isEqualTo("800000 100000");
        assertThat(CpuQuota.cpuMaxText(100, 8)).isEqualTo("max 100000");
        assertThat(CpuQuota.quotaPerSecondUsec(25, 8)).isEqualTo(OptionalLong.of(2_000_000));
        assertThat(CpuQuota.quotaPerSecondUsec(100, 2)).isEmpty();
    }

    @Test
    void ciOnesTwentyPercentOfOneCpuIsTenPercentOfTheTwoCpuRunner() {
        // CPUQuota=20% is 200 ms per second: cpu.max 20000 100000 (CI-1, run 38542933245).
        assertThat(CpuQuota.share(max("20000 100000"), 2)).isEqualTo(new CpuQuota.Share(CpuQuota.Kind.EXACT, 1_000));
        assertThat(CpuQuota.shareOfQuotaPerSecond(200_000, 2))
                .isEqualTo(new CpuQuota.Share(CpuQuota.Kind.EXACT, 1_000));
        assertThat(CpuQuota.share(max("20000 100000"), 1)).isEqualTo(new CpuQuota.Share(CpuQuota.Kind.EXACT, 2_000));
    }

    @Test
    void everyPercentageRoundTripsExactlyOnEveryProcessorCount() {
        for (int processors : List.of(1, 2, 3, 7, 8, 16, 64, 1024, CpuQuota.MOST_PROCESSORS)) {
            for (int percent = 1; percent <= 100; percent++) {
                CpuQuota.Share fromMax = CpuQuota.share(max(CpuQuota.cpuMaxText(percent, processors)), processors);
                OptionalLong perSecond = CpuQuota.quotaPerSecondUsec(percent, processors);
                CpuQuota.Share fromProperty = CpuQuota.shareOfQuotaPerSecond(
                        perSecond.isPresent() ? perSecond.getAsLong() : CpuQuota.INFINITY, processors);
                if (percent == 100) {
                    assertThat(fromMax).isEqualTo(CpuQuota.Share.UNLIMITED);
                    assertThat(fromProperty).isEqualTo(CpuQuota.Share.UNLIMITED);
                } else {
                    CpuQuota.Share expected = new CpuQuota.Share(CpuQuota.Kind.EXACT, percent * 100);
                    assertThat(fromMax).as(percent + "% of " + processors).isEqualTo(expected);
                    assertThat(fromProperty).as(percent + "% of " + processors).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void aReadBackPrismNeverWritesIsForeign() {
        assertThat(CpuQuota.share(max("12345 100000"), 8).kind()).isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.share(max("300000 100000"), 2).kind())
                .as("above the whole machine")
                .isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.share(max("1 1000000"), 1).kind())
                .as("below a hundredth of a percent")
                .isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.share(max("999999999999999999 100000"), 2).kind())
                .as("an overflow")
                .isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.shareOfQuotaPerSecond(0, 2).kind()).isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.shareOfQuotaPerSecond(-2, 2).kind()).isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.shareOfQuotaPerSecond(Long.MAX_VALUE, 2).kind()).isEqualTo(CpuQuota.Kind.FOREIGN);
        assertThat(CpuQuota.share(max("max 100000"), 4)).isEqualTo(CpuQuota.Share.UNLIMITED);
    }

    @Test
    void inputsOutsideWhatPrismMeasuresAreRefused() {
        for (int[] bad : List.of(
                new int[] {0, 1},
                new int[] {101, 1},
                new int[] {-5, 1},
                new int[] {20, 0},
                new int[] {20, -1},
                new int[] {20, CpuQuota.MOST_PROCESSORS + 1})) {
            assertThatThrownBy(() -> CpuQuota.cpuMaxText(bad[0], bad[1])).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> CpuQuota.quotaPerSecondUsec(bad[0], bad[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CpuQuota.share(max("max 100000"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuQuota.Share(CpuQuota.Kind.EXACT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CpuQuota.Share(CpuQuota.Kind.FOREIGN, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
