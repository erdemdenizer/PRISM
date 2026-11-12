package prism.srm.ui.snapshot;

import java.util.function.Consumer;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.text.Text;

/**
 * Shows a dialog in its own window, as the application does, and reports whether everything in it
 * fits the window it was sized to (UI audit LO-17): every button inside the scene with its words
 * drawn whole, and the content inside the scene. The window is given a render scale (1.0 for 100 %,
 * 1.25 for 125 %), so that the layout snaps to the device pixels of that scale before the window is
 * sized to its scene again, as on a screen of that scale.
 */
public final class DialogFit {

    private DialogFit() {}

    /** What a shown dialog measured. */
    public record Fit(
            double renderScale,
            double sceneWidth,
            double sceneHeight,
            boolean buttonsInside,
            boolean wordsWhole,
            boolean contentInside) {

        /** Whether everything fits. */
        public boolean fits() {
            return buttonsInside && wordsWhole && contentInside;
        }
    }

    /**
     * Shows the dialog, applies {@code change} to it while it shows (a choice that brings a line, an
     * invalid value), measures it and closes it. Call on the JavaFX Application Thread.
     */
    public static <T> Fit measure(Dialog<T> dialog, Consumer<Dialog<T>> change, double scale) {
        dialog.show();
        try {
            DialogPane pane = dialog.getDialogPane();
            javafx.stage.Window window = pane.getScene().getWindow();
            window.setRenderScaleX(scale);
            window.setRenderScaleY(scale);
            window.sizeToScene();
            change.accept(dialog);
            for (int pass = 0; pass < 3; pass++) {
                pane.applyCss();
                pane.layout();
            }
            double width = pane.getScene().getWidth();
            double height = pane.getScene().getHeight();
            boolean inside = true;
            boolean whole = true;
            for (ButtonType type : pane.getButtonTypes()) {
                Button button = (Button) pane.lookupButton(type);
                Bounds bounds = button.localToScene(button.getBoundsInLocal());
                inside &= bounds.getMinX() >= 0
                        && bounds.getMinY() >= 0
                        && bounds.getMaxX() <= width + 0.5
                        && bounds.getMaxY() <= height + 0.5;
                // What the button draws: JavaFX shortens the words it has no room for ("Can…").
                whole &= ((Text) button.lookup(".text")).getText().equals(button.getText());
            }
            Node content = pane.getContent();
            Bounds contentBounds = content.localToScene(content.getLayoutBounds());
            boolean contentInside = contentBounds.getMaxX() <= width + 0.5 && contentBounds.getMaxY() <= height + 0.5;
            return new Fit(pane.getScene().getWindow().getRenderScaleX(), width, height, inside, whole, contentInside);
        } finally {
            dialog.close();
        }
    }
}
