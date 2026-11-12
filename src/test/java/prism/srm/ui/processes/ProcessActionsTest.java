package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.process;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;
import prism.srm.ui.control.ResultTone;
import prism.srm.ui.format.ValueFormatter;

/**
 * What the actions on the selected process may do, through the view model of the page as the
 * page uses it: capabilities, selection, one action per process, results. Needs no JavaFX toolkit.
 */
class ProcessActionsTest {

    private final List<ControlRequest> sent = new ArrayList<>();
    private final ProcessesViewModel model =
            new ProcessesViewModel(new ValueFormatter(Locale.US), ZoneOffset.UTC, "logs", identity -> {}, sent::add);
    private final ProcessActions actions = model.actions();

    private static Map<ControlAction, Capability> windows() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        map.put(ControlAction.END_TASK, Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST));
        map.put(
                ControlAction.FORCE_KILL,
                Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.PROCESS_HANDLE_TERMINATE));
        map.put(
                ControlAction.SUSPEND,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION));
        map.put(
                ControlAction.RESUME,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION));
        map.put(
                ControlAction.CHANGE_PRIORITY,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PRIORITY_CLASS));
        return map;
    }

    private static Map<ControlAction, Capability> none() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        for (ControlAction action : ControlAction.values()) {
            map.put(action, Capability.UNAVAILABLE);
        }
        return map;
    }

    private void list(ProcessSnapshot... processes) {
        model.update(List.of(processes));
    }

    private ProcessRow row(long pid) {
        return model.rows().stream()
                .filter(r -> r.identity().pid() == pid)
                .findFirst()
                .orElseThrow();
    }

    private boolean disabled(ControlAction action) {
        return actions.disabledProperty(action).get();
    }

    private List<ControlAction> enabled() {
        List<ControlAction> enabled = new ArrayList<>();
        for (ControlAction action : ControlAction.values()) {
            if (!disabled(action)) {
                enabled.add(action);
            }
        }
        return enabled;
    }

    private void act(ControlAction action) {
        actions.request(actions.requestFor(action, Optional.empty()).orElseThrow());
    }

    private static ProcessControlResult result(ControlRequest request, OperationResult result, String message) {
        return new ProcessControlResult(request, ControlOutcome.of(result, "detail"), message);
    }

    @Test
    void untilTheCapabilitiesAreKnownEveryActionIsDisabledAndSaysWhy() {
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(enabled()).isEmpty();
        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.WAITING);
        assertThat(actions.explanationProperty(ControlAction.FORCE_KILL).get()).isEqualTo(ProcessActions.WAITING);
    }

    @Test
    void withNothingSelectedEveryActionIsDisabledQuietly() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));

        assertThat(enabled()).isEmpty();
        assertThat(actions.messageProperty().get()).isEmpty();
        assertThat(actions.explanationProperty(ControlAction.SUSPEND).get()).isEqualTo(ProcessActions.SELECT_FIRST);
    }

    @Test
    void aSelectedProcessCanBeActedOnAndResumeWaitsForASuspensionOfPrisms() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(enabled())
                .containsExactly(
                        ControlAction.END_TASK,
                        ControlAction.FORCE_KILL,
                        ControlAction.SUSPEND,
                        ControlAction.CHANGE_PRIORITY);
        assertThat(actions.explanationProperty(ControlAction.RESUME).get())
                .isEqualTo("Prism holds no suspension of this process.");
        assertThat(actions.messageProperty().get()).isEmpty();
    }

    /** CAP-5: an enabled action says what its mechanism does, without naming the system. */
    @Test
    void anEnabledActionExplainsItsMechanism() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(actions.explanationProperty(ControlAction.END_TASK).get()).contains("may", "ignore the request");
        assertThat(actions.explanationProperty(ControlAction.SUSPEND).get()).contains("one by one", "Not atomic");
        assertThat(actions.explanationProperty(ControlAction.FORCE_KILL).get()).contains("at once");
        for (ControlAction action : ControlAction.values()) {
            assertThat(actions.explanationProperty(action).get()).doesNotContain("Windows", "Linux", "macOS");
        }
    }

    /** CAP-2: what the system does not provide is disabled, not hidden, and the page says why. */
    @Test
    void unavailableActionsAreDisabledAndThePageSaysWhy() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(none(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(enabled()).isEmpty();
        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.NONE_AVAILABLE);
    }

    @Test
    void oneUnavailableActionSaysWhyAndTheOthersStayEnabled() {
        Map<ControlAction, Capability> partial = windows();
        partial.put(ControlAction.END_TASK, Capability.UNAVAILABLE);
        model.showControlProfile(new prism.srm.domain.ControlProfile(partial, java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(disabled(ControlAction.END_TASK)).isTrue();
        assertThat(actions.explanationProperty(ControlAction.END_TASK).get())
                .isEqualTo("End task is not available on this system.");
        assertThat(disabled(ControlAction.FORCE_KILL)).isFalse();
        // CAP-2: on the page, not only in the tooltip.
        assertThat(actions.messageProperty().get()).isEqualTo("End task is not available on this system.");
    }

    /** Linux without pidfd signalling: the page names every action that is not there, each disabled, Priority not. */
    @Test
    void severalUnavailableActionsAreNamedTogether() {
        Map<ControlAction, Capability> partial = new EnumMap<>(prism.srm.application.FakeProcessController.unixLike());
        for (ControlAction action : List.of(
                ControlAction.END_TASK, ControlAction.FORCE_KILL, ControlAction.SUSPEND, ControlAction.RESUME)) {
            partial.put(action, Capability.UNAVAILABLE);
        }
        model.showControlProfile(new prism.srm.domain.ControlProfile(partial, java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(actions.messageProperty().get())
                .isEqualTo("Not available on this system: End task, Force kill, Suspend, Resume.");
        assertThat(actions.explanationProperty(ControlAction.SUSPEND).get())
                .isEqualTo("Suspend is not available on this system.");
        assertThat(disabled(ControlAction.CHANGE_PRIORITY)).isFalse();
    }

    /** One action per process: every action on it waits for the result; other processes do not. */
    @Test
    void whileAnActionIsInFlightEveryActionOnThatProcessIsDisabledAndOnOthersNot() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));

        act(ControlAction.SUSPEND);

        assertThat(sent).extracting(ControlRequest::action).containsExactly(ControlAction.SUSPEND);
        assertThat(enabled()).isEmpty();
        assertThat(actions.messageProperty().get())
                .as("which action is on its way, not only that one is")
                .isEqualTo("Suspending demo (PID 10)…");
        assertThat(actions.explanationProperty(ControlAction.RESUME).get()).isEqualTo(ProcessActions.IN_PROGRESS);
        model.select(row(20));
        assertThat(enabled()).contains(ControlAction.FORCE_KILL);
        model.select(row(10));
        assertThat(enabled()).isEmpty();

        actions.request(ControlRequest.of(ControlAction.FORCE_KILL, row(10).identity(), "demo"));
        assertThat(sent).as("a disabled action is not sent").hasSize(1);

        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was suspended."));
        assertThat(enabled()).contains(ControlAction.RESUME).doesNotContain(ControlAction.SUSPEND);
    }

    @Test
    void theResultOfTheSelectedProcessIsShownAndARefusalIsAWarning() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        act(ControlAction.END_TASK);

        model.showControlResult(result(sent.get(0), OperationResult.UNSUPPORTED, "demo (PID 10) has no window."));

        assertThat(actions.messageProperty().get()).isEqualTo("demo (PID 10) has no window.");
        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.WARNING);
    }

    /** The same marks as the activity log: only a failure of the system is an error. */
    @Test
    void aFailureOfTheSystemIsMarkedAsOneAndASuccessAsNeither() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.NATIVE_ERROR, "The system did not suspend demo."));

        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.FAILURE);

        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(1), OperationResult.SUCCESS, "demo was suspended."));
        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.SUCCESS);
    }

    /** A result of a process the filter hides goes with the selection, as the details pane goes. */
    @Test
    void theResultGoesWhenTheFilterHidesItsProcess() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo was suspended."));

        model.filter("other");

        assertThat(model.selected()).isNull();
        assertThat(actions.messageProperty().get()).isEmpty();
    }

    @Test
    void aResultForAnotherProcessIsNotShownHere() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        act(ControlAction.FORCE_KILL);
        model.select(row(20));

        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "Force termination requested."));

        assertThat(actions.messageProperty().get()).isEmpty();
    }

    @Test
    void theResultStaysWhenItsProcessExitsAndGoesWhenAnotherIsSelected() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        act(ControlAction.FORCE_KILL);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "Force termination requested for demo."));

        list(process(20, "other").build());

        assertThat(model.selected()).isNull();
        assertThat(actions.messageProperty().get()).isEqualTo("Force termination requested for demo.");
        model.select(row(20));
        assertThat(actions.messageProperty().get()).isEmpty();
    }

    /** PROC-6: a listing that shows the process suspended by Prism decides; a result decides until then. */
    @Test
    void suspendAndResumeFollowTheResultAndThenTheListing() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "suspended"));
        assertThat(disabled(ControlAction.RESUME)).isFalse();

        list(process(10, "demo").state(ProcessState.SUSPENDED_BY_PRISM).build());
        assertThat(disabled(ControlAction.RESUME)).isFalse();
        assertThat(disabled(ControlAction.SUSPEND)).isTrue();
        assertThat(actions.explanationProperty(ControlAction.SUSPEND).get())
                .isEqualTo("Already suspended by Prism; use Resume.");

        list(process(10, "demo").build());
        assertThat(disabled(ControlAction.RESUME)).isTrue();
    }

    @Test
    void whenMonitoringEndsNoActionIsPossible() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        model.monitoringStopped();

        assertThat(enabled()).isEmpty();
    }

    @Test
    void aChangeOfPriorityNamesTheLevel() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));

        actions.request(actions.requestFor(ControlAction.CHANGE_PRIORITY, Optional.of(PriorityLevel.HIGH))
                .orElseThrow());

        assertThat(sent.get(0)).isEqualTo(ControlRequest.priority(row(10).identity(), "demo", PriorityLevel.HIGH));
    }

    @Test
    void aRequestForAProcessThatIsNoLongerSelectedIsNotSent() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        ControlRequest confirmedLate =
                actions.requestFor(ControlAction.FORCE_KILL, Optional.empty()).orElseThrow();
        model.select(row(20));

        actions.request(confirmedLate);

        assertThat(sent).isEmpty();
    }

    // --- M7.5 ---

    private void profile() {
        model.showControlProfile(prism.srm.application.FakeProcessController.windowsProfile());
    }

    @Test
    void theLevelOfTheSelectedProcessIsTheOneTheListingShows() {
        profile();
        list(
                process(10, "demo").priority(13).build(),
                process(20, "other").priority(4).build());

        model.select(row(10));
        assertThat(actions.observedLevelProperty().get()).isEqualTo(PriorityLevel.HIGH);
        assertThat(actions.currentPriorityProperty().get()).isEqualTo("High");
        model.select(row(20));
        assertThat(actions.observedLevelProperty().get()).isEqualTo(PriorityLevel.LOW);
        model.select(null);
        assertThat(actions.observedLevelProperty().get()).isNull();
    }

    /** No optimistic mark: the level asked for is the process's only once a listing shows it. */
    @Test
    void aChangeOfPriorityIsShownOnlyOnceTheListingShowsIt() {
        profile();
        list(process(10, "demo").priority(8).build());
        model.select(row(10));

        actions.request(actions.requestFor(ControlAction.CHANGE_PRIORITY, Optional.of(PriorityLevel.HIGH))
                .orElseThrow());
        assertThat(actions.observedLevelProperty().get()).isEqualTo(PriorityLevel.NORMAL);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "Priority set to High."));
        assertThat(actions.observedLevelProperty().get()).isEqualTo(PriorityLevel.NORMAL);

        list(process(10, "demo").priority(13).build());
        assertThat(actions.observedLevelProperty().get()).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void aNumberThatStandsForNoLevelIsShownAsWhatItIs() {
        profile();
        list(
                process(10, "demo").priority(9).build(),
                process(20, "bare").noPriority().build());

        model.select(row(10));
        assertThat(actions.observedLevelProperty().get()).isNull();
        assertThat(actions.currentPriorityProperty().get()).isEqualTo("unknown (9)");
        assertThat(actions.unmappedPriorityProperty().get()).isEqualTo("Current: 9, not one of the five levels");
        model.select(row(20));
        assertThat(actions.currentPriorityProperty().get()).isEqualTo("unknown");
        assertThat(actions.unmappedPriorityProperty().get()).isEmpty();
    }

    /** Without the table of levels (a system Prism cannot change priorities on) no number is called unknown. */
    @Test
    void withoutLevelsNoNumberIsShownAsOneThatStandsForNone() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").priority(9).build());
        model.select(row(10));

        assertThat(actions.observedLevelProperty().get()).isNull();
        assertThat(actions.unmappedPriorityProperty().get()).isEmpty();
    }

    @Test
    void askingForTheLevelTheProcessHasSendsNothing() {
        profile();
        list(process(10, "demo").priority(8).build());
        model.select(row(10));

        actions.request(actions.requestFor(ControlAction.CHANGE_PRIORITY, Optional.of(PriorityLevel.NORMAL))
                .orElseThrow());

        assertThat(sent).isEmpty();
        assertThat(enabled()).contains(ControlAction.CHANGE_PRIORITY);
    }

    /** The line: what is on its way, then the result in two parts and its tone; the reason is never a result. */
    @Test
    void theLineShowsTheActionOnItsWayThenTheResultInTwoParts() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        int before = actions.newResultProperty().get();
        act(ControlAction.END_TASK);
        assertThat(actions.lineHeadlineProperty().get()).isEqualTo("Asking demo (PID 10) to close…");
        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.NONE);
        assertThat(actions.newResultProperty().get())
                .as("a request is no result")
                .isEqualTo(before);

        model.showControlResult(new ProcessControlResult(
                sent.get(0),
                ControlOutcome.success(),
                "Close request sent to demo (PID 10).",
                "The application may ignore it."));

        assertThat(actions.lineHeadlineProperty().get()).isEqualTo("Close request sent to demo (PID 10).");
        assertThat(actions.lineExplanationProperty().get()).isEqualTo("The application may ignore it.");
        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.SUCCESS);
        assertThat(actions.messageProperty().get())
                .isEqualTo("Close request sent to demo (PID 10). The application may ignore it.");
        assertThat(actions.newResultProperty().get()).isEqualTo(before + 1);
    }

    /** A result that comes after the selection moved belongs to the Logs page, not to this line. */
    @Test
    void aResultThatComesAfterTheSelectionMovedIsNotShownUnderTheOtherProcess() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.select(row(20));
        int before = actions.newResultProperty().get();

        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was suspended."));

        assertThat(actions.messageProperty().get()).isEmpty();
        assertThat(actions.newResultProperty().get())
                .as("nothing arrives on this line")
                .isEqualTo(before);
        model.select(row(10));
        assertThat(actions.messageProperty().get())
                .as("not kept for later either")
                .isEmpty();
    }

    @Test
    void theResultStaysThroughListingsOfTheSameProcess() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was suspended."));

        list(process(10, "demo").cpu(0).build());
        list(process(10, "demo").cpu(0).build());

        assertThat(actions.messageProperty().get()).isEqualTo("demo (PID 10) was suspended.");
    }

    /** Once monitoring stopped the line says why, also with nothing selected; the last result goes. */
    @Test
    void whenMonitoringStopsTheLineSaysWhyAndTheLastResultGoes() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was suspended."));

        model.monitoringStopped();

        assertThat(model.selected()).isNull();
        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.NOT_MONITORING);
        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.NONE);
    }

    /** PROC-6 in the inspector: from the listing, as the State column, so the two never differ. */
    @Test
    void theInspectorSaysSuspendedByPrismWhenTheListingDoes() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        assertThat(model.details().suspendedByPrismProperty().get()).isEmpty();

        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "demo (PID 10) was suspended."));
        assertThat(model.details().suspendedByPrismProperty().get())
                .as("not before the listing shows it")
                .isEmpty();

        list(process(10, "demo").state(ProcessState.SUSPENDED_BY_PRISM).build());
        assertThat(model.details().suspendedByPrismProperty().get()).isEqualTo("Suspended by Prism");
        model.select(null);
        assertThat(model.details().suspendedByPrismProperty().get()).isEmpty();
    }

    /** The listing that drops the process comes while its action is on its way: the answer is still shown. */
    @Test
    void theAnswerToAnActionOnAProcessThatExitedMeanwhileIsShown() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        act(ControlAction.FORCE_KILL);

        list(process(20, "other").build());
        assertThat(model.selected()).isNull();
        assertThat(actions.messageProperty().get())
                .as("still on its way")
                .isEqualTo("Requesting termination of demo (PID 10)…");
        int before = actions.newResultProperty().get();
        model.showControlResult(new ProcessControlResult(
                sent.get(0),
                ControlOutcome.success(),
                "Force termination requested for demo (PID 10).",
                "The table shows when it has ended."));

        assertThat(actions.messageProperty().get())
                .isEqualTo("Force termination requested for demo (PID 10). The table shows when it has ended.");
        assertThat(actions.newResultProperty().get()).isEqualTo(before + 1);
        model.select(row(20));
        assertThat(actions.messageProperty().get()).isEmpty();
    }

    /**
     * The process exited while its action was on its way, then monitoring stopped: the line says why,
     * not what is on its way, and the answer that comes after is not shown there.
     */
    @Test
    void anActionOnAProcessThatExitedIsForgottenWhenMonitoringStops() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build(), process(20, "other").build());
        model.select(row(10));
        act(ControlAction.FORCE_KILL);
        list(process(20, "other").build());

        model.monitoringStopped();
        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.NOT_MONITORING);
        int before = actions.newResultProperty().get();
        model.showControlResult(result(sent.get(0), OperationResult.SUCCESS, "Force termination requested for demo."));

        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.NOT_MONITORING);
        assertThat(actions.newResultProperty().get()).isEqualTo(before);
    }

    /** A new action takes the place of the last result at once; what is on its way does not fade in. */
    @Test
    void aNewActionReplacesTheResultWithWhatIsOnItsWay() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "demo").build());
        model.select(row(10));
        act(ControlAction.SUSPEND);
        model.showControlResult(result(sent.get(0), OperationResult.NATIVE_ERROR, "The system did not suspend demo."));
        int before = actions.newResultProperty().get();

        act(ControlAction.SUSPEND);

        assertThat(actions.lineHeadlineProperty().get()).isEqualTo("Suspending demo (PID 10)…");
        assertThat(actions.lineExplanationProperty().get()).isEmpty();
        assertThat(actions.lineToneProperty().get()).isEqualTo(ResultTone.NONE);
        assertThat(actions.newResultProperty().get()).isEqualTo(before);
    }

    /** CAP-5: signals and the nice value explain themselves, still without naming the system. */
    @Test
    void theSignalMechanismsExplainThemselves() {
        model.showControlProfile(prism.srm.application.FakeProcessController.linuxProfile());
        list(process(10, "demo").build());
        model.select(row(10));

        assertThat(actions.explanationProperty(ControlAction.END_TASK).get())
                .isEqualTo("Asks the process to terminate. It may save its work first, or ignore the request.");
        assertThat(actions.explanationProperty(ControlAction.SUSPEND).get())
                .isEqualTo("Stops the whole process at once. Another program can let it run again.");
        assertThat(actions.explanationProperty(ControlAction.FORCE_KILL).get())
                .isEqualTo("Terminates the process at once. Work it has not saved is lost.");
        assertThat(actions.explanationProperty(ControlAction.CHANGE_PRIORITY).get())
                .contains("can only be lowered, not raised");
        for (ControlAction action : ControlAction.values()) {
            assertThat(actions.explanationProperty(action).get()).doesNotContain("Windows", "Linux", "macOS", "thread");
        }
    }

    /** Resume, after a stop: one continue ends every stop, the tooltip does not promise otherwise. */
    @Test
    void resumeByASignalSaysItAlsoEndsAStopAnotherProgramMade() {
        model.showControlProfile(prism.srm.application.FakeProcessController.linuxProfile());
        list(process(10, "demo")
                .state(prism.srm.domain.ProcessState.SUSPENDED_BY_PRISM)
                .build());
        model.select(row(10));

        assertThat(actions.explanationProperty(ControlAction.RESUME).get())
                .isEqualTo("Lets the process run again. If another program also stopped it, that stop ends too.");
    }

    @Test
    void anEndTaskOnItsWayIsATerminationWhereItIsASignal() {
        model.showControlProfile(prism.srm.application.FakeProcessController.linuxProfile());
        list(process(10, "demo").build());
        model.select(row(10));

        act(ControlAction.END_TASK);

        assertThat(actions.lineHeadlineProperty().get()).isEqualTo("Asking demo (PID 10) to terminate…");
    }

    /** M7.5 on Linux: the listing's number 20 is Normal, 30 Below normal. */
    @Test
    void onLinuxTheLevelIsTakenFromTheNiceValuePlusTwenty() {
        model.showControlProfile(prism.srm.application.FakeProcessController.linuxProfile());
        list(process(10, "demo").priority(30).build());
        model.select(row(10));

        assertThat(actions.observedLevelProperty().get()).isEqualTo(prism.srm.domain.PriorityLevel.BELOW_NORMAL);
    }

    /** CAP-2 and SAFE-2: on Prism's own row every action is disabled, with the reason, and nothing is sent. */
    @Test
    void prismsOwnProcessOffersNoActionAndSaysWhy() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        model.showOwnProcess(10);
        list(process(10, "PRISM").build(), process(20, "demo").build());
        model.select(row(10));

        assertThat(enabled()).isEmpty();
        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.OWN_PROCESS);
        for (ControlAction action : ControlAction.values()) {
            assertThat(actions.explanationProperty(action).get()).isEqualTo(ProcessActions.OWN_PROCESS);
            assertThat(actions.requestFor(action, Optional.of(PriorityLevel.LOW)))
                    .isEmpty();
        }
        actions.request(ControlRequest.of(ControlAction.FORCE_KILL, row(10).identity(), "PRISM"));
        assertThat(sent).isEmpty();
    }

    /** Only Prism's own PID: the next process is acted on as before. */
    @Test
    void anotherProcessKeepsItsActionsWhenPrismKnowsItsOwn() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        model.showOwnProcess(10);
        list(process(10, "PRISM").build(), process(20, "demo").build());
        model.select(row(20));

        assertThat(enabled())
                .containsExactly(
                        ControlAction.END_TASK,
                        ControlAction.FORCE_KILL,
                        ControlAction.SUSPEND,
                        ControlAction.CHANGE_PRIORITY);
        act(ControlAction.SUSPEND);
        assertThat(sent).extracting(ControlRequest::action).containsExactly(ControlAction.SUSPEND);
    }

    /** Learned while the row is selected: the actions are disabled at once, not at the next selection. */
    @Test
    void theOwnProcessIsDisabledAsSoonAsItIsKnown() {
        model.showControlProfile(new prism.srm.domain.ControlProfile(windows(), java.util.Map.of()));
        list(process(10, "PRISM").build());
        model.select(row(10));
        assertThat(enabled()).isNotEmpty();

        model.showOwnProcess(10);

        assertThat(enabled()).isEmpty();
        assertThat(actions.messageProperty().get()).isEqualTo(ProcessActions.OWN_PROCESS);
    }
}
