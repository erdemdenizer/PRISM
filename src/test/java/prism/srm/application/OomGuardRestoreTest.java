package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;

/**
 * Plan A15.9 M4 (OOM-11, O2, O7, O16): a lowered priority is restored to the level it had, only where
 * the system lets Prism raise it back; never over a level someone set meanwhile; a failure is an error;
 * the shutdown restores within its bound and the exit hook does not.
 */
class OomGuardRestoreTest {

    private static final ProcessIdentity P = OomRig.process(100);

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private OomRig rig;

    @AfterEach
    void tearDown() {
        rig.close();
    }

    private void rig(Restorability restorability, OomSignalStatus signal) {
        rig = new OomRig(folder.path(), restorability, signal);
    }

    private void loweredAtCritical(int listedPriority) {
        rig.manage(P, OomRig.oom(EmergencyAction.LOWER_PRIORITY));
        rig.switches(true, false, false);
        rig.listed(P, 100 * OomRig.MIB, listedPriority);
        rig.run();
        rig.samples(7, 7, 7);
    }

    private void normal() {
        for (int i = 0; i < 60 && rig.machine.state() != OomState.NORMAL; i++) {
            rig.sample(50);
        }
        rig.samples(50, 50);
    }

    @Test
    void restorableSystemRestoresAtNormal() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        loweredAtCritical(6);
        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        rig.listed(P, 100 * OomRig.MIB, 4);

        normal();

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "priority 100 BELOW_NORMAL");
        assertThat(rig.count("priority 100 NORMAL")).isZero();
        assertThat(rig.view().actions().get(0).kind()).isEqualTo(OomActionRecord.Kind.RESTORE_PRIORITY);
        assertThat(rig.view().actions().get(0).text()).contains("restored", "Below normal");
        assertThat(rig.hold(P).previousPriority()).isEmpty();
    }

    @Test
    void restoreFailureIsAnErrorNotSilence() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        loweredAtCritical(8);
        rig.listed(P, 100 * OomRig.MIB, 4);
        rig.controller.priority = (target, level) -> level == PriorityLevel.LOW
                ? ControlOutcome.success("set")
                : ControlOutcome.of(OperationResult.ACCESS_DENIED, "access denied");

        normal();
        long attempts = rig.count("priority 100 NORMAL");
        rig.samples(50, 50, 50);

        assertThat(attempts).isEqualTo(1);
        assertThat(rig.count("priority 100 NORMAL")).as("not tried again").isEqualTo(1);
        assertThat(rig.log(ActivitySeverity.ERROR))
                .anySatisfy(entry -> assertThat(entry.message()).contains("could not be restored", "access denied"));
        assertThat(rig.hold(P).failed()).contains("priority could not be restored: access denied");
    }

    @Test
    void restoreSkippedWhenSomeoneChangedThePriority() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        loweredAtCritical(8);
        rig.listed(P, 100 * OomRig.MIB, 10);

        normal();

        assertThat(rig.actions()).containsExactly("priority 100 LOW");
        assertThat(rig.log(ActivitySeverity.INFO))
                .anySatisfy(entry -> assertThat(entry.message()).contains("as it is", "Above normal"));
    }

    @Test
    void aProcessListedAtLowIsRestoredToItsEarlierLevel() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        loweredAtCritical(8);
        rig.listed(P, 100 * OomRig.MIB, 4);

        normal();

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "priority 100 NORMAL");
    }

    @Test
    void notReversibleSystemShowsLowerPriorityAsNotInEffectAndNeverLowers() {
        rig(Restorability.NOT_REVERSIBLE, OomSignalStatus.VALIDATED);
        loweredAtCritical(8);
        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.candidate(P).effective().action()).isEqualTo(EmergencyAction.NOTIFY);
        assertThat(rig.candidate(P).effective().notInEffect())
                .contains("not available on this system: a lowered priority cannot be raised back without privilege");
        assertThat(rig.view().availability().priority()).isPresent();
    }

    @Test
    void unvalidatedSignalDisablesLoweringToo() {
        rig(Restorability.RESTORABLE, OomSignalStatus.UNVALIDATED);
        loweredAtCritical(8);
        rig.samples(4, 4, 4, 4, 4, 4, 4);

        assertThat(rig.actions()).isEmpty();
        assertThat(rig.candidate(P).effective().notInEffect())
                .contains("not available on this system: memory signals are not validated");
    }

    @Test
    void shutdownRestoresWithinItsBound() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        ProcessIdentity q = OomRig.process(200);
        ProcessIdentity r = OomRig.process(300);
        for (ProcessIdentity each : new ProcessIdentity[] {P, q, r}) {
            rig.manage(each, OomRig.oom(EmergencyAction.LOWER_PRIORITY));
        }
        rig.switches(true, false, false);
        rig.listed(P, 300 * OomRig.MIB, 8, q, 200 * OomRig.MIB, 8, r, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(7, 7, 7, 7);
        assertThat(rig.actions()).hasSize(3);
        rig.listed(P, 300 * OomRig.MIB, 4, q, 200 * OomRig.MIB, 4, r, 100 * OomRig.MIB, 4);
        rig.run();
        rig.guard.stop();
        rig.run();
        rig.controller.priority = (target, level) -> {
            rig.scheduler.elapse(Duration.ofSeconds(3));
            return ControlOutcome.success("set");
        };

        boolean all = rig.guard.restorePriorities(Duration.ofSeconds(5));

        assertThat(all).isFalse();
        assertThat(rig.actions().stream().filter(call -> call.endsWith(" NORMAL")))
                .hasSize(2);
        assertThat(rig.log(ActivitySeverity.WARNING))
                .filteredOn(entry -> entry.message().contains("left the priority"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.message()).contains("p300 (PID 300)"));
    }

    @Test
    void switchOffMidIncidentRestoresImmediately() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        rig.manage(P, OomRig.oom(EmergencyAction.LOWER_PRIORITY));
        rig.switches(true, false, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(4, 4);
        rig.listed(P, 100 * OomRig.MIB, 4);
        rig.run();

        rig.switches(false, false, false);
        rig.run();

        assertThat(rig.machine.state()).isEqualTo(OomState.EMERGENCY);
        assertThat(rig.actions()).containsExactly("priority 100 LOW", "priority 100 NORMAL");
    }

    @Test
    void exitHookReleasesButDoesNotRestore() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(true, true, false);
        rig.listed(P, 100 * OomRig.MIB, 8);
        rig.run();
        rig.samples(7, 7, 4, 4);
        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100");
        // Listed at Low, as after the lowering: a restore would be sent now (test review of the second
        // mutation run; listed at Normal, a restore is skipped as "changed meanwhile" and proves nothing).
        rig.listed(P, 100 * OomRig.MIB, 4);
        rig.run();

        rig.guard.stop();
        rig.coordinator.releaseForExit(Duration.ofMillis(1_500));
        rig.run();

        assertThat(rig.actions()).containsExactly("priority 100 LOW", "suspend 100", "resume 100");
    }

    /**
     * OOM-11 across processes (plan A15.2, test review): at Normal the holds unwind one per evaluation,
     * the resumes first, then the restores; never two changes in one evaluation.
     */
    @Test
    void twoHoldsUnwindOnePerEvaluationResumesFirst() {
        rig(Restorability.RESTORABLE, OomSignalStatus.VALIDATED);
        ProcessIdentity q = OomRig.process(200);
        rig.manage(P, OomRig.oom(EmergencyAction.SUSPEND));
        rig.manage(q, OomRig.oom(EmergencyAction.SUSPEND));
        rig.switches(true, true, false);
        rig.listed(P, 300 * OomRig.MIB, 8, q, 200 * OomRig.MIB, 8);
        rig.run();
        for (int i = 0; i < 12; i++) {
            rig.sample(7);
        }
        for (int i = 0; i < 12; i++) {
            rig.sample(4);
        }
        assertThat(rig.actions())
                .containsExactlyInAnyOrder("priority 100 LOW", "priority 200 LOW", "suspend 100", "suspend 200");
        rig.listed(P, 300 * OomRig.MIB, 4, q, 200 * OomRig.MIB, 4);

        java.util.List<java.util.List<String>> perEvaluation = new java.util.ArrayList<>();
        for (int i = 0;
                i < 60 && perEvaluation.stream().mapToInt(java.util.List::size).sum() < 4;
                i++) {
            int before = rig.actions().size();
            rig.sample(50);
            java.util.List<String> now = rig.actions();
            if (now.size() > before) {
                perEvaluation.add(java.util.List.copyOf(now.subList(before, now.size())));
            }
        }

        assertThat(perEvaluation).allSatisfy(changes -> assertThat(changes).hasSize(1));
        java.util.List<String> order =
                perEvaluation.stream().map(changes -> changes.get(0)).toList();
        assertThat(order).hasSize(4);
        assertThat(order.subList(0, 2)).allSatisfy(call -> assertThat(call).startsWith("resume "));
        assertThat(order.subList(2, 4)).containsExactlyInAnyOrder("priority 100 NORMAL", "priority 200 NORMAL");
    }
}
