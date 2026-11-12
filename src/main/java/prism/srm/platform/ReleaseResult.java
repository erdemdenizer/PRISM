package prism.srm.platform;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import prism.srm.domain.ProcessIdentity;

/**
 * What the shutdown's clearing of native limits came to (milestone 10, external audit item 3).
 * An empty result is never proof that everything was cleared: a clearing that could not begin in
 * time says so, with the holds known then.
 *
 * @param cleared each process the clearing reached, and whether its limits were verified as cleared
 * @param notReached the processes whose jobs were held but not reached, because the limiter was busy
 *     past the bound
 * @param timedOut whether the limiter was busy past the bound; then an operation in flight may have
 *     made a hold that is not in {@code notReached}
 */
public record ReleaseResult(Map<ProcessIdentity, Boolean> cleared, Set<ProcessIdentity> notReached, boolean timedOut) {

    public ReleaseResult {
        cleared = Map.copyOf(cleared);
        notReached = Set.copyOf(notReached);
        if (!timedOut && !notReached.isEmpty()) {
            throw new IllegalArgumentException(
                    "Holds are left unreached only when the limiter was busy past the bound");
        }
    }

    /** Nothing was held, and nothing is left. */
    public static final ReleaseResult NOTHING = new ReleaseResult(Map.of(), Set.of(), false);

    /** The clearing could not begin in time; these holds were known then. */
    public static ReleaseResult timedOut(Set<ProcessIdentity> known) {
        return new ReleaseResult(Map.of(), Objects.requireNonNull(known, "known"), true);
    }

    /** How many limits are known not to be verified as cleared; more may be when {@link #timedOut}. */
    public int notCleared() {
        return (int) cleared.values().stream().filter(none -> !none).count() + notReached.size();
    }

    /** Whether every limit is known to be cleared. */
    public boolean complete() {
        return !timedOut && notCleared() == 0;
    }
}
