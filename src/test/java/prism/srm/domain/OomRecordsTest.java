package prism.srm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The value types of OOM Guard: defaults (OOM-3, OOM-5), validation, and the candidate order (OOM-10). */
class OomRecordsTest {

    private static final Instant START = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void theDefaultsAreThoseOfTheSpecificationAndInterveneInNothing() {
        assertThat(OomThresholds.DEFAULTS).isEqualTo(new OomThresholds(15, 8, 5, 268_435_456L));
        assertThat(OomSwitches.DEFAULTS).isEqualTo(new OomSwitches(true, false, false, false));
        assertThat(AppSettings.DEFAULTS.oom()).isEqualTo(OomSettings.DEFAULTS);
        assertThat(AppSettings.DEFAULTS.appearance()).isEqualTo(ThemeMode.SYSTEM);
        assertThat(OomManagement.OFF).isEqualTo(new OomManagement(false, OomPriority.NORMAL, EmergencyAction.NOTIFY));
    }

    @Test
    void thresholdsAreOrderedFromOneToNinety() {
        assertThat(OomThresholds.valid(90, 2, 1, 0)).isTrue();
        assertThat(OomThresholds.valid(91, 8, 5, 0)).isFalse();
        assertThat(OomThresholds.valid(15, 8, 0, 0)).isFalse();
        assertThat(OomThresholds.valid(15, 8, 8, 0)).isFalse();
        assertThat(OomThresholds.valid(8, 8, 5, 0)).isFalse();
        assertThat(OomThresholds.valid(15, 8, 5, -1)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new OomThresholds(5, 8, 15, 0));
        assertThat(OomThresholds.DEFAULTS.percent(OomState.CRITICAL)).isEqualTo(8);
        assertThatIllegalArgumentException().isThrownBy(() -> OomThresholds.DEFAULTS.percent(OomState.NORMAL));
    }

    @Test
    void statesAreOrderedAndGoDownOneLevel() {
        assertThat(OomState.EMERGENCY.above(OomState.CRITICAL)).isTrue();
        assertThat(OomState.WARNING.above(OomState.WARNING)).isFalse();
        assertThat(OomState.EMERGENCY.below()).isEqualTo(OomState.CRITICAL);
        assertThat(OomState.NORMAL.below()).isEqualTo(OomState.NORMAL);
        assertThat(EmergencyAction.TERMINATE.atLeast(EmergencyAction.LOWER_PRIORITY))
                .isTrue();
        assertThat(EmergencyAction.NOTIFY.atLeast(EmergencyAction.LOWER_PRIORITY))
                .isFalse();
    }

    @Test
    void aReadingIsAShareOfAPositiveTotal() {
        assertThat(new OomReading(25, 100, 0).availablePercent()).isEqualTo(25.0);
        assertThatIllegalArgumentException().isThrownBy(() -> new OomReading(0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new OomReading(101, 100, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionedSettings(0, OomSettings.DEFAULTS));
    }

    @Test
    void aViewWithoutMonitoringIsNormalAndCarriesAtMostFiftyActions() {
        OomSettings off = OomSettings.DEFAULTS.withSwitches(OomSwitches.DEFAULTS.withMonitoring(false));
        assertThat(OomView.starting(off).monitoring()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> view(off, OomState.WARNING, List.of()));
        List<OomActionRecord> many = new ArrayList<>(Collections.nCopies(51, record()));
        assertThatIllegalArgumentException().isThrownBy(() -> view(OomSettings.DEFAULTS, OomState.NORMAL, many));
        assertThat(view(OomSettings.DEFAULTS, OomState.NORMAL, many.subList(0, 50))
                        .actions())
                .hasSize(50);
    }

    @Test
    void aRecordSaysWhatCameOfTheAction() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OomActionRecord(
                        START,
                        OomState.EMERGENCY,
                        Claim.PRESSURE,
                        new ProcessIdentity(5, START),
                        "demo",
                        OomActionRecord.Kind.SUSPEND,
                        OperationResult.SUCCESS,
                        " "));
    }

    @Test
    void aHoldHoldsSomethingWhileAClaimOrAPhysicalSuspensionIsLeft() {
        assertThat(OomHold.NONE.holdsAnything()).isFalse();
        OomHold incomplete = new OomHold(
                Set.of(),
                OomHold.Physical.RELEASE_INCOMPLETE,
                false,
                Set.of(),
                Optional.empty(),
                false,
                Optional.empty());
        assertThat(incomplete.holdsAnything()).isTrue();
        OomHold lowered = new OomHold(
                Set.of(),
                OomHold.Physical.NONE,
                false,
                Set.of(Claim.PRESSURE),
                Optional.of(PriorityLevel.NORMAL),
                false,
                Optional.empty());
        assertThat(lowered.holdsAnything()).isTrue();
    }

    @Test
    void candidatesAreTakenByPriorityThenTheLargestResidentMemoryThenPidAndStart() {
        OomCandidateView last = candidate(1, OomPriority.LAST, OptionalLong.of(9_000));
        OomCandidateView firstSmall = candidate(2, OomPriority.FIRST, OptionalLong.of(10));
        OomCandidateView firstLarge = candidate(3, OomPriority.FIRST, OptionalLong.of(500));
        OomCandidateView normalUnknown = candidate(4, OomPriority.NORMAL, OptionalLong.empty());
        OomCandidateView normalKnown = candidate(9, OomPriority.NORMAL, OptionalLong.of(1));
        OomCandidateView normalTiedLow = candidate(5, OomPriority.NORMAL, OptionalLong.of(1));
        OomCandidateView normalTiedLater = new OomCandidateView(
                new ProcessIdentity(5, START.plusSeconds(1)),
                "p",
                new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND),
                new EffectiveAction(EmergencyAction.SUSPEND, false, Optional.empty()),
                OomHold.NONE,
                Optional.empty(),
                OptionalLong.of(1),
                OptionalLong.empty(),
                Optional.empty());

        List<OomCandidateView> sorted = new ArrayList<>(
                List.of(last, normalUnknown, normalTiedLater, firstSmall, normalKnown, normalTiedLow, firstLarge));
        sorted.sort(OomCandidateOrder.ORDER);

        assertThat(sorted)
                .containsExactly(
                        firstLarge, firstSmall, normalTiedLow, normalTiedLater, normalKnown, normalUnknown, last);
    }

    private static OomCandidateView candidate(long pid, OomPriority priority, OptionalLong resident) {
        return new OomCandidateView(
                new ProcessIdentity(pid, START),
                "p" + pid,
                new OomManagement(true, priority, EmergencyAction.SUSPEND),
                new EffectiveAction(EmergencyAction.SUSPEND, false, Optional.empty()),
                OomHold.NONE,
                Optional.empty(),
                resident,
                OptionalLong.empty(),
                Optional.empty());
    }

    private static OomActionRecord record() {
        return new OomActionRecord(
                START,
                OomState.EMERGENCY,
                Claim.PRESSURE,
                new ProcessIdentity(5, START),
                "demo",
                OomActionRecord.Kind.SUSPEND,
                OperationResult.SUCCESS,
                "Suspended.");
    }

    private static OomView view(OomSettings settings, OomState state, List<OomActionRecord> actions) {
        return new OomView(
                settings.switches().monitoring(),
                state,
                Optional.empty(),
                Optional.empty(),
                settings,
                Optional.empty(),
                List.of(),
                actions,
                Optional.empty(),
                false,
                Restorability.RESTORABLE,
                OomSignalStatus.VALIDATED,
                OomView.Availability.ALL);
    }
}
