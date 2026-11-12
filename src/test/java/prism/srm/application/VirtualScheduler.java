package prism.srm.application;

import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The limiter thread on a virtual clock: tasks run in the order they are due when the test
 * advances the clock, on the test's thread. A task may let time pass while it runs ({@link
 * #elapse}), as a suspend does that takes a while.
 */
final class VirtualScheduler implements LimiterScheduler {

    /** One scheduled task; cancelling it keeps it from running. */
    private static final class Task implements Future<Object> {
        final long due;
        final long order;
        final Runnable body;
        boolean cancelled;
        boolean done;

        Task(long due, long order, Runnable body) {
            this.due = due;
            this.order = order;
            this.body = body;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("A virtual task is not waited for");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("A virtual task is not waited for");
        }
    }

    private final PriorityQueue<Task> queue =
            new PriorityQueue<>(Comparator.<Task>comparingLong(task -> task.due).thenComparingLong(task -> task.order));
    private long now = 1_000_000_000L;
    private long order;
    private boolean shutDown;

    /** The clock the code under test reads. */
    LongSupplier clock() {
        return () -> now;
    }

    long now() {
        return now;
    }

    /** Time passes within the running task, as a slow call takes it. */
    void elapse(Duration duration) {
        now += duration.toNanos();
    }

    /** Lets the duration pass, running every task that falls due in it, in order. */
    void advance(Duration duration) {
        long end = now + duration.toNanos();
        while (true) {
            Task task = queue.peek();
            if (task == null || task.due > end) {
                break;
            }
            queue.poll();
            if (task.cancelled) {
                continue;
            }
            now = Math.max(now, task.due);
            task.done = true;
            task.body.run();
        }
        now = Math.max(now, end);
    }

    /** Tasks still to run. */
    long pending() {
        return queue.stream().filter(task -> !task.cancelled).count();
    }

    /** From now on every new task is refused, as by a shut down executor. */
    void shutDown() {
        shutDown = true;
    }

    @Override
    public void execute(Runnable task) {
        schedule(task, Duration.ZERO);
    }

    @Override
    public Future<?> schedule(Runnable task, Duration delay) {
        if (shutDown) {
            throw new RejectedExecutionException("shut down");
        }
        Task scheduled = new Task(now + Math.max(0, delay.toNanos()), order++, task);
        queue.add(scheduled);
        return scheduled;
    }
}
