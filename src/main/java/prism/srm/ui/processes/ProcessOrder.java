package prism.srm.ui.processes;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;

/**
 * The order of the rows of the process table.
 *
 * <p>The order is total and the same every time: rows are compared by the columns chosen, one
 * after the other, and rows that are equal in all of them by PID and then by start time. Two rows
 * never compare as equal, so a table that is sorted again after its values changed cannot shuffle
 * rows that did not change.
 *
 * <p>A process without a value in a column comes after every process that has one, whichever way
 * the column is sorted: the missing values stay together at the end and never stand between the
 * largest and the smallest.
 */
final class ProcessOrder {

    /**
     * One column to sort by.
     *
     * @param descending whether the largest value comes first
     */
    record SortKey(ProcessColumn column, boolean descending) {

        SortKey {
            Objects.requireNonNull(column, "column");
        }
    }

    private static final Comparator<ProcessRow> BY_IDENTITY = (a, b) -> {
        ProcessIdentity first = a.identity();
        ProcessIdentity second = b.identity();
        int byPid = Long.compare(first.pid(), second.pid());
        return byPid != 0 ? byPid : first.startTime().compareTo(second.startTime());
    };

    private ProcessOrder() {}

    /**
     * @param keys the columns to sort by, the most important first; none sorts by PID
     */
    static Comparator<ProcessRow> comparator(List<SortKey> keys) {
        List<SortKey> columns = List.copyOf(keys);
        return (a, b) -> {
            ProcessSnapshot first = a.snapshot();
            ProcessSnapshot second = b.snapshot();
            for (SortKey key : columns) {
                int byColumn = compare(key, first, second);
                if (byColumn != 0) {
                    return byColumn;
                }
            }
            return BY_IDENTITY.compare(a, b);
        };
    }

    private static int compare(SortKey key, ProcessSnapshot first, ProcessSnapshot second) {
        boolean firstHasValue = key.column().hasValue(first);
        boolean secondHasValue = key.column().hasValue(second);
        if (!firstHasValue || !secondHasValue) {
            // The one without a value last; the direction does not turn this around.
            return Boolean.compare(secondHasValue, firstHasValue);
        }
        int byValue = key.column().compareValues(first, second);
        return key.descending() ? -byValue : byValue;
    }
}
