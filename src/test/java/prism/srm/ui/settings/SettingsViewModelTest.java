package prism.srm.ui.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OomSignalStatus;
import prism.srm.ui.settings.SettingsViewModel.Row;

/** The capability summary of UI-7: only what the application reports, under honest words (CAP-3, CAP-5). */
class SettingsViewModelTest {

    private static final ControlProfile SOME_ACTIONS = new ControlProfile(
            Map.of(
                    ControlAction.END_TASK,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST),
                    ControlAction.SUSPEND,
                    Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION),
                    ControlAction.FORCE_KILL,
                    Capability.UNAVAILABLE),
            Map.of());

    private static LimitsSnapshot limits(LimitCapability cpu, LimitCapability memory) {
        return new LimitsSnapshot(List.of(), cpu, memory, Optional.empty(), List.of());
    }

    private final SettingsViewModel model = new SettingsViewModel();

    private Row row(String function) {
        return model.rows().stream()
                .filter(row -> row.function().equals(function))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void beforeAnythingIsReportedThePageSaysItIsReadingAndClaimsNothing() {
        assertThat(model.status()).contains("Reading what this system offers.");
        assertThat(model.rows()).isEmpty();
    }

    /**
     * LS-07: the sentence goes once a source has reported; until all three have, the rows still being
     * read stand in their place and say so, claiming nothing.
     */
    @Test
    void theRowsStillBeingReadSaySoInTheirPlaceUntilAllThreeSourcesHaveReported() {
        assertThat(model.summary()).hasSize(8).allSatisfy(row -> {
            assertThat(row.known()).isFalse();
            assertThat(row.available()).isFalse();
            assertThat(row.level()).isEqualTo(SettingsViewModel.READING);
            assertThat(row.detail()).isEmpty();
        });
        model.showProfile(SOME_ACTIONS);
        assertThat(model.status()).isEmpty();
        assertThat(model.summary())
                .extracting(Row::known)
                .containsExactly(true, true, true, true, true, false, false, false);
        model.showLimits(limits(
                LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT),
                LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT)));
        assertThat(model.summary())
                .extracting(Row::known)
                .containsExactly(true, true, true, true, true, true, true, false);
        model.showSignal(OomSignalStatus.VALIDATED);

        assertThat(model.status()).isEmpty();
        assertThat(model.summary()).isEqualTo(model.rows());
        assertThat(SettingsViewModel.functions())
                .isEqualTo(model.rows().stream().map(Row::function).toList());
        assertThat(model.rows())
                .extracting(Row::function)
                .containsExactly(
                        "End task",
                        "Force kill",
                        "Suspend",
                        "Resume",
                        "Change priority",
                        "CPU limit",
                        "Memory limit",
                        "OOM Guard readings");
    }

    @Test
    void aFailedStartIsSaidAndNothingIsClaimed() {
        model.showStartupFailure("C:\\logs");

        assertThat(model.status().orElseThrow())
                .contains("monitoring did not start")
                .contains("C:\\logs");
        assertThat(model.rows()).isEmpty();
        assertThat(model.summary()).as("nothing is said to be still coming").isEmpty();
    }

    @Test
    void anActionNamesItsLevelAndMechanism() {
        model.showProfile(SOME_ACTIONS);

        assertThat(row("End task"))
                .isEqualTo(new Row(
                        "End task", "Native soft", "A close request to its windows; the process may ignore it", true));
        assertThat(row("Suspend").detail()).contains("not atomic");
    }

    /** An action missing from the table is not assumed to work. */
    @Test
    void anUnavailableOrUnreportedActionIsUnavailable() {
        model.showProfile(SOME_ACTIONS);

        assertThat(row("Force kill").level()).isEqualTo("Unavailable");
        assertThat(row("Force kill").available()).isFalse();
        assertThat(row("Resume").level()).isEqualTo("Unavailable");
    }

    @Test
    void anEmulatedLimitIsNeverCalledNativeAndAWatchedMemoryLimitIsNotCalledACap() {
        model.showLimits(limits(
                LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.DUTY_CYCLE),
                LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.RESIDENT_POLICY)));

        assertThat(row("CPU limit").level()).isEqualTo("Emulated");
        assertThat(row("CPU limit").detail()).contains("approximate");
        assertThat(row("Memory limit").detail()).contains("not a cap");
    }

    @Test
    void anUnavailableLimitGivesItsReasonAsASentence() {
        model.showLimits(limits(
                LimitCapability.unavailable("no native limit is available on this system in this version"),
                LimitCapability.unavailable("Ends with a stop.")));

        assertThat(row("CPU limit"))
                .isEqualTo(new Row(
                        "CPU limit",
                        "Unavailable",
                        "No native limit is available on this system in this version.",
                        false));
        assertThat(row("Memory limit").detail()).isEqualTo("Ends with a stop.");
    }

    @Test
    void unvalidatedReadingsSayThatOomGuardTakesNoAutomaticAction() {
        assertThat(model.showSignal(OomSignalStatus.UNVALIDATED)).isTrue();

        assertThat(row("OOM Guard readings").level()).isEqualTo("Not validated");
        assertThat(row("OOM Guard readings").detail()).contains("no automatic action");
        assertThat(row("OOM Guard readings").available()).isFalse();
    }

    @Test
    void theSameSignalAgainChangesNothing() {
        assertThat(model.showSignal(OomSignalStatus.VALIDATED)).isTrue();
        assertThat(model.showSignal(OomSignalStatus.VALIDATED)).isFalse();
    }
}
