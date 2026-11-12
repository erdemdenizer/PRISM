package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.Listed;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomState;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.SuspendResult;

/** OOM Guard as a whole (plan A15.2): what it never does, whom it acts on, what it records and shows. */
class OomGuardTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final ProcessIdentity Q = OomRig.process(200);
    private static final ProcessIdentity BIG = OomRig.process(300);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @BeforeEach
    void setUp() {
        rig = new OomRig(folder.path());
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private void normal() {
        for (int i = 0; i < 60 && rig.machine.state() != OomState.NORMAL; i++) {
            rig.sample(50);
        }
    }

    @Test
    void withTheDefaultSwitchesNothingIsEverLoweredSuspendedOrTerminated() {
        for (EmergencyAction action : EmergencyAction.values()) {
            ProcessIdentity identity = OomRig.process(100 + action.ordinal());
            rig.manage(identity, OomRig.oom(action));
        }
        rig.listed(
                OomRig.process(100),
                100 * OomRig.MIB,
                8,
                OomRig.process(101),
                100 * OomRig.MIB,
                8,
                OomRig.process(102),
                100 * OomRig.MIB,
                8,
                OomRig.process(103),
                100 * OomRig.MIB,
                8);
        rig.run();

        rig.samples(14, 14, 7, 7, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 2, 1, 1, 1);
        rig.watch(OomRig.process(102), 50 * OomRig.MIB);
        rig.listed(OomRig.process(102), 100 * OomRig.MIB, 8);
        rig.run();
        normal();

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.view().candidates())
                .allSatisfy(
                        candidate -> assertThat(candidate.effective().action()).isEqualTo(EmergencyAction.NOTIFY));
    }

    @Test
    void theBiggestProcessIsNeverTouchedUnlessManagedWithOomOn() {
        rig.manage(BIG, new OomManagement(false, OomPriority.FIRST, EmergencyAction.TERMINATE));
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(true, true, true);
        rig.listed(BIG, 64L << 30, 8, P, 10 * OomRig.MIB, 8, Q, 32L << 30, 8);
        rig.run();

        rig.samples(7, 7, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100");
    }

    @Test
    void aReusedPidIsNeverActedOn() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        ProcessIdentity reused = new ProcessIdentity(P.pid(), P.startTime().plusSeconds(60));
        rig.listed(reused, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: not listed");
    }

    @Test
    void residentMemoryOfAReusedPidIsNotAttributed() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.watch(P, 100 * OomRig.MIB);
        rig.sample(50);
        ProcessIdentity reused = new ProcessIdentity(P.pid(), P.startTime().plusSeconds(60));
        rig.listed(reused, 500 * OomRig.MIB, 8);
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.candidate(P).residentBytes()).isEmpty();
        assertThat(rig.hold(P).exceededPending()).isFalse();
    }

    @Test
    void aRefusedActionIsRecordedOnceAndTheNextCandidateFollowsAfterTheSettle() {
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND));
        rig.manage(Q, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        rig.controller.suspend = target -> target.equals(P)
                ? SuspendResult.failed(ControlOutcome.of(OperationResult.ACCESS_DENIED, "access denied"))
                : SuspendResult.suspended(
                        ControlOutcome.success("3 threads"), new FakeProcessController.FakeSuspension(target, 3));

        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100", "suspend 200");
        assertThat(rig.view().actions())
                .filteredOn(record -> record.identity().equals(P))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.result()).isEqualTo(OperationResult.ACCESS_DENIED);
                    assertThat(record.state()).isEqualTo(OomState.EMERGENCY);
                    assertThat(record.kind()).isEqualTo(OomActionRecord.Kind.SUSPEND);
                });
    }

    @Test
    void recoveryIsNotDelayedByTheSettleInterval() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.guard.observed(rig.observation(
                OomState.NORMAL,
                60,
                OomStateMachine.Transition.Kind.LEFT,
                rig.settings().version(),
                rig.scheduler.now()));
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void whenSystemMonitoringEndsTheHoldsStayAndTurningTheSwitchOffReleasesThem() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.guard.monitorEnded();
        rig.run();
        assertThat(rig.view().monitorEnded()).isTrue();
        assertThat(rig.coordinator.heldFor(P)).contains(SuspensionReason.OOM_GUARD);

        rig.switches(false, false, false);
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void aControllerThatThrowsIsLoggedAndTheNextEvaluationRuns() {
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.TERMINATE));
        rig.manage(Q, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(false, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        rig.controller.terminate = target -> {
            if (target.equals(P)) {
                throw new IllegalStateException("a defect");
            }
            return ControlOutcome.success("started");
        };

        rig.samples(4, 4);
        assertThat(rig.log(ActivitySeverity.ERROR))
                .anySatisfy(entry -> assertThat(entry.message()).contains("could not complete an evaluation"));
        rig.samples(4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).containsExactly("terminate 100", "terminate 200");
        assertThat(rig.hold(P).terminateRequested()).isTrue();
    }

    @Test
    void anErrorIsNotCaught() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(false, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.controller.terminate = target -> {
            throw new OutOfMemoryError("pretend");
        };

        assertThatThrownBy(() -> rig.samples(4, 4)).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void anActionIsRecordedExactlyOnceAndTheRecordsAreBounded() {
        for (int i = 0; i < 60; i++) {
            rig.manage(OomRig.process(1_000 + i), OomRig.oom(EmergencyAction.TERMINATE));
        }
        rig.switches(false, false, true);
        Object[] listing = new Object[180];
        for (int i = 0; i < 60; i++) {
            listing[3 * i] = OomRig.process(1_000 + i);
            listing[3 * i + 1] = (60 - i) * OomRig.MIB;
            listing[3 * i + 2] = 8;
        }
        rig.listed(listing);
        rig.run();

        rig.sample(4);
        for (int i = 0; i < 60 * 6; i++) {
            rig.sample(4);
        }

        assertThat(rig.count("terminate 1000")).isEqualTo(1);
        assertThat(rig.actions()).hasSize(60);
        assertThat(rig.view().actions()).hasSize(50);
        assertThat(rig.view().actions().get(0).identity()).isEqualTo(OomRig.process(1_059));
        assertThat(rig.view().actions().get(49).identity()).isEqualTo(OomRig.process(1_010));
    }

    @Test
    void theViewNamesTheStateTheReadingAndWhenTheStateBegan() {
        rig.manage(P, OomRig.oom(EmergencyAction.NOTIFY));
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(14, 14);

        assertThat(rig.view().monitoring()).isTrue();
        assertThat(rig.view().state()).isEqualTo(OomState.WARNING);
        assertThat(rig.view().reading().orElseThrow().availablePercent()).isEqualTo(14.0);
        assertThat(rig.view().since()).contains(Instant.parse("2026-10-12T10:00:00Z"));
        assertThat(rig.view().underOomManagement()).isEqualTo(1);

        rig.missing();
        assertThat(rig.view().reading()).isEmpty();
        assertThat(rig.view().state()).isEqualTo(OomState.WARNING);

        rig.monitoring(false);
        rig.run();
        assertThat(rig.view().monitoring()).isFalse();
        assertThat(rig.view().state()).isEqualTo(OomState.NORMAL);
        assertThat(rig.view().since()).isEmpty();
    }

    @Test
    void aFloorAtOrAboveHalfTheMemoryIsShownAsIgnored() {
        rig.thresholds(new OomThresholds(15, 8, 5, OomRig.TOTAL / 2));
        rig.sample(50);

        assertThat(rig.view().notice())
                .hasValueSatisfying(notice -> assertThat(notice).contains("floor is ignored"));
    }

    @Test
    void anEmergencyWithNobodyLeftToActOnSaysSo() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.manage(Q, OomRig.oom(EmergencyAction.NOTIFY));
        rig.switches(false, false, false);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);

        assertThat(rig.view().notice())
                .hasValueSatisfying(notice -> assertThat(notice)
                        .contains("No further candidates", "2 under OOM management", "1 with an action not in effect"));
    }

    @Test
    void aListingIsNotKeptBeyondItsEvaluation() {
        rig.manage(P, OomRig.oom(EmergencyAction.NOTIFY));
        rig.guard.listed(Map.of(
                P, new Listed(OptionalLong.of(1), OptionalInt.of(8)),
                Q, new Listed(OptionalLong.of(2), OptionalInt.of(8))));
        rig.run();
        rig.scheduler.advance(Duration.ofSeconds(1));

        assertThat(rig.view().candidates()).hasSize(1);
        assertThat(rig.candidate(P).residentBytes()).hasValue(1);
    }
}
