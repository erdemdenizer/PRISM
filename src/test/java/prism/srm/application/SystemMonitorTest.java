package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.SystemSnapshot;

/**
 * Runs the monitor on real scheduled executors. Waiting is done on latches only: a positive
 * expectation gets a generous timeout that is never reached when the code works, a negative one
 * ("nothing more happens") a short window.
 */
class SystemMonitorTest {

    private static final Duration INTERVAL = Duration.ofMillis(20);
    private static final long PATIENCE_SECONDS = 10;

    private final FakeSystemMetricsSource source = new FakeSystemMetricsSource();
    private ScheduledExecutorService scheduler = newScheduler(1);

    @AfterEach
    void shutDownScheduler() throws InterruptedException {
        scheduler.shutdownNow();
        // So that no cycle of this test is still running, and logging, during the next one.
        assertThat(scheduler.awaitTermination(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
    }

    @Test
    void deliversASnapshotToEveryListenerEachCycle() throws InterruptedException {
        CountDownLatch firstListener = new CountDownLatch(3);
        CountDownLatch secondListener = new CountDownLatch(3);
        SystemMonitor monitor = newMonitor();
        monitor.addListener(snapshot -> firstListener.countDown());
        monitor.addListener(snapshot -> secondListener.countDown());

        monitor.start();

        assertThat(firstListener.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(secondListener.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Holds the first cycle inside the source for ten intervals. With a single thread a second
     * cycle could only queue up; with four it could also run alongside the first. Neither may
     * happen, and the cycles missed meanwhile must not be made up for afterwards.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aSlowSourceCannotMakeCyclesOverlapOrPileUp(int threads) throws InterruptedException {
        scheduler.shutdownNow();
        scheduler = newScheduler(threads);
        CountDownLatch firstCycleEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondCycleEntered = new CountDownLatch(1);
        AtomicInteger cyclesStarted = new AtomicInteger();
        AtomicLong firstCycleResumedNanos = new AtomicLong();
        AtomicLong secondCycleEnteredNanos = new AtomicLong();
        source.onCpuReading = () -> {
            int cycle = cyclesStarted.incrementAndGet();
            if (cycle == 1) {
                firstCycleEntered.countDown();
                awaitUninterruptibly(release);
                firstCycleResumedNanos.set(System.nanoTime());
            } else if (cycle == 2) {
                secondCycleEnteredNanos.set(System.nanoTime());
                secondCycleEntered.countDown();
            }
        };
        SystemMonitor monitor = newMonitor();

        monitor.start();

        assertThat(firstCycleEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(secondCycleEntered.await(10 * INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
                .as("a second cycle started while the first was still held")
                .isFalse();
        assertThat(cyclesStarted).hasValue(1);

        release.countDown();

        assertThat(secondCycleEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        // A backlog would run immediately. The next cycle instead waits a full interval after the
        // held one has finished, and it finished later than the moment recorded inside it.
        assertThat(secondCycleEnteredNanos.get() - firstCycleResumedNanos.get())
                .isGreaterThanOrEqualTo(INTERVAL.toNanos());
    }

    /** The same for a listener: the next cycle waits for it instead of being delivered alongside. */
    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aSlowListenerCannotMakeCyclesOverlapOrPileUp(int threads) throws InterruptedException {
        scheduler.shutdownNow();
        scheduler = newScheduler(threads);
        CountDownLatch firstDeliveryEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondCycleStarted = new CountDownLatch(1);
        AtomicInteger cyclesStarted = new AtomicInteger();
        AtomicInteger deliveries = new AtomicInteger();
        AtomicLong firstDeliveryResumedNanos = new AtomicLong();
        AtomicLong secondCycleStartedNanos = new AtomicLong();
        source.onCpuReading = () -> {
            if (cyclesStarted.incrementAndGet() == 2) {
                secondCycleStartedNanos.set(System.nanoTime());
                secondCycleStarted.countDown();
            }
        };
        SystemMonitor monitor = newMonitor();
        monitor.addListener(snapshot -> {
            if (deliveries.incrementAndGet() == 1) {
                firstDeliveryEntered.countDown();
                awaitUninterruptibly(release);
                firstDeliveryResumedNanos.set(System.nanoTime());
            }
        });

        monitor.start();

        assertThat(firstDeliveryEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
        assertThat(secondCycleStarted.await(10 * INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
                .as("a second cycle started while a listener still held the first")
                .isFalse();
        assertThat(cyclesStarted).hasValue(1);
        assertThat(deliveries).hasValue(1);

        release.countDown();

        assertThat(secondCycleStarted.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(secondCycleStartedNanos.get() - firstDeliveryResumedNanos.get())
                .isGreaterThanOrEqualTo(INTERVAL.toNanos());
    }

    @Test
    void startingTwiceIsRefusedSoThatTwoCyclesCanNeverRunSideBySide() {
        SystemMonitor monitor = newMonitor();
        monitor.start();

        assertThatIllegalStateException().isThrownBy(monitor::start);
    }

    @Test
    void aMonitorIsNotRestartedAfterStop() {
        SystemMonitor monitor = newMonitor();
        monitor.start();
        monitor.stop();

        assertThatIllegalStateException().isThrownBy(monitor::start);
    }

    @Test
    void aListenerThatThrowsDoesNotStopMonitoringOrOtherListeners() throws InterruptedException {
        try (LogCapture log = new LogCapture(SystemMonitor.class)) {
            CountDownLatch delivered = new CountDownLatch(3);
            SystemMonitor monitor = newMonitor();
            monitor.addListener(snapshot -> {
                throw new IllegalStateException("listener failed");
            });
            monitor.addListener(snapshot -> delivered.countDown());

            monitor.start();

            assertThat(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(log.warnings()).hasSize(1);
        }
    }

    /**
     * An Error is not a failure monitoring can recover from. It must not be swallowed: the cycle
     * that met it is the last one, and the log says why.
     */
    @Test
    void anErrorFromAListenerEndsMonitoringAndIsLogged() throws Exception {
        try (LogCapture log = new LogCapture(SystemMonitor.class)) {
            CountDownLatch firstDelivery = new CountDownLatch(1);
            CountDownLatch secondCycleStarted = new CountDownLatch(1);
            AtomicInteger cyclesStarted = new AtomicInteger();
            AtomicInteger laterListenerCalls = new AtomicInteger();
            source.onCpuReading = () -> {
                if (cyclesStarted.incrementAndGet() == 2) {
                    secondCycleStarted.countDown();
                }
            };
            SystemMonitor monitor = newMonitor();
            monitor.addListener(snapshot -> {
                firstDelivery.countDown();
                throw new OutOfMemoryError("simulated");
            });
            monitor.addListener(snapshot -> laterListenerCalls.incrementAndGet());

            monitor.start();

            assertThat(firstDelivery.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            // The scheduler has one thread: once this task has run, the failed cycle has ended.
            scheduler.submit(() -> {}).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
            assertThat(secondCycleStarted.await(10 * INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
                    .as("monitoring went on after an Error")
                    .isFalse();
            assertThat(cyclesStarted).hasValue(1);
            assertThat(laterListenerCalls).hasValue(0);
            assertThat(log.errors()).hasSize(1);
            assertThat(log.warnings()).isEmpty();
        }
    }

    @Test
    void anErrorFromTheSourceEndsMonitoringAndIsLogged() throws Exception {
        try (LogCapture log = new LogCapture(SystemMonitor.class)) {
            CountDownLatch firstCycleStarted = new CountDownLatch(1);
            CountDownLatch secondCycleStarted = new CountDownLatch(1);
            AtomicInteger cyclesStarted = new AtomicInteger();
            AtomicInteger deliveries = new AtomicInteger();
            source.onCpuReading = () -> {
                if (cyclesStarted.incrementAndGet() == 1) {
                    firstCycleStarted.countDown();
                } else {
                    secondCycleStarted.countDown();
                }
            };
            // What a source backed by native code throws when its library cannot be loaded.
            source.fail(FakeSystemMetricsSource.Metric.MEMORY, new UnsatisfiedLinkError("library not found"));
            SystemMonitor monitor = newMonitor();
            monitor.addListener(snapshot -> deliveries.incrementAndGet());

            monitor.start();

            assertThat(firstCycleStarted.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            scheduler.submit(() -> {}).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
            assertThat(secondCycleStarted.await(10 * INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
                    .as("monitoring went on after an Error")
                    .isFalse();
            assertThat(deliveries).hasValue(0);
            assertThat(log.errors()).hasSize(1);
        }
    }

    @Test
    void anUnexpectedFailureInsideACycleDoesNotEndMonitoring() throws InterruptedException {
        AtomicBoolean failed = new AtomicBoolean();
        LongSupplier clockThatFailsOnce = () -> {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("clock failed");
            }
            return System.nanoTime();
        };
        CountDownLatch delivered = new CountDownLatch(2);
        SystemMonitor monitor = new SystemMonitor(source, scheduler, clockThatFailsOnce, INTERVAL);
        monitor.addListener(snapshot -> delivered.countDown());

        monitor.start();

        assertThat(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(failed).isTrue();
    }

    @Test
    void aSourceThatThrowsEveryCycleDoesNotEndMonitoring() throws InterruptedException {
        source.fail(FakeSystemMetricsSource.Metric.CPU, new IllegalStateException("cpu query failed"));
        CountDownLatch delivered = new CountDownLatch(3);
        AtomicInteger withMemory = new AtomicInteger();
        SystemMonitor monitor = newMonitor();
        monitor.addListener(snapshot -> {
            if (snapshot.cpu().isEmpty() && snapshot.memory().isPresent()) {
                withMemory.incrementAndGet();
            }
            delivered.countDown();
        });

        monitor.start();

        assertThat(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(withMemory).hasValueGreaterThanOrEqualTo(3);
    }

    @Test
    void aRemovedListenerReceivesNothingMore() throws InterruptedException {
        AtomicInteger removedListenerCalls = new AtomicInteger();
        Consumer<SystemSnapshot> removed = snapshot -> removedListenerCalls.incrementAndGet();
        CountDownLatch laterCycles = new CountDownLatch(3);
        SystemMonitor monitor = newMonitor();
        monitor.addListener(removed);
        // Runs on the sampling thread after the first listener, so the removal takes effect
        // exactly between the first and the second cycle.
        monitor.addListener(snapshot -> {
            monitor.removeListener(removed);
            laterCycles.countDown();
        });

        monitor.start();

        assertThat(laterCycles.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(removedListenerCalls).hasValue(1);
    }

    @Test
    void stopEndsTheCycles() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch twoDelivered = new CountDownLatch(2);
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch deliveredAfterStop = new CountDownLatch(1);
        SystemMonitor monitor = newMonitor();
        monitor.addListener(snapshot -> {
            delivered.incrementAndGet();
            twoDelivered.countDown();
            if (stopped.get()) {
                deliveredAfterStop.countDown();
            }
        });
        monitor.start();
        assertThat(twoDelivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        monitor.stop();
        // The scheduler has one thread: once this task has run, a cycle that was in flight when
        // stop() was called has finished.
        scheduler.submit(() -> stopped.set(true)).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        int deliveredWhenStopped = delivered.get();

        assertThat(deliveredAfterStop.await(10 * INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
                .isFalse();
        assertThat(delivered).hasValue(deliveredWhenStopped);
    }

    @Test
    void stopBeforeStartDoesNothing() {
        newMonitor().stop();
    }

    @Test
    void rejectsAnIntervalThatIsNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SystemMonitor(source, scheduler, System::nanoTime, Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SystemMonitor(source, scheduler, System::nanoTime, Duration.ofSeconds(-1)));
    }

    @Test
    void theHistoryCoversSixtySecondsAtTheSamplingInterval() {
        assertThat(SystemMonitor.historyCapacity(Duration.ofSeconds(1))).isEqualTo(60);
        assertThat(SystemMonitor.historyCapacity(Duration.ofSeconds(2))).isEqualTo(30);
        assertThat(SystemMonitor.historyCapacity(Duration.ofMillis(250))).isEqualTo(240);
        assertThat(SystemMonitor.historyCapacity(Duration.ofMinutes(5))).isEqualTo(1);
    }

    private SystemMonitor newMonitor() {
        return new SystemMonitor(source, scheduler, System::nanoTime, INTERVAL);
    }

    private static ScheduledExecutorService newScheduler(int threads) {
        return Executors.newScheduledThreadPool(threads, task -> {
            Thread thread = new Thread(task, "system-monitor-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            // The scheduler is being shut down at the end of the test.
            Thread.currentThread().interrupt();
        }
    }
}
