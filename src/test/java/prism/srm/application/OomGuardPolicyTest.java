package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.Claim;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;
import prism.srm.domain.SuspensionReason;

/**
 * The emulated memory policy (LIM-9, plan A15.3, A15.9 M7 and M9): one action per crossing of the
 * limit, a hold that ends when the resident memory stays below the margin or when the user ends it,
 * the same switches as OOM Guard, and nothing at all where the readings are not validated.
 */
class OomGuardPolicyTest {

    private static final ProcessIdentity P = OomRig.process(100);
    private static final long MIB = OomRig.MIB;
    private static final long LIMIT = 100 * MIB;
    private static final long CLEAR = OomGuard.clearBelow(LIMIT, OomGuard.Tuning.DEFAULTS);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private void rig(EmergencyAction action, OomSignalStatus signal) {
        rig = new OomRig(folder.path(), Restorability.RESTORABLE, signal);
        rig.manage(P, OomRig.oom(action));
        rig.switches(true, true, true);
        rig.watch(P, LIMIT);
        rig.sample(50);
    }

    private void listed(long resident) {
        rig.listed(P, resident, 8);
        rig.run();
    }

    @Test
    void anExceededLimitIsActedOnOnceAndOnlyANewCrossingActsAgain() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("suspend 100");
        assertThat(rig.hold(P).suspendClaims()).containsExactly(Claim.RESIDENT_POLICY);
        listed(LIMIT + 1);
        listed(LIMIT * 2);
        assertThat(rig.count("suspend 100")).isEqualTo(1);

        listed(CLEAR);
        listed(CLEAR);
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
        rig.sample(50);
        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100", "suspend 100");
    }

    /** As under pressure: never lowered from a level that is not known, nor from Low (native review). */
    @Test
    void thePolicyLowersOnlyFromAKnownLevelAboveLow() {
        rig(EmergencyAction.LOWER_PRIORITY, OomSignalStatus.VALIDATED);
        // 24 is Realtime on Windows: no level of the table.
        rig.listed(P, LIMIT + 1, 24);
        rig.run();
        rig.listed(P, LIMIT + 1, 4);
        rig.run();
        assertThat(rig.actions()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: already Low");

        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        assertThat(rig.hold(P).previousPriority()).contains(prism.srm.domain.PriorityLevel.NORMAL);
    }

    @Test
    void theFirstListingOfANewWatchIsACrossingWhenItIsAboveTheLimit() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT * 3);
        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    @Test
    void aListingWithoutResidentMemoryNeitherStartsNorClears() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        rig.guard.listed(java.util.Map.of(
                P, new prism.srm.domain.Listed(java.util.OptionalLong.empty(), java.util.OptionalInt.of(8))));
        rig.run();
        assertThat(rig.actions()).isEmpty();

        listed(LIMIT + 1);
        rig.guard.listed(java.util.Map.of(
                P, new prism.srm.domain.Listed(java.util.OptionalLong.empty(), java.util.OptionalInt.of(8))));
        rig.run();
        listed(CLEAR);
        rig.guard.listed(java.util.Map.of(
                P, new prism.srm.domain.Listed(java.util.OptionalLong.empty(), java.util.OptionalInt.of(8))));
        rig.run();
        assertThat(rig.actions()).as("one listing below is not two").containsExactly("suspend 100");
    }

    @Test
    void exactlyAtTheClearanceClearsAndOneByteAboveDoesNot() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        listed(CLEAR + 1);
        listed(CLEAR + 1);
        listed(CLEAR + 1);
        assertThat(rig.actions()).containsExactly("suspend 100");
        listed(CLEAR);
        listed(CLEAR);
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void exactlyTheLimitIsNotAboveIt() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT);
        listed(LIMIT);
        assertThat(rig.actions()).isEmpty();
        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    @Test
    void aOneMiBLimitCanBeCleared() {
        rig = new OomRig(folder.path());
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(false, true, false);
        rig.watch(P, MIB);
        rig.sample(50);
        rig.listed(P, MIB + 1, 8);
        rig.run();
        assertThat(rig.actions()).containsExactly("suspend 100");

        rig.listed(P, 0, 8);
        rig.run();
        rig.listed(P, 0, 8);
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void belowTheLimitWithTheFlagSetIsNotActedOn() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        rig.switches(true, false, true);
        listed(101 * MIB);
        assertThat(rig.hold(P).exceededPending()).isTrue();
        listed(97 * MIB);
        assertThat(97 * MIB).isGreaterThan(CLEAR);
        assertThat(rig.hold(P).exceededPending())
                .as("not cleared: 97 MiB is above 95")
                .isTrue();

        rig.switches(true, true, true);
        rig.sample(50);
        listed(97 * MIB);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: below the limit");
        listed(101 * MIB);
        assertThat(rig.actions()).containsExactly("suspend 100");
    }

    @Test
    void afterTheGuardReleasesAProcessStillOverItsLimitThePolicyWaitsForANewCrossing() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        rig.guard.releaseHold(P);
        rig.run();
        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");

        for (int i = 0; i < 5; i++) {
            rig.sample(50);
            listed(LIMIT + 1);
        }

        assertThat(rig.count("suspend 100")).isEqualTo(1);
    }

    @Test
    void aSuspendedProcessKeepsItsHoldWhileItsMemoryStaysUntilTheUserEndsIt() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        for (int i = 0; i < 10; i++) {
            rig.sample(50);
            listed(LIMIT + 1);
        }
        assertThat(rig.coordinator.heldFor(P)).contains(SuspensionReason.OOM_GUARD);

        rig.guard.releaseHold(P);
        rig.run();

        assertThat(rig.coordinator.heldFor(P)).isEmpty();
    }

    @Test
    void aLimitRaisedAboveTheResidentMemoryReleasesByTheClearanceRule() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        rig.watch(P, 300 * MIB);
        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("suspend 100");
        listed(LIMIT + 1);

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void aWatchThatEndsReleasesItsHold() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        rig.unwatch(P);
        rig.run();

        assertThat(rig.actions()).containsExactly("suspend 100", "resume 100");
    }

    @Test
    void withOomManagementOffACrossingIsOnlyLogged() {
        rig = new OomRig(folder.path());
        rig.manage(P, new OomManagement(false, OomPriority.NORMAL, EmergencyAction.SUSPEND));
        rig.switches(true, true, true);
        rig.watch(P, LIMIT);
        rig.sample(50);
        listed(LIMIT + 1);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: OOM management is off for this process");
        assertThat(rig.log(ActivitySeverity.WARNING))
                .filteredOn(entry -> entry.message().contains("above its watched limit"))
                .hasSize(1);
    }

    @Test
    void withMonitoringOffThePolicyTakesNoAction() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        rig.monitoring(false);
        rig.sample(50);
        listed(LIMIT + 1);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.notNow(P)).contains("not now: monitoring is off");
    }

    @Test
    void aLoweredPriorityForTheLimitIsRestoredWhenTheMemoryFalls() {
        rig(EmergencyAction.LOWER_PRIORITY, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        rig.listed(P, CLEAR, 4);
        rig.run();
        rig.listed(P, CLEAR, 4);
        rig.run();

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "priority 100 NORMAL");
    }

    @Test
    void aTerminationForTheLimitIsRequestedOncePerSession() {
        rig(EmergencyAction.TERMINATE, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        listed(CLEAR);
        listed(CLEAR);
        listed(LIMIT + 1);

        assertThat(rig.actions()).containsExactly("terminate 100");
    }

    @Test
    void theSameProcessUnderPressureAndOverItsLimitIsSuspendedOnce() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        rig.samples(4, 4);
        assertThat(rig.actions()).isEmpty();
        listed(LIMIT + 1);
        rig.samples(4, 4);

        assertThat(rig.actions()).containsExactly("suspend 100");
        assertThat(rig.hold(P).suspendClaims()).containsExactlyInAnyOrder(Claim.PRESSURE, Claim.RESIDENT_POLICY);
    }

    /** M7: the full set-up that acts, with only the signal changed, acts not at all; and positively once validated. */
    @ParameterizedTest
    @CsvSource({"LOWER_PRIORITY, priority 100 LOW", "SUSPEND, suspend 100", "TERMINATE, terminate 100"})
    void policyInterventionIsOffOnAnUnvalidatedSystem(EmergencyAction action, String call) throws Exception {
        rig(action, OomSignalStatus.UNVALIDATED);
        int written = rig.files.writes;
        listed(LIMIT + 1);

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.journaled()).isEmpty();
        assertThat(rig.files.writes).isEqualTo(written);
        assertThat(rig.hold(P).exceededPending()).isTrue();
        assertThat(rig.candidate(P).effective().action()).isEqualTo(EmergencyAction.NOTIFY);
        assertThat(rig.candidate(P).effective().notInEffect())
                .contains("not available on this system: memory signals are not validated");
        assertThat(rig.log(ActivitySeverity.WARNING))
                .filteredOn(entry -> entry.message().contains("above its watched limit"))
                .hasSize(1);
        rig.close();

        rig(action, OomSignalStatus.VALIDATED);
        listed(LIMIT + 1);
        assertThat(rig.calls()).containsExactly("facts 100", call);
        if (action == EmergencyAction.SUSPEND) {
            assertThat(rig.coordinator.held()).contains(P);
        }
    }

    /**
     * Linux before its evidence is reviewed (ADR-0015 §7): restorability NOT_REVERSIBLE and the signal
     * not validated. With every switch on, an Emergency and a crossing of the watched limit together
     * make no native call and no journal write; the state, the warning and the log still work.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = EmergencyAction.class,
            names = {"LOWER_PRIORITY", "SUSPEND", "TERMINATE"})
    void onLinuxBeforeValidationNeitherPressureNorTheLimitActs(EmergencyAction action) {
        rig = new OomRig(folder.path(), Restorability.NOT_REVERSIBLE, OomSignalStatus.UNVALIDATED);
        rig.manage(P, OomRig.oom(action));
        rig.switches(true, true, true);
        rig.watch(P, LIMIT);
        int written = rig.files.writes;

        listed(LIMIT + 1);
        rig.samples(7, 7, 4, 4, 4, 4, 4, 4, 4, 4);
        listed(LIMIT * 2);

        assertThat(rig.calls()).isEmpty();
        assertThat(rig.coordinator.held()).isEmpty();
        assertThat(rig.files.writes).isEqualTo(written);
        assertThat(rig.view().state()).isEqualTo(prism.srm.domain.OomState.EMERGENCY);
        assertThat(rig.view().availability().suspend())
                .contains("not available on this system: memory signals are not validated");
        assertThat(rig.candidate(P).effective().action()).isEqualTo(EmergencyAction.NOTIFY);
        assertThat(rig.log(ActivitySeverity.WARNING))
                .anySatisfy(entry -> assertThat(entry.message()).contains("above its watched limit"));
    }

    /**
     * Plan A15.9 M9 boundary row (mutation N30 of the second run): a resident memory equal to the
     * limit is not above it. Nothing is flagged or logged; one byte more crosses.
     */
    @Test
    void aResidentEqualToTheLimitIsNotAbove() {
        rig(EmergencyAction.SUSPEND, OomSignalStatus.VALIDATED);
        listed(LIMIT);
        listed(LIMIT);

        assertThat(rig.hold(P).exceededPending()).isFalse();
        assertThat(rig.log(ActivitySeverity.WARNING))
                .noneSatisfy(entry -> assertThat(entry.message()).contains("above its watched limit"));
        assertThat(rig.actions()).isEmpty();

        listed(LIMIT + 1);
        assertThat(rig.actions()).containsExactly("suspend 100");
    }
}
