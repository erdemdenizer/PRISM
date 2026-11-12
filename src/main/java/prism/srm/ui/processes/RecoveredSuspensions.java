package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryCheck;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspensionReason;
import prism.srm.ui.control.ResultLineState;

/**
 * The processes an earlier session of Prism may have left suspended, as the start-up found them
 * (ADR-0011), with Resume and Dismiss, and what the last of those requests came to. Prism resumes
 * none of them by itself: Resume is the user's, after a confirmation that says the stop may not be
 * Prism's; Dismiss closes the entry and leaves the process as it is.
 *
 * <p>While a journal of an earlier session is unread, the list may be incomplete, and the summary
 * says so even when nothing is listed: an empty list is never shown as a whole one.
 *
 * <p>Shown whether process monitoring runs or not, while there is an entry or a message. One
 * request per process at a time. Every method is called on the JavaFX Application Thread; needs no
 * running toolkit.
 */
final class RecoveredSuspensions {

    static final String CAPTION =
            "Possibly left suspended by an earlier session. Prism resumes none of them by itself.";

    private final Consumer<RecoveryRequest> requests;
    private final ObservableList<RecoveredSuspension> rows = FXCollections.observableArrayList();
    private final ObservableList<RecoveredSuspension> rowsView = FXCollections.unmodifiableObservableList(rows);
    private final ReadOnlyBooleanWrapper shown = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyObjectWrapper<RecoveryCheck> check = new ReadOnlyObjectWrapper<>(RecoveryCheck.COMPLETE);
    private final ReadOnlyBooleanWrapper summaryShown = new ReadOnlyBooleanWrapper(false);
    private final ResultLineState line = new ResultLineState();
    private final Set<RecoveryRequest> pending = new HashSet<>();

    RecoveredSuspensions(Consumer<RecoveryRequest> requests) {
        this.requests = Objects.requireNonNull(requests, "requests");
    }

    ObservableList<RecoveredSuspension> rows() {
        return rowsView;
    }

    /** Whether the page shows this: while there is an entry, or the message of the last request. */
    ReadOnlyBooleanProperty shownProperty() {
        return shown.getReadOnlyProperty();
    }

    /** Whether every journal of an earlier session has been read. */
    ReadOnlyObjectProperty<RecoveryCheck> checkProperty() {
        return check.getReadOnlyProperty();
    }

    /** Whether the summary beside the filter shows: while there is an entry, or the list may be incomplete. */
    ReadOnlyBooleanProperty summaryShownProperty() {
        return summaryShown.getReadOnlyProperty();
    }

    ResultLineState line() {
        return line;
    }

    /** Whether a request about the process is on its way. */
    boolean pending(RecoveredSuspension entry) {
        return pending.stream().anyMatch(request -> request.identity().equals(entry.identity()));
    }

    /** The list as the coordinator has it now. */
    void show(List<RecoveredSuspension> entries) {
        List<RecoveredSuspension> now = List.copyOf(entries);
        if (!now.equals(rows)) {
            rows.setAll(now);
        }
        refresh();
    }

    /** Whether the list is whole, as the runtime has it now. */
    void showCheck(RecoveryCheck now) {
        check.set(Objects.requireNonNull(now, "now"));
        refresh();
    }

    /** Asks to resume a listed process; the caller has had the user confirm it. */
    void resume(RecoveredSuspension entry) {
        if (!entry.resumable()) {
            return;
        }
        send(new RecoveryRequest(RecoveryRequest.Action.RESUME, entry.identity(), entry.name()));
    }

    /** Asks to close an entry, leaving its process as it is. */
    void dismiss(RecoveredSuspension entry) {
        send(new RecoveryRequest(RecoveryRequest.Action.DISMISS, entry.identity(), entry.name()));
    }

    void accept(RecoveryResult result) {
        if (!pending.remove(result.request())) {
            return;
        }
        line.showResult(result);
        refresh();
        line.arrived();
    }

    private void send(RecoveryRequest request) {
        for (RecoveryRequest waiting : pending) {
            if (waiting.identity().equals(request.identity())) {
                return;
            }
        }
        pending.add(request);
        line.showText(inProgress(request));
        refresh();
        requests.accept(request);
    }

    private void refresh() {
        shown.set(!rows.isEmpty() || !line.wholeProperty().get().isEmpty());
        summaryShown.set(!rows.isEmpty() || check.get() != RecoveryCheck.COMPLETE);
    }

    /**
     * The summary beside the filter, short enough for the smallest window: with entries and an
     * incomplete list it says how many are listed, and the tooltip and the dialog say what they are.
     */
    static String summary(int count, RecoveryCheck check) {
        return switch (check) {
            case COMPLETE -> count + " possibly left suspended";
            case PENDING -> count == 0 ? "Checking an earlier session…" : count + " listed; checking more…";
            case EXHAUSTED ->
                count == 0 ? "Earlier session not checked; restart Prism" : count + " listed; list incomplete";
        };
    }

    /** Why the list may be incomplete, in full; empty when it is whole. */
    static String incompleteness(RecoveryCheck check) {
        return switch (check) {
            case COMPLETE -> "";
            case PENDING ->
                "A journal of an earlier session of Prism cannot be read yet: that session has ended, but the"
                        + " system still holds its lock. Until Prism has read it, this list may be incomplete."
                        + " Prism keeps trying for about two minutes.";
            case EXHAUSTED ->
                "A journal of an earlier session could not be read, so this list may be incomplete. Its file"
                        + " is kept: restarting Prism checks it again.";
        };
    }

    /** The whole of the summary, for its tooltip and for a screen reader. */
    static String summaryInFull(int count, RecoveryCheck check) {
        String listed = count == 0
                ? ""
                : count + " process(es) an earlier session of Prism may have left suspended. Prism resumes none of"
                        + " them by itself.";
        String why = incompleteness(check);
        return listed.isEmpty() ? why : why.isEmpty() ? listed : listed + " " + why;
    }

    static String inProgress(RecoveryRequest request) {
        String target = request.name() + " (PID " + request.identity().pid() + ")";
        return switch (request.action()) {
            case RESUME -> "Checking and resuming " + target + "…";
            case DISMISS -> "Dismissing the entry for " + target + "…";
        };
    }

    /** Why the earlier session held the process, in words. */
    static String reasons(Set<SuspensionReason> reasons) {
        List<String> words = new ArrayList<>();
        for (SuspensionReason reason : SuspensionReason.values()) {
            if (reasons.contains(reason)) {
                words.add(
                        switch (reason) {
                            case USER -> "suspended by you";
                            case CPU_LIMITER -> "CPU limit";
                            case OOM_GUARD -> "OOM Guard";
                        });
            }
        }
        if (words.isEmpty()) {
            return "incomplete suspension";
        }
        String joined = String.join(", ", words);
        return joined.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + joined.substring(1);
    }

    /** The heading of the confirmation of Resume. */
    static String confirmHeading() {
        return "Resume this process?";
    }

    /**
     * What the confirmation of Resume says first: the stop it ends may not be Prism's. On Windows a
     * suspension is a count without an owner, so another program may have added one.
     */
    static String confirmConsequence(Optional<ControlMechanism> resume) {
        boolean threads =
                resume.filter(ControlMechanism.PER_THREAD_SUSPENSION::equals).isPresent();
        return "Prism checks it first, but the stop may not be Prism's. Resuming may end a stop another program"
                + " made after Prism closed" + (threads ? ", or remove a suspension another program added." : ".");
    }
}
