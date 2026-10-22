package prism.srm.ui.metric;

import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * One fact about the system: a small caption with the value below it. A text that stands in for
 * the fact ("Reading...", "Unavailable") is set back, like the same words in the metric panels.
 */
public final class FactView extends VBox {

    private static final PseudoClass PLACEHOLDER = PseudoClass.getPseudoClass("placeholder");

    /**
     * @param whenKnown the tooltip of the value while it is the fact itself; {@code null} for none
     */
    public FactView(String name, FactReadout readout, Tooltip whenKnown) {
        getStyleClass().add("fact");

        Label caption = new Label(name);
        caption.getStyleClass().addAll("text-small", "text-muted");

        Label value = new Label();
        value.textProperty().bind(readout.textProperty());
        value.getStyleClass().add("fact-value");
        Tooltip reason = new Tooltip(MetricPanel.UNAVAILABLE_REASON);
        showState(value, readout.stateProperty().get(), whenKnown, reason);
        readout.stateProperty().addListener((observable, before, after) -> showState(value, after, whenKnown, reason));

        getChildren().setAll(caption, value);
    }

    /** A fact whose text may be cut with an ellipsis; the tooltip then has it in full. */
    public static FactView truncating(String name, FactReadout readout) {
        Tooltip full = new Tooltip();
        full.textProperty().bind(readout.textProperty());
        return new FactView(name, readout, full);
    }

    private static void showState(Label value, MetricState state, Tooltip whenKnown, Tooltip whenUnavailable) {
        value.pseudoClassStateChanged(PLACEHOLDER, state != MetricState.LIVE);
        value.setTooltip(
                switch (state) {
                    case LIVE -> whenKnown;
                    case WAITING -> null;
                    case UNAVAILABLE -> whenUnavailable;
                });
    }
}
