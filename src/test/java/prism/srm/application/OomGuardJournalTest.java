package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.FakeProcessController.FakeSuspension;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.SuspendResult;

/**
 * Plan A15.9 M5 and A15.2 (ADR-0011, O14 (a)): no suspension without a durable declaration, every
 * suspension that holds nothing takes its declaration out, and a failed journal releases every claim
 * at once while a release the platform leaves incomplete keeps its ownership.
 */
class OomGuardJournalTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final ProcessIdentity Q = OomRig.process(200);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @BeforeEach
    void setUp() {
        rig = new OomRig(folder.path());
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    /** The journal fails the way a disk does: another declaration cannot be written. */
    private void failTheJournal() {
        rig.files.diskFull = true;
        rig.coordinator.declare(OomRig.process(999), "other", SuspensionReason.CPU_LIMITER, "CPU limit 10 %");
        assertThat(rig.coordinator.journalHealthy()).isFalse();
    }

    @Test
    void aSuspensionIsDeclaredDurablyBeforeTheProcessIsTouched() throws Exception {
        rig.switches(false, true, false);
        rig.controller.suspend = target -> {
            // At the moment of the suspension the journal on disk holds the declaration already.
            try {
                assertThat(rig.journaled())
                        .singleElement()
                        .satisfies(entry -> assertThat(entry.reasons()).containsExactly(SuspensionReason.OOM_GUARD));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            FakeSuspension suspension = new FakeSuspension(target, 3);
            rig.controller.suspensions.add(suspension);
            return SuspendResult.suspended(ControlOutcome.success("3 threads"), suspension);
        };

        rig.samples(4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    @Test
    void noSuspendWithoutADurableDeclaration() {
        rig.switches(false, true, false);
        failTheJournal();

        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.candidate(P).effective().notInEffect()).contains("the suspension journal cannot be written");
        assertThat(rig.view().availability().journal()).isPresent();
    }

    /** The journal was healthy until the declaration itself could not be written: no suspend (test review). */
    @Test
    void aDeclarationThatCannotBeWrittenSuspendsNothing() {
        rig.switches(false, true, false);
        assertThat(rig.coordinator.journalHealthy()).isTrue();
        rig.files.diskFull = true;

        rig.samples(4, 4);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.log(prism.srm.domain.ActivitySeverity.ERROR))
                .anySatisfy(entry -> assertThat(entry.message()).contains("suspension journal cannot be written"));
        assertThat(rig.hold(P).failed()).contains("the suspension journal cannot be written");
        assertThat(rig.log(prism.srm.domain.ActivitySeverity.ERROR))
                .anySatisfy(entry -> assertThat(entry.message())
                        .isEqualTo(
                                "OOM Guard did not suspend p100 (PID 100): the suspension journal cannot be written."));
    }

    @Test
    void aSuspendThatFailsTakesTheDeclarationOutOfTheJournal() throws Exception {
        rig.switches(false, true, false);
        rig.controller.suspend =
                target -> SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "access denied"));

        rig.samples(4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100");
        assertThat(rig.journaled()).isEmpty();
        assertThat(rig.hold(P).failed().orElseThrow()).contains("access denied");
        rig.samples(4, 4, 4, 4, 4, 4, 4);
        assertThat(rig.count("suspend 100"))
                .as("not tried again in the incident")
                .isEqualTo(1);
    }

    @Test
    void aTransientSuspendFailureIsNotRetriedByOomGuard() throws Exception {
        rig.switches(false, true, false);
        rig.controller.suspend = target ->
                SuspendResult.transientFailure(ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread was ending"));

        rig.samples(4, 4, 4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.journaled()).isEmpty();
    }

    @Test
    void aProcessStoppedElsewhereLeavesNoEntry() throws Exception {
        rig.switches(false, true, false);
        rig.controller.suspend = target ->
                SuspendResult.stoppedElsewhere(ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "stopped"));

        rig.samples(4, 4);

        assertThat(rig.journaled()).isEmpty();
        assertThat(rig.hold(P).failed()).contains("stopped by another program");
        assertThat(rig.coordinator.heldFor(P)).isEmpty();
    }

    @Test
    void anIncompleteSuspendIsReleasedAtOnceAndRecordedAsFailed() throws Exception {
        rig.switches(false, true, false);
        rig.controller.suspend = target -> {
            FakeSuspension suspension = new FakeSuspension(target, 3);
            suspension.outstanding = 1;
            rig.controller.suspensions.add(suspension);
            return new SuspendResult(
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "one thread left"),
                    java.util.Optional.of(suspension));
        };

        rig.samples(4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
        assertThat(rig.hold(P).failed()).contains("the suspension was incomplete");
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.journaled()).isEmpty();
        assertThat(rig.log(ActivitySeverity.ERROR))
                .anySatisfy(entry -> assertThat(entry.message()).contains("could not suspend", "completely"));
    }

    @Test
    void anIncompleteReleaseKeepsTheJournalEntryAndIsTriedAgainOnTheNextTick() throws Exception {
        rig.switches(false, true, false);
        rig.samples(4, 4);
        AtomicInteger resumes = new AtomicInteger();
        rig.controller.resume = suspension -> {
            if (resumes.incrementAndGet() == 1) {
                suspension.outstanding = 1;
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
            }
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        rig.switches(false, false, false);
        rig.run();

        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.RELEASE_INCOMPLETE);
        assertThat(rig.count("resume 100")).isEqualTo(1);
        assertThat(rig.journaled()).hasSize(1);
        assertThat(rig.coordinator.held()).contains(P);

        rig.sample(50);

        assertThat(rig.count("resume 100")).isEqualTo(2);
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
        assertThat(rig.journaled()).isEmpty();
        assertThat(rig.coordinator.held()).isEmpty();
    }

    @Test
    void aJournalFailureReleasesEveryClaimAndResumes() throws Exception {
        rig.switches(false, true, false);
        rig.samples(4, 4);
        int written = rig.files.writes;

        failTheJournal();
        rig.guard.journalFailed("disk full");
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.hold(P).suspendClaims()).isEmpty();
        assertThat(rig.coordinator.heldFor(P)).isEmpty();
        assertThat(rig.journaled()).as("kept as evidence").hasSize(1);
        assertThat(rig.files.writes - written)
                .as("only the failed write of the other declaration")
                .isEqualTo(1);
    }

    @Test
    void aFailedNativeReleaseOnJournalFailureKeepsOwnershipAndReportsIncomplete() throws Exception {
        rig.switches(false, true, false);
        rig.samples(4, 4);
        AtomicInteger resumes = new AtomicInteger();
        rig.controller.resume = suspension -> {
            if (resumes.incrementAndGet() == 1) {
                suspension.outstanding = 1;
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, "a thread refused");
            }
            suspension.outstanding = 0;
            return ControlOutcome.success("released");
        };
        failTheJournal();
        rig.guard.journalFailed("disk full");
        rig.run();

        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.RELEASE_INCOMPLETE);
        assertThat(rig.hold(P).failed().orElseThrow()).contains("release incomplete");
        assertThat(rig.coordinator.held()).contains(P);
        assertThat(rig.journaled()).hasSize(1);
        int written = rig.files.writes;

        rig.sample(4);

        assertThat(rig.count("resume 100")).isEqualTo(2);
        assertThat(rig.hold(P).physical()).isEqualTo(OomHold.Physical.NONE);
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.journaled())
                .as("unchanged: nothing is written while the journal is failed")
                .hasSize(1);
        assertThat(rig.files.writes).isEqualTo(written);
    }

    @Test
    void journalFailedTwiceIsIdempotent() {
        rig.switches(false, true, false);
        rig.samples(4, 4);
        failTheJournal();
        rig.guard.journalFailed("disk full");
        rig.guard.journalFailed("disk full");
        rig.run();
        rig.guard.journalFailed("disk full");
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void journalFailureAfterMonitorEndedStillReleases() {
        rig.switches(false, true, false);
        rig.samples(4, 4);
        rig.guard.monitorEnded();
        rig.run();
        assertThat(rig.actions()).as("O5: holds stay when monitoring ends").containsExactly("suspend 100");

        failTheJournal();
        rig.guard.journalFailed("disk full");
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void lowerPriorityAndTerminateAreNotAffectedByAFailedJournal() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.LOWER_PRIORITY));
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.switches(true, true, true);
        failTheJournal();
        rig.guard.journalFailed("disk full");
        rig.run();

        rig.samples(7, 7, 7, 4, 4);

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "priority 200 LOW", "terminate 100");
    }
}
