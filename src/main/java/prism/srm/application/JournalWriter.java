package prism.srm.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the suspension journal for the coordinator, never under the coordinator's lock
 * (milestone 9 review, item 3): a disk that is slow or stops answering must not keep Prism from
 * releasing the processes it holds. The coordinator hands over what the journal should hold,
 * numbered; only the newest is written, in order, on the executor given. Whoever must know that
 * an entry is on disk before acting (a CPU limit before its first suspend, the user's suspension
 * before the process is touched) waits for its number, within a bound: a journal that does not
 * answer within it is failed (stalled), which stops every automatic suspension. Releasing never
 * waits for the journal.
 *
 * <p>The journal's other operations (the check of the file, a new attempt after a failure, the
 * close) run on the same executor, so they never overlap a write, and are waited for within the
 * same bound.
 */
final class JournalWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JournalWriter.class);

    /** What the journal should hold after the change numbered {@code version}. */
    record Batch(long version, List<JournalEntry> entries) {
        Batch {
            entries = List.copyOf(entries);
        }
    }

    private final SuspensionJournal journal;
    private final Executor executor;
    private final Runnable failed;
    private final Object guard = new Object();

    // Guarded by the guard.
    private Batch newest;
    private long written;
    private boolean draining;

    private volatile boolean stalled;
    private volatile String stallReason = "";

    /**
     * @param failed told when a write fails (on the writing thread) or the journal stalls (on the
     *     thread whose wait ran out); it must not block
     */
    JournalWriter(SuspensionJournal journal, Executor executor, Runnable failed) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.failed = Objects.requireNonNull(failed, "failed");
    }

    /** Whether a wait for the journal ran out: it is then failed for the rest of the session. */
    boolean stalled() {
        return stalled;
    }

    /** The newest change that is on disk, or was attempted. */
    long written() {
        synchronized (guard) {
            return written;
        }
    }

    /** Hands over what the journal should hold now; returns at once. Any thread, no coordinator lock. */
    void submit(Batch batch) {
        boolean start;
        synchronized (guard) {
            if (newest == null || batch.version() > newest.version()) {
                newest = batch;
            }
            start = !draining;
            draining = true;
        }
        if (start) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException full) {
                // The writing thread takes no more work: it is stuck, or gone. Never written on the
                // caller's thread, which may be one that releases.
                synchronized (guard) {
                    draining = false;
                }
                stall("the journal's thread takes no more work");
            }
        }
    }

    private void drain() {
        boolean finished = false;
        try {
            while (true) {
                Batch batch;
                synchronized (guard) {
                    batch = newest;
                    if (batch == null || batch.version() <= written) {
                        draining = false;
                        guard.notifyAll();
                        finished = true;
                        return;
                    }
                }
                boolean done;
                try {
                    done = !stalled && journal.write(batch.entries());
                } catch (RuntimeException e) {
                    // A write that throws is a write that failed; the journal says why.
                    LOG.error("A write of the suspension journal failed", e);
                    done = false;
                }
                synchronized (guard) {
                    written = Math.max(written, batch.version());
                    guard.notifyAll();
                }
                if (!done) {
                    notifyFailed();
                }
            }
        } finally {
            if (!finished) {
                // Left by an Error: the next hand-over starts a drain again rather than waiting on this one.
                synchronized (guard) {
                    draining = false;
                    guard.notifyAll();
                }
            }
        }
    }

    /**
     * Waits until the change numbered {@code version} is written, within the bound. True if it was
     * and the journal is healthy; false otherwise, and a wait that ran out stalls the journal.
     */
    boolean await(long version, Duration bound) {
        return switch (waitFor(version, bound)) {
            case WRITTEN -> !stalled && journal.health().healthy();
            case TIMED_OUT -> {
                stall("the journal did not answer within " + bound.toMillis() + " ms");
                yield false;
            }
            // Stalled already, or interrupted (a shutdown): not durable, and nothing to add.
            case STALLED, INTERRUPTED -> false;
        };
    }

    /**
     * Waits until the change numbered {@code version} is written, within the bound, and only reports:
     * a wait that runs out stalls nothing and tells no one. For the shutdown and the exit, which must
     * not fail the journal of the next start for a disk that is merely slow at the end.
     */
    boolean awaitQuietly(long version, Duration bound) {
        return waitFor(version, bound) == Waited.WRITTEN;
    }

    private enum Waited {
        WRITTEN,
        TIMED_OUT,
        STALLED,
        INTERRUPTED
    }

    private Waited waitFor(long version, Duration bound) {
        long deadline = System.nanoTime() + bound.toNanos();
        synchronized (guard) {
            while (written < version) {
                if (stalled) {
                    // Nothing more will be written in this session: no wait runs its bound out again.
                    return Waited.STALLED;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return Waited.TIMED_OUT;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(guard, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Waited.INTERRUPTED;
                }
            }
            return Waited.WRITTEN;
        }
    }

    /**
     * Runs a journal operation on the writing thread, after what was handed over before it, and
     * waits for it within the bound. Empty if it did not finish in time, which stalls the journal.
     */
    <T> Optional<T> call(Supplier<T> operation, Duration bound) {
        if (stalled) {
            // The writing thread is caught in a write: nothing queued behind it would run in time.
            return Optional.empty();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(operation.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException full) {
            stall("the journal's thread takes no more work");
            return Optional.empty();
        }
        try {
            return Optional.of(result.get(bound.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            stall("a journal operation did not finish within " + bound.toMillis() + " ms");
            return Optional.empty();
        } catch (ExecutionException e) {
            LOG.warn("A journal operation failed", e.getCause());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The reason the journal is stalled, while it is. */
    Optional<String> stallReason() {
        return stalled ? Optional.of(stallReason) : Optional.empty();
    }

    private void stall(String reason) {
        synchronized (guard) {
            if (stalled) {
                return;
            }
            stallReason = reason;
            stalled = true;
            // Whoever waits for a write stops waiting: none will come in this session.
            guard.notifyAll();
        }
        LOG.error("The suspension journal stalled: {}", reason);
        notifyFailed();
    }

    private void notifyFailed() {
        try {
            failed.run();
        } catch (RuntimeException e) {
            LOG.warn("The listener for a failed journal write failed", e);
        }
    }
}
