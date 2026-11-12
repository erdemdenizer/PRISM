package prism.srm.demo;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One worker thread of the CPU load: busy for the part of each period its {@link DutyCycle} says,
 * asleep for the rest, until it is told to stop. The busy part is plain arithmetic that reads the
 * clock every few thousand steps, so a worker reacts to a stop within microseconds and never spins
 * on the clock itself.
 */
final class CpuWorker implements Runnable {

    static final long PERIOD_NANOS = 100_000_000L;

    private static final int STEPS_BETWEEN_CLOCK_READS = 2_000;

    private final DutyCycle cycle;
    private final AtomicBoolean stop;

    /** The result of the arithmetic is kept observable, so that the JIT cannot remove the loop. */
    private volatile double sink;

    CpuWorker(DutyCycle cycle, AtomicBoolean stop) {
        this.cycle = cycle;
        this.stop = stop;
    }

    @Override
    public void run() {
        // Read as well as written: a field that is only written is a field the JIT may drop.
        double x = sink + 1.0;
        while (!stop.get()) {
            long periodStart = System.nanoTime();
            long busyUntil = periodStart + cycle.busyNanos();
            while (System.nanoTime() < busyUntil && !stop.get()) {
                for (int i = 0; i < STEPS_BETWEEN_CLOCK_READS; i++) {
                    x = Math.sqrt(x + 1.0);
                }
            }
            sink = x;
            long busy = System.nanoTime() - periodStart;
            long idle = cycle.periodNanos() - busy;
            if (idle > 0 && !stop.get()) {
                try {
                    Thread.sleep(Duration.ofNanos(idle));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            cycle.periodEnded(busy, System.nanoTime() - periodStart);
        }
    }
}
