package prism.srm.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * The periodic cycle that the monitors share: take a sample, deliver it to the listeners, wait,
 * repeat.
 *
 * <p>Cycles run on the injected executor, which the caller owns and shuts down. A cycle starts a
 * fixed delay after the previous one has finished, listeners included, so a slow sampler or a
 * slow listener delays the following cycle; cycles never overlap and never queue up.
 *
 * <p>A {@link RuntimeException} from the sampler or from a listener is logged once per kind and
 * the loop continues; a cycle whose sampler failed delivers nothing. An {@link Error} is not
 * recoverable here: it is logged and rethrown, and no further cycle runs.
 *
 * <p>Whatever leaves a cycle ends the loop for good, because the executor does not run a periodic
 * task again after it has thrown. The termination listeners are told when that happens, so that
 * nobody goes on believing in a monitor that has ended. They are told from a {@code finally}
 * block: what is being thrown is neither caught nor changed. {@link #stop()} does not end the
 * loop in this sense and tells nobody.
 *
 * @param <T> what one cycle produces
 */
final class SamplingLoop<T> {

    private final String name;
    private final Logger log;
    private final Supplier<T> sampler;
    private final ScheduledExecutorService scheduler;
    private final long intervalNanos;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> terminationListeners = new CopyOnWriteArrayList<>();
    private final FailureLog failures;

    private ScheduledFuture<?> cycle;

    /**
     * @param name what is being monitored, for the log
     * @param log the logger of the monitor that owns this loop
     * @param sampler produces one sample; called on the executor only
     * @param scheduler runs the cycles; owned by the caller
     * @param interval delay between the end of one cycle and the start of the next
     */
    SamplingLoop(String name, Logger log, Supplier<T> sampler, ScheduledExecutorService scheduler, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        this.name = name;
        this.log = log;
        this.sampler = sampler;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.intervalNanos = interval.toNanos();
        this.failures = new FailureLog(log);
    }

    void addListener(Consumer<T> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    /**
     * Adds a listener that is told when the loop ends on its own. It is called once, on the
     * executor, while the failure that ended the loop is still on its way out, and must return
     * quickly. A {@link RuntimeException} it throws is logged and does not replace that failure.
     * An {@link Error} it throws is not caught either: it takes the place of the first one, which
     * has been logged by then, and listeners added after it are not told.
     */
    void addTerminationListener(Runnable listener) {
        terminationListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Starts the cycles; the first one runs at once.
     *
     * @throws IllegalStateException if the loop was started before
     */
    synchronized void start() {
        if (cycle != null) {
            // A second periodic task would overlap the first.
            throw new IllegalStateException("The monitor was already started");
        }
        cycle = scheduler.scheduleWithFixedDelay(this::runCycle, 0, intervalNanos, TimeUnit.NANOSECONDS);
    }

    /** Stops scheduling further cycles. A cycle already running completes. */
    synchronized void stop() {
        if (cycle != null) {
            cycle.cancel(false);
        }
    }

    private void runCycle() {
        boolean completed = false;
        try {
            sampleAndDeliver();
            completed = true;
        } catch (Error fatal) {
            // Not handled: an Error means the runtime itself is in trouble, and carrying on would
            // only pretend otherwise. It is rethrown, which ends this periodic task. The log line
            // exists because the executor keeps the cause to itself and monitoring would
            // otherwise stop without a trace.
            log.error("{} stopped by a fatal error", name, fatal);
            throw fatal;
        } finally {
            if (!completed) {
                // Something is leaving this cycle, so no cycle follows. Nothing is caught here:
                // the listeners are told, and what was thrown goes on its way.
                announceTermination();
            }
        }
    }

    private void sampleAndDeliver() {
        T sample;
        try {
            sample = sampler.get();
        } catch (RuntimeException e) {
            failures.warnOnce("Sampling cycle", e);
            return;
        }
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(sample);
            } catch (RuntimeException e) {
                failures.warnOnce("Snapshot listener", e);
            }
        }
    }

    private void announceTermination() {
        for (Runnable listener : terminationListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // Must not take the place of the failure that is ending the loop.
                log.warn("A termination listener of {} failed", name, e);
            }
        }
    }
}
