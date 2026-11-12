package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.process;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import prism.srm.application.FakeProcessController;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspendedProcess;
import prism.srm.ui.control.ResultTone;
import prism.srm.ui.format.ValueFormatter;

/**
 * After process monitoring stopped, a suspension Prism holds can still be released from the page,
 * and nothing else can be done. Through the view model as the page uses it; needs no toolkit.
 */
class HeldSuspensionsTest {

    private final List<ControlRequest> sent = new ArrayList<>();
    private final ProcessesViewModel model =
            new ProcessesViewModel(new ValueFormatter(Locale.US), ZoneOffset.UTC, "logs", identity -> {}, sent::add);
    private final HeldSuspensions held = model.held();
    private final ProcessIdentity demo = ProcessListings.identity(10);
    private final ProcessIdentity other = ProcessListings.identity(20);

    private void monitoringStopsWhilePrismHoldsTheDemo() {
        model.showControlProfile(
                new prism.srm.domain.ControlProfile(FakeProcessController.windowsLike(), java.util.Map.of()));
        model.update(List.of(process(10, "demo").build(), process(20, "other").build()));
        model.showHeld(List.of(new SuspendedProcess(demo, "demo")));
        model.monitoringStopped();
    }

    private static ProcessControlResult result(ControlRequest request, OperationResult result, String message) {
        return new ProcessControlResult(request, ControlOutcome.of(result, "detail"), message);
    }

    @Test
    void whileMonitoringRunsNothingIsOfferedHere() {
        model.showHeld(List.of(new SuspendedProcess(demo, "demo")));

        assertThat(held.shownProperty().get()).isFalse();
        assertThat(held.rows()).isEmpty();
        held.resume(demo);
        assertThat(sent).isEmpty();
    }

    @Test
    void afterMonitoringStoppedAHeldProcessHasResumeAndEveryOtherActionIsDisabled() {
        monitoringStopsWhilePrismHoldsTheDemo();

        assertThat(held.shownProperty().get()).isTrue();
        assertThat(held.rows()).containsExactly(new SuspendedProcess(demo, "demo"));
        for (ControlAction action : ControlAction.values()) {
            assertThat(model.actions().disabledProperty(action).get())
                    .as(action.name())
                    .isTrue();
        }

        held.resume(demo);

        assertThat(sent).containsExactly(ControlRequest.of(ControlAction.RESUME, demo, "demo"));
        assertThat(held.resuming(demo)).isTrue();
        assertThat(held.messageProperty().get()).isEqualTo("Resuming demo (PID 10)…");
        assertThat(held.rows())
                .as("the row stays while its Resume is on its way")
                .containsExactly(new SuspendedProcess(demo, "demo"));
    }

    @Test
    void oneResumeAtATimeAndTheResultIsShownWhenItComes() {
        monitoringStopsWhilePrismHoldsTheDemo();
        held.resume(demo);
        held.resume(demo);
        assertThat(sent).hasSize(1);

        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was resumed."));
        model.showHeld(List.of());

        assertThat(held.messageProperty().get()).isEqualTo("demo (PID 10) was resumed.");
        assertThat(held.lineToneProperty().get()).isEqualTo(ResultTone.SUCCESS);
        assertThat(held.rows()).isEmpty();
        assertThat(held.resuming(demo)).isFalse();
        assertThat(held.shownProperty().get())
                .as("the result stays on the page")
                .isTrue();
    }

    @Test
    void aProcessThatHadExitedIsAWarningAndAFailureOfTheSystemAnError() {
        monitoringStopsWhilePrismHoldsTheDemo();
        held.resume(demo);
        model.showControlResult(result(
                sent.get(0),
                OperationResult.PROCESS_NOT_FOUND,
                "demo (PID 10) has exited; Prism no longer holds it suspended."));
        assertThat(held.lineToneProperty().get()).isEqualTo(ResultTone.WARNING);

        held.resume(demo);
        model.showControlResult(result(sent.get(1), OperationResult.NATIVE_ERROR, "Resuming demo (PID 10) failed."));
        assertThat(held.lineToneProperty().get()).isEqualTo(ResultTone.FAILURE);
    }

    @Test
    void aProcessPrismDoesNotHoldGetsNoResume() {
        monitoringStopsWhilePrismHoldsTheDemo();

        held.resume(other);

        assertThat(sent).isEmpty();
        assertThat(held.rows()).extracting(SuspendedProcess::identity).containsExactly(demo);
    }

    @Test
    void withNothingHeldTheStoppedPageOffersNothing() {
        model.monitoringStopped();
        model.showHeld(List.of());

        assertThat(held.shownProperty().get()).isFalse();
    }

    @Test
    void aResultOfAnotherActionOrOfAResumeNotAskedForHereIsNotShownHere() {
        monitoringStopsWhilePrismHoldsTheDemo();

        model.showControlResult(result(
                ControlRequest.of(ControlAction.RESUME, demo, "demo"), OperationResult.SUCCESS, "demo was resumed."));

        assertThat(held.messageProperty().get()).isEmpty();
    }

    /** The order the runtime hands them over in: the held list first, then the result. */
    @Test
    void theHeldListThatEmptiesBeforeTheResultComesKeepsThePageUntilTheResultIsShown() {
        monitoringStopsWhilePrismHoldsTheDemo();
        held.resume(demo);

        model.showHeld(List.of());
        assertThat(held.shownProperty().get()).isTrue();
        assertThat(held.messageProperty().get()).isEqualTo("Resuming demo (PID 10)…");

        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was resumed."));
        assertThat(held.messageProperty().get()).isEqualTo("demo (PID 10) was resumed.");
        assertThat(held.shownProperty().get()).isTrue();
        assertThat(held.rows()).isEmpty();
    }

    @Test
    void theRecoveredResultHasTheTwoPartsAndArrivesOnce() {
        monitoringStopsWhilePrismHoldsTheDemo();
        held.resume(demo);
        int before = held.newResultProperty().get();

        model.showControlResult(new ProcessControlResult(
                sent.get(0),
                ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "exited"),
                "demo (PID 10) has exited.",
                "Prism no longer holds it suspended."));

        assertThat(held.lineHeadlineProperty().get()).isEqualTo("demo (PID 10) has exited.");
        assertThat(held.lineExplanationProperty().get()).isEqualTo("Prism no longer holds it suspended.");
        assertThat(held.lineToneProperty().get()).isEqualTo(ResultTone.WARNING);
        assertThat(held.newResultProperty().get()).isEqualTo(before + 1);
    }

    /** The answer to a Resume asked for here belongs here: the inspector, its table emptied, keeps saying why. */
    @Test
    void theRecoveredResultIsNotShownBesideTheActionsOfTheInspector() {
        model.showControlProfile(prism.srm.application.FakeProcessController.windowsProfile());
        model.update(List.of(process(10, "demo").build()));
        model.select(model.rows().get(0));
        model.showHeld(List.of(new SuspendedProcess(demo, "demo")));
        model.monitoringStopped();
        int before = model.actions().newResultProperty().get();

        held.resume(demo);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was resumed."));

        assertThat(model.actions().messageProperty().get()).isEqualTo(ProcessActions.NOT_MONITORING);
        assertThat(model.actions().newResultProperty().get()).isEqualTo(before);
        assertThat(held.messageProperty().get()).isEqualTo("demo (PID 10) was resumed.");
    }
}
