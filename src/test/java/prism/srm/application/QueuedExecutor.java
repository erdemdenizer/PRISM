package prism.srm.application;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * An executor that keeps what it is given until the test runs it, on the test's own thread, so a
 * test decides exactly when each task runs. It can be made to refuse tasks, as a full queue does.
 */
public final class QueuedExecutor implements Executor {

    private final Deque<Runnable> tasks = new ArrayDeque<>();
    public boolean refusing;

    @Override
    public void execute(Runnable task) {
        if (refusing) {
            throw new RejectedExecutionException("full");
        }
        tasks.add(task);
    }

    public int waiting() {
        return tasks.size();
    }

    /** Runs the tasks waiting now, and those they queue, in order. */
    public void runAll() {
        while (!tasks.isEmpty()) {
            tasks.poll().run();
        }
    }

    /** Runs the first waiting task only. */
    public void runNext() {
        tasks.poll().run();
    }
}
