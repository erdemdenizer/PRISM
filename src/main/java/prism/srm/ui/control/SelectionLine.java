package prism.srm.ui.control;

import java.util.Objects;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * One line under a table whose inspector is a sheet over the page (fix round G, PR-02, PR-07, LO-09):
 * the name and the PID of the selected process, then the line about the last action on it. It stays
 * while the sheet is closed, so a result that comes after the sheet closed, or of an action taken from
 * the context menu, is on screen; and the name says that the selection has details to open.
 *
 * <p>Layout and binding only: the texts and the result are the page's. A long name gives way to the PID
 * and to the result, cut with its whole in a tooltip.
 */
public final class SelectionLine extends HBox {

    private final ActionResultLine result;

    /**
     * @param name the name of the selected process, or the words for none
     * @param pid its PID as text, or empty
     * @param state the line about the last action on it
     */
    public SelectionLine(ObservableValue<String> name, ObservableValue<String> pid, ResultLineState state) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pid, "pid");
        Objects.requireNonNull(state, "state");
        getStyleClass().add("selection-line");
        Label shown = new Label();
        shown.textProperty().bind(name);
        shown.getStyleClass().add("selection-line-name");
        shown.setMinWidth(0);
        CutTextTooltip.install(shown);
        Label number = new Label();
        number.textProperty().bind(pid);
        number.getStyleClass().addAll("text-small", "text-muted", "selection-line-pid");
        number.setMinWidth(Region.USE_PREF_SIZE);
        result = new ActionResultLine(state);
        result.getStyleClass().add("selection-line-result");
        // The result keeps the room it needs; the name gives way first, down to a few letters.
        shown.setMaxWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(result, Priority.ALWAYS);
        result.setMaxWidth(Double.MAX_VALUE);
        getChildren().setAll(shown, number, result);
        setMinWidth(0);
    }
}
