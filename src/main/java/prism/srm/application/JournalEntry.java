package prism.srm.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * One process in the suspension journal (LIFE-6, ADR-0011): why Prism holds it or may hold it
 * suspended, written before Prism suspends it for those reasons and kept until it is let go.
 *
 * @param reasons why Prism holds it or may hold it: the user's suspension, a CPU limit in force
 *     (written when the limit starts, not on every cycle), OOM Guard; empty for a suspension that
 *     failed part way and still holds threads ({@code incomplete})
 * @param memento which units the suspension holds, as the platform recorded them (Windows: the
 *     threads); empty where the identity is the unit, or where it was not recorded
 * @param cause what brought the suspension about, for whoever reads the entry later
 * @param writer which session of Prism wrote it
 * @param incomplete part of a suspension Prism made could not be undone and is still held
 * @param carried listed after a crash of an earlier session and carried into this one's journal,
 *     so that it survives another crash until the user acts on it
 */
public record JournalEntry(
        ProcessIdentity identity,
        String name,
        Set<SuspensionReason> reasons,
        Optional<String> memento,
        Optional<String> cause,
        String writer,
        Instant created,
        Instant updated,
        boolean incomplete,
        boolean carried) {

    public JournalEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        reasons = reasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(reasons));
        Objects.requireNonNull(memento, "memento");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(updated, "updated");
    }

    /** The longest name shown or logged of an entry read from a file. */
    static final int DISPLAYED_NAME = 256;

    /**
     * The name on one line and of bounded length: an entry is read from a file, and its name must
     * not make a line of the log, or of the page, look like another.
     */
    public String displayName() {
        StringBuilder shown = new StringBuilder(Math.min(name.length(), DISPLAYED_NAME));
        for (int i = 0; i < name.length() && shown.length() < DISPLAYED_NAME; i++) {
            char c = name.charAt(i);
            shown.append(Character.isISOControl(c) ? ' ' : c);
        }
        return shown.toString();
    }

    /** The whole entry in words, for the log and for whoever decides by hand. */
    public String describe() {
        return "PID " + identity.pid() + " started " + identity.startTime() + " (" + displayName() + "): reasons "
                + (reasons.isEmpty() ? "none" : reasons) + (incomplete ? ", part of a suspension still held" : "")
                + "; threads recorded: " + (memento.isPresent() ? "yes" : "no")
                + cause.map(text -> "; cause: " + text).orElse("") + "; written by " + writer + ", created "
                + created + ", updated " + updated + (carried ? "; carried over from an earlier session" : "");
    }
}
