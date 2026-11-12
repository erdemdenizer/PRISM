package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The precondition of the native suites (plan §11.6): a skip where required is a failure, never a pass. */
class NativeValidationTest {

    @Test
    void aChildInNoJobRunsTheTest() {
        assertThat(NativeValidation.decide(Optional.of(false), true)).isEqualTo(NativeValidation.Decision.RUN);
        assertThat(NativeValidation.decide(Optional.of(false), false)).isEqualTo(NativeValidation.Decision.RUN);
    }

    @Test
    void aChildInAJobOrOfUnknownMembershipFailsWhereRequiredAndIsSkippedElsewhere() {
        for (Optional<Boolean> child : java.util.List.of(Optional.of(true), Optional.<Boolean>empty())) {
            assertThat(NativeValidation.decide(child, true)).isEqualTo(NativeValidation.Decision.FAIL);
            assertThat(NativeValidation.decide(child, false)).isEqualTo(NativeValidation.Decision.SKIP);
        }
    }
}
