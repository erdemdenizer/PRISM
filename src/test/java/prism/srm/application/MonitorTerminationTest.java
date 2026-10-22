package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a monitor tells its termination listeners, and what it does not do for them: the failure
 * that ends a monitor is not caught, not replaced and not wrapped on their account.
 *
 * <p>The monitors run on a real scheduler that records what its periodic task was ended by, which
 * is the only place where that can be seen from outside. Waiting is done on latches only.
 */
class MonitorTerminationTest {

    private static final Duration INTERVAL = Duration.ofMillis(20);
    private static final long PATIENCE_SECONDS = 10;

    /** A single-thread scheduler that keeps the failure a task of its ended with. */
    private static final class RecordingScheduler extends ScheduledThreadPoolExecutor {

        private final AtomicReference<Throwable> thrown = new AtomicReference<>();
        private final CountDownLatch taskEnded = new CountDownLatch(1);

        RecordingScheduler() {
            super(1, task -> {
                Thread thread = new Thread(task, "termination-test");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            super.afterExecute(task, failure);
            // A periodic task that ran normally is not done: it waits for its next run. It is done
            // once it has thrown, and then get() hands out what it threw.
            if (task instanceof Future<?> future && future.isDone() && !future.isCancelled()) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    thrown.set(e.getCause());
                    taskEnded.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Throwable whatEndedTheTask() throws InterruptedException {
            assertThat(taskEnded.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .as("the periodic task ended with a failure")
                    .isTrue();
            return thrown.get();
        }
    }

    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final FakeSystemMetricsSource systemSource = new FakeSystemMetricsSource();
    private final FakeProcessSource processSource = new FakeProcessSource();
    private final AtomicInteger told = new AtomicInteger();

    @AfterEach
    void shutDownScheduler() throws InterruptedException {
        scheduler.shutdownNow();
        assertThat(scheduler.awaitTermination(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
    }

    @Test
    void anErrorInTheSystemSourceTellsTheListenerOnceAndIsStillThrown() throws Exception {
        UnsatisfiedLinkError fatal = new UnsatisfiedLinkError("library not found");
        AtomicInteger cycles = new AtomicInteger();
        // Two good cycles first: the monitor ends while it runs, not while it starts.
        systemSource.onCpuReading = () -> {
            if (cycles.incrementAndGet() == 3) {
                throw fatal;
            }
        };
        SystemMonitor monitor = new SystemMonitor(systemSource, scheduler, System::nanoTime, INTERVAL);
        monitor.addTerminationListener(told::incrementAndGet);

        monitor.start();

        // The very Error, not a wrapper and not a RuntimeException made of it.
        assertThat(scheduler.whatEndedTheTask()).isSameAs(fatal);
        assertThat(told).hasValue(1);
        assertThat(cycles).hasValue(3);
    }

    @Test
    void anErrorInASnapshotListenerTellsTheTerminationListener() throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        SystemMonitor monitor = new SystemMonitor(systemSource, scheduler, System::nanoTime, INTERVAL);
        monitor.addListener(snapshot -> {
            throw fatal;
        });
        monitor.addTerminationListener(told::incrementAndGet);

        monitor.start();

        assertThat(scheduler.whatEndedTheTask()).isSameAs(fatal);
        assertThat(told).hasValue(1);
    }

    @Test
    void anErrorInTheProcessSourceTellsTheListenerOnceAndIsStillThrown() throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        AtomicInteger listings = new AtomicInteger();
        processSource.duringRead = () -> {
            if (listings.incrementAndGet() == 3) {
                throw fatal;
            }
        };
        ProcessMonitor monitor = new ProcessMonitor(processSource, scheduler, System::nanoTime, 4, INTERVAL);
        monitor.addTerminationListener(told::incrementAndGet);

        monitor.start();

        assertThat(scheduler.whatEndedTheTask()).isSameAs(fatal);
        assertThat(told).hasValue(1);
        assertThat(listings).hasValue(3);
    }

    /** The listener must not be able to turn the Error into something milder. */
    @Test
    void aTerminationListenerThatThrowsDoesNotReplaceTheErrorAndTheOthersAreStillTold() throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        systemSource.fail(FakeSystemMetricsSource.Metric.MEMORY, fatal);
        SystemMonitor monitor = new SystemMonitor(systemSource, scheduler, System::nanoTime, INTERVAL);
        monitor.addTerminationListener(() -> {
            throw new IllegalStateException("listener failed");
        });
        monitor.addTerminationListener(told::incrementAndGet);

        try (LogCapture log = new LogCapture(SystemMonitor.class)) {
            monitor.start();

            assertThat(scheduler.whatEndedTheTask()).isSameAs(fatal);
            assertThat(told).hasValue(1);
            assertThat(log.errors()).containsExactly("System monitoring stopped by a fatal error");
            assertThat(log.warnings()).containsExactly("A termination listener of System monitoring failed");
        }
    }

    /** Stopping is not an end of this kind: whoever stops a monitor knows that it has stopped. */
    @Test
    void stoppingAMonitorTellsNoTerminationListener() throws Exception {
        CountDownLatch sampled = new CountDownLatch(2);
        SystemMonitor system = new SystemMonitor(systemSource, scheduler, System::nanoTime, INTERVAL);
        system.addListener(snapshot -> sampled.countDown());
        system.addTerminationListener(told::incrementAndGet);
        ProcessMonitor processes = new ProcessMonitor(processSource, scheduler, System::nanoTime, 4, INTERVAL);
        processes.addTerminationListener(told::incrementAndGet);
        system.start();
        processes.start();
        assertThat(sampled.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        system.stop();
        processes.stop();
        // The scheduler has one thread: once this task has run, no cycle is still running.
        scheduler.submit(() -> {}).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        scheduler.shutdown();

        assertThat(scheduler.awaitTermination(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
        assertThat(told).hasValue(0);
    }

    /** A failed cycle is not the end of the monitor: it is logged and the next cycle runs. */
    @Test
    void aCycleThatFailsWithAnExceptionTellsNoTerminationListener() throws Exception {
        CountDownLatch cyclesAfterTheFailure = new CountDownLatch(3);
        AtomicInteger cycles = new AtomicInteger();
        systemSource.onCpuReading = () -> {
            if (cycles.incrementAndGet() == 1) {
                throw new IllegalStateException("one bad cycle");
            }
            cyclesAfterTheFailure.countDown();
        };
        SystemMonitor monitor = new SystemMonitor(systemSource, scheduler, System::nanoTime, INTERVAL);
        monitor.addTerminationListener(told::incrementAndGet);

        monitor.start();

        assertThat(cyclesAfterTheFailure.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
        assertThat(told).hasValue(0);
    }
}
