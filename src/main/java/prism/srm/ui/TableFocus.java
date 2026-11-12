package prism.srm.ui;

import javafx.beans.InvalidationListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.control.TableView;

/**
 * Where the keyboard is in a table (fix round H, keyboard acceptance KB-2): on a row, which draws its
 * ring (table.css), or on the table itself while it has rows but none of them has the focus, as when
 * its rows came after its focus model was reset. Then the table takes the pseudo-class
 * {@code :no-focused-row}, and the stylesheet draws the focus ring of every control around its frame,
 * so a Tab stop never draws nothing. An empty table does not take it: its placeholder draws the ring
 * (table.css, KB-1), and one ring is enough. The behavior of the table is left as it is: no row is
 * focused or selected for it.
 */
public final class TableFocus {

    /** On a table that has rows while none of them has the focus. */
    public static final PseudoClass NO_FOCUSED_ROW = PseudoClass.getPseudoClass("no-focused-row");

    private TableFocus() {}

    /** Keeps {@link #NO_FOCUSED_ROW} on the table up to date. Call on the JavaFX Application Thread. */
    public static void track(TableView<?> table) {
        InvalidationListener update = observable -> update(table);
        table.getFocusModel().focusedIndexProperty().addListener(update);
        table.itemsProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.removeListener(update);
            }
            if (after != null) {
                after.addListener(update);
            }
            update(table);
        });
        ObservableList<?> items = table.getItems();
        if (items != null) {
            items.addListener(update);
        }
        update(table);
    }

    private static void update(TableView<?> table) {
        int index = table.getFocusModel().getFocusedIndex();
        int rows = table.getItems() == null ? 0 : table.getItems().size();
        table.pseudoClassStateChanged(NO_FOCUSED_ROW, rows > 0 && (index < 0 || index >= rows));
    }
}
