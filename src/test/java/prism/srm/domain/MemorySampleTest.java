package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemorySampleTest {

    @Test
    void usedIsTotalMinusAvailable() {
        MemorySample sample = new MemorySample(16_000, 4_000, Optional.empty());

        assertThat(sample.usedBytes()).isEqualTo(12_000);
        assertThat(sample.usedPercent()).isEqualTo(75.0);
    }

    @Test
    void theBoundariesAreNoneAndAllOfTheMemory() {
        assertThat(new MemorySample(16_000, 16_000, Optional.empty()).usedPercent())
                .isEqualTo(0.0);
        assertThat(new MemorySample(16_000, 0, Optional.empty()).usedPercent()).isEqualTo(100.0);
    }

    @Test
    void usedPercentIsExactForSizesBeyondIntRange() {
        long total = 64L * 1024 * 1024 * 1024;

        assertThat(new MemorySample(total, total / 4, Optional.empty()).usedPercent())
                .isEqualTo(75.0);
    }

    @Test
    void rejectsReadingsThatCannotBeTrue() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MemorySample(0, 0, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemorySample(1_000, -1, Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> new MemorySample(1_000, 1_001, Optional.empty()));
    }

    @Test
    void swapWithoutASizeIsNotASwapSpace() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SwapUsage(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new SwapUsage(1_000, -1));
    }
}
