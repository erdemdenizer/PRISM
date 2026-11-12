package prism.srm.ui.control;

import javafx.animation.FadeTransition;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import prism.srm.ui.Motion;

/**
 * One line about an action: what came of it, then, muted, what that does not say; or, as one
 * muted part, the action on its way or the reason the actions are disabled. The first part keeps
 * its room: where the line is too narrow, the second part goes first, cut and then left out, and
 * only then is the first cut. A tooltip carries the whole line while any of it is cut, and a
 * screen reader always gets the whole line. Its rules are in {@code control.css}.
 *
 * <p>A new result fades in over {@link Motion#CROSSFADE_MS}: it marks that something came, also
 * when it reads like the one before. Nothing else moves, nothing waits on a timer, and with
 * reduced motion the result is there at once.
 */
public final class ActionResultLine extends Region {

    private static final PseudoClass SUCCESS = PseudoClass.getPseudoClass("success");
    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private static final PseudoClass FAILURE = PseudoClass.getPseudoClass("failure");

    /** The space between the two parts. */
    private static final double GAP = 4;

    /** Less room than this, and the second part is left out rather than cut to a few letters. */
    private static final double SHORTEST_EXPLANATION = 48;

    private final Label headline = new Label();
    private final Label explanation = new Label();
    private final Tooltip whole = new Tooltip();
    private FadeTransition arriving;
    private boolean tooltipShown;

    /** Draws the state; each result it counts fades the line in. */
    public ActionResultLine(ResultLineState state) {
        ReadOnlyStringProperty headlineText = state.headlineProperty();
        ReadOnlyStringProperty explanationText = state.explanationProperty();
        ReadOnlyObjectProperty<ResultTone> tone = state.toneProperty();
        ReadOnlyStringProperty wholeText = state.wholeProperty();
        ObservableValue<? extends Number> newResult = state.resultsProperty();
        getStyleClass().add("action-result");
        headline.getStyleClass().addAll("text-small", "action-result-headline");
        explanation.getStyleClass().addAll("text-small", "text-muted", "action-result-explanation");
        headline.textProperty().bind(headlineText);
        // Cut in the middle: a long name gives way, while what happened and the PID stay in view.
        headline.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
        explanation.textProperty().bind(explanationText);
        whole.setWrapText(true);
        whole.setMaxWidth(640);
        whole.textProperty().bind(wholeText);
        setAccessibleRole(AccessibleRole.TEXT);
        accessibleTextProperty().bind(wholeText);
        showTone(tone.get());
        tone.addListener((observable, before, after) -> showTone(after));
        newResult.addListener((observable, before, after) -> arrive());
        getChildren().setAll(headline, explanation);
        setMinWidth(0);
    }

    private void showTone(ResultTone tone) {
        headline.pseudoClassStateChanged(SUCCESS, tone == ResultTone.SUCCESS);
        headline.pseudoClassStateChanged(WARNING, tone == ResultTone.WARNING);
        headline.pseudoClassStateChanged(FAILURE, tone == ResultTone.FAILURE);
    }

    /** Marks the arrival of a result: a finite fade, none with reduced motion. */
    private void arrive() {
        if (arriving != null) {
            arriving.stop();
        }
        Duration duration = Motion.of(this, Motion.CROSSFADE_MS);
        if (duration.equals(Duration.ZERO)) {
            arriving = null;
            setOpacity(1);
            return;
        }
        arriving = new FadeTransition(duration, this);
        // From the first frame: without this the result shows whole for a frame before it fades in.
        setOpacity(0);
        arriving.setFromValue(0);
        arriving.setToValue(1);
        arriving.setOnFinished(event -> arriving = null);
        arriving.play();
    }

    /** Whether the tooltip with the whole line is installed, which it is while any of it is cut; for tests. */
    public boolean tooltipShown() {
        return tooltipShown;
    }

    /** Whether a fade is running; for tests. */
    public boolean arriving() {
        return arriving != null;
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computePrefWidth(double height) {
        double first = headline.prefWidth(-1);
        double second = explanation.getText().isEmpty() ? 0 : GAP + explanation.prefWidth(-1);
        return snappedLeftInset() + first + second + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + Math.max(headline.prefHeight(-1), explanation.prefHeight(-1)) + snappedBottomInset();
    }

    @Override
    protected void layoutChildren() {
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double room = getWidth() - left - snappedRightInset();
        double height = getHeight() - top - snappedBottomInset();
        double first = Math.min(headline.prefWidth(-1), room);
        layoutInArea(headline, left, top, first, height, 0, HPos.LEFT, VPos.CENTER);
        double rest = room - first - GAP;
        boolean secondShown = !explanation.getText().isEmpty() && rest >= SHORTEST_EXPLANATION;
        explanation.setVisible(secondShown);
        if (secondShown) {
            double second = Math.min(explanation.prefWidth(-1), rest);
            layoutInArea(explanation, left + first + GAP, top, second, height, 0, HPos.LEFT, VPos.CENTER);
        }
        boolean cut = headline.prefWidth(-1) > room
                || !explanation.getText().isEmpty() && (!secondShown || explanation.prefWidth(-1) > rest);
        if (cut != tooltipShown) {
            tooltipShown = cut;
            if (cut) {
                Tooltip.install(this, whole);
            } else {
                Tooltip.uninstall(this, whole);
            }
        }
    }
}
