package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DutyCycleTest {

    private static final long PERIOD = 100;

    @Test
    void startsAtTheDuty() {
        assertThat(new DutyCycle(0.5, PERIOD).busyNanos()).isEqualTo(50);
        assertThat(new DutyCycle(1.0, PERIOD).busyNanos()).isEqualTo(100);
        assertThat(new DutyCycle(0.16, PERIOD).busyNanos()).isEqualTo(16);
        assertThat(new DutyCycle(0.001, PERIOD).busyNanos()).isZero();
    }

    @Test
    void anOversleptPeriodIsMadeUpInTheNextOne() {
        DutyCycle cycle = new DutyCycle(0.5, PERIOD);
        cycle.periodEnded(50, 115);
        assertThat(cycle.debtNanos()).isEqualTo(8);
        assertThat(cycle.busyNanos()).isEqualTo(58);
        cycle.periodEnded(58, 100);
        assertThat(cycle.debtNanos()).isZero();
        assertThat(cycle.busyNanos()).isEqualTo(50);
    }

    @Test
    void aPeriodThatWasTooBusyIsCorrectedTheOtherWay() {
        DutyCycle cycle = new DutyCycle(0.5, PERIOD);
        cycle.periodEnded(60, 100);
        assertThat(cycle.busyNanos()).isEqualTo(40);
        cycle.periodEnded(100, 100);
        assertThat(cycle.busyNanos()).isZero();
    }

    /** A worker that was suspended for a minute is busy for two periods afterwards, not for a minute. */
    @Test
    void whatIsOwedAfterALongSuspensionIsBoundedToOnePeriod() {
        DutyCycle cycle = new DutyCycle(0.5, PERIOD);
        cycle.periodEnded(50, 60_000_000_000L);
        assertThat(cycle.debtNanos()).isEqualTo(PERIOD);

        long[] busy = new long[6];
        for (int i = 0; i < busy.length; i++) {
            busy[i] = cycle.busyNanos();
            cycle.periodEnded(busy[i], PERIOD);
        }
        assertThat(busy).containsExactly(100, 100, 50, 50, 50, 50);
    }

    /**
     * A suspension that falls into the busy part arrives as a period that was all busy: the worker
     * then owes nothing for a while, but at most one period, never three hundred.
     */
    @Test
    void whatWasOverdoneDuringALongSuspensionIsBoundedToOnePeriodToo() {
        DutyCycle cycle = new DutyCycle(0.5, PERIOD);
        cycle.periodEnded(60_000_000_000L, 60_000_000_000L);
        assertThat(cycle.debtNanos()).isEqualTo(-PERIOD);

        long[] busy = new long[6];
        for (int i = 0; i < busy.length; i++) {
            busy[i] = cycle.busyNanos();
            cycle.periodEnded(busy[i], PERIOD);
        }
        assertThat(busy).containsExactly(0, 0, 50, 50, 50, 50);
    }

    @Test
    void aFullyBusyWorkerNeverOwesAnything() {
        DutyCycle cycle = new DutyCycle(1.0, PERIOD);
        cycle.periodEnded(100, 100);
        cycle.periodEnded(100, 5_000);
        assertThat(cycle.busyNanos()).isEqualTo(PERIOD);
        cycle.periodEnded(100, 100);
        assertThat(cycle.busyNanos()).isEqualTo(PERIOD);
    }

    @Test
    void theBusyPartNeverLeavesThePeriod() {
        DutyCycle cycle = new DutyCycle(0.01, PERIOD);
        cycle.periodEnded(100, 100);
        assertThat(cycle.busyNanos()).isZero();
        cycle.periodEnded(0, 1_000_000);
        assertThat(cycle.busyNanos()).isEqualTo(PERIOD);
    }

    @Test
    void rejectsADutyOutsideItsRangeAndANonPositivePeriod() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DutyCycle(0, PERIOD));
        assertThatIllegalArgumentException().isThrownBy(() -> new DutyCycle(1.01, PERIOD));
        assertThatIllegalArgumentException().isThrownBy(() -> new DutyCycle(Double.NaN, PERIOD));
        assertThatIllegalArgumentException().isThrownBy(() -> new DutyCycle(0.5, 0));
    }
}
