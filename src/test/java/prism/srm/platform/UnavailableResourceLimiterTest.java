package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Where this version has no native limiter (Linux, macOS), nothing native can have been left. */
class UnavailableResourceLimiterTest {

    @Test
    void noEarlierSessionCanHaveLeftANativeLimitWhereThereIsNoNativeLimiter() {
        assertThat(UnavailableResourceLimiter.here().mayHaveLeftLimits()).isFalse();
    }
}
