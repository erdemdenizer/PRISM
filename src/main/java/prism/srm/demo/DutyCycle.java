package prism.srm.demo;

/**
 * The busy part of each period of a worker, with a bounded correction for the time the worker did
 * not get.
 *
 * <p>A worker is busy for {@code duty} of each period and sleeps for the rest. A sleep can take
 * longer than asked, and a period can take much longer when the process is suspended or held back
 * by a CPU limit, which is exactly what this process exists to demonstrate. What the worker owes
 * after such a period is carried into the next one, but never more than one period: a worker that
 * was suspended for a minute is busy for at most two periods in a row afterwards and then back at
 * its duty. Chasing the cumulative share instead would make it burn flat out for as long as it had
 * been held, and a demonstration of a limit would end in a demonstration of the opposite.
 */
final class DutyCycle {

    private final double duty;
    private final long periodNanos;
    private long debtNanos;

    DutyCycle(double duty, long periodNanos) {
        if (!(duty > 0 && duty <= 1)) {
            throw new IllegalArgumentException("duty must be in (0, 1], was " + duty);
        }
        if (periodNanos <= 0) {
            throw new IllegalArgumentException("period must be positive");
        }
        this.duty = duty;
        this.periodNanos = periodNanos;
    }

    long periodNanos() {
        return periodNanos;
    }

    /** How long the worker stays busy in the period that starts now. */
    long busyNanos() {
        return clamp(Math.round(duty * periodNanos) + debtNanos, 0, periodNanos);
    }

    /** Accounts for the period that just ended: how long the worker was busy and how long the period took. */
    void periodEnded(long busyNanos, long actualPeriodNanos) {
        long owed = Math.round(duty * actualPeriodNanos) - busyNanos;
        debtNanos = clamp(debtNanos + owed, -periodNanos, periodNanos);
    }

    /** What is carried into the next period: positive when the worker got less than its duty. */
    long debtNanos() {
        return debtNanos;
    }

    private static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }
}
