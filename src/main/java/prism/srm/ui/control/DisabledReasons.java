package prism.srm.ui.control;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javafx.beans.Observable;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * Why the controls of a group are disabled, as text on screen (CAP-2): a disabled control receives no
 * pointer events and shows no tooltip of its own, and the keyboard focus shows none either, so a reason
 * only in a tooltip on the control could be read by nobody.
 *
 * <p>Each control stands in a holder that carries its tooltip, whether the control is enabled or not
 * (what it does, or why it cannot), and the same text is the help a screen reader says for it. This line
 * lists the reasons of the controls that are disabled now, each reason once, in the order of the
 * controls; it takes no room while every control is enabled. When a control is enabled or disabled
 * changes nothing here: that is the page's.
 */
public final class DisabledReasons extends Label {

    private final List<ButtonBase> controls = new ArrayList<>();

    public DisabledReasons() {
        getStyleClass().addAll("disabled-reasons", "text-small", "text-muted");
        setWrapText(true);
        setMinHeight(Region.USE_PREF_SIZE);
        // As wide as what holds it, so that a rule drawn on it spans the group, not the words (LO-07).
        setMaxWidth(Double.MAX_VALUE);
        visibleProperty().bind(textProperty().isNotEmpty());
        managedProperty().bind(visibleProperty());
    }

    /**
     * Puts a control in a holder that shows its explanation as a tooltip, enabled or not, makes the
     * explanation its help for a screen reader, and lists its reason here while it is disabled. The
     * holder is shown and takes room only while the control does.
     *
     * @param explanation what the control does, or why it is disabled
     */
    public StackPane hold(ButtonBase control, ObservableValue<String> explanation) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(explanation, "explanation");
        control.accessibleHelpProperty().bind(explanation);
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(explanation);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(400);
        tooltip.setShowDuration(Duration.seconds(10));
        StackPane holder = new StackPane(control);
        holder.getStyleClass().add("action-holder");
        holder.setMinWidth(Region.USE_PREF_SIZE);
        holder.visibleProperty().bind(control.visibleProperty());
        holder.managedProperty().bind(control.managedProperty());
        Tooltip.install(holder, tooltip);
        controls.add(control);
        control.disabledProperty().addListener(this::refresh);
        control.visibleProperty().addListener(this::refresh);
        control.accessibleHelpProperty().addListener(this::refresh);
        refresh(null);
        return holder;
    }

    private void refresh(Observable ignored) {
        Set<String> distinct = new LinkedHashSet<>();
        for (ButtonBase control : controls) {
            String reason = control.getAccessibleHelp();
            if (control.isVisible() && control.isDisabled() && reason != null && !reason.isBlank()) {
                distinct.add(reason.strip());
            }
        }
        setText(String.join(" ", distinct));
    }
}
