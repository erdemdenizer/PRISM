package prism.srm.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ActivitySeverity;

/**
 * The steps of a controlled shutdown, run in the order they were added.
 *
 * <p>A step that fails is recorded and the remaining steps still run (LIFE-3): what a later step
 * releases must not stay held because an earlier one could not finish. Only a {@link
 * RuntimeException} is a failed step; an {@link Error} is not handled here.
 */
final class ShutdownSequence {

    private record Step(String name, Runnable action) {}

    private final ActivityLog activity;
    private final List<Step> steps = new ArrayList<>();

    ShutdownSequence(ActivityLog activity) {
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    /**
     * Appends a step.
     *
     * @param name what the step does, for the log: "stop system monitoring"
     */
    ShutdownSequence step(String name, Runnable action) {
        steps.add(new Step(Objects.requireNonNull(name, "name"), Objects.requireNonNull(action, "action")));
        return this;
    }

    /** Runs every step and returns the names of those that failed, in order. */
    List<String> run() {
        List<String> failed = new ArrayList<>();
        for (Step step : steps) {
            try {
                step.action().run();
            } catch (RuntimeException e) {
                failed.add(step.name());
                activity.record(ActivitySeverity.ERROR, "Shutdown step failed: " + step.name() + ".", e);
            }
        }
        return List.copyOf(failed);
    }
}
