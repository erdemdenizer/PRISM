package prism.srm.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LatestValueRelayTest {

    private static final long PATIENCE_SECONDS = 10;

    /** Stands in for the user-interface thread: tasks wait until the test runs them. */
    private final ArrayDeque<Runnable> uiQueue = new ArrayDeque<>();

    private final List<Integer> delivered = new ArrayList<>();
    private final LatestValueRelay<Integer> relay = new LatestValueRelay<>(uiQueue::add, delivered::add);

    @Test
    void manyOffersBeforeTheUserInterfaceRunsQueueOneTaskAndDeliverTheNewestValue() {
        for (int value = 1; value <= 100; value++) {
            relay.offer(value);
        }

        assertThat(uiQueue).hasSize(1);
        runUiTasks();
        assertThat(delivered).containsExactly(100);
    }

    @Test
    void anOfferAfterADeliverySchedulesAgain() {
        relay.offer(1);
        runUiTasks();

        relay.offer(2);

        assertThat(uiQueue).hasSize(1);
        runUiTasks();
        assertThat(delivered).containsExactly(1, 2);
    }

    @Test
    void nothingIsScheduledWithoutAnOffer() {
        assertThat(uiQueue).isEmpty();
    }

    @Test
    void aConsumerThatThrowsDoesNotStopLaterDeliveries() {
        AtomicInteger calls = new AtomicInteger();
        LatestValueRelay<Integer> failing = new LatestValueRelay<>(uiQueue::add, value -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("consumer failed");
            }
            delivered.add(value);
        });
        failing.offer(1);
        assertThatIllegalStateException().isThrownBy(this::runUiTasks);

        failing.offer(2);

        assertThat(uiQueue).hasSize(1);
        runUiTasks();
        assertThat(delivered).containsExactly(2);
    }

    @Test
    void anExecutorThatRefusesTheTaskDoesNotStopLaterDeliveries() {
        AtomicInteger submissions = new AtomicInteger();
        Executor refusingOnce = task -> {
            if (submissions.incrementAndGet() == 1) {
                throw new RejectedExecutionException("refused");
            }
            uiQueue.add(task);
        };
        LatestValueRelay<Integer> refused = new LatestValueRelay<>(refusingOnce, delivered::add);
        try {
            refused.offer(1);
        } catch (RejectedExecutionException expected) {
            // The producer is told; the relay must still work afterwards.
        }

        refused.offer(2);

        assertThat(uiQueue).hasSize(1);
        runUiTasks();
        assertThat(delivered).containsExactly(2);
    }

    @Test
    void aNullValueIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> relay.offer(null));
        assertThat(uiQueue).isEmpty();
    }

    /**
     * A producer on its own thread against a real single-thread executor whose first delivery is
     * held. However many values are offered meanwhile, only one task may wait behind it, values
     * arrive in increasing order, and the last one offered arrives.
     */
    @Test
    void underAHeldUserInterfaceThreadAtMostOneTaskWaitsAndTheNewestValueArrives() throws Exception {
        int values = 10_000;
        ExecutorService ui = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "relay-test-ui");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger pending = new AtomicInteger();
        AtomicInteger mostPending = new AtomicInteger();
        Executor counting = task -> {
            mostPending.accumulateAndGet(pending.incrementAndGet(), Math::max);
            ui.execute(() -> {
                pending.decrementAndGet();
                task.run();
            });
        };
        CountDownLatch firstDeliveryEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch lastValueArrived = new CountDownLatch(1);
        List<Integer> arrived = new ArrayList<>();
        LatestValueRelay<Integer> threaded = new LatestValueRelay<>(counting, value -> {
            if (arrived.isEmpty()) {
                firstDeliveryEntered.countDown();
                awaitUninterruptibly(release);
            }
            arrived.add(value);
            if (value == values) {
                lastValueArrived.countDown();
            }
        });
        try {
            threaded.offer(0);
            assertThat(firstDeliveryEntered.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .isTrue();

            for (int value = 1; value <= values; value++) {
                threaded.offer(value);
            }
            release.countDown();

            assertThat(lastValueArrived.await(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            // One task was running (held) and one was waiting behind it; never more.
            assertThat(mostPending).hasValueLessThanOrEqualTo(1);
            // The 10 000 values offered while the first was held collapse into the newest.
            assertThat(ui.submit(() -> List.copyOf(arrived)).get(PATIENCE_SECONDS, TimeUnit.SECONDS))
                    .containsExactly(0, values);
        } finally {
            release.countDown();
            ui.shutdownNow();
            assertThat(ui.awaitTermination(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void runUiTasks() {
        Runnable task;
        while ((task = uiQueue.poll()) != null) {
            task.run();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (latch.await(PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                        return;
                    }
                    throw new IllegalStateException("The test never released the held delivery");
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
