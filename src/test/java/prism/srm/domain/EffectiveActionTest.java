package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The action in effect (OOM-6, OOM-8, OOM-9; plan A7.1): every combination of the chosen action and
 * the three switches, then the axes of the system. Every expectation is written out by hand, not
 * computed from the rule under test.
 */
class EffectiveActionTest {

    private static final Map<ControlAction, Capability> WINDOWS = capabilities(
            Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.PROCESS_HANDLE_TERMINATE),
            Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION),
            Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PRIORITY_CLASS));

    /** The capabilities the Linux controller reports (pidfd present): signals and the nice value. */
    private static final Map<ControlAction, Capability> LINUX = capabilities(
            Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.KILL_SIGNAL),
            Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL),
            Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.NICE_VALUE));

    /**
     * Linux before its evidence is reviewed (ADR-0015 §7): with every switch on and a healthy journal,
     * no action is in effect, and the reason is the signal, not a switch.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = EmergencyAction.class,
            names = {"LOWER_PRIORITY", "SUSPEND", "TERMINATE"})
    void onLinuxBeforeValidationEveryActionFallsBackToNotify(EmergencyAction chosen) {
        EffectiveAction effective = EffectiveAction.of(
                new OomManagement(true, OomPriority.NORMAL, chosen),
                new OomSwitches(true, true, true, true),
                LINUX,
                true,
                Restorability.NOT_REVERSIBLE,
                OomSignalStatus.UNVALIDATED);

        org.assertj.core.api.Assertions.assertThat(effective.action()).isEqualTo(EmergencyAction.NOTIFY);
        org.assertj.core.api.Assertions.assertThat(effective.notInEffect())
                .contains("not available on this system: memory signals are not validated");
        org.assertj.core.api.Assertions.assertThat(EffectiveAction.whyNotLowering(
                        new OomSwitches(true, true, true, true),
                        LINUX,
                        Restorability.NOT_REVERSIBLE,
                        OomSignalStatus.UNVALIDATED))
                .isPresent();
    }

    private static Map<ControlAction, Capability> capabilities(
            Capability forceKill, Capability suspension, Capability priority) {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        map.put(ControlAction.END_TASK, Capability.UNAVAILABLE);
        map.put(ControlAction.FORCE_KILL, forceKill);
        map.put(ControlAction.SUSPEND, suspension);
        map.put(ControlAction.RESUME, suspension);
        map.put(ControlAction.CHANGE_PRIORITY, priority);
        return map;
    }

    private static EffectiveAction effective(
            EmergencyAction chosen, boolean priority, boolean suspend, boolean terminate) {
        return EffectiveAction.of(
                new OomManagement(true, OomPriority.NORMAL, chosen),
                new OomSwitches(true, priority, suspend, terminate),
                WINDOWS,
                true,
                Restorability.RESTORABLE,
                OomSignalStatus.VALIDATED);
    }

    @ParameterizedTest(name = "{0} priority={1} suspend={2} terminate={3} -> {4} lowers={5} \"{6}\"")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            NOTIFY         | false | false | false | NOTIFY         | false | -
            NOTIFY         | true  | true  | true  | NOTIFY         | false | -
            LOWER_PRIORITY | false | false | false | NOTIFY         | false | automatic priority reduction is off
            LOWER_PRIORITY | false | true  | true  | NOTIFY         | false | automatic priority reduction is off
            LOWER_PRIORITY | true  | false | false | LOWER_PRIORITY | true  | -
            LOWER_PRIORITY | true  | true  | true  | LOWER_PRIORITY | true  | -
            SUSPEND        | false | false | false | NOTIFY         | false | automatic suspend is off
            SUSPEND        | true  | false | false | NOTIFY         | true  | automatic suspend is off
            SUSPEND        | false | true  | false | SUSPEND        | false | -
            SUSPEND        | true  | true  | false | SUSPEND        | true  | -
            SUSPEND        | true  | true  | true  | SUSPEND        | true  | -
            SUSPEND        | false | false | true  | NOTIFY         | false | automatic suspend is off
            TERMINATE      | false | false | false | NOTIFY         | false | emergency termination is off
            TERMINATE      | true  | true  | false | NOTIFY         | true  | emergency termination is off
            TERMINATE      | false | true  | false | NOTIFY         | false | emergency termination is off
            TERMINATE      | false | false | true  | TERMINATE      | false | -
            TERMINATE      | true  | false | true  | TERMINATE      | true  | -
            TERMINATE      | true  | true  | true  | TERMINATE      | true  | -
            """)
    void theChosenActionTakesEffectOnlyAsFarAsTheSwitchesAllow(
            EmergencyAction chosen,
            boolean priority,
            boolean suspend,
            boolean terminate,
            EmergencyAction expected,
            boolean lowers,
            String why) {
        EffectiveAction effective = effective(chosen, priority, suspend, terminate);

        assertThat(effective.action()).isEqualTo(expected);
        assertThat(effective.lowersAtCritical()).isEqualTo(lowers);
        assertThat(effective.notInEffect()).isEqualTo(Optional.ofNullable(why));
    }

    @Test
    void withOomManagementOffNothingIsInEffectWhateverTheSwitches() {
        EffectiveAction off = EffectiveAction.of(
                new OomManagement(false, OomPriority.FIRST, EmergencyAction.TERMINATE),
                new OomSwitches(true, true, true, true),
                WINDOWS,
                true,
                Restorability.RESTORABLE,
                OomSignalStatus.VALIDATED);

        assertThat(off)
                .isEqualTo(new EffectiveAction(
                        EmergencyAction.NOTIFY, false, Optional.of("OOM management is off for this process")));
    }

    @Test
    void whereAPriorityCannotBeRestoredLoweringIsNotAvailableAndSuspendStillIs() {
        OomSwitches all = new OomSwitches(true, true, true, true);
        EffectiveAction lower = EffectiveAction.of(
                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.LOWER_PRIORITY),
                all,
                WINDOWS,
                true,
                Restorability.NOT_REVERSIBLE,
                OomSignalStatus.VALIDATED);
        EffectiveAction suspend = EffectiveAction.of(
                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND),
                all,
                WINDOWS,
                true,
                Restorability.NOT_REVERSIBLE,
                OomSignalStatus.VALIDATED);

        assertThat(lower)
                .isEqualTo(
                        new EffectiveAction(
                                EmergencyAction.NOTIFY,
                                false,
                                Optional.of(
                                        "not available on this system: a lowered priority cannot be raised back without privilege")));
        assertThat(suspend).isEqualTo(new EffectiveAction(EmergencyAction.SUSPEND, false, Optional.empty()));
    }

    @Test
    void onASystemWhoseSignalIsNotValidatedEveryActionFallsBackToNotify() {
        OomSwitches all = new OomSwitches(true, true, true, true);
        for (EmergencyAction chosen : new EmergencyAction[] {
            EmergencyAction.LOWER_PRIORITY, EmergencyAction.SUSPEND, EmergencyAction.TERMINATE
        }) {
            EffectiveAction effective = EffectiveAction.of(
                    new OomManagement(true, OomPriority.NORMAL, chosen),
                    all,
                    WINDOWS,
                    true,
                    Restorability.RESTORABLE,
                    OomSignalStatus.UNVALIDATED);
            assertThat(effective)
                    .as("%s", chosen)
                    .isEqualTo(new EffectiveAction(
                            EmergencyAction.NOTIFY,
                            false,
                            Optional.of("not available on this system: memory signals are not validated")));
        }
    }

    @Test
    void aSuspensionWithoutAJournalIsNotInEffectButTerminationIs() {
        OomSwitches all = new OomSwitches(true, false, true, true);
        assertThat(EffectiveAction.of(
                        new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND),
                        all,
                        WINDOWS,
                        false,
                        Restorability.RESTORABLE,
                        OomSignalStatus.VALIDATED))
                .isEqualTo(new EffectiveAction(
                        EmergencyAction.NOTIFY, false, Optional.of("the suspension journal cannot be written")));
        assertThat(EffectiveAction.of(
                                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.TERMINATE),
                                all,
                                WINDOWS,
                                false,
                                Restorability.RESTORABLE,
                                OomSignalStatus.VALIDATED)
                        .action())
                .isEqualTo(EmergencyAction.TERMINATE);
    }

    @Test
    void anActionTheSystemCannotTakeIsNotAvailable() {
        Map<ControlAction, Capability> none =
                capabilities(Capability.UNAVAILABLE, Capability.UNAVAILABLE, Capability.UNAVAILABLE);
        OomSwitches all = new OomSwitches(true, true, true, true);
        assertThat(EffectiveAction.of(
                                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND),
                                all,
                                none,
                                true,
                                Restorability.RESTORABLE,
                                OomSignalStatus.VALIDATED)
                        .notInEffect())
                .contains("not available on this system: processes cannot be suspended and resumed");
        assertThat(EffectiveAction.of(
                                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.TERMINATE),
                                all,
                                none,
                                true,
                                Restorability.RESTORABLE,
                                OomSignalStatus.VALIDATED)
                        .notInEffect())
                .contains("not available on this system: processes cannot be terminated");
        assertThat(EffectiveAction.of(
                                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.LOWER_PRIORITY),
                                all,
                                none,
                                true,
                                Restorability.RESTORABLE,
                                OomSignalStatus.VALIDATED)
                        .notInEffect())
                .contains("not available on this system: priorities cannot be changed");
    }

    @Test
    void anActionNotInEffectIsAlwaysNotifyOnly() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EffectiveAction(EmergencyAction.SUSPEND, false, Optional.of("off")));
    }
}
