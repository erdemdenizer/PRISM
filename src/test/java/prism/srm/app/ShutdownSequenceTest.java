package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;

class ShutdownSequenceTest {

    private final ActivityLog activity = new ActivityLog(10, () -> Instant.EPOCH);
    private final List<String> ran = new ArrayList<>();

    @Test
    void runsTheStepsInTheOrderTheyWereAdded() {
        List<String> failed = new ShutdownSequence(activity)
                .step("first", () -> ran.add("first"))
                .step("second", () -> ran.add("second"))
                .step("third", () -> ran.add("third"))
                .run();

        assertThat(ran).containsExactly("first", "second", "third");
        assertThat(failed).isEmpty();
        assertThat(activity.entries()).isEmpty();
    }

    /** LIFE-3: a cleanup step that fails is logged and does not prevent the remaining steps. */
    @Test
    void aFailingStepIsRecordedAndTheRemainingStepsStillRun() {
        List<String> failed = new ShutdownSequence(activity)
                .step("first", () -> ran.add("first"))
                .step("second", () -> {
                    throw new IllegalStateException("cannot stop");
                })
                .step("third", () -> ran.add("third"))
                .step("fourth", () -> {
                    throw new UnsupportedOperationException("cannot stop either");
                })
                .step("fifth", () -> ran.add("fifth"))
                .run();

        assertThat(ran).containsExactly("first", "third", "fifth");
        assertThat(failed).containsExactly("second", "fourth");
        assertThat(activity.entries())
                .extracting(ActivityEntry::severity, ActivityEntry::message)
                .containsExactly(
                        tuple(ActivitySeverity.ERROR, "Shutdown step failed: second."),
                        tuple(ActivitySeverity.ERROR, "Shutdown step failed: fourth."));
    }

    /** An Error is not a failed step: it is not caught, and nothing pretends the shutdown went on. */
    @Test
    void anErrorIsNotHandled() {
        OutOfMemoryError fatal = new OutOfMemoryError("simulated");
        ShutdownSequence sequence = new ShutdownSequence(activity)
                .step("first", () -> {
                    throw fatal;
                })
                .step("second", () -> ran.add("second"));

        assertThatThrownBy(sequence::run).isSameAs(fatal);

        assertThat(ran).isEmpty();
        assertThat(activity.entries()).isEmpty();
    }

    @Test
    void aSequenceWithoutStepsDoesNothing() {
        assertThat(new ShutdownSequence(activity).run()).isEmpty();
    }
}
