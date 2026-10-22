package prism.srm.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;
import prism.srm.domain.SystemSnapshot;
import prism.srm.platform.SystemMetricsSource;

/**
 * Samples the system metrics periodically and delivers each snapshot to its listeners.
 *
 * <p>Sampling runs on the injected executor, which the caller owns and shuts down. A cycle starts
 * a fixed delay after the previous one has finished, listeners included, so a slow source or a
 * slow listener delays the following cycle; cycles never overlap and never queue up.
 *
 * <p>A {@link RuntimeException} from the sampler or from a listener is logged once per kind and
 * monitoring continues. An {@link Error} is not recoverable here: it is logged and rethrown, and
 * no further cycle runs; the termination listeners are told.
 */
public final class SystemMonitor {

    /** Default time between two sampling cycles. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);

    private static final Duration HISTORY_WINDOW = Duration.ofSeconds(60);

    private final SamplingLoop<SystemSnapshot> loop;

    /**
     * @param scheduler runs the sampling cycles; owned by the caller
     * @param nanoClock monotonic clock, such as {@code System::nanoTime}
     * @param interval delay between the end of one cycle and the start of the next; the histories
     *     hold as many samples as fit into 60 seconds at this interval
     */
    public SystemMonitor(
            SystemMetricsSource source, ScheduledExecutorService scheduler, LongSupplier nanoClock, Duration interval) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(nanoClock, "nanoClock");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        SystemSampler sampler = new SystemSampler(source, nanoClock, historyCapacity(interval));
        this.loop = new SamplingLoop<>(
                "System monitoring",
                LoggerFactory.getLogger(SystemMonitor.class),
                sampler::sample,
                scheduler,
                interval);
    }

    /** Returns the number of samples that span the history window at the given interval, at least one. */
    public static int historyCapacity(Duration interval) {
        return Math.toIntExact(Math.max(1, HISTORY_WINDOW.dividedBy(interval)));
    }

    /**
     * Adds a listener. It is called on the sampling thread with every snapshot, newest last, and
     * must return quickly: the next cycle waits for it.
     */
    public void addListener(Consumer<SystemSnapshot> listener) {
        loop.addListener(listener);
    }

    public void removeListener(Consumer<SystemSnapshot> listener) {
        loop.removeListener(listener);
    }

    /**
     * Adds a listener that is told when monitoring ends on its own: an {@link Error}, or anything
     * else that leaves a cycle, ends it for good. The listener is called once, on the sampling
     * thread, while that failure is still on its way out; nothing is caught for it. {@link
     * #stop()} is not such an end and calls no listener.
     */
    public void addTerminationListener(Runnable listener) {
        loop.addTerminationListener(listener);
    }

    /**
     * Starts sampling; the first cycle runs at once. A monitor is started once and is not
     * restarted after {@link #stop()}.
     *
     * @throws IllegalStateException if the monitor was started before
     */
    public void start() {
        loop.start();
    }

    /**
     * Stops scheduling further cycles. A cycle already running completes; awaiting it is up to
     * the owner of the executor.
     */
    public void stop() {
        loop.stop();
    }
}
