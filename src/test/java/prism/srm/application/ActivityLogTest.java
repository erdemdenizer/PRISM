package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;

class ActivityLogTest {

    private static final Instant START = Instant.parse("2026-10-05T12:00:00Z");
    private static final long PATIENCE_SECONDS = 10;

    /** A clock that advances one second with every reading. */
    private final AtomicLong readings = new AtomicLong();

    private final InstantSource clock = () -> START.plusSeconds(readings.getAndIncrement());

    @Test
    void keepsEntriesOldestFirstWithTheTimeOfTheInjectedClock() {
        ActivityLog log = new ActivityLog(10, clock);

        log.record(ActivitySeverity.INFO, "Started.");
        log.record(ActivitySeverity.WARNING, "Process monitoring was not started.");

        assertThat(log.entries())
                .containsExactly(
                        new ActivityEntry(START, ActivitySeverity.INFO, "Started."),
                        new ActivityEntry(
                                START.plusSeconds(1), ActivitySeverity.WARNING, "Process monitoring was not started."));
    }

    @Test
    void neverHoldsMoreThanItsCapacityAndDropsTheOldest() {
        ActivityLog log = new ActivityLog(3, clock);

        for (int event = 1; event <= 5; event++) {
            log.record(ActivitySeverity.INFO, "Event " + event);
            assertThat(log.entries()).hasSizeLessThanOrEqualTo(3);
        }

        assertThat(log.entries()).extracting(ActivityEntry::message).containsExactly("Event 3", "Event 4", "Event 5");
    }

    @Test
    void aCapacityOfOneKeepsTheNewestEntryOnly() {
        ActivityLog log = new ActivityLog(1, clock);

        log.record(ActivitySeverity.INFO, "First");
        log.record(ActivitySeverity.ERROR, "Second");

        assertThat(log.entries()).extracting(ActivityEntry::message).containsExactly("Second");
    }

    @Test
    void aCapacityBelowOneIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivityLog(0, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new ActivityLog(-1, clock));
    }

    @Test
    void theEntriesHandedOutDoNotChangeAfterwards() {
        ActivityLog log = new ActivityLog(2, clock);
        log.record(ActivitySeverity.INFO, "First");

        List<ActivityEntry> before = log.entries();
        log.record(ActivitySeverity.INFO, "Second");
        log.record(ActivitySeverity.INFO, "Third");

        assertThat(before).extracting(ActivityEntry::message).containsExactly("First");
        assertThat(before).isUnmodifiable();
    }

    @Test
    void writesEveryEventThroughTheLoggerAtTheLevelOfItsSeverity() {
        ActivityLog log = new ActivityLog(10, clock);

        try (LogCapture logged = new LogCapture(ActivityLog.class)) {
            log.record(ActivitySeverity.INFO, "Started.");
            log.record(ActivitySeverity.WARNING, "A warning.");
            log.record(ActivitySeverity.ERROR, "A failure.", new IllegalStateException("cause"));

            assertThat(logged.warnings()).containsExactly("A warning.");
            assertThat(logged.errors()).containsExactly("A failure.");
        }
        // The cause is for the log file only.
        assertThat(log.entries())
                .extracting(ActivityEntry::message)
                .containsExactly("Started.", "A warning.", "A failure.");
    }

    @Test
    void anEventWithoutAMessageIsRejectedAndNothingIsKept() {
        ActivityLog log = new ActivityLog(10, clock);

        assertThatIllegalArgumentException().isThrownBy(() -> log.record(ActivitySeverity.INFO, " "));

        assertThat(log.entries()).isEmpty();
    }

    /** Four threads record at once: no entry is lost, and the bound holds. */
    @Test
    void recordingFromSeveralThreadsLosesNothingWithinTheCapacity() throws InterruptedException {
        int threads = 4;
        int perThread = 500;
        ActivityLog log = new ActivityLog(threads * perThread, clock);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> writers = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            String name = "writer-" + thread;
            Thread writer = new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            if (go.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                                for (int event = 0; event < perThread; event++) {
                                    log.record(ActivitySeverity.INFO, name + " event " + event);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    },
                    name);
            writer.setDaemon(true);
            writers.add(writer);
            writer.start();
        }
        assertThat(ready.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

        go.countDown();

        assertThat(done.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        List<ActivityEntry> entries = log.entries();
        assertThat(entries).hasSize(threads * perThread);
        assertThat(entries).extracting(ActivityEntry::message).doesNotHaveDuplicates();
    }
}
