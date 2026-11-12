package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.domain.Claim;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomState;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * Plan A15.9 M1: the lowering at CRITICAL and the emergency action are separate rights; the incident
 * ledger keeps one emergency action per process and incident, one lowering per process and incident,
 * one termination per identity and session, and a settings change never clears it.
 */
class OomGuardLedgerTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final ProcessIdentity Q = OomRig.process(200);

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

    /** N, then CRITICAL, a tick, EMERGENCY, the settle interval and more ticks. */
    private void criticalThenEmergency() {
        rig.samples(7, 7, 7);
        rig.samples(4, 4);
        rig.samples(4, 4, 4, 4, 4, 4, 4);
    }

    private void normal() {
        for (int i = 0; i < 60 && rig.machine.state() != OomState.NORMAL; i++) {
            rig.sample(50);
        }
        assertThat(rig.machine.state()).isEqualTo(OomState.NORMAL);
    }

    @ParameterizedTest(name = "{0} priority={1} suspend={2} terminate={3} -> {4}")
    @CsvSource(delimiter = '|', textBlock = """
            LOWER_PRIORITY | true  | false | false | priority 100 LOW
            LOWER_PRIORITY | true  | true  | true  | priority 100 LOW
            LOWER_PRIORITY | false | false | false | -
            LOWER_PRIORITY | false | true  | true  | -
            SUSPEND        | true  | true  | false | priority 100 LOW;suspend 100
            SUSPEND        | true  | true  | true  | priority 100 LOW;suspend 100
            SUSPEND        | true  | false | false | priority 100 LOW
            SUSPEND        | true  | false | true  | priority 100 LOW
            SUSPEND        | false | true  | false | suspend 100
            SUSPEND        | false | true  | true  | suspend 100
            SUSPEND        | false | false | false | -
            SUSPEND        | false | false | true  | -
            TERMINATE      | true  | false | true  | priority 100 LOW;terminate 100
            TERMINATE      | true  | true  | true  | priority 100 LOW;terminate 100
            TERMINATE      | true  | false | false | priority 100 LOW
            TERMINATE      | true  | true  | false | priority 100 LOW
            TERMINATE      | false | false | true  | terminate 100
            TERMINATE      | false | true  | true  | terminate 100
            TERMINATE      | false | false | false | -
            TERMINATE      | false | true  | false | -
            LOWER_PRIORITY | true  | true  | false | priority 100 LOW
            LOWER_PRIORITY | true  | false | true  | priority 100 LOW
            LOWER_PRIORITY | false | true  | false | -
            LOWER_PRIORITY | false | false | true  | -
            """)
    void everyConfigurationProducesItsLiteralCallSequence(
            EmergencyAction action, boolean priority, boolean suspend, boolean terminate, String expected) {
        rig.manage(P, OomRig.oom(action));
        rig.switches(priority, suspend, terminate);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        criticalThenEmergency();

        List<String> calls = "-".equals(expected) ? List.of() : Arrays.asList(expected.split(";"));
        assertThat(rig.actions()).containsExactlyElementsOf(calls);
    }

    @Test
    void criticalLoweringDoesNotConsumeTheEmergencyAction() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(true, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(7, 7, 7);
        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100");
        assertThat(rig.hold(P).suspendClaims()).containsExactly(Claim.PRESSURE);
        assertThat(rig.hold(P).priorityClaims()).containsExactly(Claim.PRESSURE);

        rig.samples(4, 4, 4, 4, 4, 4, 4, 9, 9, 9, 9);
        assertThat(rig.count("priority 100 LOW")).isEqualTo(1);
        assertThat(rig.count("suspend 100")).isEqualTo(1);
    }

    @Test
    void criticalLoweringThenTerminate() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(true, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        criticalThenEmergency();

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "terminate 100");
        assertThat(rig.hold(P).terminateRequested()).isTrue();
        // The exit is seen by the next listing; the limit service takes the process off the list.
        rig.controller.priority = (target, level) -> ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone");
        rig.unmanage(P);
        rig.run();
        assertThat(rig.view().candidates()).isEmpty();
    }

    @Test
    void memoryRelievedWithinSettleStopsTheSecondAction() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.manage(Q, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 200 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("suspend 100");
        rig.samples(20, 20, 20, 20, 20, 20);
        assertThat(rig.machine.state()).as("the hysteresis keeps EMERGENCY").isEqualTo(OomState.EMERGENCY);
        assertThat(rig.actions()).containsExactly("suspend 100");
        assertThat(rig.notNow(Q)).contains("not now: pressure relieved");

        normal();
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void lowerPriorityConfiguredIsNotLoweredTwice() {
        rig.manage(P, OomRig.oom(EmergencyAction.LOWER_PRIORITY));
        rig.switches(true, false, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        criticalThenEmergency();

        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        assertThat(rig.notNow(P)).contains("not now: already lowered");
    }

    @Test
    void directEmergencyLowersWhenCriticalWasSkipped() {
        rig.manage(P, OomRig.oom(EmergencyAction.LOWER_PRIORITY));
        rig.switches(true, false, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(3, 3, 3, 3, 3, 3, 3, 3);

        assertThat(rig.actions()).containsExactly("priority 100 LOW");
    }

    @Test
    void suspendWithPrioritySwitchOffSkipsLoweringButStillSuspends() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(7, 7, 7);
        assertThat(rig.actions()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: automatic priority reduction is off");
        rig.samples(4, 4, 4);
        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    @Test
    void suspendSwitchOffAtCriticalThenOnAtEmergencySuspendsOnce() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(true, false, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(7, 7, 7, 4, 4, 4);
        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        assertThat(rig.candidate(P).effective().notInEffect()).contains("automatic suspend is off");
        assertThat(rig.notNow(P)).contains("not now: automatic suspend is off");

        rig.switches(true, true, false);
        rig.samples(4, 4, 4);

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100");
    }

    @Test
    void unwindOrderIsResumeThenRestore() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(true, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        criticalThenEmergency();
        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100");
        assertThat(rig.coordinator.heldFor(P)).contains(prism.srm.domain.SuspensionReason.OOM_GUARD);
        rig.listed(P, 100 * OomRig.MIB, 4);
        rig.run();

        for (int i = 0; i < 60 && rig.machine.state() != OomState.NORMAL; i++) {
            rig.sample(50);
        }
        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100", "resume 100");
        rig.sample(50);

        assertThat(rig.actions())
                .containsExactly("priority 100 LOW", "suspend 100", "resume 100", "priority 100 NORMAL");
    }

    @Test
    void actionChangeMidIncidentDoesNotEscalate() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("suspend 100");

        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.samples(4, 4, 4, 4, 4, 4, 4, 4);
        assertThat(rig.actions()).as("released, never escalated").containsExactly("suspend 100", "resume 100");

        normal();
        rig.samples(3, 3);
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100", "terminate 100");
    }

    @Test
    void aSettingsChangeTriggersNoInterventionByItself() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(true, false, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        criticalThenEmergency();
        assertThat(rig.actions()).containsExactly("priority 100 LOW");

        rig.switches(true, false, true);
        rig.run();
        assertThat(rig.actions()).containsExactly("priority 100 LOW");

        rig.sample(4);
        assertThat(rig.actions()).containsExactly("priority 100 LOW", "terminate 100");
    }

    @Test
    void secondSuspendInSameIncidentNeverHappens() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.guard.releaseHold(P);
        rig.run();
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");

        rig.samples(4, 4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.notNow(P)).contains("not now: acted this incident");
    }

    @Test
    void forgetThenReManageDoesNotReArm() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.unmanage(P);
        rig.run();
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.samples(4, 4, 4, 4, 4, 4, 4);
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void aNewIncidentResetsTheLedgerButNotTerminateSent() {
        rig.controller.terminate = target -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "error 5");
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(false, true, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("terminate 100");
        assertThat(rig.hold(P).failed().orElseThrow()).contains("not retried this session");

        normal();
        rig.samples(4, 4, 4, 4, 4, 4, 4);
        assertThat(rig.actions()).containsExactly("terminate 100");

        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.samples(4, 4, 4);
        assertThat(rig.actions()).containsExactly("terminate 100", "suspend 100");
    }

    @Test
    void terminateIsSentOnceForAnIdentityAcrossIncidentsAndBothAttemptsAreRecorded() {
        rig.controller.terminate = target -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "error 5");
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.switches(false, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        normal();
        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.count("terminate 100")).isEqualTo(1);
        assertThat(rig.view().actions())
                .filteredOn(record -> record.kind() == prism.srm.domain.OomActionRecord.Kind.TERMINATE)
                .hasSize(1)
                .allSatisfy(record -> assertThat(record.text()).contains("not tried again in this session"));
        assertThat(rig.notNow(P)).contains("not now: termination was requested in this session already");
    }

    @Test
    void oscillationEceIsOneIncident() {
        rig.controller.terminate = target -> ControlOutcome.of(OperationResult.NATIVE_ERROR, "error 5");
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.TERMINATE));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.LOWER_PRIORITY));
        rig.switches(true, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(3, 3, 3, 3, 3, 3, 3, 3);
        for (int i = 0; i < 12 && rig.machine.state() == OomState.EMERGENCY; i++) {
            rig.sample(10);
        }
        assertThat(rig.machine.state()).isEqualTo(OomState.CRITICAL);
        rig.samples(10, 10, 4, 4, 4, 4, 4, 4, 4, 4);
        assertThat(rig.machine.state()).isEqualTo(OomState.EMERGENCY);

        // P, not lowered yet, is lowered once at CRITICAL; nothing acts twice in the one incident.
        assertThat(rig.actions()).containsExactly("terminate 100", "priority 200 LOW", "priority 100 LOW");
    }

    @Test
    void aCandidateThatExitsIsRecordedAndTheNextFollowsAfterTheSettle() {
        rig.controller.terminate = target -> target.equals(P)
                ? ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "gone")
                : ControlOutcome.success("started");
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.TERMINATE));
        rig.manage(Q, new OomManagement(true, OomPriority.NORMAL, EmergencyAction.TERMINATE));
        rig.switches(false, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();

        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("terminate 100");
        rig.samples(4, 4, 4);
        assertThat(rig.actions()).as("within the settle interval").containsExactly("terminate 100");
        assertThat(rig.notNow(Q)).contains("not now: settling");
        rig.samples(4, 4, 4);

        assertThat(rig.actions()).containsExactly("terminate 100", "terminate 200");
        assertThat(rig.view().actions().get(1).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
    }

    @Test
    void aNewIncidentIsNotHeldBackByTheSettleOfTheLast() {
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.TERMINATE));
        rig.switches(false, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("terminate 100");
        // Straight back to normal by hand: the next incident begins within five seconds of the action.
        rig.guard.observed(rig.observation(
                OomState.NORMAL,
                50,
                OomStateMachine.Transition.Kind.LEFT,
                rig.settings().version(),
                rig.scheduler.now()));
        rig.run();
        rig.guard.observed(rig.observation(
                OomState.EMERGENCY,
                4,
                OomStateMachine.Transition.Kind.ENTERED,
                rig.settings().version(),
                rig.scheduler.now()));
        rig.run();

        assertThat(rig.actions()).containsExactly("terminate 100", "terminate 200");
    }

    @Test
    void aSwitchTurnedOnMidIncidentActsAtTheNextSampleAfterTheSettle() {
        rig.manage(P, new OomManagement(true, OomPriority.FIRST, EmergencyAction.TERMINATE));
        rig.manage(Q, new OomManagement(true, OomPriority.LAST, EmergencyAction.SUSPEND));
        rig.switches(false, false, true);
        rig.listed(P, 100 * OomRig.MIB, 8, Q, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        assertThat(rig.actions()).containsExactly("terminate 100");

        rig.switches(false, true, true);
        rig.run();
        rig.sample(4);
        assertThat(rig.actions()).as("still settling").containsExactly("terminate 100");
        rig.scheduler.elapse(Duration.ofSeconds(4));
        rig.sample(4);

        assertThat(rig.actions()).containsExactly("terminate 100", "suspend 200");
        assertThat(Set.copyOf(rig.coordinator.heldFor(Q))).contains(prism.srm.domain.SuspensionReason.OOM_GUARD);
    }

    /**
     * Plan A15.9 M1 (mutation N01 of the second run): CRITICAL in the middle of an incident does not
     * give the emergency action back. Released by the user while EMERGENCY lasts, the process is not
     * suspended again when the state passes through CRITICAL and returns to EMERGENCY.
     */
    @Test
    void passingThroughCriticalDoesNotGiveTheEmergencyActionBack() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.guard.releaseHold(P);
        rig.run();
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");

        for (int i = 0; i < 12 && rig.machine.state() == OomState.EMERGENCY; i++) {
            rig.sample(10);
        }
        assertThat(rig.machine.state()).isEqualTo(OomState.CRITICAL);
        rig.samples(4, 4, 4, 4, 4, 4, 4, 4);
        assertThat(rig.machine.state()).isEqualTo(OomState.EMERGENCY);

        assertThat(rig.count("suspend 100")).isEqualTo(1);
        assertThat(rig.notNow(P)).contains("not now: acted this incident");
    }

    /**
     * Plan A15.9 M1 (mutation N05 of the second run): {@code forgetThenReManageDoesNotReArm} with the
     * process listed again after it is managed again, so that only the ledger can stop a second action.
     */
    @Test
    void forgetThenReManageAndListAgainDoesNotReArm() {
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, true);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.unmanage(P);
        rig.run();
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.samples(4, 4, 4, 4, 4, 4, 4);
        assertThat(rig.notNow(P)).contains("not now: acted this incident");
        rig.manage(P, OomRig.oom(EmergencyAction.TERMINATE));
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        assertThat(rig.notNow(P)).contains("not now: acted this incident");
    }
}
