package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.application.FakeProcessController.FakeSuspension;
import prism.srm.application.OomStateMachine.Transition.Kind;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomState;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.platform.SuspendResult;

/**
 * Plan A15.9 M8, M10 and M11: every way into OOM Guard and every condition of the gate. One setup
 * meets every condition and acts; each other test takes away exactly one condition and expects the
 * reason of that condition, word for word, and no native call. Observations are made by hand; the
 * clock does not move unless a test says so.
 */
class OomGuardGateTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final ProcessIdentity Q = OomRig.process(200);
    private static final ProcessIdentity R = OomRig.process(300);
    private static final long LIMIT = 100 * OomRig.MIB;

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @BeforeEach
    void setUp() {
        rig = new OomRig(folder.path());
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.run();
    }

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private long version() {
        return rig.settings().version();
    }

    private void emergency() {
        rig.observed(OomState.EMERGENCY, 4, Kind.ENTERED, version());
    }

    private void listP() {
        rig.listed(P, 100 * OomRig.MIB, 8);
    }

    // ----- The positive control -----

    @Test
    void whenEveryConditionHoldsTheActionIsTaken() {
        listP();
        emergency();
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
        assertThat(rig.notNow(P)).isEmpty();
    }

    // ----- Item 0: the trigger -----

    @ParameterizedTest
    @ValueSource(strings = {"settings", "managed", "monitor ended"})
    void aSecondaryTriggerStartsNoIntervention(String trigger) {
        emergency();
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: not listed");
        listP();
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: waiting for a sample");

        switch (trigger) {
            case "settings" -> rig.guard.settingsChanged();
            case "managed" -> rig.push();
            default -> rig.guard.monitorEnded();
        }
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: waiting for a sample");
    }

    @Test
    void aJournalTriggerStartsNoIntervention() {
        emergency();
        rig.run();
        listP();
        rig.run();
        rig.guard.journalFailed("checked");
        rig.run();

        assertThat(rig.calls()).isEmpty();
    }

    /** The journal trigger, where the journal itself does not block the action: Terminate (test review). */
    @Test
    void aJournalTriggerStartsNoTermination() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(false, false, true);
        emergency();
        rig.run();
        listP();
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: waiting for a sample");

        rig.guard.journalFailed("checked");
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: waiting for a sample");
    }

    /** Item 1 at the last moment: stopped while the facts were read, nothing is done (test review). */
    @Test
    void stoppedWhileTheFactsAreReadActsNothing() {
        rig.controller.facts = target -> {
            rig.guard.stop();
            return prism.srm.domain.ProtectionFacts.NONE;
        };
        listP();
        emergency();
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100");
        assertThat(rig.coordinator.held()).isEmpty();
    }

    // ----- Items 1 to 8 -----

    @Test
    void item1StoppedActsNothing() {
        listP();
        emergency();
        rig.guard.stop();
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: stopped");
    }

    @Test
    void aSampleAfterStopQueuesNothing() {
        listP();
        rig.run();
        rig.guard.stop();
        rig.run();
        emergency();

        assertThat(rig.scheduler.pending()).isZero();
        assertThat(rig.calls()).isEmpty();
    }

    @Test
    void item2MonitoringOffActsNothing() {
        listP();
        rig.run();
        rig.monitoring(false);
        rig.run();
        emergency();
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: monitoring is off");
    }

    @Test
    void item3AStaleReadingActsNothing() {
        listP();
        rig.run();
        long at = rig.scheduler.now();
        rig.scheduler.elapse(Duration.ofSeconds(6));
        rig.guard.observed(rig.observation(OomState.EMERGENCY, 4, Kind.ENTERED, version(), at));
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: stale reading");
    }

    @Test
    void item4NoReadingAGapOrNoMonitoringActsNothing() {
        listP();
        rig.run();
        rig.observed(OomState.EMERGENCY, 4, Kind.HOLD, version());
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: no reading");

        rig.observed(OomState.EMERGENCY, 4, Kind.GAP, version());
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: after a gap");

        rig.observed(OomState.EMERGENCY, 4, Kind.OFF, version());
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: not monitoring");

        rig.guard.monitorEnded();
        emergency();
        rig.run();
        assertThat(rig.notNow(P)).contains("not now: not monitoring");
        assertThat(rig.calls()).isEmpty();
    }

    @Test
    void item5AReadingThatNoLongerMeetsTheLevelActsNothing() {
        listP();
        rig.observed(OomState.EMERGENCY, 20, Kind.NONE, version());
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: pressure relieved");
    }

    @Test
    void item5TheSettleIntervalHoldsTheNextCandidate() {
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        emergency();
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");

        rig.scheduler.elapse(Duration.ofSeconds(4));
        rig.observed(OomState.EMERGENCY, 4, Kind.NONE, version());
        rig.run();
        assertThat(rig.notNow(Q)).contains("not now: settling");

        rig.scheduler.elapse(Duration.ofSeconds(1));
        rig.observed(OomState.EMERGENCY, 4, Kind.NONE, version());
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100", "facts 200", "suspend 200");
    }

    @Test
    void item6TheSwitchOffActsNothingAndSaysSo() {
        listP();
        rig.run();
        rig.switches(false, false, false);
        rig.run();
        emergency();
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: automatic suspend is off");
    }

    @Test
    void item7AProcessNotListedIsNotActedOn() {
        emergency();
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: not listed");
    }

    @Test
    void item7EveryActionReChecksProtectionFirstAndAProtectedProcessIsNotTriedAgain() {
        rig.controller.facts = target -> new ProtectionFacts(false, Optional.of(true), false);
        listP();
        emergency();
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100");
        assertThat(rig.notNow(P)).contains("not now: refused: protected");

        rig.observed(OomState.EMERGENCY, 4, Kind.NONE, version());
        rig.run();
        assertThat(rig.calls()).as("not asked again in the session").containsExactly("facts 100");
        assertThat(rig.notNow(P)).contains("not now: refused: protected");
    }

    @Test
    void item8TheLedgerAllowsOneEmergencyActionPerIncident() {
        listP();
        emergency();
        rig.run();
        rig.guard.releaseHold(P);
        rig.run();
        int before = rig.calls().size();

        rig.scheduler.elapse(Duration.ofSeconds(6));
        rig.observed(OomState.EMERGENCY, 4, Kind.NONE, version());
        rig.run();

        assertThat(rig.calls().subList(before, rig.calls().size())).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: acted this incident");
    }

    @Test
    void aQueuedEvaluateAfterStopMakesNoNativeCall() {
        listP();
        rig.run();
        emergency();
        assertThat(rig.scheduler.pending()).isEqualTo(1);
        rig.guard.stop();
        rig.run();

        assertThat(rig.calls()).isEmpty();
    }

    @Test
    void postGapHealthyReadingActsNothing() {
        rig.samples(3, 3);
        assertThat(rig.machine.state()).isEqualTo(OomState.EMERGENCY);
        listP();
        rig.run();
        rig.scheduler.elapse(Duration.ofSeconds(10));
        assertThat(rig.sample(30).kind()).isEqualTo(Kind.GAP);
        for (int i = 0; i < 40; i++) {
            rig.sample(30);
        }

        assertThat(rig.machine.state()).isEqualTo(OomState.NORMAL);
        assertThat(rig.actions()).isEmpty();
    }

    @Test
    void noNewActionOnAMissingReading() {
        listP();
        rig.run();
        rig.samples(3, 3);
        assertThat(rig.actions()).containsExactly("suspend 100");
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        for (int i = 0; i < 10; i++) {
            rig.missing();
        }

        assertThat(rig.actions()).as("no release either: the state holds").containsExactly("suspend 100");
        assertThat(rig.notNow(Q)).contains("not now: no reading");
    }

    // ----- M10: the version of the settings -----

    @Test
    void aQueuedEvaluationCannotUseAPreChangeObservation() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(false, false, false);
        rig.run();
        long v1 = version();

        listP();
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: no reading");

        rig.observed(OomState.EMERGENCY, 4, Kind.ENTERED, v1);
        assertThat(rig.scheduler.pending()).isEqualTo(1);
        long v2 = rig.switches(false, false, true);
        assertThat(v2).isEqualTo(v1 + 1);
        assertThat(rig.scheduler.pending())
                .as("one evaluation, the triggers merged")
                .isEqualTo(1);

        rig.run();
        assertThat(rig.scheduler.pending()).isZero();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: settings changed; waiting for a sample");

        rig.observed(OomState.EMERGENCY, 4, Kind.NONE, v2);
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "terminate 100");
    }

    @Test
    void aChangedThresholdNeedsAReadingThatMeetsItUnderTheNewVersion() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        long v1 = rig.switches(false, false, true);
        listP();
        rig.run();

        rig.observed(OomState.EMERGENCY, 2, Kind.ENTERED, v1);
        long v2 = rig.thresholds(new OomThresholds(15, 8, 3, 256 * OomRig.MIB));
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: settings changed; waiting for a sample");

        rig.observed(OomState.EMERGENCY, 4, Kind.NONE, v2);
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: pressure relieved");

        rig.observed(OomState.EMERGENCY, 2, Kind.NONE, v2);
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "terminate 100");
    }

    @Test
    void theGateReadsTheSettingsOncePerAuthorization() {
        listP();
        rig.run();
        emergency();
        int before = rig.settingsReads.get();
        rig.run();

        // One read for the evaluation, one in the gate, one at the start of the native call (under the
        // action lock, in order with publication: ADR-0015 §2), one for the view.
        assertThat(rig.settingsReads.get() - before).isEqualTo(4);
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
    }

    // ----- M11: the trigger and the intent -----

    @Test
    void aListingTriggerNeverAuthorizesAPressureIntervention() {
        emergency();
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: not listed");

        listP();
        assertThat(rig.scheduler.pending()).isEqualTo(1);
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: waiting for a sample");

        emergency();
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
    }

    @Test
    void aSampleTriggerNeverAuthorizesAPolicyIntervention() {
        rig.watch(P, LIMIT);
        rig.listed(P, LIMIT + 1, 8);
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: no reading");
        assertThat(rig.hold(P).exceededPending()).isTrue();

        rig.observed(OomState.NORMAL, 50, Kind.NONE, version());
        rig.run();
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: waiting for a listing");
        assertThat(rig.hold(P).exceededPending()).isTrue();

        rig.listed(P, LIMIT + 1, 8);
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
        assertThat(rig.hold(P).exceededPending()).isFalse();
    }

    @Test
    void coalescedTriggersAuthorizeEachIntentOnceWithOneActionPerEvaluate() {
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.watch(Q, LIMIT);
        rig.run();

        emergency();
        assertThat(rig.scheduler.pending()).isEqualTo(1);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, LIMIT + 1, 8);
        assertThat(rig.scheduler.pending()).isEqualTo(1);
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
        assertThat(rig.hold(Q).exceededPending()).isTrue();
        assertThat(rig.notNow(Q)).contains("not now: one action per evaluation");

        rig.listed(P, 100 * OomRig.MIB, 8, Q, LIMIT + 1, 8);
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100", "facts 200", "suspend 200");
    }

    @Test
    void policyRunsInTheSameEvaluateWhenPressureDidNotAct() {
        rig.manage(R, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.watch(Q, LIMIT);
        rig.listed(R, 100 * OomRig.MIB, 8, P, 100 * OomRig.MIB, 8, Q, LIMIT / 2, 8);
        emergency();
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 300", "suspend 300");

        emergency();
        rig.listed(R, 100 * OomRig.MIB, 8, P, 100 * OomRig.MIB, 8, Q, LIMIT + 1, 8);
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 300", "suspend 300", "facts 200", "suspend 200");
        assertThat(rig.notNow(P)).contains("not now: settling");
    }

    @Test
    void pendingTriggersAreClearedAtTaskStartNotEnd() {
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.watch(Q, LIMIT);
        listP();
        rig.run();
        AtomicLong seen = new AtomicLong(-1);
        rig.controller.suspend = target -> {
            if (target.equals(P)) {
                rig.listed(P, 100 * OomRig.MIB, 8, Q, LIMIT + 1, 8);
                seen.set(rig.scheduler.pending());
            }
            FakeSuspension suspension = new FakeSuspension(target, 3);
            rig.controller.suspensions.add(suspension);
            return SuspendResult.suspended(ControlOutcome.success("3 threads"), suspension);
        };

        emergency();
        rig.run();

        assertThat(seen.get()).isEqualTo(1);
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100", "facts 200", "suspend 200");
    }

    @Test
    void theLatestObservationIsEvaluatedWhenTriggersCoalesce() {
        listP();
        rig.run();
        rig.observed(OomState.NORMAL, 50, Kind.NONE, version());
        emergency();
        rig.run();
        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
    }

    @Test
    void theEarlierObservationIsNotActedOnWhenTheLatestIsNormal() {
        listP();
        rig.run();
        emergency();
        rig.observed(OomState.NORMAL, 50, Kind.NONE, version());
        rig.run();
        assertThat(rig.calls()).isEmpty();
    }

    @Test
    void anObservationDuringAnEvaluationIsNotLost() {
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        rig.controller.suspend = target -> {
            rig.observed(OomState.NORMAL, 50, Kind.LEFT, version());
            FakeSuspension suspension = new FakeSuspension(target, 3);
            rig.controller.suspensions.add(suspension);
            return SuspendResult.suspended(ControlOutcome.success("3 threads"), suspension);
        };
        int views = rig.views.size();

        emergency();
        rig.run();

        assertThat(rig.views.size() - views).as("two evaluations").isEqualTo(2);
        assertThat(rig.view().state()).isEqualTo(OomState.NORMAL);
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(List.copyOf(rig.coordinator.held())).isEmpty();
    }

    /**
     * Plan A15.9 M8 (mutation N43 of the second run): after a gap, as after a sleep, no action for a
     * settle interval, even when the readings after the gap still meet EMERGENCY.
     */
    @Test
    void afterAGapAReadingStillAtEmergencyWaitsForTheSettle() {
        rig.samples(3, 3);
        assertThat(rig.machine.state()).isEqualTo(OomState.EMERGENCY);
        listP();
        rig.run();
        rig.scheduler.elapse(Duration.ofSeconds(10));
        assertThat(rig.sample(3).kind()).isEqualTo(Kind.GAP);
        assertThat(rig.actions()).isEmpty();

        rig.sample(3);
        assertThat(rig.actions()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: settling");

        rig.samples(3, 3, 3, 3, 3);
        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    /**
     * Item 6 at the gate itself (plan A15.9 M8, test review of the second mutation run): the candidate
     * was chosen under settings with the switch on; before the gate runs, the user turns it off and a
     * sample under the new settings arrives. Items 0b and the start check pass (the observation and the
     * settings are both new); only item 6 refuses.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SUSPEND", "TERMINATE"})
    void item6ASwitchTurnedOffBetweenTheChoiceAndTheGateActsNothing(String action) {
        boolean terminate = action.equals("TERMINATE");
        rig.manage(P, OomRig.oom(EmergencyAction.valueOf(action)));
        rig.switches(false, !terminate, terminate);
        listP();
        rig.run();
        rig.afterSettingsRead = () -> {
            rig.afterSettingsRead = () -> {};
            long next = rig.switches(false, false, false);
            rig.observed(OomState.EMERGENCY, 4, Kind.NONE, next);
        };
        emergency();
        rig.run();

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P))
                .contains(terminate ? "not now: emergency termination is off" : "not now: automatic suspend is off");
    }

    /** The positive control of the test above: the same sequence without the switch change acts. */
    @Test
    void item6WithoutTheSwitchChangeTheSameSequenceActs() {
        listP();
        rig.run();
        rig.afterSettingsRead = () -> {
            rig.afterSettingsRead = () -> {};
            rig.observed(OomState.EMERGENCY, 4, Kind.NONE, version());
        };
        emergency();
        rig.run();

        assertThat(rig.calls()).containsExactly("facts 100", "suspend 100");
    }

    /**
     * Item 1 (test review of the second mutation run): a stop that arrives after the evaluation began,
     * right when the gate reads the settings, ends the gate before any native call, not even a read of
     * the protection facts.
     */
    @Test
    void aStopDuringTheGateMakesNoNativeCallNotEvenAFactsRead() {
        listP();
        rig.run();
        AtomicLong reads = new AtomicLong();
        rig.afterSettingsRead = () -> {
            if (reads.incrementAndGet() == 2) {
                rig.guard.stop();
            }
        };
        emergency();
        rig.run();

        assertThat(reads.get()).as("the evaluation's read, then the gate's").isGreaterThanOrEqualTo(2);
        assertThat(rig.calls()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: stopped");
    }
}
