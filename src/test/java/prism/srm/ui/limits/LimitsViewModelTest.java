package prism.srm.ui.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;
import static prism.srm.ui.LimitsFixtures.limited;
import static prism.srm.ui.LimitsFixtures.managed;
import static prism.srm.ui.LimitsFixtures.snapshot;
import static prism.srm.ui.LimitsFixtures.throttling;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.control.ResultTone;
import prism.srm.ui.format.ValueFormatter;

/**
 * The Limits page (UI-4) as text and actions: every status in words that claim no more than was
 * measured (LIM-4), how a limit is applied, why limits are off, and requests one at a time. Needs no
 * toolkit.
 */
class LimitsViewModelTest {

    private static final ProcessIdentity DEMO = identity(4_242);

    private final List<LimitRequest> sent = new ArrayList<>();
    private final LimitsViewModel model = new LimitsViewModel(new ValueFormatter(Locale.US), sent::add);

    private LimitRow only() {
        assertThat(model.rows()).hasSize(1);
        return model.rows().get(0);
    }

    private LimitRow statusOf(
            LimitStatus status, Optional<String> detail, OptionalDouble measured, OptionalDouble lowest) {
        model.show(snapshot(limited(DEMO, "demo", 30, true, status, detail, measured, lowest)));
        return only();
    }

    @Test
    void untilTheFirstSnapshotThePageReadsAndOffersNothing() {
        assertThat(model.whenEmptyProperty().get()).isEqualTo(LimitsViewModel.READING);
        for (LimitsViewModel.Action action : LimitsViewModel.Action.values()) {
            assertThat(model.disabledProperty(action).get()).isTrue();
        }
    }

    @Test
    void withNothingManagedThePageSaysHowToManageAProcess() {
        model.show(snapshot());

        assertThat(model.rows()).isEmpty();
        assertThat(model.whenEmptyProperty().get()).contains("choose Manage in the context menu");
        assertThat(model.countProperty().get()).isEmpty();
    }

    /** The panel of the page shows the current row: the selected one as the latest snapshot has it, or none. */
    @Test
    void theCurrentRowFollowsTheSelectionAndEachSnapshotAndGoesWithTheProcess() {
        assertThat(model.currentProperty().get()).isNull();
        model.show(snapshot(throttling(DEMO, "demo", 30, 29.6), managed(identity(3_000), "notepad.exe")));
        model.select(DEMO);
        assertThat(model.currentProperty().get().status()).isEqualTo("Throttling, 29.6 %");

        model.show(snapshot(throttling(DEMO, "demo", 30, 31.2), managed(identity(3_000), "notepad.exe")));
        assertThat(model.currentProperty().get().status()).isEqualTo("Throttling, 31.2 %");

        model.show(snapshot(managed(identity(3_000), "notepad.exe")));
        assertThat(model.currentProperty().get()).isNull();
    }

    @Test
    void aManagedProcessWithoutALimitHasDashesAndNoImplementation() {
        model.show(snapshot(managed(DEMO, "demo")));

        LimitRow row = only();
        assertThat(row.cpuLimit()).isEqualTo(LimitsViewModel.DASH);
        assertThat(row.memoryLimit()).isEqualTo(LimitsViewModel.DASH);
        assertThat(row.status()).isEqualTo("No CPU limit");
        assertThat(row.implementation()).isEqualTo(LimitsViewModel.DASH);
        assertThat(model.countProperty().get()).isEqualTo("1 managed");
    }

    @Test
    void aLimitHeldIsCalledThrottlingWithTheMeasuredUseAndIsEmulated() {
        model.show(snapshot(throttling(DEMO, "demo", 30, 29.84)));

        LimitRow row = only();
        assertThat(row.cpuLimit()).isEqualTo("30 %");
        assertThat(row.status()).isEqualTo("Throttling, 29.8 %");
        assertThat(row.tone()).isEqualTo(LimitRow.Tone.NORMAL);
        assertThat(row.implementation()).isEqualTo("Emulated");
    }

    @Test
    void aLimitBelowTheFloorIsNeverCalledHeldAndSaysTheLowestReached() {
        LimitRow row = statusOf(
                LimitStatus.ACTIVE_ABOVE_TARGET,
                Optional.of("floor: the limit is below the lowest share Prism can hold this process to"),
                OptionalDouble.of(6.1),
                OptionalDouble.of(6.1));

        assertThat(row.status()).isEqualTo("Above limit, lowest 6.1 %");
        assertThat(row.tone()).isEqualTo(LimitRow.Tone.WARNING);
        assertThat(row.detail()).contains("lowest share");
    }

    @Test
    void everyStatusHasWordsAndNoneOfThemClaimsTheLimitIsMetWithoutAMeasurement() {
        for (LimitStatus status : LimitStatus.values()) {
            LimitRow row = statusOf(status, Optional.of("detail"), OptionalDouble.empty(), OptionalDouble.empty());
            assertThat(row.status()).as("%s", status).isNotBlank();
            if (status != LimitStatus.ACTIVE_THROTTLING) {
                assertThat(row.status()).as("%s", status).doesNotContain("Throttling");
            }
        }
    }

    @Test
    void anUnmeasuredLimitIsAWarningAndAFailureAnError() {
        assertThat(statusOf(
                                LimitStatus.ADJUSTING,
                                Optional.of("not measured: the processor time could not be read"),
                                OptionalDouble.empty(),
                                OptionalDouble.empty())
                        .tone())
                .isEqualTo(LimitRow.Tone.WARNING);
        LimitRow failed = statusOf(
                LimitStatus.FAILED, Optional.of("journal: disk full"), OptionalDouble.empty(), OptionalDouble.empty());
        assertThat(failed.status()).isEqualTo("Failed");
        assertThat(failed.tone()).isEqualTo(LimitRow.Tone.ERROR);
        assertThat(failed.detail()).contains("journal: disk full");
    }

    @Test
    void limitsThatAreOffSayWhyAsAFailureAndAnUnavailableLimitAsAFact() {
        model.show(new LimitsSnapshot(
                List.of(), LimitsFixtures.EMULATED, Optional.of("Limits are off: Prism cannot record them.")));
        assertThat(model.noticeProperty().get()).startsWith("Limits are off");
        assertThat(model.limitsOffProperty().get()).isTrue();

        model.show(new LimitsSnapshot(
                List.of(), LimitsFixtures.unavailable("this kernel has no pidfd"), Optional.empty()));
        assertThat(model.noticeProperty().get()).contains("this kernel has no pidfd");
        assertThat(model.limitsOffProperty().get()).isFalse();
    }

    @Test
    void theActionsFollowTheSelectedProcess() {
        model.show(snapshot(managed(DEMO, "demo")));
        model.select(DEMO);

        assertThat(model.disabledProperty(LimitsViewModel.Action.EDIT).get()).isFalse();
        assertThat(model.disabledProperty(LimitsViewModel.Action.REMOVE).get()).isTrue();
        assertThat(model.explanationProperty(LimitsViewModel.Action.REMOVE).get())
                .isEqualTo("It has no CPU limit.");
        assertThat(model.toggleTextProperty().get()).isEqualTo("Disable");

        model.show(snapshot(limited(
                DEMO,
                "demo",
                30,
                false,
                LimitStatus.INACTIVE,
                Optional.of("disabled"),
                OptionalDouble.empty(),
                OptionalDouble.empty())));
        assertThat(model.toggleTextProperty().get()).isEqualTo("Enable");
        assertThat(only().status()).isEqualTo("Disabled");
        model.request(LimitsViewModel.Action.TOGGLE);
        assertThat(sent).extracting(LimitRequest::action).containsExactly(LimitAction.ENABLE);
    }

    @Test
    void whereNoCpuLimitIsAvailableEditSaysWhy() {
        model.show(new LimitsSnapshot(
                List.of(managed(DEMO, "demo")),
                LimitsFixtures.unavailable("this kernel has no pidfd"),
                Optional.empty()));
        model.select(DEMO);

        assertThat(model.disabledProperty(LimitsViewModel.Action.EDIT).get()).isTrue();
        assertThat(model.explanationProperty(LimitsViewModel.Action.EDIT).get()).contains("this kernel has no pidfd");
    }

    @Test
    void oneRequestPerProcessAtATimeAndOnlyItsOwnAnswerIsShown() {
        model.show(snapshot(throttling(DEMO, "demo", 30, 30)));
        model.select(DEMO);

        model.setCpuLimit(DEMO, 50);
        assertThat(model.line().wholeProperty().get()).isEqualTo("Setting the CPU limit of demo (PID 4242)…");
        assertThat(model.disabledProperty(LimitsViewModel.Action.UNMANAGE).get())
                .isTrue();
        model.request(LimitsViewModel.Action.UNMANAGE);
        assertThat(sent).hasSize(1);

        LimitRequest fromElsewhere = LimitRequest.of(LimitAction.MANAGE, identity(7), "other");
        model.accept(new LimitResult(fromElsewhere, ControlOutcome.success(), "other is managed.", ""));
        assertThat(model.line().wholeProperty().get()).startsWith("Setting");

        model.accept(new LimitResult(
                sent.get(0), ControlOutcome.of(OperationResult.NATIVE_ERROR, "journal"), "Did not start.", "Why."));
        assertThat(model.line().wholeProperty().get()).isEqualTo("Did not start. Why.");
        assertThat(model.line().toneProperty().get()).isEqualTo(ResultTone.FAILURE);
        assertThat(model.disabledProperty(LimitsViewModel.Action.UNMANAGE).get())
                .isFalse();
    }

    @Test
    void aProcessThatLeavesTheListTakesTheSelectionWithIt() {
        model.show(snapshot(managed(DEMO, "demo")));
        model.select(DEMO);

        model.show(snapshot());

        assertThat(model.selected()).isEmpty();
        assertThat(model.disabledProperty(LimitsViewModel.Action.UNMANAGE).get())
                .isTrue();
    }
}
