package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import prism.srm.domain.CpuSample;
import prism.srm.domain.CpuTicks;
import prism.srm.domain.CpuUsage;

class RateCalculatorTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void rateIsTheCounterIncreaseDividedByTheElapsedTime() {
        assertThat(RateCalculator.perSecond(1_000, 3_000, 2 * SECOND)).hasValue(1_000.0);
        assertThat(RateCalculator.perSecond(1_000, 3_000, SECOND / 2)).hasValue(4_000.0);
    }

    @Test
    void anUnchangedCounterIsARateOfZero() {
        assertThat(RateCalculator.perSecond(5_000, 5_000, SECOND)).hasValue(0.0);
    }

    @Test
    void aZeroOrNegativeIntervalYieldsNoRate() {
        assertThat(RateCalculator.perSecond(1_000, 3_000, 0)).isEmpty();
        assertThat(RateCalculator.perSecond(1_000, 3_000, -SECOND)).isEmpty();
    }

    @Test
    void aCounterResetYieldsNoRate() {
        assertThat(RateCalculator.perSecond(3_000, 0, SECOND)).isEmpty();
        assertThat(RateCalculator.perSecond(3_000, 2_999, SECOND)).isEmpty();
    }

    @Test
    void aWrapped32BitCounterYieldsNoRate() {
        long justBelowWrap = 0xFFFF_FFFFL - 100;

        assertThat(RateCalculator.perSecond(justBelowWrap, 400, SECOND)).isEmpty();
    }

    @Test
    void theLargestPossibleIncreaseStaysFinite() {
        OptionalDouble rate = RateCalculator.perSecond(0, Long.MAX_VALUE, SECOND);

        assertThat(rate).isPresent();
        assertThat(rate.getAsDouble()).isFinite().isCloseTo(9.223372036854776e18, within(1e4));
    }

    @Test
    void processCpuIsMeasuredAgainstTheWholeMachine() {
        // 4 s of processor time in 2 s on 8 processors: two of the eight were busy.
        assertThat(RateCalculator.machineCpuPercent(1_000, 5_000, 2 * SECOND, 2 * SECOND, 8))
                .hasValue(25.0);
        assertThat(RateCalculator.machineCpuPercent(1_000, 5_000, 2 * SECOND, 2 * SECOND, 2))
                .hasValue(100.0);
        assertThat(RateCalculator.machineCpuPercent(1_000, 1_000, 2 * SECOND, 2 * SECOND, 8))
                .hasValue(0.0);
    }

    @Test
    void processCpuNeedsAValidPairOfReadingsAndAProcessorCount() {
        assertThat(RateCalculator.machineCpuPercent(5_000, 1_000, 2 * SECOND, 2 * SECOND, 8))
                .as("counter went backwards")
                .isEmpty();
    }

    /**
     * Each case uses so little processor time that the capacity rule alone would let it through,
     * so only the rule named can be what rejects it. Without these rules the results would be
     * 0/0, "100 %" for 10 ms of work, and a negative percentage.
     */
    @Test
    void processCpuNeedsTimeToHavePassedAndAtLeastOneProcessor() {
        long tenMillis = SECOND / 100;

        assertThat(RateCalculator.machineCpuPercent(1_000, 1_000, 0, 0, 8))
                .as("idle process, no time passed")
                .isEmpty();
        assertThat(RateCalculator.machineCpuPercent(0, 10, 0, 0, 8))
                .as("10 ms used, no time passed")
                .isEmpty();
        assertThat(RateCalculator.machineCpuPercent(0, 5, -tenMillis, -tenMillis, 8))
                .as("clock went backwards")
                .isEmpty();
        assertThat(RateCalculator.machineCpuPercent(1_000, 1_000, 2 * SECOND, 2 * SECOND, 0))
                .as("idle process, no processors")
                .isEmpty();
        assertThat(RateCalculator.machineCpuPercent(1_000, 1_000, 2 * SECOND, 2 * SECOND, -1))
                .as("idle process, negative processor count")
                .isEmpty();
    }

    @Test
    void theMidpointOfAReadIsHalfwayBetweenItsStartAndItsEnd() {
        assertThat(RateCalculator.midpoint(100, 300)).isEqualTo(200);
        assertThat(RateCalculator.midpoint(500, 500)).isEqualTo(500);
        assertThat(RateCalculator.midpoint(-300, -100)).isEqualTo(-200);
    }

    @Test
    void theMidpointStaysCorrectWhenTheClockValueWraps() {
        assertThat(RateCalculator.midpoint(Long.MAX_VALUE - 9, Long.MIN_VALUE + 10))
                .isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void processCpuNeverExceedsTheMachineAndRejectsWhatTheMachineCouldNotSupply() {
        // One processor, 2 s apart by the best estimate, at most 2.1 s apart.
        assertThat(RateCalculator.machineCpuPercent(0, 2_000, 2 * SECOND, 2 * SECOND + SECOND / 10, 1))
                .hasValue(100.0);
        assertThat(RateCalculator.machineCpuPercent(0, 2_100, 2 * SECOND, 2 * SECOND + SECOND / 10, 1))
                .as("possible if the counter was read at the far end of the reading")
                .hasValue(100.0);
        assertThat(RateCalculator.machineCpuPercent(0, 2_120, 2 * SECOND, 2 * SECOND + SECOND / 10, 1))
                .as("within one clock tick of accounting slack")
                .hasValue(100.0);
        assertThat(RateCalculator.machineCpuPercent(0, 2_121, 2 * SECOND, 2 * SECOND + SECOND / 10, 1))
                .as("more than the machine could have supplied")
                .isEmpty();
    }

    @Test
    void busyPercentIsTheShareOfTimeNotIdle() {
        // 200 ticks passed, 50 of them idle.
        assertThat(RateCalculator.busyPercent(new CpuTicks(100, 200), new CpuTicks(150, 400)))
                .hasValue(75.0);
    }

    @Test
    void busyPercentCoversTheWholeRange() {
        assertThat(RateCalculator.busyPercent(new CpuTicks(0, 0), new CpuTicks(500, 500)))
                .hasValue(0.0);
        assertThat(RateCalculator.busyPercent(new CpuTicks(10, 20), new CpuTicks(10, 520)))
                .hasValue(100.0);
    }

    @Test
    void busyPercentNeedsTimeToHavePassed() {
        CpuTicks reading = new CpuTicks(100, 200);

        assertThat(RateCalculator.busyPercent(reading, reading)).isEmpty();
    }

    @Test
    void busyPercentSkipsCountersThatWentBackwards() {
        assertThat(RateCalculator.busyPercent(new CpuTicks(100, 200), new CpuTicks(10, 20)))
                .as("total reset")
                .isEmpty();
        assertThat(RateCalculator.busyPercent(new CpuTicks(100, 200), new CpuTicks(90, 300)))
                .as("idle went backwards")
                .isEmpty();
    }

    @Test
    void busyPercentSkipsAnIdleIncreaseLargerThanTheTotalIncrease() {
        // Would otherwise be reported as a negative utilization.
        assertThat(RateCalculator.busyPercent(new CpuTicks(100, 1_000), new CpuTicks(300, 1_100)))
                .isEmpty();
    }

    @Test
    void cpuUsageCombinesTheMachineAndEveryLogicalProcessor() {
        CpuSample previous = new CpuSample(new CpuTicks(0, 0), List.of(new CpuTicks(0, 0), new CpuTicks(0, 0)));
        CpuSample current =
                new CpuSample(new CpuTicks(120, 200), List.of(new CpuTicks(90, 100), new CpuTicks(30, 100)));

        Optional<CpuUsage> usage = RateCalculator.cpuUsage(previous, current);

        assertThat(usage).contains(new CpuUsage(40.0, List.of(OptionalDouble.of(10.0), OptionalDouble.of(70.0))));
    }

    @Test
    void cpuUsageIsSkippedWhenTheMachineCountersAreInvalid() {
        CpuSample previous = new CpuSample(new CpuTicks(500, 900), List.of(new CpuTicks(0, 0)));
        CpuSample afterReset = new CpuSample(new CpuTicks(5, 9), List.of(new CpuTicks(50, 100)));

        assertThat(RateCalculator.cpuUsage(previous, afterReset)).isEmpty();
    }

    @Test
    void oneProcessorWithInvalidCountersIsSkippedOnItsOwn() {
        CpuSample previous = new CpuSample(new CpuTicks(0, 0), List.of(new CpuTicks(0, 0), new CpuTicks(40, 80)));
        CpuSample current = new CpuSample(new CpuTicks(100, 200), List.of(new CpuTicks(50, 100), new CpuTicks(4, 8)));

        Optional<CpuUsage> usage = RateCalculator.cpuUsage(previous, current);

        assertThat(usage).contains(new CpuUsage(50.0, List.of(OptionalDouble.of(50.0), OptionalDouble.empty())));
    }

    @Test
    void noProcessorHasAValueWhenTheirNumberChanged() {
        CpuSample previous = new CpuSample(new CpuTicks(0, 0), List.of(new CpuTicks(0, 0)));
        CpuSample current =
                new CpuSample(new CpuTicks(100, 200), List.of(new CpuTicks(50, 100), new CpuTicks(50, 100)));

        Optional<CpuUsage> usage = RateCalculator.cpuUsage(previous, current);

        assertThat(usage).contains(new CpuUsage(50.0, List.of(OptionalDouble.empty(), OptionalDouble.empty())));
    }
}
