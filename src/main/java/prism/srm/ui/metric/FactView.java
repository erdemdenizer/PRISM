package prism.srm.ui.metric;

import java.util.Objects;
import javafx.css.PseudoClass;
import javafx.scene.AccessibleAction;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * One fact about the system: a small caption with the value below it. A text that stands in for
 * the fact ("Reading...", "Unavailable") is set back, like the same words in the metric panels.
 */
public final class FactView extends VBox {

    private static final PseudoClass PLACEHOLDER = PseudoClass.getPseudoClass("placeholder");

    private final String name;
    private final FactReadout readout;

    /** What a click, Enter or Space on the fact opens; {@code null} while it opens nothing. */
    private Runnable open;

    /**
     * @param whenKnown the tooltip of the value while it is the fact itself; {@code null} for none
     */
    public FactView(String name, FactReadout readout, Tooltip whenKnown) {
        this.name = name;
        this.readout = readout;
        getStyleClass().add("fact");

        Label caption = new Label(name);
        caption.getStyleClass().addAll("text-caption", "text-muted");

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

    /**
     * Makes the fact lead to the page that has it in full (UI-1): the fact is a button. Call once,
     * on the JavaFX Application Thread.
     *
     * @param target the page it opens, as an assistive technology says it: "Limits"
     * @param onOpen opens that page
     */
    public void openWith(String target, Runnable onOpen) {
        if (open != null) {
            throw new IllegalStateException("The fact opens a page already: " + name);
        }
        Objects.requireNonNull(target, "target");
        open = Objects.requireNonNull(onOpen, "onOpen");
        OpenablePart.install(this, onOpen);
        // The words follow the fact: "Managed: 2. Open Limits".
        readout.textProperty()
                .addListener(
                        (observable, before, after) -> setAccessibleText(name + ": " + after + ". Open " + target));
        setAccessibleText(name + ": " + readout.textProperty().get() + ". Open " + target);
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE && open != null) {
            open.run();
        } else {
            super.executeAccessibleAction(action, parameters);
        }
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
