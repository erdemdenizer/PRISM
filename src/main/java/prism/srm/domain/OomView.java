package prism.srm.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the OOM Guard page, the Overview and the status bar show (UI-5, UI-1), at one moment. Made by
 * one producer only, the limiter thread, and never changed afterwards.
 *
 * @param monitoring whether OOM monitoring is on
 * @param state the memory pressure state; NORMAL while monitoring is off
 * @param reading the last memory reading; empty when it was unavailable
 * @param readAt when that reading was taken, by the wall clock
 * @param since when the state was entered; empty for NORMAL since start-up or while monitoring is off
 * @param candidates the Managed Processes, in the order OOM Guard would act on them
 * @param actions the latest automatic actions, the newest first, at most {@link #MOST_ACTIONS}
 * @param notice a sentence the page states beside the state, such as an ignored floor; empty when none
 * @param monitorEnded system monitoring has ended for the session: OOM Guard reads nothing more
 * @param availability why each automatic switch cannot be used on this system; empty for one that can
 */
public record OomView(
        boolean monitoring,
        OomState state,
        Optional<OomReading> reading,
        Optional<Instant> readAt,
        OomSettings settings,
        Optional<Instant> since,
        List<OomCandidateView> candidates,
        List<OomActionRecord> actions,
        Optional<String> notice,
        boolean monitorEnded,
        Restorability restorability,
        OomSignalStatus signal,
        Availability availability) {

    /** The most actions a view carries. */
    public static final int MOST_ACTIONS = 50;

    /**
     * Why each automatic switch is unavailable on this system (CAP-2): the page shows it disabled with
     * the reason. Empty for a switch that can be used.
     *
     * @param journal why a suspension would not be in effect now: the suspension journal cannot be
     *     written; empty while it can
     */
    public record Availability(
            Optional<String> priority,
            Optional<String> suspend,
            Optional<String> termination,
            Optional<String> journal) {

        public static final Availability ALL =
                new Availability(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        public Availability {
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(suspend, "suspend");
            Objects.requireNonNull(termination, "termination");
            Objects.requireNonNull(journal, "journal");
        }
    }

    /** Before OOM Guard has seen a sample. */
    public static OomView starting(OomSettings settings) {
        return new OomView(
                settings.switches().monitoring(),
                OomState.NORMAL,
                Optional.empty(),
                Optional.empty(),
                settings,
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty(),
                false,
                Restorability.NOT_REVERSIBLE,
                OomSignalStatus.UNVALIDATED,
                Availability.ALL);
    }

    public OomView {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reading, "reading");
        Objects.requireNonNull(readAt, "readAt");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(since, "since");
        candidates = List.copyOf(candidates);
        actions = List.copyOf(actions);
        Objects.requireNonNull(notice, "notice");
        Objects.requireNonNull(restorability, "restorability");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(availability, "availability");
        if (actions.size() > MOST_ACTIONS) {
            throw new IllegalArgumentException("At most " + MOST_ACTIONS + " actions: " + actions.size());
        }
        if (!monitoring && state != OomState.NORMAL) {
            throw new IllegalArgumentException("Without monitoring the state is NORMAL: " + state);
        }
    }

    /** How many Managed Processes have OOM management on. */
    public long underOomManagement() {
        return candidates.stream()
                .filter(candidate -> candidate.oom().managed())
                .count();
    }
}
