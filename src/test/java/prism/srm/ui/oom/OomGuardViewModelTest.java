package prism.srm.ui.oom;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.OomFixtures.candidate;
import static prism.srm.ui.OomFixtures.identity;
import static prism.srm.ui.OomFixtures.oom;
import static prism.srm.ui.OomFixtures.reading;
import static prism.srm.ui.OomFixtures.settings;
import static prism.srm.ui.OomFixtures.view;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import prism.srm.domain.Claim;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomCandidateView;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.OomView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.oom.OomGuardViewModel.CandidateRow;
import prism.srm.ui.oom.OomGuardViewModel.Switch;
import prism.srm.ui.oom.OomGuardViewModel.Tone;

/** What the OOM Guard page says and does (UI-5), without the toolkit, under a Turkish default locale. */
class OomGuardViewModelTest {

    private final Locale before = Locale.getDefault();
    private final List<OomSettings> published = new ArrayList<>();
    private final List<LimitRequest> sent = new ArrayList<>();
    private final List<ProcessIdentity> released = new ArrayList<>();
    private final OomGuardViewModel model;

    OomGuardViewModelTest() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        model = new OomGuardViewModel(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                OomSettings.DEFAULTS,
                published::add,
                sent::add,
                released::add);
        model.showControlProfile(OomFixtures.windows());
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(before);
    }

    private static OomCandidateView suspendable(long pid, OomSettings settings, OomHold hold) {
        return candidate(
                pid,
                "java.exe",
                oom(EmergencyAction.SUSPEND),
                settings,
                hold,
                Optional.of(PriorityLevel.NORMAL),
                512,
                Optional.empty());
    }

    @Test
    void theStateTheReadingAndTheMomentAreSaidInWordsWithTheirTone() {
        model.show(view(
                settings(false, false, false), OomState.WARNING, Optional.of(reading(13.4)), List.of(), List.of()));

        assertThat(model.state().textProperty().get()).isEqualTo("Warning");
        assertThat(model.stateToneProperty().get()).isEqualTo(Tone.WARNING);
        assertThat(model.available().textProperty().get()).isEqualTo("4.3 GiB, 13 %");
        assertThat(model.since().textProperty().get()).isEqualTo("14:02:11");

        model.show(view(settings(false, false, false), OomState.EMERGENCY, Optional.empty(), List.of(), List.of()));
        assertThat(model.stateToneProperty().get()).isEqualTo(Tone.ERROR);
        assertThat(model.available().textProperty().get()).isEqualTo("Unavailable");
    }

    @Test
    void withMonitoringOffTheStateIsOffAndMuted() {
        OomSettings off = OomSettings.DEFAULTS.withSwitches(
                OomSettings.DEFAULTS.switches().withMonitoring(false));
        model.show(view(off, OomState.NORMAL, Optional.empty(), List.of(), List.of()));

        assertThat(model.state().textProperty().get()).isEqualTo("Off");
        assertThat(model.stateToneProperty().get()).isEqualTo(Tone.MUTED);
        assertThat(model.monitoringProperty().get()).isFalse();
        assertThat(model.since().textProperty().get()).isEqualTo("–");
    }

    @Test
    void theThresholdsAreSaidInOneLine() {
        assertThat(model.thresholdsProperty().get())
                .isEqualTo("Warning 15 %, Critical 8 %, Emergency 5 % or under 256 MiB");
    }

    @Test
    void anActionNotInEffectIsMutedAndSaidAboveTheTable() {
        OomSettings off = settings(false, false, false);
        model.show(view(
                off,
                OomState.NORMAL,
                Optional.of(reading(50)),
                List.of(suspendable(100, off, OomHold.NONE), suspendable(200, off, OomHold.NONE)),
                List.of()));

        assertThat(model.rows()).allSatisfy(row -> {
            assertThat(row.action()).isEqualTo("Suspend, not in effect");
            assertThat(row.actionMuted()).isTrue();
        });
        assertThat(model.notices())
                .containsExactly(new OomGuardViewModel.Notice(
                        "Automatic suspend is off: 2 processes fall back to Notify only.", Tone.MUTED));
        assertThat(model.countProperty().get()).isEqualTo("2 under OOM management");
    }

    /** One line for every switch that is off, so that the table keeps its rows in the smallest window. */
    @Test
    void theSwitchesThatAreOffAreSaidInOneLine() {
        OomSettings off = settings(false, false, false);
        model.show(view(
                off,
                OomState.NORMAL,
                Optional.of(reading(50)),
                List.of(
                        suspendable(100, off, OomHold.NONE),
                        candidate(
                                200,
                                "node.exe",
                                oom(EmergencyAction.TERMINATE),
                                off,
                                OomHold.NONE,
                                Optional.of(PriorityLevel.NORMAL),
                                64,
                                Optional.empty()),
                        candidate(
                                300,
                                "make.exe",
                                oom(EmergencyAction.LOWER_PRIORITY),
                                off,
                                OomHold.NONE,
                                Optional.of(PriorityLevel.NORMAL),
                                64,
                                Optional.empty())),
                List.of()));

        assertThat(model.notices())
                .containsExactly(new OomGuardViewModel.Notice(
                        "Automatic suspend is off; emergency termination is off; automatic priority reduction is"
                                + " off: 3 processes fall back to Notify only.",
                        Tone.MUTED));
    }

    /** A journal that cannot be written is a failure of Prism: said once, as an error, with what the system said. */
    @Test
    void aJournalThatCannotBeWrittenIsAnErrorSaidOnce() {
        OomSettings acting = settings(false, true, false);
        model.show(OomFixtures.view(
                acting,
                OomState.NORMAL,
                Optional.of(reading(50)),
                List.of(candidate(
                        100,
                        "java.exe",
                        oom(EmergencyAction.SUSPEND),
                        acting,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        64,
                        Optional.empty(),
                        false,
                        OomSignalStatus.VALIDATED)),
                List.of(),
                Optional.empty(),
                false,
                OomSignalStatus.VALIDATED,
                new OomView.Availability(
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("disk full"))));

        assertThat(model.notices())
                .containsExactly(new OomGuardViewModel.Notice(
                        "The suspension journal cannot be written (disk full): automatic suspend is not in effect,"
                                + " 1 process falls back to Notify only.",
                        Tone.ERROR));
        assertThat(model.mechanismProperty().get()).doesNotContain("journal");
    }

    /** Before the first view nothing is said of what the system offers: it is being read. */
    @Test
    void untilTheFirstViewTheSwitchesAreDisabledAndReading() {
        OomGuardViewModel fresh = new OomGuardViewModel(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                OomSettings.DEFAULTS,
                settings -> {},
                request -> {},
                identity -> {});
        fresh.showControlProfile(OomFixtures.windows());

        assertThat(fresh.mechanismProperty().get()).isEqualTo("Reading…");
        for (Switch which : Switch.values()) {
            assertThat(fresh.switchDisabledProperty(which).get())
                    .as("%s", which)
                    .isTrue();
            assertThat(fresh.switchHelpProperty(which).get()).isEqualTo("Reading…");
        }

        fresh.show(
                view(settings(false, false, false), OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));

        assertThat(fresh.switchDisabledProperty(Switch.SUSPEND).get()).isFalse();
        assertThat(fresh.mechanismProperty().get()).startsWith("Suspension is per thread");
    }

    @Test
    void whatOomGuardHoldsIsTheStatusAndOnlyResultsTakeAColor() {
        OomSettings on = settings(true, true, false);
        model.show(view(
                on,
                OomState.EMERGENCY,
                Optional.of(reading(4)),
                List.of(
                        suspendable(100, on, OomFixtures.suspended(Set.of(Claim.PRESSURE))),
                        suspendable(101, on, OomFixtures.suspended(Set.of(Claim.RESIDENT_POLICY))),
                        suspendable(102, on, OomFixtures.lowered(PriorityLevel.BELOW_NORMAL)),
                        suspendable(103, on, OomFixtures.failed("suspend failed: access denied")),
                        suspendable(104, on, OomFixtures.incomplete())),
                List.of()));

        assertThat(model.rows())
                .extracting(OomGuardViewModel.CandidateRow::status, OomGuardViewModel.CandidateRow::statusTone)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Suspended", Tone.NORMAL),
                        org.assertj.core.groups.Tuple.tuple("Suspended: memory limit", Tone.NORMAL),
                        org.assertj.core.groups.Tuple.tuple("Priority lowered (was Below normal)", Tone.NORMAL),
                        org.assertj.core.groups.Tuple.tuple("Failed: suspend failed: access denied", Tone.ERROR),
                        org.assertj.core.groups.Tuple.tuple("Suspended, release incomplete", Tone.WARNING));
    }

    @Test
    void aSwitchTheSystemCannotUseIsDisabledWithItsReasonOnThePage() {
        OomSettings quiet = settings(false, false, false);
        model.show(OomFixtures.view(
                quiet,
                OomState.NORMAL,
                Optional.of(reading(50)),
                List.of(OomFixtures.candidate(
                        30_324,
                        "java.exe",
                        OomFixtures.oom(EmergencyAction.SUSPEND),
                        quiet,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        512,
                        Optional.empty(),
                        true,
                        OomSignalStatus.UNVALIDATED)),
                List.of(),
                Optional.empty(),
                false,
                OomSignalStatus.UNVALIDATED,
                OomFixtures.unvalidated()));

        for (Switch which : Switch.values()) {
            assertThat(model.switchDisabledProperty(which).get())
                    .as("%s", which)
                    .isTrue();
            assertThat(model.switchHelpProperty(which).get())
                    .isEqualTo("Not available on this system: memory signals are not validated.");
        }
        assertThat(model.mechanismProperty().get())
                .isEqualTo("Not available on this system: memory signals are not validated. OOM Guard warns and"
                        + " logs only.");
        assertThat(model.notices()).as("said once, under the switches").isEmpty();
        assertThat(model.rows()).extracting(CandidateRow::action).containsExactly("Suspend, not in effect");
    }

    @Test
    void onWindowsTheMechanismsAreNamed() {
        model.show(
                view(settings(false, false, false), OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));

        assertThat(model.mechanismProperty().get())
                .isEqualTo("Suspension is per thread and not atomic. A lowered priority is raised back at Normal.");
    }

    @Test
    void aChangedSwitchIsPublishedAndStaysShownUntilAViewShowsIt() {
        OomSettings off = settings(false, false, false);
        model.show(view(off, OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));

        model.setSwitch(Switch.SUSPEND, true);
        assertThat(published).containsExactly(off.withSwitches(off.switches().withAutoSuspend(true)));
        assertThat(model.switchProperty(Switch.SUSPEND).get()).isTrue();

        // A view made before the change arrives: the switch stays as the user set it.
        model.show(view(off, OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));
        assertThat(model.switchProperty(Switch.SUSPEND).get()).isTrue();

        model.setSwitch(Switch.PRIORITY, true);
        assertThat(published.get(1).switches())
                .isEqualTo(off.switches().withAutoSuspend(true).withPriorityReduction(true));

        model.show(view(published.get(1), OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));
        model.show(view(off, OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));
        assertThat(model.switchProperty(Switch.SUSPEND).get())
                .as("a later view is believed again")
                .isFalse();
    }

    @Test
    void theSameSettingsAreNotPublishedTwice() {
        model.setMonitoring(true);
        model.setThresholds(OomThresholds.DEFAULTS);

        assertThat(published).isEmpty();
    }

    /** LO-22: the reason a disabled action gives names what is missing: a view, a managed process, a selection. */
    @Test
    void theReasonOfADisabledActionSaysWhatIsMissing() {
        OomGuardViewModel.Action management = OomGuardViewModel.Action.MANAGEMENT;
        String beforeAnyView = model.explanationProperty(management).get();
        OomSettings on = settings(false, true, false);
        model.show(view(on, OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));
        String noneManaged = model.explanationProperty(management).get();
        model.show(view(
                on, OomState.NORMAL, Optional.of(reading(50)), List.of(suspendable(100, on, OomHold.NONE)), List.of()));
        String noneSelected = model.explanationProperty(management).get();

        assertThat(List.of(beforeAnyView, noneManaged, noneSelected))
                .containsExactly("Reading…", "No process is managed.", "Select a managed process first.");
        assertThat(model.disabledProperty(management).get()).isTrue();
    }

    @Test
    void theOomSettingsOfTheSelectedProcessGoOutAndItsResultComesBack() {
        OomSettings on = settings(false, true, false);
        model.show(view(
                on, OomState.NORMAL, Optional.of(reading(50)), List.of(suspendable(100, on, OomHold.NONE)), List.of()));
        assertThat(model.disabledProperty(OomGuardViewModel.Action.MANAGEMENT).get())
                .isTrue();
        model.select(identity(100));
        assertThat(model.disabledProperty(OomGuardViewModel.Action.MANAGEMENT).get())
                .isFalse();
        assertThat(model.disabledProperty(OomGuardViewModel.Action.RELEASE).get())
                .isTrue();
        assertThat(model.explanationProperty(OomGuardViewModel.Action.RELEASE).get())
                .isEqualTo("OOM Guard holds nothing for this process.");

        OomManagement chosen = new OomManagement(true, OomPriority.FIRST, EmergencyAction.TERMINATE);
        model.setOom(identity(100), chosen);

        assertThat(sent).containsExactly(LimitRequest.oom(identity(100), "java.exe", chosen));
        assertThat(model.disabledProperty(OomGuardViewModel.Action.MANAGEMENT).get())
                .isTrue();
        assertThat(model.line().wholeProperty().get()).startsWith("Setting the OOM management of java.exe");

        model.accept(new LimitResult(
                sent.get(0), ControlOutcome.success(), "java.exe (PID 100) is under OOM management.", ""));
        assertThat(model.line().wholeProperty().get()).isEqualTo("java.exe (PID 100) is under OOM management.");
        assertThat(model.disabledProperty(OomGuardViewModel.Action.MANAGEMENT).get())
                .isFalse();

        model.accept(new LimitResult(
                LimitRequest.oom(identity(200), "other", chosen), ControlOutcome.success(), "Not this page's.", ""));
        assertThat(model.line().wholeProperty().get()).isEqualTo("java.exe (PID 100) is under OOM management.");
    }

    /** Sets OOM management on 100 under these settings and returns the request sent. */
    private LimitRequest managedUnder(OomSettings settings, EmergencyAction action) {
        model.show(view(
                settings,
                OomState.NORMAL,
                Optional.of(reading(50)),
                List.of(candidate(
                        100,
                        "java.exe",
                        oom(action),
                        settings,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        512,
                        Optional.empty())),
                List.of()));
        model.select(identity(100));
        model.setOom(identity(100), new OomManagement(true, OomPriority.NORMAL, action));
        return sent.get(sent.size() - 1);
    }

    private static final String HEADLINE =
            "java.exe (PID 100) is under OOM management: Terminate (force kill), normal priority.";
    private static final String OFF_SO_NOTIFIES =
            "Not in effect: emergency termination is off, so OOM Guard only notifies.";

    /**
     * External audit P1 (2026-10-13): a result that said "not in effect" while a switch was off must
     * not stay on the page as current once the switch is turned on. The headline stays (it is still
     * true); the explanation of that moment goes.
     */
    @Test
    void turningTheSwitchOnRemovesTheNotInEffectExplanationOfAnEarlierResult() {
        OomSettings off = settings(false, false, false);
        LimitRequest request = managedUnder(off, EmergencyAction.TERMINATE);
        model.accept(new LimitResult(request, ControlOutcome.success(), HEADLINE, OFF_SO_NOTIFIES));
        assertThat(model.line().explanationProperty().get()).isEqualTo(OFF_SO_NOTIFIES);

        model.setSwitch(Switch.TERMINATE, true);

        assertThat(model.line().headlineProperty().get()).isEqualTo(HEADLINE);
        assertThat(model.line().explanationProperty().get()).isEmpty();
        assertThat(model.line().wholeProperty().get()).isEqualTo(HEADLINE).doesNotContain("only notifies");
    }

    /** The same for Suspend, and when the settings come back in a view rather than from this page. */
    @Test
    void settingsThatArriveInAViewAlsoRemoveTheExplanation() {
        OomSettings off = settings(false, false, false);
        LimitRequest request = managedUnder(off, EmergencyAction.SUSPEND);
        String explanation = "Not in effect: automatic suspend is off, so OOM Guard only notifies.";
        model.accept(new LimitResult(
                request,
                ControlOutcome.success(),
                "java.exe (PID 100) is under OOM management: Suspend, normal priority.",
                explanation));

        OomSettings on = settings(false, true, false);
        model.show(view(
                on, OomState.NORMAL, Optional.of(reading(50)), List.of(suspendable(100, on, OomHold.NONE)), List.of()));

        assertThat(model.line().wholeProperty().get()).doesNotContain("only notifies");
    }

    /** A result that was asked for under other settings than those in force when it arrives (delayed). */
    @Test
    void aResultThatArrivesAfterTheSwitchChangedDoesNotSayItsOldExplanation() {
        OomSettings off = settings(false, false, false);
        LimitRequest request = managedUnder(off, EmergencyAction.TERMINATE);
        model.setSwitch(Switch.TERMINATE, true);

        model.accept(new LimitResult(request, ControlOutcome.success(), HEADLINE, OFF_SO_NOTIFIES));

        assertThat(model.line().headlineProperty().get()).isEqualTo(HEADLINE);
        assertThat(model.line().wholeProperty().get()).doesNotContain("only notifies");
    }

    /** Turning the switch off again: the line claims nothing stale; the table says "not in effect". */
    @Test
    void turningTheSwitchOffLeavesNoClaimThatItActs() {
        OomSettings on = settings(false, false, true);
        LimitRequest request = managedUnder(on, EmergencyAction.TERMINATE);
        model.accept(new LimitResult(request, ControlOutcome.success(), HEADLINE, ""));

        model.setSwitch(Switch.TERMINATE, false);
        OomSettings off = settings(false, false, false);
        model.show(view(
                off,
                OomState.NORMAL,
                Optional.of(reading(50)),
                List.of(candidate(
                        100,
                        "java.exe",
                        oom(EmergencyAction.TERMINATE),
                        off,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        512,
                        Optional.empty())),
                List.of()));

        assertThat(model.line().wholeProperty().get()).isEqualTo(HEADLINE);
        assertThat(model.rows()).extracting(CandidateRow::action).containsExactly("Terminate, not in effect");
    }

    /** Unchanged settings keep the explanation, which is still true. */
    @Test
    void anUnchangedSettingKeepsTheExplanation() {
        OomSettings off = settings(false, false, false);
        LimitRequest request = managedUnder(off, EmergencyAction.TERMINATE);
        model.accept(new LimitResult(request, ControlOutcome.success(), HEADLINE, OFF_SO_NOTIFIES));

        model.show(view(
                off,
                OomState.WARNING,
                Optional.of(reading(12)),
                List.of(candidate(
                        100,
                        "java.exe",
                        oom(EmergencyAction.TERMINATE),
                        off,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        512,
                        Optional.empty())),
                List.of()));

        assertThat(model.line().explanationProperty().get()).isEqualTo(OFF_SO_NOTIFIES);
    }

    @Test
    void releasingAHoldNamesTheProcess() {
        OomSettings on = settings(false, true, false);
        model.show(view(
                on,
                OomState.EMERGENCY,
                Optional.of(reading(4)),
                List.of(suspendable(100, on, OomFixtures.suspended(Set.of(Claim.PRESSURE)))),
                List.of()));
        model.select(identity(100));

        model.releaseHold();

        assertThat(released).containsExactly(identity(100));
    }

    @Test
    void theNotesOfTheDialogSayWhatEachActionDoesAndWhetherItIsInEffect() {
        model.show(view(settings(false, false, true), OomState.NORMAL, Optional.of(reading(50)), List.of(), List.of()));

        assertThat(model.actionNotes().get(EmergencyAction.SUSPEND))
                .isEqualTo("Suspends it, thread by thread, in an Emergency; resumed at Normal. Not in effect:"
                        + " automatic suspend is off.");
        assertThat(model.actionNotes().get(EmergencyAction.TERMINATE))
                .isEqualTo("Force-kills it in an Emergency, at most once in this session.");
    }

    @Test
    void theRecentActionsAreListedNewestFirstAndAFailureIsAnError() {
        model.show(view(
                settings(false, true, false),
                OomState.EMERGENCY,
                Optional.of(reading(4)),
                List.of(),
                List.of(
                        OomFixtures.record(
                                OomState.EMERGENCY,
                                100,
                                "java.exe",
                                OomActionRecord.Kind.SUSPEND,
                                OperationResult.ACCESS_DENIED,
                                "OOM Guard could not suspend java.exe (PID 100): access denied."),
                        OomFixtures.record(
                                OomState.CRITICAL,
                                101,
                                "node.exe",
                                OomActionRecord.Kind.LOWER_PRIORITY,
                                OperationResult.SUCCESS,
                                "OOM Guard lowered the priority of node.exe (PID 101) to Low."))));

        assertThat(model.actions()).hasSize(2);
        assertThat(model.actions().get(0).failed()).isTrue();
        assertThat(model.actions().get(0).time()).isEqualTo("14:02:11");
        assertThat(model.actions().get(1).action()).isEqualTo("Lower priority");
        assertThat(model.actions().get(1).failed()).isFalse();
    }

    @Test
    void anEqualViewChangesNoRow() {
        OomSettings on = settings(false, true, false);
        OomView same = view(
                on, OomState.NORMAL, Optional.of(reading(50)), List.of(suspendable(100, on, OomHold.NONE)), List.of());
        model.show(same);
        List<String> changes = new ArrayList<>();
        model.rows().addListener((ListChangeListener<OomGuardViewModel.CandidateRow>) change -> changes.add("rows"));
        model.actions().addListener((ListChangeListener<OomGuardViewModel.ActionRow>) change -> changes.add("actions"));

        model.show(same);

        assertThat(changes).isEmpty();
    }

    @Test
    void notMonitoringAnyMoreIsSaid() {
        model.show(OomFixtures.view(
                settings(false, true, false),
                OomState.EMERGENCY,
                Optional.of(reading(4)),
                List.of(),
                List.of(),
                Optional.empty(),
                true,
                OomSignalStatus.VALIDATED,
                OomView.Availability.ALL));

        assertThat(model.state().textProperty().get()).isEqualTo("Not monitoring");
        assertThat(model.notices()).first().satisfies(notice -> {
            assertThat(notice.text()).startsWith("OOM Guard is not monitoring: system monitoring stopped.");
            assertThat(notice.tone()).isEqualTo(Tone.WARNING);
        });
    }
}
