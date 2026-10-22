package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static prism.srm.application.FakeProcessSource.identity;
import static prism.srm.application.FakeProcessSource.reading;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessSnapshot;

/**
 * Runs the monitor on a real scheduled executor. The cycle itself, shared with the system
 * monitor, is covered in depth by {@link SystemMonitorTest}; this class covers what is specific
 * to processes and that the shared rules hold here too.
 */
class ProcessMonitorTest {

    private static final Duration INTERVAL = Duration.ofMillis(20);
    private static final long PATIENCE_SECONDS = 10;

    private final FakeProcessSource source = new FakeProcessSource();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "process-monitor-test");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void shutDownScheduler() throws InterruptedException {
        scheduler.shutdownNow();
        assertThat(scheduler.awaitTermination(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .isTrue();
    }

    @Test
    void processesAreEnumeratedEveryTwoSecondsByDefault() {
        assertThat(ProcessMonitor.DEFAULT_INTERVAL).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void deliversTheSnapshotsOfEachCycleToEveryListener() throws InterruptedException {
        source.processes = List.of(reading(identity(10, 1), 0, 0, 0), reading(identity(20, 2), 0, 0, 0));
        CountDownLatch firstListener = new CountDownLatch(3);
        CountDownLatch secondListener = new CountDownLatch(3);
        AtomicReference<List<ProcessSnapshot>> delivered = new AtomicReference<>();
        ProcessMonitor monitor = newMonitor();
        monitor.addListener(snapshots -> {
            delivered.set(snapshots);
            firstListener.countDown();
        });
        monitor.addListener(snapshots -> secondListener.countDown());

        monitor.start();

        assertThat(firstListener.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(secondListener.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered.get())
                .extracting(ProcessSnapshot::identity)
                .containsExactly(identity(10, 1), identity(20, 2));
    }

    @Test
    void oneEnumerationPerCycleServesAllListeners() throws Exception {
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicInteger deliveries = new AtomicInteger();
        ProcessMonitor monitor = newMonitor();
        monitor.addListener(snapshots -> deliveries.incrementAndGet());
        monitor.addListener(snapshots -> deliveries.incrementAndGet());
        monitor.addListener(snapshots -> {
            deliveries.incrementAndGet();
            delivered.countDown();
        });

        monitor.start();

        assertThat(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        monitor.stop();
        // The scheduler has one thread: once this task has run, no cycle is in flight.
        scheduler.submit(() -> {}).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        assertThat(deliveries.get()).isEqualTo(3 * source.listings);
    }

    @Test
    void aFailedEnumerationDeliversNothingForThatCycleAndMonitoringContinues() throws InterruptedException {
        try (LogCapture log = new LogCapture(ProcessMonitor.class)) {
            CountDownLatch failedCycles = new CountDownLatch(3);
            CountDownLatch delivered = new CountDownLatch(2);
            source.failure = new IllegalStateException("listing failed");
            source.duringRead = failedCycles::countDown;
            ProcessMonitor monitor = newMonitor();
            monitor.addListener(snapshots -> delivered.countDown());

            monitor.start();

            assertThat(failedCycles.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered.getCount())
                    .as("nothing delivered while listing fails")
                    .isEqualTo(2);
            assertThat(log.warnings()).hasSize(1);
            assertThat(log.errors()).isEmpty();
        }
    }

    @Test
    void monitoringRecoversWhenTheEnumerationWorksAgain() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicInteger listings = new AtomicInteger();
        // The first two listings fail, the following ones succeed.
        source.failure = new IllegalStateException("listing failed");
        source.duringRead = () -> {
            if (listings.incrementAndGet() == 3) {
                source.failure = null;
            }
        };
        ProcessMonitor monitor = newMonitor();
        monitor.addListener(snapshots -> delivered.countDown());

        monitor.start();

        assertThat(delivered.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anErrorFromTheSourceEndsMonitoringAndIsLogged() throws Exception {
        try (LogCapture log = new LogCapture(ProcessMonitor.class)) {
            CountDownLatch firstCycleStarted = new CountDownLatch(1);
            CountDownLatch secondCycleStarted = new CountDownLatch(1);
            AtomicInteger deliveries = new AtomicInteger();
            source.failure = new OutOfMemoryError("simulated");
            source.duringRead = () -> {
                if (firstCycleStarted.getCount() == 1) {
                    firstCycleStarted.countDown();
                } else {
                    secondCycleStarted.countDown();
                }
            };
            ProcessMonitor monitor = newMonitor();
            monitor.addListener(snapshots -> deliveries.incrementAndGet());

            monitor.start();

            assertThat(firstCycleStarted.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            scheduler.submit(() -> {}).get(PATIENCE_SECONDS, TimeUnit.SECONDS);
            assertThat(secondCycleStarted.await(10 * INTERVAL.toMillis(), TimeUnit.MILLISECONDS))
                    .as("monitoring went on after an Error")
                    .isFalse();
            assertThat(deliveries).hasValue(0);
            assertThat(log.errors()).hasSize(1);
            assertThat(log.errors().get(0)).contains("Process monitoring");
        }
    }

    @Test
    void startingTwiceIsRefused() {
        ProcessMonitor monitor = newMonitor();
        monitor.start();

        assertThatIllegalStateException().isThrownBy(monitor::start);
    }

    @Test
    void rejectsAnIntervalThatIsNotPositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessMonitor(source, scheduler, System::nanoTime, 8, Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessMonitor(source, scheduler, System::nanoTime, 8, Duration.ofSeconds(-1)));
    }

    @Test
    void rejectsAMachineWithoutLogicalProcessors() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProcessMonitor(source, scheduler, System::nanoTime, 0, INTERVAL));
    }

    private ProcessMonitor newMonitor() {
        return new ProcessMonitor(source, scheduler, System::nanoTime, 8, INTERVAL);
    }
}
