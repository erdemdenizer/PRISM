package prism.srm.ui.processes;

import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Text;

/**
 * Gives a label or a cell a tooltip with its text in full, but only while the text does not fit
 * and is cut with an ellipsis. A text that can be read as it stands gets no tooltip.
 *
 * <p>The tooltip and the node that measures the text are created once, with the control; a change
 * of the text or of the width only sets them.
 */
final class CutTextTooltip {

    /**
     * The most a tooltip shows. A command line can be tens of thousands of characters long, and a
     * tooltip of that size would not fit on any screen.
     */
    static final int LONGEST = 2000;

    private static final double WIDEST = 640;

    private final Labeled control;
    private final Tooltip full = new Tooltip();
    private final Text measure = new Text();

    private CutTextTooltip(Labeled control) {
        this.control = control;
        full.setWrapText(true);
        full.setMaxWidth(WIDEST);
        measure.fontProperty().bind(control.fontProperty());
    }

    /** Keeps the tooltip of a control in step with its text and its width from now on. */
    static void install(Labeled control) {
        CutTextTooltip tooltip = new CutTextTooltip(control);
        control.textProperty().addListener(observable -> tooltip.refresh());
        control.widthProperty().addListener(observable -> tooltip.refresh());
        control.fontProperty().addListener(observable -> tooltip.refresh());
        tooltip.refresh();
    }

    private void refresh() {
        String text = control.getText();
        if (text == null || text.isEmpty() || fits(text)) {
            control.setTooltip(null);
            return;
        }
        full.setText(shortened(text));
        control.setTooltip(full);
    }

    private boolean fits(String text) {
        measure.setText(text);
        double room = control.getWidth() - control.snappedLeftInset() - control.snappedRightInset();
        return measure.getLayoutBounds().getWidth() <= room;
    }

    /** The text for the tooltip: in full, or its beginning with a count of what was left out. */
    static String shortened(String text) {
        if (text.length() <= LONGEST) {
            return text;
        }
        return text.substring(0, LONGEST) + "… (" + (text.length() - LONGEST) + " more characters)";
    }
}
