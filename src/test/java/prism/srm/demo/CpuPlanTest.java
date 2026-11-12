package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The share is of the whole machine (PROC-2): workers times duty over processors. */
class CpuPlanTest {

    private static final OptionalInt FEWEST = OptionalInt.empty();

    @Test
    void usesTheFewestWorkersThatCanCarryTheShare() {
        assertThat(CpuPlan.plan(25, 16, FEWEST)).isEqualTo(new CpuPlan(4, 1.0));
        assertThat(CpuPlan.plan(100, 16, FEWEST)).isEqualTo(new CpuPlan(16, 1.0));
        assertThat(CpuPlan.plan(6.25, 16, FEWEST)).isEqualTo(new CpuPlan(1, 1.0));
        assertThat(CpuPlan.plan(50, 1, FEWEST)).isEqualTo(new CpuPlan(1, 0.5));
        assertThat(CpuPlan.plan(100, 128, FEWEST)).isEqualTo(new CpuPlan(128, 1.0));
    }

    @Test
    void aShareBetweenWholeProcessorsGetsOneMoreWorkerThatIsNotFullyBusy() {
        CpuPlan thirty = CpuPlan.plan(30, 16, FEWEST);
        assertThat(thirty.workers()).isEqualTo(5);
        assertThat(thirty.dutyCycle()).isCloseTo(0.96, within(1e-12));

        CpuPlan one = CpuPlan.plan(1, 16, FEWEST);
        assertThat(one.workers()).isEqualTo(1);
        assertThat(one.dutyCycle()).isCloseTo(0.16, within(1e-12));

        CpuPlan tiny = CpuPlan.plan(0.5, 4, FEWEST);
        assertThat(tiny.workers()).isEqualTo(1);
        assertThat(tiny.dutyCycle()).isCloseTo(0.02, within(1e-12));
    }

    @Test
    void aWholeNumberOfProcessorsComputedInFloatingPointNeedsNoExtraWorker() {
        assertThat(CpuPlan.plan(12.5, 8, FEWEST)).isEqualTo(new CpuPlan(1, 1.0));
        assertThat(CpuPlan.plan(37.5, 8, FEWEST)).isEqualTo(new CpuPlan(3, 1.0));
        assertThat(CpuPlan.plan(10, 10, FEWEST)).isEqualTo(new CpuPlan(1, 1.0));
        assertThat(CpuPlan.plan(70, 10, FEWEST)).isEqualTo(new CpuPlan(7, 1.0));
    }

    @Test
    void theShareOfTheMachineIsWhatWasAsked() {
        double[] percents = {0.5, 1, 6.25, 12.5, 25, 30, 33.3, 50, 99, 100};
        int[] processors = {1, 2, 4, 8, 16, 64, 128};
        for (int n : processors) {
            for (double percent : percents) {
                CpuPlan plan = CpuPlan.plan(percent, n, FEWEST);
                assertThat(plan.workers()).isBetween(1, n);
                assertThat(plan.dutyCycle()).isGreaterThan(0).isLessThanOrEqualTo(1.0);
                assertThat(plan.machinePercent(n))
                        .as("%s%% on %d processors", percent, n)
                        .isCloseTo(percent, within(1e-9));
            }
        }
    }

    @Test
    void zeroMeansNoWorkers() {
        assertThat(CpuPlan.plan(0, 16, FEWEST)).isSameAs(CpuPlan.NONE);
        assertThat(CpuPlan.NONE.machinePercent(16)).isZero();
    }

    @Test
    void moreWorkersSpreadTheSameShare() {
        assertThat(CpuPlan.plan(25, 16, OptionalInt.of(8))).isEqualTo(new CpuPlan(8, 0.5));
        assertThat(CpuPlan.plan(25, 16, OptionalInt.of(16))).isEqualTo(new CpuPlan(16, 0.25));
        assertThat(CpuPlan.plan(25, 16, OptionalInt.of(4))).isEqualTo(new CpuPlan(4, 1.0));
    }

    @Test
    void tooFewWorkersForTheShareIsAnError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CpuPlan.plan(25, 16, OptionalInt.of(2)))
                .withMessage(
                        "--cpu 25.0 on 16 logical processors needs at least 4 workers; --workers 2 cannot reach it");
    }

    @Test
    void workersOutsideTheProcessorsOfTheMachineAreAnError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CpuPlan.plan(25, 16, OptionalInt.of(0)))
                .withMessage("--workers 0: expected 1 to 16, the logical processors of this machine");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CpuPlan.plan(25, 16, OptionalInt.of(17)))
                .withMessageContaining("--workers 17");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CpuPlan.plan(1, 16, OptionalInt.of(Integer.MAX_VALUE)))
                .withMessageContaining("--workers 2147483647");
    }

    @Test
    void workersWithoutALoadIsAnError() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CpuPlan.plan(0, 16, OptionalInt.of(1)))
                .withMessage("--workers needs --cpu above 0");
    }

    @Test
    void rejectsSharesOutsideTheRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> CpuPlan.plan(-1, 16, FEWEST));
        assertThatIllegalArgumentException().isThrownBy(() -> CpuPlan.plan(100.01, 16, FEWEST));
        assertThatIllegalArgumentException().isThrownBy(() -> CpuPlan.plan(Double.NaN, 16, FEWEST));
        assertThatIllegalArgumentException().isThrownBy(() -> CpuPlan.plan(25, 0, FEWEST));
    }
}
