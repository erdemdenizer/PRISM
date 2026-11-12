package prism.srm.ui.oom;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.application.LimitMessages;
import prism.srm.domain.Capability;
import prism.srm.domain.Claim;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.EffectiveAction;
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
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.OomView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;
import prism.srm.ui.control.ResultLineState;
import prism.srm.ui.format.OomWords;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactReadout;

/**
 * What the OOM Guard page shows and may do (UI-5): the state, the reading, the thresholds and the
 * switches, with monitoring and automatic intervention kept apart; the managed processes with their
 * emergency action and what OOM Guard holds on them; the latest automatic actions. No JavaFX node
 * and no system call; every method is called on the JavaFX Application Thread.
 *
 * <p>The settings go out through the function given at construction, which publishes them; the view
 * that comes back carries them. While a change is on its way, what the user chose stays shown, so a
 * view made before the change does not flip a switch back.
 */
final class OomGuardViewModel {

    /** The automatic interventions, each a switch of its own (OOM-5). */
    enum Switch {
        PRIORITY,
        SUSPEND,
        TERMINATE
    }

    /** The actions of the page on the selected managed process. */
    enum Action {
        MANAGEMENT,
        RELEASE
    }

    /** The tone of a cell or a line: only a real condition takes a color. */
    enum Tone {
        NORMAL,
        MUTED,
        WARNING,
        ERROR
    }

    /** One line above the table, in its tone. */
    record Notice(String text, Tone tone) {}

    /** A managed process as the table shows it. */
    record CandidateRow(
            ProcessIdentity identity,
            String name,
            String pid,
            String action,
            boolean actionMuted,
            String status,
            Tone statusTone,
            String priority,
            String memory,
            OomManagement oom,
            boolean holds) {}

    /** One automatic action as the table of recent actions shows it. */
    record ActionRow(String time, String state, String process, String action, String result, boolean failed) {}

    static final String DASH = "–";
    static final String READING = "Reading…";
    static final String NO_MANAGED = "No process is managed. Manage one on the Processes page, then turn OOM management"
            + " on for it with Add to OOM management.";
    static final String NO_ACTIONS = "No automatic action has been taken in this session.";
    static final String SELECT_FIRST = "Select a managed process first.";
    /** Why the actions are disabled while the table has no row to select (UI audit LO-22). */
    static final String NONE_MANAGED = "No process is managed.";

    static final String IN_PROGRESS = "A request about this process is in progress.";
    static final String HOLDS_NOTHING = "OOM Guard holds nothing for this process.";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** A time as the table of recent actions shows it, to measure its column; the digits are tabular. */
    static final String TIME_SAMPLE = "23:58:58";

    private static final String MEMORY_LIMIT = ", memory limit";

    private final ValueFormatter formatter;
    private final OomWords words;
    private final ZoneId zone;
    private final Consumer<OomSettings> settingsOut;
    private final Consumer<LimitRequest> requests;
    private final Consumer<ProcessIdentity> release;

    private final ReadOnlyStringWrapper count = new ReadOnlyStringWrapper("");
    private final FactReadout state = new FactReadout();
    private final ReadOnlyObjectWrapper<Tone> stateTone = new ReadOnlyObjectWrapper<>(Tone.NORMAL);
    private final FactReadout available = new FactReadout();
    private final FactReadout since = new FactReadout();
    private final ReadOnlyBooleanWrapper monitoring = new ReadOnlyBooleanWrapper(true);
    private final ReadOnlyStringWrapper thresholds = new ReadOnlyStringWrapper("");
    private final Map<Switch, ReadOnlyBooleanWrapper> switchOn = new EnumMap<>(Switch.class);
    private final Map<Switch, ReadOnlyBooleanWrapper> switchDisabled = new EnumMap<>(Switch.class);
    private final Map<Switch, ReadOnlyStringWrapper> switchHelp = new EnumMap<>(Switch.class);
    private final ReadOnlyStringWrapper mechanism = new ReadOnlyStringWrapper("");
    private final ObservableList<Notice> notices = FXCollections.observableArrayList();
    private final ObservableList<CandidateRow> rows = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper whenEmpty = new ReadOnlyStringWrapper(READING);
    private final ObservableList<ActionRow> actions = FXCollections.observableArrayList();
    private final Map<Action, ReadOnlyBooleanWrapper> disabled = new EnumMap<>(Action.class);
    private final Map<Action, ReadOnlyStringWrapper> explanations = new EnumMap<>(Action.class);
    private final ResultLineState line = new ResultLineState();
    private final Set<LimitRequest> pending = new HashSet<>();
    /** The OOM settings each pending request was sent under. */
    private final Map<LimitRequest, OomSettings> sentUnder = new HashMap<>();
    /**
     * The settings the explanation of the result line describes ("Not in effect: …"), while it
     * shows one; when other settings come, the explanation is dropped and the headline stays.
     */
    private Optional<OomSettings> lineUnder = Optional.empty();

    private OomView latest;
    /** Whether a view of OOM Guard has come; until then what the system offers is not known. */
    private boolean viewSeen;

    private Optional<ControlProfile> profile = Optional.empty();
    /** The settings the user chose last, until a view shows them. */
    private Optional<OomSettings> awaiting = Optional.empty();

    private ProcessIdentity selected;

    OomGuardViewModel(
            ValueFormatter formatter,
            ZoneId zone,
            OomSettings initial,
            Consumer<OomSettings> settingsOut,
            Consumer<LimitRequest> requests,
            Consumer<ProcessIdentity> release) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.words = new OomWords(formatter);
        this.zone = Objects.requireNonNull(zone, "zone");
        this.settingsOut = Objects.requireNonNull(settingsOut, "settingsOut");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.release = Objects.requireNonNull(release, "release");
        for (Switch each : Switch.values()) {
            switchOn.put(each, new ReadOnlyBooleanWrapper(false));
            switchDisabled.put(each, new ReadOnlyBooleanWrapper(false));
            switchHelp.put(each, new ReadOnlyStringWrapper(""));
        }
        for (Action action : Action.values()) {
            disabled.put(action, new ReadOnlyBooleanWrapper(true));
            explanations.put(action, new ReadOnlyStringWrapper(READING));
        }
        latest = OomView.starting(Objects.requireNonNull(initial, "initial"));
        showSettings(initial);
    }

    // ----- What the page binds to -----

    ReadOnlyStringProperty countProperty() {
        return count.getReadOnlyProperty();
    }

    FactReadout state() {
        return state;
    }

    ReadOnlyObjectProperty<Tone> stateToneProperty() {
        return stateTone.getReadOnlyProperty();
    }

    FactReadout available() {
        return available;
    }

    FactReadout since() {
        return since;
    }

    ReadOnlyBooleanProperty monitoringProperty() {
        return monitoring.getReadOnlyProperty();
    }

    /** "Warning 15 %, Critical 8 %, Emergency 5 % or under 256 MiB". */
    ReadOnlyStringProperty thresholdsProperty() {
        return thresholds.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty switchProperty(Switch which) {
        return switchOn.get(which).getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty switchDisabledProperty(Switch which) {
        return switchDisabled.get(which).getReadOnlyProperty();
    }

    /** What a switch does, or why it cannot be used here; for its tooltip and a screen reader. */
    ReadOnlyStringProperty switchHelpProperty(Switch which) {
        return switchHelp.get(which).getReadOnlyProperty();
    }

    /** The mechanisms of this system, and why a switch is unavailable, in one muted line (CAP-2, CAP-5). */
    ReadOnlyStringProperty mechanismProperty() {
        return mechanism.getReadOnlyProperty();
    }

    ObservableList<Notice> notices() {
        return notices;
    }

    ObservableList<CandidateRow> rows() {
        return rows;
    }

    ReadOnlyStringProperty whenEmptyProperty() {
        return whenEmpty.getReadOnlyProperty();
    }

    ObservableList<ActionRow> actions() {
        return actions;
    }

    ReadOnlyBooleanProperty disabledProperty(Action action) {
        return disabled.get(action).getReadOnlyProperty();
    }

    ReadOnlyStringProperty explanationProperty(Action action) {
        return explanations.get(action).getReadOnlyProperty();
    }

    ResultLineState line() {
        return line;
    }

    OomSettings settings() {
        return awaiting.orElse(latest.settings());
    }

    Optional<CandidateRow> selected() {
        return rows.stream().filter(row -> row.identity().equals(selected)).findFirst();
    }

    /** The total of physical memory in the last reading, to check a floor against; empty without one. */
    Optional<Long> totalBytes() {
        return latest.reading().map(reading -> reading.totalBytes());
    }

    // ----- What comes in -----

    void show(OomView view) {
        latest = Objects.requireNonNull(view, "view");
        viewSeen = true;
        if (awaiting.isPresent() && awaiting.get().equals(view.settings())) {
            awaiting = Optional.empty();
        }
        showSettings(settings());
        lineDescribes(settings());
        showFacts(view);
        List<CandidateRow> now = new ArrayList<>();
        for (OomCandidateView candidate : view.candidates()) {
            now.add(row(candidate));
        }
        if (!now.equals(rows)) {
            rows.setAll(now);
        }
        whenEmpty.set(NO_MANAGED);
        long under = view.underOomManagement();
        count.set(view.candidates().isEmpty() ? "" : formatter.count(under) + " under OOM management");
        List<ActionRow> done = new ArrayList<>();
        for (OomActionRecord record : view.actions()) {
            done.add(actionRow(record));
        }
        if (!done.equals(actions)) {
            actions.setAll(done);
        }
        List<Notice> lines = notices(view);
        if (!lines.equals(notices)) {
            notices.setAll(lines);
        }
        refresh();
    }

    void showControlProfile(ControlProfile known) {
        profile = Optional.of(Objects.requireNonNull(known, "known"));
        showSettings(settings());
    }

    /** Takes the result of any request about limits; only the answer to one this page sent is shown. */
    void accept(LimitResult result) {
        if (!pending.remove(result.request())) {
            return;
        }
        OomSettings sent = sentUnder.remove(result.request());
        line.showResult(result);
        lineUnder = Optional.ofNullable(sent);
        // Asked for under other settings than those shown now: its explanation may be stale already.
        lineDescribes(settings());
        refresh();
        line.arrived();
    }

    /** @param identity {@code null} for no selection */
    void select(ProcessIdentity identity) {
        selected = identity;
        refresh();
    }

    // ----- What the user does -----

    void setMonitoring(boolean on) {
        OomSettings now = settings();
        publish(now.withSwitches(now.switches().withMonitoring(on)));
    }

    /** Termination is asked for here only once the user has confirmed it (OOM-9). */
    void setSwitch(Switch which, boolean on) {
        if (switchDisabled.get(which).get() && on) {
            return;
        }
        OomSettings now = settings();
        OomSwitches switches = now.switches();
        publish(now.withSwitches(
                switch (which) {
                    case PRIORITY -> switches.withPriorityReduction(on);
                    case SUSPEND -> switches.withAutoSuspend(on);
                    case TERMINATE -> switches.withTermination(on);
                }));
    }

    void setThresholds(OomThresholds chosen) {
        publish(settings().withThresholds(chosen));
    }

    /** Sets the OOM settings of the selected process; nothing if the action is disabled. */
    void setOom(ProcessIdentity identity, OomManagement oom) {
        Optional<CandidateRow> row = selected().filter(found -> found.identity().equals(identity));
        if (row.isEmpty() || disabled.get(Action.MANAGEMENT).get()) {
            return;
        }
        LimitRequest request = LimitRequest.oom(identity, row.get().name(), oom);
        pending.add(request);
        sentUnder.put(request, settings());
        line.showText(LimitMessages.inProgress(request));
        lineUnder = Optional.empty();
        refresh();
        requests.accept(request);
    }

    /** Ends what OOM Guard holds on the selected process (plan O5); nothing if it holds nothing. */
    void releaseHold() {
        Optional<CandidateRow> row = selected();
        if (row.isEmpty() || disabled.get(Action.RELEASE).get()) {
            return;
        }
        line.showText("OOM Guard lets go of " + row.get().name() + " (PID "
                + row.get().pid() + ").");
        lineUnder = Optional.empty();
        release.accept(row.get().identity());
    }

    /**
     * What each emergency action does on this system, and why it would not be in effect now; for the
     * OOM management dialog (CAP-5, OOM-8).
     */
    Map<EmergencyAction, String> actionNotes() {
        Map<EmergencyAction, String> notes = new LinkedHashMap<>();
        OomSwitches switches = settings().switches();
        for (EmergencyAction action : EmergencyAction.values()) {
            String does = switch (action) {
                case NOTIFY -> "A warning and a log entry; nothing is done to the process.";
                case LOWER_PRIORITY ->
                    latest.restorability() == Restorability.RESTORABLE
                            ? "Lowers its priority to Low at Critical and in an Emergency; restored at Normal."
                            : "Lowers its priority to Low.";
                case SUSPEND -> suspendWords();
                case TERMINATE -> "Force-kills it in an Emergency, at most once in this session.";
            };
            Optional<String> why = EffectiveAction.of(
                            new OomManagement(true, OomPriority.NORMAL, action),
                            switches,
                            capabilities(),
                            latest.availability().journal().isEmpty(),
                            latest.restorability(),
                            latest.signal())
                    .notInEffect();
            notes.put(
                    action,
                    why.map(reason -> does + " Not in effect: " + reason + ".").orElse(does));
        }
        return notes;
    }

    /** The OOM settings of the selected process, to start the dialog from. */
    Optional<OomManagement> currentOom() {
        return selected().map(CandidateRow::oom);
    }

    // ----- How it is said -----

    private void publish(OomSettings next) {
        if (next.equals(settings())) {
            return;
        }
        awaiting = Optional.of(next);
        showSettings(next);
        lineDescribes(next);
        settingsOut.accept(next);
    }

    /**
     * The settings now shown: a result line whose explanation describes other settings keeps only
     * its headline, so that it never presents an old "Not in effect" (or its absence) as current
     * (external audit P1). What is in effect now is said in the table and the notices.
     */
    private void lineDescribes(OomSettings now) {
        if (lineUnder.isPresent() && !lineUnder.get().equals(now)) {
            line.dropExplanation();
            lineUnder = Optional.empty();
        }
    }

    private void showSettings(OomSettings settings) {
        monitoring.set(settings.switches().monitoring());
        OomThresholds limits = settings.thresholds();
        thresholds.set("Warning " + limits.warningPercent() + " %, Critical " + limits.criticalPercent()
                + " %, Emergency " + limits.emergencyPercent() + " %"
                + (limits.emergencyFloorBytes() > 0 ? " or under " + floorText(limits.emergencyFloorBytes()) : ""));
        switchOn.get(Switch.PRIORITY).set(settings.switches().priorityReduction());
        switchOn.get(Switch.SUSPEND).set(settings.switches().autoSuspend());
        switchOn.get(Switch.TERMINATE).set(settings.switches().termination());
        OomView.Availability availability = latest.availability();
        unavailable(
                Switch.PRIORITY,
                availability.priority(),
                "Lowers the priority of a managed process to Low at"
                        + " Critical when its emergency action is Lower priority or stronger.");
        unavailable(
                Switch.SUSPEND,
                availability.suspend(),
                "Suspends a managed process in an Emergency when its" + " emergency action is Suspend.");
        unavailable(
                Switch.TERMINATE,
                availability.termination(),
                "Force-kills a managed process in an Emergency" + " when its emergency action is Terminate.");
        mechanism.set(mechanismLine());
    }

    private void unavailable(Switch which, Optional<String> why, String does) {
        if (!viewSeen) {
            switchDisabled.get(which).set(true);
            switchHelp.get(which).set(READING);
            return;
        }
        boolean off = why.isPresent();
        // A switch that is on where it cannot act stays shown as it is, disabled: nothing is hidden.
        switchDisabled.get(which).set(off && !switchOn.get(which).get());
        switchHelp.get(which).set(why.map(reason -> capitalized(reason) + ".").orElse(does));
    }

    private String mechanismLine() {
        if (!viewSeen) {
            return READING;
        }
        if (latest.signal() == OomSignalStatus.UNVALIDATED) {
            return "Not available on this system: memory signals are not validated. OOM Guard warns and logs only.";
        }
        List<String> parts = new ArrayList<>();
        Capability suspend = capabilities().getOrDefault(ControlAction.SUSPEND, Capability.UNAVAILABLE);
        if (suspend.available()) {
            parts.add(
                    suspend.mechanism()
                                    .filter(ControlMechanism.STOP_SIGNAL::equals)
                                    .isPresent()
                            ? "A stop signal suspends the whole process."
                            : "Suspension is per thread and not atomic.");
        } else if (profile.isPresent()) {
            parts.add(latest.availability()
                    .suspend()
                    .map(reason -> capitalized(reason) + ".")
                    .orElse(""));
        }
        parts.add(
                latest.restorability() == Restorability.RESTORABLE
                        ? "A lowered priority is raised back at Normal."
                        : "Automatic priority reduction is not available: a lowered priority cannot be raised back without"
                                + " privilege.");
        return String.join(" ", parts.stream().filter(part -> !part.isEmpty()).toList());
    }

    private String suspendWords() {
        Capability suspend = capabilities().getOrDefault(ControlAction.SUSPEND, Capability.UNAVAILABLE);
        if (suspend.mechanism().filter(ControlMechanism.STOP_SIGNAL::equals).isPresent()) {
            return "Stops it with a stop signal in an Emergency; continued at Normal.";
        }
        return "Suspends it, thread by thread, in an Emergency; resumed at Normal.";
    }

    private Map<ControlAction, Capability> capabilities() {
        return profile.map(ControlProfile::capabilities).orElse(Map.of());
    }

    private void showFacts(OomView view) {
        if (view.monitorEnded()) {
            state.show(Optional.of("Not monitoring"), 1);
        } else {
            state.show(Optional.of(OomWords.state(view)), 1);
        }
        stateTone.set(
                switch (OomWords.tone(view)) {
                    case NONE -> view.monitoring() ? Tone.NORMAL : Tone.MUTED;
                    case WARNING -> Tone.WARNING;
                    case ERROR -> Tone.ERROR;
                });
        if (!view.monitoring()) {
            available.show(Optional.of(DASH), 1);
        } else if (view.reading().isPresent()) {
            available.show(words.available(view), 1);
        } else {
            available.showUnavailable();
        }
        since.show(Optional.of(view.since().map(this::time).orElse(DASH)), 1);
    }

    private List<Notice> notices(OomView view) {
        List<Notice> lines = new ArrayList<>();
        if (view.monitorEnded()) {
            lines.add(new Notice(
                    "OOM Guard is not monitoring: system monitoring stopped. What it holds stays until you release it.",
                    Tone.WARNING));
        }
        view.notice().ifPresent(text -> lines.add(new Notice(text, Tone.MUTED)));
        if (view.signal() == OomSignalStatus.UNVALIDATED) {
            // The line under the switches says it for every process already.
            return lines;
        }
        // One line for all that is off, so that the table keeps its rows in the smallest window.
        Map<String, Integer> fallBack = new LinkedHashMap<>();
        int journalBound = 0;
        for (OomCandidateView candidate : view.candidates()) {
            Optional<String> why =
                    candidate.oom().managed() ? candidate.effective().notInEffect() : Optional.empty();
            if (why.isEmpty()) {
                continue;
            }
            if (why.get().equals(EffectiveAction.JOURNAL)) {
                journalBound++;
            } else {
                fallBack.merge(why.get(), 1, Integer::sum);
            }
        }
        if (view.availability().journal().isPresent() || journalBound > 0) {
            String problem =
                    view.availability().journal().map(text -> " (" + text + ")").orElse("");
            lines.add(new Notice(
                    "The suspension journal cannot be written" + problem + ": automatic suspend is not in effect"
                            + (journalBound == 0 ? "." : ", " + processes(journalBound) + " back to Notify only."),
                    Tone.ERROR));
        }
        if (!fallBack.isEmpty()) {
            int many = fallBack.values().stream().mapToInt(Integer::intValue).sum();
            lines.add(new Notice(
                    capitalized(String.join("; ", fallBack.keySet())) + ": " + processes(many)
                            + " back to Notify only.",
                    Tone.MUTED));
        }
        return lines;
    }

    private String processes(int many) {
        return formatter.count(many) + (many == 1 ? " process falls" : " processes fall");
    }

    private CandidateRow row(OomCandidateView candidate) {
        OomManagement oom = candidate.oom();
        String action;
        boolean muted;
        if (!oom.managed()) {
            action = "Off";
            muted = true;
        } else if (candidate.effective().notInEffect().isPresent()) {
            action = actionName(oom.action()) + ", not in effect";
            muted = true;
        } else {
            action = actionName(oom.action());
            muted = false;
        }
        OomHold hold = candidate.hold();
        Status status = status(candidate, hold);
        return new CandidateRow(
                candidate.identity(),
                candidate.name(),
                Long.toString(candidate.identity().pid()),
                action,
                muted,
                status.text(),
                status.tone(),
                oom.managed()
                        ? candidate.priority().map(OomGuardViewModel::level).orElse(DASH)
                        : DASH,
                candidate.residentBytes().isPresent()
                        ? formatter.bytes(candidate.residentBytes().getAsLong()).text()
                        : DASH,
                oom,
                hold.holdsAnything());
    }

    private record Status(String text, Tone tone) {}

    private static Status status(OomCandidateView candidate, OomHold hold) {
        if (hold.physical() == OomHold.Physical.RELEASE_INCOMPLETE) {
            return new Status("Suspended, release incomplete", Tone.WARNING);
        }
        if (!hold.suspendClaims().isEmpty()) {
            return new Status(
                    hold.suspendClaims().equals(Set.of(Claim.RESIDENT_POLICY))
                            ? "Suspended: memory limit"
                            : "Suspended",
                    Tone.NORMAL);
        }
        if (!hold.priorityClaims().isEmpty()) {
            return new Status(
                    "Priority lowered"
                            + hold.previousPriority()
                                    .map(was -> " (was " + level(was) + ")")
                                    .orElse(""),
                    Tone.NORMAL);
        }
        if (hold.failed().isPresent()) {
            return new Status("Failed: " + hold.failed().get(), Tone.ERROR);
        }
        if (hold.terminateRequested()) {
            return new Status("Terminate requested", Tone.NORMAL);
        }
        if (candidate.notNow().isPresent()) {
            return new Status(capitalized(candidate.notNow().get()), Tone.MUTED);
        }
        return new Status(DASH, Tone.MUTED);
    }

    private ActionRow actionRow(OomActionRecord record) {
        boolean failed =
                record.result() != OperationResult.SUCCESS && record.result() != OperationResult.PROCESS_NOT_FOUND;
        return new ActionRow(
                time(record.time()),
                OomWords.name(record.state()) + (record.cause() == Claim.RESIDENT_POLICY ? MEMORY_LIMIT : ""),
                record.name() + " (PID " + record.identity().pid() + ")",
                kindName(record.kind()),
                record.text(),
                failed);
    }

    private static String kindName(OomActionRecord.Kind kind) {
        return switch (kind) {
            case LOWER_PRIORITY -> "Lower priority";
            case SUSPEND -> "Suspend";
            case TERMINATE -> "Terminate";
            case RESUME -> "Resume";
            case RESTORE_PRIORITY -> "Restore priority";
        };
    }

    /** Every text the State column of the recent actions may hold. */
    static List<String> recordStateTexts() {
        List<String> texts = new ArrayList<>();
        for (OomState each : OomState.values()) {
            texts.add(OomWords.name(each));
            texts.add(OomWords.name(each) + MEMORY_LIMIT);
        }
        return texts;
    }

    /** Every text the Action column of the recent actions may hold. */
    static List<String> recordActionTexts() {
        List<String> texts = new ArrayList<>();
        for (OomActionRecord.Kind kind : OomActionRecord.Kind.values()) {
            texts.add(kindName(kind));
        }
        return texts;
    }

    private void refresh() {
        Optional<CandidateRow> row = selected();
        for (Action action : Action.values()) {
            Optional<String> reason = reasonAgainst(action, row);
            disabled.get(action).set(reason.isPresent());
            explanations.get(action).set(reason.orElseGet(() -> description(action)));
        }
    }

    private Optional<String> reasonAgainst(Action action, Optional<CandidateRow> row) {
        if (row.isEmpty()) {
            // With no row to select, "select one first" would send the user looking for one.
            if (!rows.isEmpty()) {
                return Optional.of(SELECT_FIRST);
            }
            return Optional.of(viewSeen ? NONE_MANAGED : READING);
        }
        if (pending.stream()
                .anyMatch(request -> request.identity().equals(row.get().identity()))) {
            return Optional.of(IN_PROGRESS);
        }
        return switch (action) {
            case MANAGEMENT -> Optional.empty();
            case RELEASE -> row.get().holds() ? Optional.empty() : Optional.of(HOLDS_NOTHING);
        };
    }

    private static String description(Action action) {
        return switch (action) {
            case MANAGEMENT -> "Sets OOM management, the OOM priority and the emergency action of this process.";
            case RELEASE ->
                "Resumes the process and restores its priority, if OOM Guard holds them; the process may"
                        + " be acted on again only in a later incident.";
        };
    }

    /** Every text the Emergency action column may hold. */
    static List<String> actionTexts() {
        List<String> texts = new ArrayList<>(List.of("Off"));
        for (EmergencyAction action : EmergencyAction.values()) {
            texts.add(actionName(action));
            texts.add(actionName(action) + ", not in effect");
        }
        return texts;
    }

    /** Every fixed text the Status column may hold; a failure is cut and has its tooltip. */
    static List<String> statusTexts() {
        List<String> texts = new ArrayList<>(
                List.of("Suspended, release incomplete", "Suspended: memory limit", "Terminate requested"));
        for (PriorityLevel level : PriorityLevel.values()) {
            texts.add("Priority lowered (was " + level(level) + ")");
        }
        return texts;
    }

    /** Every text the Priority column may hold. */
    static List<String> priorityTexts() {
        List<String> texts = new ArrayList<>(List.of(DASH));
        for (PriorityLevel level : PriorityLevel.values()) {
            texts.add(level(level));
        }
        return texts;
    }

    static String actionName(EmergencyAction action) {
        return switch (action) {
            case NOTIFY -> "Notify only";
            case LOWER_PRIORITY -> "Lower priority";
            case SUSPEND -> "Suspend";
            case TERMINATE -> "Terminate";
        };
    }

    static String level(PriorityLevel level) {
        return switch (level) {
            case LOW -> "Low";
            case BELOW_NORMAL -> "Below normal";
            case NORMAL -> "Normal";
            case ABOVE_NORMAL -> "Above normal";
            case HIGH -> "High";
        };
    }

    /** "256 MiB" for a whole number of MiB, as the thresholds dialog takes it; otherwise the formatted size. */
    private String floorText(long bytes) {
        return bytes % (1L << 20) == 0
                ? formatter.count(bytes >> 20) + " MiB"
                : formatter.bytes(bytes).text();
    }

    private String time(Instant moment) {
        return TIME.format(moment.atZone(zone));
    }

    private static String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
