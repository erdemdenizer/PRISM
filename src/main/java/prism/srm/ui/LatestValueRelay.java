package prism.srm.ui;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Carries values from a background thread to the user-interface thread, the newest only.
 *
 * <p>A producer may offer values faster than the user interface takes them. The relay then keeps
 * the newest value and drops the ones it replaces, so at most one task is ever waiting on the
 * user-interface thread and a slow frame cannot build a queue of stale updates.
 *
 * <p>How it holds: {@code latest} is non-null exactly while a delivery is owed. An offer that
 * finds it null schedules one delivery; an offer that finds a value only replaces it, because the
 * delivery already scheduled will take whatever is there when it runs. The delivery empties
 * {@code latest} in the same step in which it takes the value, so the next offer schedules again.
 *
 * @param <T> what is delivered; immutable, since it crosses threads
 */
public final class LatestValueRelay<T> {

    private final Executor uiExecutor;
    private final Consumer<T> consumer;
    private final AtomicReference<T> latest = new AtomicReference<>();

    /**
     * @param uiExecutor runs a task on the user-interface thread, such as {@code Platform::runLater}
     * @param consumer receives the values on that thread
     */
    public LatestValueRelay(Executor uiExecutor, Consumer<T> consumer) {
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    /** Offers a value from any thread. Returns at once. */
    public void offer(T value) {
        if (latest.getAndSet(Objects.requireNonNull(value, "value")) == null) {
            try {
                uiExecutor.execute(this::deliver);
            } catch (RuntimeException rejected) {
                // No delivery is owed after all. Without this the relay would wait for ever for
                // a task that was never accepted.
                latest.set(null);
                throw rejected;
            }
        }
    }

    private void deliver() {
        T value = latest.getAndSet(null);
        if (value != null) {
            consumer.accept(value);
        }
    }
}
