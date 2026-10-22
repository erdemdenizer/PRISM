package prism.srm.ui.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javafx.collections.ObservableList;

/**
 * Keeps the rows of a table in step with the devices of a snapshot.
 *
 * <p>While the devices are the same ones in the same order, which is the case from one cycle to
 * the next, the rows stay and only their values are set: the table keeps its cells, its selection
 * and its scroll position. Only when a device appears, disappears or changes its place are the
 * rows replaced.
 */
final class RowSync {

    private RowSync() {}

    /**
     * @param rows the rows of the table
     * @param items the devices of the snapshot, in the order to show them
     * @param rowKey what identifies the device of a row
     * @param itemKey what identifies a device
     * @param newRow creates the row of a device; its values are set by {@code update}
     * @param update sets the values of a row from its device
     */
    static <T, R> void sync(
            ObservableList<R> rows,
            List<T> items,
            Function<R, String> rowKey,
            Function<T, String> itemKey,
            Function<T, R> newRow,
            BiConsumer<R, T> update) {
        if (sameDevices(rows, items, rowKey, itemKey)) {
            for (int index = 0; index < items.size(); index++) {
                update.accept(rows.get(index), items.get(index));
            }
            return;
        }
        List<R> fresh = new ArrayList<>(items.size());
        for (T item : items) {
            R row = newRow.apply(item);
            update.accept(row, item);
            fresh.add(row);
        }
        rows.setAll(fresh);
    }

    private static <T, R> boolean sameDevices(
            List<R> rows, List<T> items, Function<R, String> rowKey, Function<T, String> itemKey) {
        if (rows.size() != items.size()) {
            return false;
        }
        for (int index = 0; index < items.size(); index++) {
            if (!rowKey.apply(rows.get(index)).equals(itemKey.apply(items.get(index)))) {
                return false;
            }
        }
        return true;
    }
}
