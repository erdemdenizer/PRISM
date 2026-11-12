package prism.srm.ui.metric;

import java.util.Objects;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * Makes a part of a summary page lead to the page that has it in full (UI-1, "Each part leads to
 * its page"): the part behaves as a button. It is a stop in the Tab order, a click, Enter or Space
 * opens the page, the pointer becomes a hand over it, and an assistive technology reads it as a
 * button. Its look under the pointer and with the keyboard focus is in the stylesheet, under the
 * style class {@code openable}.
 */
final class OpenablePart {

    static final String STYLE_CLASS = "openable";

    private OpenablePart() {}

    /**
     * @param part the part; it must not be a stop in the Tab order already
     * @param open opens the page of the part
     */
    static void install(Region part, Runnable open) {
        Objects.requireNonNull(open, "open");
        part.getStyleClass().add(STYLE_CLASS);
        part.setFocusTraversable(true);
        part.setAccessibleRole(AccessibleRole.BUTTON);
        part.setCursor(Cursor.HAND);
        part.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            // A drag that ends on the part, as when the pointer reads a graph, is not a click.
            if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                open.run();
                event.consume();
            }
        });
        part.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (isActivation(event)) {
                open.run();
                event.consume();
            }
        });
    }

    /** Whether a key opens a part: Enter or Space, without a modifier. */
    static boolean isActivation(KeyEvent event) {
        return (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)
                && !event.isControlDown()
                && !event.isAltDown()
                && !event.isMetaDown()
                && !event.isShiftDown();
    }
}
