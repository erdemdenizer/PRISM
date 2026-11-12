package prism.srm.application;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Where the limiter's work runs: the one thread {@code srm-limiter}, in order. The steps of every
 * limit and the requests of the limit service run there, so a limit needs no lock of its own. The
 * composition root gives a scheduled executor; tests give a virtual clock.
 */
public interface LimiterScheduler {

    /**
     * Runs the task soon.
     *
     * @throws RejectedExecutionException once the limiter is shut down
     */
    void execute(Runnable task);

    /**
     * Runs the task once the delay has passed; the future cancels it.
     *
     * @throws RejectedExecutionException once the limiter is shut down
     */
    Future<?> schedule(Runnable task, Duration delay);
}
