package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.processes.ProcessListings.process;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.SuspensionReason;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;

/**
 * Manage, Set CPU limit and Unmanage on the Processes page (MGD-2, UI-2) and the mark of a managed
 * process, through the view model as the page uses it; needs no toolkit.
 */
class ManagedActionsTest {

    private final List<LimitRequest> sent = new ArrayList<>();
    private final List<RecoveryRequest> recoveries = new ArrayList<>();
    private final ProcessesViewModel model = new ProcessesViewModel(
            new ValueFormatter(Locale.US),
            ZoneOffset.UTC,
            "logs",
            identity -> {},
            request -> {},
            sent::add,
            recoveries::add);
    private final ManagedActions managed = model.managed();
    private final ProcessIdentity demo = ProcessListings.identity(10);

    private void listAndSelectTheDemo() {
        model.update(List.of(process(10, "demo").build(), process(20, "other").build()));
        model.select(model.rows().stream()
                .filter(row -> row.identity().equals(demo))
                .findFirst()
                .orElseThrow());
    }

    private boolean disabled(ManagedActions.Choice choice) {
        return managed.disabledProperty(choice).get();
    }

    @Test
    void untilTheLimitsAreKnownNothingIsOffered() {
        listAndSelectTheDemo();

        for (ManagedActions.Choice choice : ManagedActions.Choice.values()) {
            assertThat(disabled(choice)).isTrue();
            assertThat(managed.explanationProperty(choice).get()).isEqualTo(ManagedActions.WAITING);
        }
    }

    @Test
    void aProcessThatIsNotManagedCanBeManagedOrLimitedAndHasNoMark() {
        model.showLimits(LimitsFixtures.snapshot());
        listAndSelectTheDemo();

        assertThat(disabled(ManagedActions.Choice.MANAGE)).isFalse();
        assertThat(disabled(ManagedActions.Choice.SET_CPU_LIMIT)).isFalse();
        assertThat(disabled(ManagedActions.Choice.UNMANAGE)).isTrue();
        assertThat(managed.markProperty().get()).isEmpty();
    }

    @Test
    void settingALimitOnAProcessThatIsNotManagedManagesItFirst() {
        model.showLimits(LimitsFixtures.snapshot());
        listAndSelectTheDemo();

        managed.setCpuLimit(demo, 30);

        assertThat(sent)
                .extracting(LimitRequest::action)
                .containsExactly(LimitAction.MANAGE, LimitAction.SET_CPU_LIMIT);
        assertThat(sent.get(1).percent()).hasValue(30);
        assertThat(model.actions().messageProperty().get()).isEqualTo("Setting the CPU limit of demo (PID 10)…");
        assertThat(disabled(ManagedActions.Choice.UNMANAGE)).isTrue();

        model.showLimitResult(new LimitResult(sent.get(0), ControlOutcome.success(), "demo (PID 10) is managed.", ""));
        assertThat(disabled(ManagedActions.Choice.MANAGE))
                .as("one of two answers")
                .isTrue();
        model.showLimits(LimitsFixtures.snapshot(LimitsFixtures.throttling(demo, "demo", 30, 30)));
        model.showLimitResult(new LimitResult(
                sent.get(1), ControlOutcome.success(), "demo (PID 10) is limited to 30 % of the CPU.", "Approximate."));

        assertThat(model.actions().messageProperty().get())
                .isEqualTo("demo (PID 10) is limited to 30 % of the CPU. Approximate.");
        assertThat(managed.markProperty().get()).isEqualTo("Managed, CPU limit 30 %");
        assertThat(disabled(ManagedActions.Choice.MANAGE)).isTrue();
        assertThat(disabled(ManagedActions.Choice.UNMANAGE)).isFalse();
    }

    @Test
    void aManagedProcessIsSetDirectlyAndCanBeUnmanaged() {
        model.showLimits(LimitsFixtures.snapshot(LimitsFixtures.managed(demo, "demo")));
        listAndSelectTheDemo();

        managed.setCpuLimit(demo, 40);
        assertThat(sent).extracting(LimitRequest::action).containsExactly(LimitAction.SET_CPU_LIMIT);
        assertThat(managed.markProperty().get()).isEqualTo("Managed");
    }

    @Test
    void whereNoCpuLimitIsAvailableSetCpuLimitIsDisabledWithTheReason() {
        model.showLimits(new LimitsSnapshot(
                List.of(), LimitsFixtures.unavailable("this kernel has no pidfd"), Optional.empty()));
        listAndSelectTheDemo();

        assertThat(disabled(ManagedActions.Choice.SET_CPU_LIMIT)).isTrue();
        assertThat(managed.explanationProperty(ManagedActions.Choice.SET_CPU_LIMIT)
                        .get())
                .contains("this kernel has no pidfd");
        assertThat(disabled(ManagedActions.Choice.MANAGE)).isFalse();
    }

    @Test
    void aRefusalIsShownOnTheLineInTheToneOfAWarning() {
        model.showLimits(LimitsFixtures.snapshot());
        listAndSelectTheDemo();
        managed.choose(ManagedActions.Choice.MANAGE);

        model.showLimitResult(new LimitResult(
                sent.get(0),
                ControlOutcome.of(OperationResult.ACCESS_DENIED, "refused"),
                "Prism does not manage demo (PID 10).",
                "It is a system process."));

        assertThat(model.actions().messageProperty().get())
                .isEqualTo("Prism does not manage demo (PID 10). It is a system process.");
        assertThat(model.actions().lineToneProperty().get()).isEqualTo(prism.srm.ui.control.ResultTone.WARNING);
    }

    @Test
    void theRecoveryListGoesToItsOwnPartAndResumeIsOnlyForWhatPrismOffers() {
        model.showRecovered(List.of(
                LimitsFixtures.userLeft(demo, "demo"),
                LimitsFixtures.recovered(
                        ProcessListings.identity(20),
                        "other",
                        EnumSet.of(SuspensionReason.CPU_LIMITER),
                        false,
                        "Prism changes nothing.")));
        RecoveredSuspensions recovered = model.recovered();

        assertThat(recovered.shownProperty().get()).isTrue();
        recovered.resume(recovered.rows().get(1));
        assertThat(recoveries).isEmpty();
        recovered.resume(recovered.rows().get(0));
        recovered.dismiss(recovered.rows().get(0));
        assertThat(recoveries).extracting(RecoveryRequest::action).containsExactly(RecoveryRequest.Action.RESUME);
        assertThat(recovered.line().wholeProperty().get()).isEqualTo("Checking and resuming demo (PID 10)…");
    }

    // ----- OOM management (milestone 11, UI-2) -----

    private static prism.srm.domain.ManagedProcessView withOom(
            prism.srm.domain.ManagedProcessView view, prism.srm.domain.OomManagement oom) {
        return new prism.srm.domain.ManagedProcessView(
                view.process().with(view.process().settings().withOom(oom)),
                view.cpu(),
                view.memory(),
                view.capabilities());
    }

    @Test
    void addingAProcessThatIsNotManagedToOomManagementManagesItFirstAndTurnsItOn() {
        model.showLimits(LimitsFixtures.snapshot());
        listAndSelectTheDemo();
        assertThat(disabled(ManagedActions.Choice.OOM_MANAGE)).isFalse();
        assertThat(disabled(ManagedActions.Choice.OOM_UNMANAGE)).isTrue();
        assertThat(managed.explanationProperty(ManagedActions.Choice.OOM_UNMANAGE)
                        .get())
                .isEqualTo("Not under OOM management.");

        managed.choose(ManagedActions.Choice.OOM_MANAGE);

        assertThat(sent)
                .containsExactly(
                        LimitRequest.of(prism.srm.domain.LimitAction.MANAGE, demo, "demo"),
                        LimitRequest.oom(demo, "demo", prism.srm.domain.OomManagement.ON));
    }

    @Test
    void aProcessUnderOomManagementCanBeTakenOutKeepingItsChoicesAndIsMarked() {
        prism.srm.domain.OomManagement chosen = new prism.srm.domain.OomManagement(
                true, prism.srm.domain.OomPriority.FIRST, prism.srm.domain.EmergencyAction.SUSPEND);
        model.showLimits(LimitsFixtures.snapshot(withOom(LimitsFixtures.managed(demo, "demo"), chosen)));
        listAndSelectTheDemo();

        assertThat(managed.markProperty().get()).isEqualTo("Managed, OOM management");
        assertThat(disabled(ManagedActions.Choice.OOM_MANAGE)).isTrue();
        assertThat(managed.explanationProperty(ManagedActions.Choice.OOM_MANAGE).get())
                .isEqualTo("Under OOM management already.");

        managed.choose(ManagedActions.Choice.OOM_UNMANAGE);

        assertThat(sent).containsExactly(LimitRequest.oom(demo, "demo", chosen.withManaged(false)));
    }

    /** MGD-4 and CAP-2: Prism's own process cannot be managed, limited or put under OOM management, and the page says why. */
    @Test
    void prismsOwnProcessOffersNoManagementAndSendsNothing() {
        model.showLimits(LimitsFixtures.snapshot());
        model.showOwnProcess(10);
        listAndSelectTheDemo();

        for (ManagedActions.Choice choice : ManagedActions.Choice.values()) {
            assertThat(disabled(choice)).as(choice.name()).isTrue();
            assertThat(managed.explanationProperty(choice).get()).isEqualTo(ProcessActions.OWN_PROCESS);
        }
        managed.choose(ManagedActions.Choice.MANAGE);
        managed.choose(ManagedActions.Choice.OOM_MANAGE);
        managed.setCpuLimit(demo, 30);
        assertThat(sent).isEmpty();
    }
}
