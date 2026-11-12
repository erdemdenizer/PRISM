package prism.srm.domain;

import java.util.Comparator;
import java.util.OptionalLong;

/**
 * The order in which OOM Guard takes Managed Processes in an emergency (OOM-10): by OOM priority, then
 * by resident memory, the largest first and an unknown one last, then by PID and start time, so that
 * the order is total and the same on every evaluation.
 */
public final class OomCandidateOrder {

    public static final Comparator<OomCandidateView> ORDER = Comparator.comparing(
                    (OomCandidateView candidate) -> candidate.oom().priority())
            .thenComparing(OomCandidateView::residentBytes, OomCandidateOrder::largestFirst)
            .thenComparingLong(candidate -> candidate.identity().pid())
            .thenComparing(candidate -> candidate.identity().startTime());

    private OomCandidateOrder() {}

    private static int largestFirst(OptionalLong a, OptionalLong b) {
        if (a.isPresent() && b.isPresent()) {
            return Long.compare(b.getAsLong(), a.getAsLong());
        }
        if (a.isPresent() != b.isPresent()) {
            return a.isPresent() ? -1 : 1;
        }
        return 0;
    }
}
