package prism.srm.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.platform.ProcessSource;

/**
 * Reads all processes periodically and delivers the snapshots of each cycle to its listeners.
 * One reading per cycle serves every listener.
 *
 * <p>Sampling runs on the injected executor, which the caller owns and shuts down. A cycle starts
 * a fixed delay after the previous one has finished, listeners included, so a slow source or a
 * slow listener delays the following cycle; cycles never overlap and never queue up.
 *
 * <p>A {@link RuntimeException} from the source or from a listener is logged once per kind and
 * monitoring continues. An {@link Error} is not recoverable here: it is logged and rethrown, and
 * no further cycle runs; the termination listeners are told.
 */
public final class ProcessMonitor {

    /** Default time between two sampling cycles. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);

    private final SamplingLoop<List<ProcessSnapshot>> loop;

    /**
     * @param scheduler runs the sampling cycles; owned by the caller
     * @param nanoClock monotonic clock, such as {@code System::nanoTime}
     * @param logicalProcessors the number of logical processors of the machine, as the platform's
     *     processor information reports it. Not {@code Runtime.availableProcessors()}, which
     *     counts only the processors this JVM may run on.
     * @param interval delay between the end of one cycle and the start of the next
     */
    public ProcessMonitor(
            ProcessSource source,
            ScheduledExecutorService scheduler,
            LongSupplier nanoClock,
            int logicalProcessors,
            Duration interval) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(nanoClock, "nanoClock");
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException("logicalProcessors must be positive: " + logicalProcessors);
        }
        ProcessSampler sampler = new ProcessSampler(source, nanoClock, logicalProcessors);
        this.loop = new SamplingLoop<>(
                "Process monitoring",
                LoggerFactory.getLogger(ProcessMonitor.class),
                sampler::sample,
                scheduler,
                interval);
    }

    /**
     * Adds a listener. It is called on the sampling thread with the snapshots of every cycle,
     * one per running process, and must return quickly: the next cycle waits for it. The list is
     * not modifiable.
     */
    public void addListener(Consumer<List<ProcessSnapshot>> listener) {
        loop.addListener(listener);
    }

    public void removeListener(Consumer<List<ProcessSnapshot>> listener) {
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
