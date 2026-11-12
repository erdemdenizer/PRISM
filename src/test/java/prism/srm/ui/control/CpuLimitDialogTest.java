package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import prism.srm.ui.LimitsFixtures;

/** LIM-2: a CPU limit is a whole percentage from 1 to 100; CAP-5: the dialog says what the limit is. */
class CpuLimitDialogTest {

    @Test
    void onlyAWholeNumberFromOneToAHundredIsALimit() {
        assertThat(CpuLimitDialog.parse("1")).hasValue(1);
        assertThat(CpuLimitDialog.parse("30")).hasValue(30);
        assertThat(CpuLimitDialog.parse("100")).hasValue(100);
        assertThat(CpuLimitDialog.parse("0")).isEmpty();
        assertThat(CpuLimitDialog.parse("101")).isEmpty();
        assertThat(CpuLimitDialog.parse("")).isEmpty();
        assertThat(CpuLimitDialog.parse("-5")).isEmpty();
        assertThat(CpuLimitDialog.parse("2.5")).isEmpty();
        assertThat(CpuLimitDialog.parse("0030")).isEmpty();
        assertThat(CpuLimitDialog.parse(null)).isEmpty();
    }

    @Test
    void theDialogCallsAnEmulatedLimitApproximateAndAnUnavailableOneByItsReason() {
        assertThat(CpuLimitDialog.means(LimitsFixtures.EMULATED))
                .startsWith("Emulated")
                .contains("approximate");
        assertThat(CpuLimitDialog.means(LimitsFixtures.unavailable("no pidfd"))).contains("no pidfd");
    }
}
