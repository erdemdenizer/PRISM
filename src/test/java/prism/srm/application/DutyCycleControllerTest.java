package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.LimitStatus;

/**
 * The feedback of the emulated CPU limiter against a simulated target (MILESTONES §9): a target
 * that would use {@code demand} of the machine uses {@code demand x run share} when it runs that
 * share of each cycle. No clock and no thread.
 */
class DutyCycleControllerTest {

    private static final DutyCycleController.Tuning TUNING = DutyCycleController.Tuning.DEFAULTS;

    /** Runs the controller against a target of fixed demand; returns the use of the last interval. */
    private static double converge(DutyCycleController controller, double demand, int intervals) {
        double use = 0;
        for (int i = 0; i < intervals; i++) {
            double ran = controller.runShare();
            use = demand * ran;
            controller.measured(use, ran);
        }
        return use;
    }

    @Test
    void aSingleThreadedTargetOnEightProcessorsIsNeverSuspendedUnderAThirtyPercentLimit() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.30);

        for (int i = 0; i < 50; i++) {
            controller.measured(0.125, controller.runShare());
            assertThat(controller.throttles()).as("interval " + i).isFalse();
        }
        assertThat(controller.judgement().status()).isEqualTo(LimitStatus.ACTIVE_NOT_THROTTLING);
    }

    @Test
    void theSameTargetUnderAFivePercentLimitConvergesNearFivePercent() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.05);

        double use = converge(controller, 0.125, 20);

        assertThat(use).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.005 + 0.1 * 0.05));
        assertThat(controller.judgement().status()).isEqualTo(LimitStatus.ACTIVE_THROTTLING);
    }

    @Test
    void aMultiThreadedTargetUsingEightyPercentConvergesNearTwentyUnderATwentyPercentLimit() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.20);

        double use = converge(controller, 0.80, 20);

        assertThat(use).isCloseTo(0.20, org.assertj.core.data.Offset.offset(0.02));
        assertThat(controller.runShare()).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    void aNoisyReadingInsideTheDeadBandChangesNothing() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.20);
        converge(controller, 0.80, 20);
        double settled = controller.runShare();

        for (double noise : new double[] {0.19, 0.21, 0.205, 0.195}) {
            controller.measured(noise, settled);
            assertThat(controller.runShare()).isEqualTo(settled);
        }
    }

    @Test
    void oneUpdateNeverMovesTheRunShareByMoreThanItsBound() {
        DutyCycleController first = new DutyCycleController(TUNING, 0.01);
        first.measured(1.0, 1.0);
        assertThat(first.runShare()).as("the largest step down").isEqualTo(1 - TUNING.stepDown());

        java.util.Random random = new java.util.Random(9);
        DutyCycleController controller = new DutyCycleController(TUNING, 0.15);
        for (int i = 0; i < 2_000; i++) {
            double before = controller.runShare();
            controller.measured(random.nextDouble(), Math.max(0.02, before));
            double after = controller.runShare();
            assertThat(after - before)
                    .as("step " + i)
                    .isBetween(-TUNING.stepDown() * before - 1e-12, TUNING.stepUp() * before + 1e-12);
            assertThat(after).isBetween(TUNING.minRunShare(), 1.0);
        }
    }

    @Test
    void aLimitBelowTheFloorIsReportedAboveTheTargetWithTheLowestUseNeverAsThrottling() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.01);

        double use = converge(controller, 1.0, 30);

        assertThat(controller.runShare()).isEqualTo(TUNING.minRunShare());
        assertThat(use).isGreaterThan(0.01);
        DutyCycleController.Judgement judgement = controller.judgement();
        assertThat(judgement.status()).isEqualTo(LimitStatus.ACTIVE_ABOVE_TARGET);
        assertThat(judgement.detail().orElseThrow()).startsWith("floor");
        assertThat(judgement.note()).isEqualTo(prism.srm.domain.LimitNote.FLOOR);
        assertThat(judgement.lowest().getAsDouble()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aFullLimitNeverSuspends() {
        DutyCycleController controller = new DutyCycleController(TUNING, 1.0);

        converge(controller, 1.0, 20);

        assertThat(controller.throttles()).isFalse();
    }

    @Test
    void aZeroUseLetsTheTargetRunInFull() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.10);
        converge(controller, 0.80, 10);

        for (int i = 0; i < 10; i++) {
            controller.measured(0, controller.runShare());
        }

        assertThat(controller.runShare()).isEqualTo(1.0);
    }

    @Test
    void anIntervalTheTargetHardlyRanSaysNothingAboutItsDemand() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.10);
        converge(controller, 0.80, 10);
        double before = controller.runShare();

        controller.measured(0, 0.01);

        assertThat(controller.runShare()).isEqualTo(before);
    }

    @Test
    void unmeasuredIntervalsLetTheTargetRunInFullAfterAFewAndAreNeverThrottling() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.10);
        converge(controller, 0.80, 10);

        controller.unmeasured();
        controller.unmeasured();
        assertThat(controller.throttles()).as("one or two keep the share").isTrue();
        controller.unmeasured();

        assertThat(controller.throttles()).isFalse();
        assertThat(controller.judgement().status()).isEqualTo(LimitStatus.ADJUSTING);
        assertThat(controller.judgement().detail().orElseThrow()).startsWith("not measured");
        assertThat(controller.judgement().note()).isEqualTo(prism.srm.domain.LimitNote.NOT_MEASURED);
    }

    @Test
    void aShareHeldElsewhereStartsTheJudgementAfresh() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.20);
        converge(controller, 0.80, 20);

        controller.suspendJudgement();

        assertThat(controller.judgement().status()).isNotEqualTo(LimitStatus.ACTIVE_THROTTLING);
    }

    @Test
    void theStatusIsAdjustingUntilThreeIntervalsWereWithinTheLimit() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.20);

        controller.measured(0.80, 1.0);

        assertThat(controller.judgement().status()).isEqualTo(LimitStatus.ADJUSTING);
    }

    @Test
    void aMeasuredUseAboveTheLimitIsNeverReportedAsThrottling() {
        DutyCycleController controller = new DutyCycleController(TUNING, 0.20);
        converge(controller, 0.80, 20);

        for (int i = 0; i < 3; i++) {
            controller.measured(0.30, controller.runShare());
        }

        assertThat(controller.judgement().status()).isNotEqualTo(LimitStatus.ACTIVE_THROTTLING);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1_000_000, 15_000_000})
    void theCycleIsTheShortestAllowedWhileSuspendingIsCheap(long suspendNanos) {
        assertThat(TUNING.cycleFor(suspendNanos)).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void theCycleGrowsWithTheCostOfASuspendWithinItsBounds() {
        assertThat(TUNING.cycleFor(110_000_000)).isEqualTo(Duration.ofMillis(1100));
        assertThat(TUNING.cycleFor(900_000_000)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aCycleShorterThanLim8AllowsIsRejected() {
        assertThatThrownBy(() -> new DutyCycleController.Tuning(
                        Duration.ofMillis(50), Duration.ofSeconds(1), 10, 0.05, 0.5, 0.1, 0.005, 0.25, 0.5, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -0.1, 1.01})
    void aLimitOutsideTheMachineIsRejected(double limit) {
        assertThatThrownBy(() -> new DutyCycleController(TUNING, limit)).isInstanceOf(IllegalArgumentException.class);
    }
}
