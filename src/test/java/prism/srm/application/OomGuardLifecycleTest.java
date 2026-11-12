package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import prism.srm.application.OomStateMachine.Transition.Kind;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomState;
import prism.srm.domain.ProcessIdentity;

/**
 * Stop against an intervention (ADR-0015 §6, external review): an intervention the gate authorized
 * but that had not begun when stop() returned is never dispatched; one that had begun may end, and
 * the shutdown hook waits for it only within its bound. Each race is made deterministic: the test
 * stops OOM Guard exactly between the last check of the gate and the native call, or holds the native
 * call open while it stops.
 */
class OomGuardLifecycleTest {

    private static final ProcessIdentity P = OomRig.process(100);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private void managed(EmergencyAction action) {
        rig = new OomRig(folder.path());
        rig.manage(P, OomRig.oom(action));
        rig.switches(true, true, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
    }

    private void observe(EmergencyAction action) {
        OomState state = action == EmergencyAction.LOWER_PRIORITY ? OomState.CRITICAL : OomState.EMERGENCY;
        rig.observed(
                state,
                state == OomState.CRITICAL ? 7 : 4,
                Kind.ENTERED,
                rig.settings().version());
    }

    /** Stopped after the gate authorized it and before it was dispatched: not dispatched at all. */
    @ParameterizedTest
    @EnumSource(
            value = EmergencyAction.class,
            names = {"LOWER_PRIORITY", "SUSPEND", "TERMINATE"})
    void anActionAuthorizedBeforeStopButNotBegunIsNeverDispatched(EmergencyAction action) {
        managed(action);
        rig.guard.beforeDispatch = () -> rig.guard.stop();
        int written = rig.files.writes;

        observe(action);
        rig.run();

        assertThat(rig.calls()).as("the gate ran, the action did not").containsExactly("facts 100");
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.files.writes).as("nothing declared").isEqualTo(written);
        assertThat(rig.notNow(P)).contains("not now: stopped");
    }

    /** The positive control of the seam: without a stop it dispatches. */
    @Test
    void withoutAStopTheSameActionIsDispatched() {
        managed(EmergencyAction.TERMINATE);
        rig.guard.beforeDispatch = () -> {};

        observe(EmergencyAction.TERMINATE);
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100", "terminate 100");
    }

    /**
     * An action in flight when stop() is called: stop() returns at once, the wait is bounded and says
     * the action is still in flight, the action ends once, and nothing new begins after it.
     */
    @Test
    void anActionInFlightEndsOnceAndTheWaitForItIsBounded() throws Exception {
        managed(EmergencyAction.TERMINATE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.controller.terminate = target -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ControlOutcome.success("started");
        };
        observe(EmergencyAction.TERMINATE);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread limiter = new Thread(
                () -> {
                    try {
                        rig.run();
                    } catch (RuntimeException e) {
                        failed.set(e);
                    }
                },
                "test-limiter");
        limiter.start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // The virtual scheduler is not shared between threads: stop() finds it shut, as the limiter's
        // executor is during a shutdown, and queues nothing beside the running task.
        rig.scheduler.shutDown();
        long start = System.nanoTime();
        rig.guard.stop();
        long stopNanos = System.nanoTime() - start;
        boolean endedWithin50ms = rig.guard.awaitActionsEnded(Duration.ofMillis(50));
        release.countDown();
        boolean endedWithin5s = rig.guard.awaitActionsEnded(Duration.ofSeconds(5));
        limiter.join(5_000);

        assertThat(failed.get()).isNull();
        assertThat(Duration.ofNanos(stopNanos)).as("stop does not wait").isLessThan(Duration.ofMillis(500));
        assertThat(endedWithin50ms)
                .as("still in flight: the bounded wait says so")
                .isFalse();
        assertThat(endedWithin5s).isTrue();
        assertThat(rig.count("terminate 100"))
                .as("the one begun before stop ended once")
                .isEqualTo(1);

        observe(EmergencyAction.TERMINATE);
        rig.run();
        assertThat(rig.count("terminate 100")).as("nothing new after stop").isEqualTo(1);
    }

    @Test
    void withNothingInFlightTheWaitReturnsAtOnce() {
        managed(EmergencyAction.SUSPEND);
        rig.guard.stop();

        long start = System.nanoTime();
        assertThat(rig.guard.awaitActionsEnded(Duration.ofSeconds(5))).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
    }
}
