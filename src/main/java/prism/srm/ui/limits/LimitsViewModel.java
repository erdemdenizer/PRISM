package prism.srm.ui.limits;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.FallbackOffer;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitNote;
import prism.srm.domain.LimitReport;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsNotice;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.control.ResultLineState;
import prism.srm.ui.format.CapabilityWords;
import prism.srm.ui.format.ValueFormatter;

/**
 * What the Limits page shows and may do (UI-4): the managed processes with their CPU limit, the
 * memory limit this version does not have, the status of the limit in words that never claim more
 * than was measured (LIM-4), and how it is applied; the actions on the selected one; and why limits
 * are off when they are. No JavaFX node and no system call; every method is called on the JavaFX
 * Application Thread.
 */
final class LimitsViewModel {

    /** The actions of the page on the selected managed process. */
    enum Action {
        EDIT,
        REMOVE,
        EDIT_MEMORY,
        REMOVE_MEMORY,
        USE_EMULATED,
        USE_WATCHED,
        TOGGLE,
        UNMANAGE
    }

    static final String READING = "Reading…";
    static final String EMPTY = "No managed processes. To manage one, choose Manage in the context menu of a process"
            + " on the Processes page.";
    /** What the memory column stands for: said in its header (CAP-3: a watched limit is never called a cap). */
    static final String MEMORY_NOTE = "The memory each process may commit (native cap), or, where no native cap is"
            + " available, the resident memory Prism watches.";

    static final String DASH = "–";
    static final String SELECT_FIRST = "Select a managed process first.";
    static final String IN_PROGRESS = "A request about this process is in progress.";

    private final ValueFormatter formatter;
    private final ReadOnlyObjectWrapper<LimitRow> current = new ReadOnlyObjectWrapper<>();
    private final Consumer<LimitRequest> requests;
    private final ObservableList<LimitRow> rows = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper whenEmpty = new ReadOnlyStringWrapper(READING);
    private final ReadOnlyStringWrapper count = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper notice = new ReadOnlyStringWrapper("");
    private final ReadOnlyBooleanWrapper limitsOff = new ReadOnlyBooleanWrapper(false);
    private final ObservableList<Notice> notices = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper toggleText = new ReadOnlyStringWrapper("Disable");
    private final Map<Action, ReadOnlyBooleanWrapper> disabled = new EnumMap<>(Action.class);
    private final Map<Action, ReadOnlyStringWrapper> explanations = new EnumMap<>(Action.class);
    private final ResultLineState line = new ResultLineState();
    private final ReadOnlyBooleanWrapper watchOffered = new ReadOnlyBooleanWrapper(false);
    private final Set<LimitRequest> pending = new HashSet<>();

    private LimitsSnapshot latest = LimitsSnapshot.EMPTY;
    private boolean received;
    private ProcessIdentity selected;

    LimitsViewModel(ValueFormatter formatter, Consumer<LimitRequest> requests) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.requests = Objects.requireNonNull(requests, "requests");
        for (Action action : Action.values()) {
            disabled.put(action, new ReadOnlyBooleanWrapper(true));
            explanations.put(action, new ReadOnlyStringWrapper(SELECT_FIRST));
        }
    }

    ObservableList<LimitRow> rows() {
        return rows;
    }

    ReadOnlyStringProperty whenEmptyProperty() {
        return whenEmpty.getReadOnlyProperty();
    }

    /** "2 managed", at the right of the header; empty before the first snapshot. */
    ReadOnlyStringProperty countProperty() {
        return count.getReadOnlyProperty();
    }

    /** Why limits are off or unavailable on this system; empty while they can be set. */
    ReadOnlyStringProperty noticeProperty() {
        return notice.getReadOnlyProperty();
    }

    /** One line above the table, in the tone of what it says: a failure, a warning, or a fact of the system. */
    record Notice(String text, LimitRow.Tone tone) {}

    /** The notices, each on a line of its own; the page shows one label per notice. */
    ObservableList<Notice> notices() {
        return notices;
    }

    /** Whether the notice is a failure (the journal), not a fact of the system. */
    ReadOnlyBooleanProperty limitsOffProperty() {
        return limitsOff.getReadOnlyProperty();
    }

    /** "Disable" or "Enable", as the selected process stands. */
    ReadOnlyStringProperty toggleTextProperty() {
        return toggleText.getReadOnlyProperty();
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

    /** The selected row, if it is still listed. */
    Optional<LimitRow> selected() {
        return rows.stream().filter(row -> row.identity().equals(selected)).findFirst();
    }

    /** What a CPU limit is on this system. */
    LimitCapability capability() {
        return latest.cpu();
    }

    void show(LimitsSnapshot snapshot) {
        latest = Objects.requireNonNull(snapshot, "snapshot");
        received = true;
        List<LimitRow> now = new ArrayList<>();
        for (ManagedProcessView view : snapshot.processes()) {
            now.add(row(view));
        }
        if (!now.equals(rows)) {
            rows.setAll(now);
        }
        whenEmpty.set(EMPTY);
        int managed = snapshot.processes().size();
        count.set(managed == 0 ? "" : formatter.count(managed) + " managed");
        List<Notice> lines = new ArrayList<>();
        if (snapshot.limitsOff().isPresent()) {
            lines.add(new Notice(snapshot.limitsOff().get(), LimitRow.Tone.ERROR));
        } else if (!snapshot.cpu().available()) {
            lines.add(new Notice(
                    "CPU limits are not available on this system: "
                            + snapshot.cpu().reason().orElse("") + ".",
                    LimitRow.Tone.MUTED));
        }
        for (LimitsNotice each : snapshot.notices()) {
            lines.add(new Notice(each.text(), tone(each.kind())));
        }
        if (!lines.equals(notices)) {
            notices.setAll(lines);
        }
        notice.set(String.join("\n", lines.stream().map(Notice::text).toList()));
        limitsOff.set(lines.stream().anyMatch(each -> each.tone() == LimitRow.Tone.ERROR));
        refresh();
    }

    /**
     * A failure of Prism is an error; a limit that may still be in force where Prism cannot reach it is a
     * warning; a fact of the system is muted (docs/UI_GUIDE.md, color).
     */
    static LimitRow.Tone tone(LimitsNotice.Kind kind) {
        return switch (kind) {
            case LEDGER_FAILED -> LimitRow.Tone.ERROR;
            case RESIDUE, PRIOR_SESSION, RECOVERY_PENDING -> LimitRow.Tone.WARNING;
            case SELF_IN_JOB, NATIVE_UNAVAILABLE -> LimitRow.Tone.MUTED;
        };
    }

    /** @param identity {@code null} for no selection */
    void select(ProcessIdentity identity) {
        selected = identity;
        refresh();
    }

    /** Sends a request of the page about the selected process; nothing if the action is disabled. */
    void request(Action action) {
        Optional<LimitRow> row = selected();
        if (row.isEmpty()
                || disabled.get(action).get()
                || action == Action.EDIT
                || action == Action.EDIT_MEMORY
                || action == Action.USE_EMULATED
                || action == Action.USE_WATCHED) {
            return;
        }
        LimitAction limitAction = switch (action) {
            case REMOVE -> LimitAction.REMOVE_CPU_LIMIT;
            case REMOVE_MEMORY -> LimitAction.REMOVE_MEMORY_LIMIT;
            case TOGGLE -> row.get().enabled() ? LimitAction.DISABLE : LimitAction.ENABLE;
            case UNMANAGE -> LimitAction.UNMANAGE;
            case EDIT, EDIT_MEMORY, USE_EMULATED, USE_WATCHED ->
                throw new IllegalStateException("Asked through its dialog: " + action);
        };
        send(LimitRequest.of(limitAction, row.get().identity(), row.get().name()));
    }

    /** Sets the memory limit of a listed process; nothing if the action is disabled for it. */
    void setMemoryLimit(ProcessIdentity identity, long bytes) {
        Optional<LimitRow> row = selected().filter(found -> found.identity().equals(identity));
        if (row.isEmpty() || disabled.get(Action.EDIT_MEMORY).get()) {
            return;
        }
        send(LimitRequest.memoryLimit(identity, row.get().name(), bytes));
    }

    /** Accepts the emulated CPU limit offered for the selected process; nothing if none is offered. */
    void acceptEmulated() {
        Optional<LimitRow> row = selected();
        if (row.isEmpty() || disabled.get(Action.USE_EMULATED).get()) {
            return;
        }
        send(LimitRequest.of(
                LimitAction.ACCEPT_EMULATED_CPU, row.get().identity(), row.get().name()));
    }

    /** Whether the selected process has the watched resident memory offered (plan O9). */
    ReadOnlyBooleanProperty watchOfferedProperty() {
        return watchOffered.getReadOnlyProperty();
    }

    /** Why the native memory limit of the selected process was not applied, while the watch is offered. */
    Optional<String> residentOffer() {
        return selected().flatMap(row -> latest.find(row.identity())).flatMap(ManagedProcessView::residentOffer);
    }

    /** Accepts the watched resident memory offered for the selected process; nothing if none is offered. */
    void acceptWatched() {
        Optional<LimitRow> row = selected();
        if (row.isEmpty() || disabled.get(Action.USE_WATCHED).get()) {
            return;
        }
        send(LimitRequest.of(
                LimitAction.ACCEPT_RESIDENT_POLICY,
                row.get().identity(),
                row.get().name()));
    }

    /** Whether a memory limit set for the selected process is watched resident memory, not a native cap. */
    boolean memoryWatched() {
        return selected()
                .flatMap(row -> latest.find(row.identity()))
                .map(view -> view.capability(LimitKind.MEMORY))
                .or(() -> Optional.of(latest.memory()))
                .flatMap(LimitCapability::mechanism)
                .filter(mechanism -> mechanism == ControlMechanism.RESIDENT_POLICY)
                .isPresent();
    }

    /** The emulated limit offered for the selected process, with its cost, for the consent dialog. */
    Optional<FallbackOffer> offer() {
        return selected()
                .filter(LimitRow::fallbackOffered)
                .flatMap(row -> latest.find(row.identity()))
                .flatMap(ManagedProcessView::offer);
    }

    /** The memory limit of the selected process, if it has one. */
    OptionalLong currentMemoryLimit() {
        return selected()
                .flatMap(row -> latest.find(row.identity()))
                .map(view -> view.process().settings().memoryLimitBytes())
                .orElse(OptionalLong.empty());
    }

    /** Sets the CPU limit of a listed process; nothing if Edit is disabled for it. */
    void setCpuLimit(ProcessIdentity identity, int percent) {
        Optional<LimitRow> row = selected().filter(found -> found.identity().equals(identity));
        if (row.isEmpty() || disabled.get(Action.EDIT).get()) {
            return;
        }
        send(LimitRequest.cpuLimit(identity, row.get().name(), percent));
    }

    /** The CPU limit of the selected process, if it has one. */
    OptionalInt currentLimit() {
        return selected()
                .flatMap(row -> latest.find(row.identity()))
                .map(view -> view.process().settings().cpuLimitPercent())
                .orElse(OptionalInt.empty());
    }

    /** Takes the result of any request about limits; only the answer to one this page sent is shown. */
    void accept(LimitResult result) {
        if (!pending.remove(result.request())) {
            return;
        }
        line.showResult(result);
        refresh();
        line.arrived();
    }

    private void send(LimitRequest request) {
        pending.add(request);
        line.showText(inProgress(request));
        refresh();
        requests.accept(request);
    }

    private boolean inFlight(ProcessIdentity identity) {
        return pending.stream().anyMatch(request -> request.identity().equals(identity));
    }

    /**
     * The row of the selected process as it is listed now, or {@code null}: what the editor panel
     * shows. A new value only when the row changes (the rows are records).
     */
    ReadOnlyObjectProperty<LimitRow> currentProperty() {
        return current.getReadOnlyProperty();
    }

    private void refresh() {
        Optional<LimitRow> row = selected();
        current.set(row.orElse(null));
        // System-wide, not per selection: where a native cap may fail, the button stays in its place.
        watchOffered.set(latest.memory().available()
                && latest.memory()
                        .mechanism()
                        .filter(mechanism -> mechanism == ControlMechanism.RESIDENT_POLICY)
                        .isEmpty());
        toggleText.set(row.map(found -> found.enabled() ? "Disable" : "Enable").orElse("Disable"));
        for (Action action : Action.values()) {
            Optional<String> reason = reasonAgainst(action, row);
            disabled.get(action).set(reason.isPresent());
            explanations.get(action).set(reason.orElseGet(() -> description(action, row)));
        }
    }

    private Optional<String> reasonAgainst(Action action, Optional<LimitRow> row) {
        if (!received) {
            return Optional.of(READING);
        }
        if (row.isEmpty()) {
            return Optional.of(SELECT_FIRST);
        }
        if (inFlight(row.get().identity())) {
            return Optional.of(IN_PROGRESS);
        }
        return switch (action) {
            case EDIT ->
                latest.cpu().available()
                        ? Optional.empty()
                        : Optional.of("A CPU limit is not available here: "
                                + latest.cpu().reason().orElse("") + ".");
            case REMOVE -> row.get().hasLimit() ? Optional.empty() : Optional.of("It has no CPU limit.");
            case EDIT_MEMORY -> {
                LimitCapability memory = latest.find(row.get().identity())
                        .map(view -> view.capability(LimitKind.MEMORY))
                        .orElse(latest.memory());
                yield memory.available()
                        ? Optional.empty()
                        : Optional.of("A memory limit is not available for this process: "
                                + memory.reason().orElse("") + ".");
            }
            case REMOVE_MEMORY ->
                row.get().hasMemoryLimit() ? Optional.empty() : Optional.of("It has no memory limit.");
            case USE_EMULATED ->
                row.get().fallbackOffered()
                        ? Optional.empty()
                        : Optional.of("Offered only after a native CPU limit could not be applied to this process.");
            case USE_WATCHED ->
                residentOffer().isPresent()
                        ? Optional.empty()
                        : Optional.of("Offered only after a native memory limit could not be applied to this process.");
            case TOGGLE, UNMANAGE -> Optional.empty();
        };
    }

    private static String description(Action action, Optional<LimitRow> row) {
        return switch (action) {
            case EDIT -> "Sets the CPU limit, a share of the whole machine.";
            case REMOVE -> "Removes the CPU limit; the process runs freely.";
            case EDIT_MEMORY ->
                "Sets the memory limit: a native cap on what the process may commit where the system has one,"
                        + " otherwise its resident memory, watched and not capped.";
            case REMOVE_MEMORY -> "Removes the memory limit.";
            case USE_EMULATED -> "Uses the emulated CPU limit, approximate and costlier, after the native one failed.";
            case USE_WATCHED -> "Watches the resident memory of the process instead of capping it; not a cap.";
            case TOGGLE ->
                row.map(found -> found.enabled()).orElse(true)
                        ? "Stops applying its limits and keeps them for later."
                        : "Applies its limits again.";
            case UNMANAGE -> "Removes its limits and takes it off the managed list.";
        };
    }

    static String inProgress(LimitRequest request) {
        return LimitMessages.inProgress(request);
    }

    private LimitRow row(ManagedProcessView view) {
        LimitReport cpu = view.cpu();
        LimitReport memory = view.memory();
        OptionalInt requested = cpu.requestedPercent();
        Status status = status(cpu);
        boolean shown = requested.isPresent() || cpu.status() != LimitStatus.INACTIVE;
        String implementation = shown ? implementation(cpu) : DASH;
        if (implementation.equals(DASH) && memory.set()) {
            implementation = implementation(memory);
        }
        return new LimitRow(
                view.identity(),
                view.process().name(),
                requested.isPresent() ? requested.getAsInt() + " %" : DASH,
                memoryText(memory),
                status.text(),
                status.tone(),
                status.detail(),
                implementation,
                view.process().settings().enabled(),
                requested.isPresent(),
                memory.detail().orElse(""),
                memory.set() || memory.status() == LimitStatus.FAILED,
                cpu.note() == LimitNote.FALLBACK_OFFERED,
                mechanism(shown ? cpu : memory));
    }

    /** The memory limit and, where it is not simply in force, how it stands. */
    static String memoryText(LimitReport memory) {
        if (!memory.set()) {
            return memory.status() == LimitStatus.FAILED ? "Failed" : DASH;
        }
        String value = EffectiveLimits.bytes(memory.requestedBytes().getAsLong());
        if (memory.inEffect()
                .mechanism()
                .filter(mechanism -> mechanism == ControlMechanism.RESIDENT_POLICY)
                .isPresent()) {
            // CAP-3: a watched limit is said to be watched, never a cap; above it, it says so.
            return value + (memory.status() == LimitStatus.ACTIVE_ABOVE_TARGET ? " (watched, above)" : " (watched)");
        }
        return switch (memory.status()) {
            case FAILED -> value + ", failed";
            case INACTIVE -> memory.note() == LimitNote.DISABLED ? value + ", disabled" : value;
            case UNAVAILABLE -> value + ", unavailable";
            default -> value;
        };
    }

    static String implementation(LimitReport report) {
        String level = levelName(report.inEffect().level());
        return report.note() == LimitNote.EMULATED_BY_CHOICE ? level + " (chosen)" : level;
    }

    static String mechanism(LimitReport report) {
        return report.inEffect()
                .mechanism()
                .map(mechanism -> switch (mechanism) {
                    case JOB_OBJECT -> "Job Object: the system enforces the limit.";
                    case DUTY_CYCLE -> "Duty cycle: Prism stops and continues the process; approximate.";
                    case RESIDENT_POLICY ->
                        "Watched resident memory: not a cap; OOM Guard applies the emergency action above the limit.";
                    default -> mechanism.name();
                })
                .orElse(report.inEffect().reason().orElse(""));
    }

    /** The status in a few words, its tone, and the whole of it for the tooltip. */
    record Status(String text, LimitRow.Tone tone, String detail) {}

    Status status(LimitReport cpu) {
        String detail = cpu.detail().orElse("");
        Optional<String> measured = percent(cpu.measuredPercent());
        return switch (cpu.status()) {
            case INACTIVE -> {
                if (cpu.requestedPercent().isEmpty()) {
                    yield new Status("No CPU limit", LimitRow.Tone.MUTED, "");
                }
                yield cpu.note() == LimitNote.DISABLED
                        ? new Status("Disabled", LimitRow.Tone.MUTED, "Its limits are disabled; enable them to apply.")
                        : new Status("Inactive", LimitRow.Tone.MUTED, detail);
            }
            case ACTIVE_NOT_THROTTLING ->
                new Status(
                        "Not throttling" + measured.map(m -> ", " + m).orElse(""),
                        LimitRow.Tone.NORMAL,
                        "It uses less than its limit, so Prism does not stop it.");
            case ADJUSTING ->
                cpu.note() == LimitNote.NOT_MEASURED
                        ? new Status("Not measured", LimitRow.Tone.WARNING, detail)
                        : new Status(
                                "Adjusting" + measured.map(m -> ", " + m).orElse(""),
                                LimitRow.Tone.MUTED,
                                "Prism is finding the share of time that holds it near its limit.");
            case ACTIVE_THROTTLING ->
                new Status(
                        "Throttling" + measured.map(m -> ", " + m).orElse(""),
                        LimitRow.Tone.NORMAL,
                        "Prism stops and continues it in short cycles; the use is measured over the last second.");
            case ACTIVE_ABOVE_TARGET -> {
                if (cpu.note() == LimitNote.FLOOR) {
                    String lowest =
                            percent(cpu.lowestPercent()).or(() -> measured).orElse(DASH);
                    yield new Status(
                            "Above limit, lowest " + lowest,
                            LimitRow.Tone.WARNING,
                            "The limit is below the lowest share Prism can hold this process to: " + lowest + ".");
                }
                yield new Status(
                        "Above limit" + measured.map(m -> ", " + m).orElse(""),
                        LimitRow.Tone.WARNING,
                        "The load changes faster than the limiter follows.");
            }
            case HELD_BY_OTHER ->
                detail.contains("OOM Guard")
                        ? new Status(
                                "Waiting: held by OOM Guard",
                                LimitRow.Tone.MUTED,
                                "The limit goes on when OOM Guard lets go of the process.")
                        : new Status(
                                "Waiting: suspended by you",
                                LimitRow.Tone.MUTED,
                                "The limit goes on when you resume it.");
            case PAUSED_EXTERNAL_STOP ->
                new Status(
                        "Waiting: stopped elsewhere",
                        LimitRow.Tone.WARNING,
                        "Another program has stopped it. Prism waits and does not continue it.");
            case ACTIVE_ENFORCED ->
                new Status(
                        "Enforced" + measured.map(m -> ", " + m).orElse(""),
                        LimitRow.Tone.NORMAL,
                        "The system caps the process and the child processes it started after the limit; Prism"
                                + " does nothing per cycle. The use shown is this process alone.");
            case UNAVAILABLE ->
                switch (cpu.note()) {
                    case PRIOR_SESSION ->
                        new Status("Capped by an earlier session", LimitRow.Tone.WARNING, detail + ".");
                    case IN_PRISM_SCOPE ->
                        new Status("Not offered: in a Prism scope", LimitRow.Tone.WARNING, detail + ".");
                    default -> new Status("Unavailable", LimitRow.Tone.WARNING, detail);
                };
            case FAILED ->
                switch (cpu.note()) {
                    case FALLBACK_OFFERED ->
                        new Status(
                                "Not applied, emulated offered",
                                LimitRow.Tone.WARNING,
                                detail
                                        + ". Choose Use emulated limit to accept the emulated one; nothing runs until you do.");
                    case NATIVE_UNSETTLED -> new Status("Failed: limit differs", LimitRow.Tone.ERROR, detail + ".");
                    case IN_PRISM_SCOPE ->
                        new Status("Not offered: in a Prism scope", LimitRow.Tone.WARNING, detail + ".");
                    case RELEASE_INCOMPLETE ->
                        new Status(
                                "Failed: threads still held",
                                LimitRow.Tone.ERROR,
                                detail + ". Resume the process on the Processes page, then set the limit again.");
                    default -> new Status("Failed", LimitRow.Tone.ERROR, "The limit ended: " + detail + ".");
                };
        };
    }

    private Optional<String> percent(OptionalDouble value) {
        return value.isPresent()
                ? Optional.of(formatter.percent(value.getAsDouble()).text())
                : Optional.empty();
    }

    static String levelName(CapabilityLevel level) {
        return CapabilityWords.level(level);
    }
}
