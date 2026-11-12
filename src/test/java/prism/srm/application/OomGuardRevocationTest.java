package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.OomStateMachine.Transition.Kind;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomState;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.ProcessIdentity;

/**
 * Settings published against an intervention (external code audit, P0; ADR-0015 §2 and §6): the gate
 * authorizes under one version of the settings; if the user publishes another before the native call
 * begins, the call is not made. A call that began before the publication may end; nothing new
 * begins under the settings the user replaced. Each interleaving is forced: the test publishes
 * exactly between the gate and the native call (the {@code beforeDispatch} seam), or while a native
 * call is held open on a thread of its own.
 */
class OomGuardRevocationTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final long LIMIT = 100 * OomRig.MIB;

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @AfterEach
    void tearDown() throws Exception {
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

    /** Publishes once, at the seam: between the gate and the native call. */
    private void atTheSeam(Consumer<OomRig> publication) {
        AtomicBoolean done = new AtomicBoolean();
        rig.guard.beforeDispatch = () -> {
            if (done.compareAndSet(false, true)) {
                publication.accept(rig);
            }
        };
    }

    private void assertNotDispatched() throws Exception {
        assertThat(rig.calls()).as("the gate ran, the action did not").containsExactly("facts 100");
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.journaled()).isEmpty();
        // Refused at the start with "settings changed"; the evaluation the publication queued then says
        // why under the new settings.
        assertThat(rig.notNow(P)).isPresent();
        assertThat(rig.log(prism.srm.domain.ActivitySeverity.WARNING))
                .noneSatisfy(
                        entry -> assertThat(entry.message()).contains("OOM Guard suspended", "requested the forced"));
    }

    // ----- Revocation between the gate and the native call -----

    @Test
    void terminateRevokedAfterTheGateIsNotSent() throws Exception {
        managed(EmergencyAction.TERMINATE);
        atTheSeam(r -> r.switches(true, true, false));

        observe(EmergencyAction.TERMINATE);
        rig.run();

        assertNotDispatched();
        assertThat(rig.hold(P).terminateRequested()).isFalse();
    }

    @Test
    void suspendRevokedAfterTheGateIsNotMadeNorDeclared() throws Exception {
        managed(EmergencyAction.SUSPEND);
        int written = rig.files.writes;
        atTheSeam(r -> r.switches(true, false, true));

        observe(EmergencyAction.SUSPEND);
        rig.run();

        assertNotDispatched();
        assertThat(rig.files.writes).as("nothing declared").isEqualTo(written);
    }

    @Test
    void lowerPriorityRevokedAfterTheGateIsNotMade() throws Exception {
        managed(EmergencyAction.LOWER_PRIORITY);
        atTheSeam(r -> r.switches(false, true, true));

        observe(EmergencyAction.LOWER_PRIORITY);
        rig.run();

        assertNotDispatched();
        assertThat(rig.hold(P).priorityClaims()).isEmpty();
    }

    @Test
    void monitoringTurnedOffAfterTheGateStopsTheAction() throws Exception {
        managed(EmergencyAction.TERMINATE);
        atTheSeam(r -> r.monitoring(false));

        observe(EmergencyAction.TERMINATE);
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100");
    }

    @Test
    void thresholdsChangedAfterTheGateStopAnActionJudgedUnderTheOldOnes() throws Exception {
        managed(EmergencyAction.SUSPEND);
        atTheSeam(r -> r.thresholds(new OomThresholds(15, 8, 3, 0)));

        observe(EmergencyAction.SUSPEND);
        rig.run();

        assertNotDispatched();
    }

    /** The memory limit (LIM-9) goes through the same start: revoked between its gate and its call. */
    @Test
    void theMemoryLimitsSuspendRevokedAfterTheGateIsNotMade() throws Exception {
        managed(EmergencyAction.SUSPEND);
        rig.watch(P, LIMIT);
        rig.run();
        rig.sample(50);
        atTheSeam(r -> r.switches(true, false, true));

        rig.listed(P, LIMIT + OomRig.MIB, 8);
        rig.run();

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.coordinator.held()).isEmpty();
    }

    // ----- Positive controls -----

    @Test
    void withNothingPublishedTheActionIsDispatched() throws Exception {
        managed(EmergencyAction.TERMINATE);
        atTheSeam(r -> {});

        observe(EmergencyAction.TERMINATE);
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100", "terminate 100");
    }

    @Test
    void theSameSettingsPublishedAgainChangeNothingAndTheActionIsDispatched() throws Exception {
        managed(EmergencyAction.SUSPEND);
        atTheSeam(r -> r.switches(true, true, true));

        observe(EmergencyAction.SUSPEND);
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
        assertThat(rig.coordinator.held()).contains(P);
    }

    @Test
    void afterARevokedActionTheNextSampleUnderTheNewSettingsDoesNotActEither() throws Exception {
        managed(EmergencyAction.TERMINATE);
        atTheSeam(r -> r.switches(true, true, false));
        observe(EmergencyAction.TERMINATE);
        rig.run();

        observe(EmergencyAction.TERMINATE);
        rig.run();

        assertThat(rig.count("terminate 100")).isZero();
        assertThat(rig.candidate(P).effective().action()).isEqualTo(EmergencyAction.NOTIFY);
    }

    // ----- Forced interleavings on real threads -----

    /**
     * The limiter thread is held between the gate and the native call while another thread, as the
     * JavaFX thread would, publishes the revocation; then the limiter goes on. Nothing is sent.
     */
    @Test
    void aRevocationFromAnotherThreadWhileTheLimiterWaitsAtTheSeamWins() throws Exception {
        managed(EmergencyAction.TERMINATE);
        CountDownLatch atSeam = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        rig.guard.beforeDispatch = () -> {
            atSeam.countDown();
            await(go);
        };
        observe(EmergencyAction.TERMINATE);
        // The virtual scheduler is not shared between threads: the publisher's notice is refused.
        rig.scheduler.shutDown();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread limiter = thread("test-limiter", rig::run, failed);
        limiter.start();
        assertThat(atSeam.await(10, TimeUnit.SECONDS)).isTrue();

        Thread publisher = thread("test-fx", () -> rig.switches(true, true, false), failed);
        publisher.start();
        publisher.join(5_000);
        assertThat(publisher.isAlive())
                .as("publication does not wait for the limiter")
                .isFalse();
        go.countDown();
        limiter.join(5_000);

        assertThat(failed.get()).isNull();
        assertThat(rig.count("terminate 100")).isZero();
    }

    /**
     * The native call began before the publication and is held open: the publication returns at once,
     * without waiting for the call; the call that began ends once, and it is the only one.
     */
    @Test
    void aCallThatBeganBeforeThePublicationEndsOnceAndThePublicationDoesNotWaitForIt() throws Exception {
        managed(EmergencyAction.TERMINATE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.controller.terminate = target -> {
            entered.countDown();
            await(release);
            return ControlOutcome.success("started");
        };
        observe(EmergencyAction.TERMINATE);
        rig.scheduler.shutDown();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread limiter = thread("test-limiter", rig::run, failed);
        limiter.start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        Thread publisher = thread("test-fx", () -> rig.switches(true, true, false), failed);
        publisher.start();
        publisher.join(5_000);
        long publishNanos = System.nanoTime() - start;
        boolean publisherDone = !publisher.isAlive();
        release.countDown();
        limiter.join(5_000);

        assertThat(failed.get()).isNull();
        assertThat(publisherDone).isTrue();
        assertThat(Duration.ofNanos(publishNanos))
                .as("no lock is held during the native call")
                .isLessThan(Duration.ofSeconds(2));
        assertThat(rig.count("terminate 100"))
                .as("begun before the revocation: in flight, once")
                .isEqualTo(1);
    }

    /**
     * The interleaving of the external audit (mutation R2): the start of the action has read the old
     * version but has not yet counted itself in flight, and the publication runs exactly then. Under
     * the action lock the publication cannot return in that gap: it returns only after the start has
     * counted itself, so the action that began under the old settings was in flight before the new
     * ones were in force. Without the lock the publication returns inside the gap, and an action
     * under the replaced settings begins after the publication returned. The moment the publication
     * returns is observed without the lock.
     */
    @Test
    void aPublicationCannotCompleteBetweenTheVersionCheckAndTheInFlightCount() throws Exception {
        managed(EmergencyAction.TERMINATE);
        CountDownLatch readInStart = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        CountDownLatch callEntered = new CountDownLatch(1);
        CountDownLatch callRelease = new CountDownLatch(1);
        // The next read of the settings after the gate is the one at the start of the action.
        rig.guard.beforeDispatch = () -> {
            AtomicBoolean once = new AtomicBoolean();
            rig.afterSettingsRead = () -> {
                if (once.compareAndSet(false, true)) {
                    readInStart.countDown();
                    await(goOn);
                }
            };
        };
        rig.controller.terminate = target -> {
            callEntered.countDown();
            await(callRelease);
            return ControlOutcome.success("started");
        };
        observe(EmergencyAction.TERMINATE);
        rig.scheduler.shutDown();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread limiter = thread("test-limiter", rig::run, failed);
        limiter.start();
        assertThat(readInStart.await(10, TimeUnit.SECONDS))
                .as("the start read the old version")
                .isTrue();

        // Observed without the action lock: the moment the publication returns. Asking OOM Guard what is
        // in flight would take the lock and so wait for the start, hiding the gap.
        CountDownLatch published = new CountDownLatch(1);
        Thread publisher = thread(
                "test-fx",
                () -> {
                    rig.switches(true, true, false);
                    published.countDown();
                },
                failed);
        publisher.start();
        // The start is held between its version read and its in-flight count for half a second.
        boolean publishedInTheGap = published.await(500, TimeUnit.MILLISECONDS);
        goOn.countDown();
        boolean callBegan = callEntered.await(5, TimeUnit.SECONDS);
        assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
        callRelease.countDown();
        limiter.join(5_000);
        publisher.join(5_000);

        assertThat(failed.get()).isNull();
        assertThat(publishedInTheGap && callBegan)
                .as("the publication returned while the start had read the old version but was not yet in"
                        + " flight, and that action then began under the replaced settings")
                .isFalse();
        assertThat(publishedInTheGap)
                .as("the publication waited for the start to finish counting")
                .isFalse();
        assertThat(callBegan)
                .as("the action began before the publication: in flight, once")
                .isTrue();
        assertThat(rig.count("terminate 100")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread thread(String name, Runnable body, AtomicReference<Throwable> failed) {
        return new Thread(
                () -> {
                    try {
                        body.run();
                    } catch (RuntimeException e) {
                        failed.set(e);
                    }
                },
                name);
    }
}
