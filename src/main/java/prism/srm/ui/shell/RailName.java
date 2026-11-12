package prism.srm.ui.shell;

import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.stage.PopupWindow.AnchorLocation;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * The name of an entry of the compact rail, shown beside the rail (SH-01, SH-03): one label for the
 * mouse and the keyboard, so there is never more than one and never one left over.
 *
 * <p>It is a tooltip that is not installed on any entry, so the stock tooltip behavior, which shows a
 * tooltip at the pointer (10 px right of it and 7 px below), never handles it. It stands at a place
 * fixed by the entry and the rail: {@link #GAP} right of the rail's edge, centred on the entry, so it can
 * never cover the pointer while the pointer is on an entry. A popup that covered the pointer took the
 * pointer from the window, the entry lost its hover, the name went, and came back when the pointer was
 * on the entry again: it blinked under a still pointer. It has no shadow (app.css, {@code .nav-name}): a
 * shadow makes the popup window larger than the label, and that larger window is what reached the
 * pointer.
 *
 * <p>The mouse: the name shows {@link #DELAY} after the pointer comes onto an entry, at once when the
 * pointer comes from another entry within {@link #WARM}, and goes as the pointer leaves the entry or a
 * button of the mouse is pressed. The keyboard: an entry that Tab or an arrow key reaches shows its name
 * at once, and it goes when the focus leaves the entry. A focus the keyboard did not move (Ctrl+1 to
 * Ctrl+7, the window opening) shows none.
 */
final class RailName {

    /** The room between the rail's edge and the name. */
    static final double GAP = 8;

    /** How long the pointer rests on an entry before its name shows (LAB_DESIGN_SPEC section 2). */
    static final Duration DELAY = Duration.millis(500);

    /** How soon after a name went the next entry under the pointer shows its own at once. */
    static final Duration WARM = Duration.millis(300);

    /** Why a name shows. */
    enum Cause {
        KEYBOARD,
        POINTER
    }

    private final Tooltip tip = new Tooltip();
    private final Label keys = new Label();
    private final PauseTransition delay = new PauseTransition(DELAY);
    private final Node rail;
    private Node shownFor;
    private Cause cause;
    private Node pending;
    private String pendingName = "";
    private String pendingKeys = "";
    /** When a name shown for the pointer went, by {@link System#nanoTime()}; 0 for not lately. */
    private long pointerLeftAt;

    /** @param rail the rail the entries stand in; the name stands right of it */
    RailName(Node rail) {
        this.rail = rail;
        tip.getStyleClass().add("nav-name");
        // Placed by the entry, never moved to fit: a name moved left onto the rail could cover the pointer.
        tip.setAutoFix(false);
        tip.setAutoHide(false);
        tip.setConsumeAutoHidingEvents(false);
        tip.setAnchorLocation(AnchorLocation.CONTENT_TOP_LEFT);
        keys.getStyleClass().add("nav-name-keys");
        tip.setGraphic(keys);
        tip.setContentDisplay(ContentDisplay.RIGHT);
        tip.setGraphicTextGap(12);
        delay.setOnFinished(event -> delayElapsed());
    }

    /** The pointer came onto an entry. */
    void pointerEntered(Node entry, String name, String shortcut) {
        boolean warm = pointerLeftAt != 0 && System.nanoTime() - pointerLeftAt < (long) (WARM.toMillis() * 1_000_000);
        if (warm || (shownFor != null && cause == Cause.POINTER)) {
            show(entry, name, shortcut, Cause.POINTER);
            return;
        }
        pending = entry;
        pendingName = name;
        pendingKeys = shortcut;
        delay.playFromStart();
    }

    /** The pointer left an entry. */
    void pointerExited(Node entry) {
        if (pending == entry) {
            cancelPending();
        }
        if (shownFor == entry && cause == Cause.POINTER) {
            hide();
            pointerLeftAt = System.nanoTime();
        }
    }

    /** The keyboard brought the focus to an entry. */
    void keyboardReached(Node entry, String name, String shortcut) {
        cancelPending();
        show(entry, name, shortcut, Cause.KEYBOARD);
    }

    /** The focus is on another node now: a name shown for the keyboard on an entry it left goes. */
    void focusMovedTo(Node owner) {
        if (shownFor != null && cause == Cause.KEYBOARD && owner != shownFor) {
            hide();
        }
    }

    /** Takes the name away, and the one about to show, whatever brought it. */
    void hideAll() {
        cancelPending();
        hide();
        pointerLeftAt = 0;
    }

    /** The entry whose name shows for a cause; for tests. */
    Optional<Node> shownFor(Cause which) {
        return cause == which ? Optional.ofNullable(shownFor) : Optional.empty();
    }

    /** Whether a name is waiting for the pointer to rest; for tests. */
    boolean waiting() {
        return pending != null;
    }

    /** Ends the wait of the pointer at once, as if the delay had passed; for tests, which cannot wait. */
    void elapseDelay() {
        delay.stop();
        delayElapsed();
    }

    /** The tooltip itself, for tests of its settings; it is never installed on a node. */
    Tooltip tooltip() {
        return tip;
    }

    /**
     * Where the name stands: {@link #GAP} right of the rail's edge, centred on the entry, in the
     * coordinates the bounds are in (the screen's). Its left edge is right of the rail, so it shares
     * no point with any entry of it.
     */
    static Rectangle2D placement(Bounds entry, double railRight, double width, double height) {
        double x = Math.round(railRight + GAP);
        double y = Math.round(entry.getMinY() + (entry.getHeight() - height) / 2);
        return new Rectangle2D(x, y, Math.max(0, width), Math.max(0, height));
    }

    private void delayElapsed() {
        Node entry = pending;
        String name = pendingName;
        String shortcut = pendingKeys;
        cancelPending();
        if (entry != null) {
            show(entry, name, shortcut, Cause.POINTER);
        }
    }

    private void cancelPending() {
        delay.stop();
        pending = null;
    }

    private void show(Node entry, String name, String shortcut, Cause why) {
        shownFor = entry;
        cause = why;
        tip.setText(name);
        keys.setText(shortcut);
        Window window = entry.getScene() == null ? null : entry.getScene().getWindow();
        if (window == null || !window.isShowing()) {
            // No window to show it over (the tests' scenes): the state is kept all the same.
            return;
        }
        Bounds at = entry.localToScreen(entry.getLayoutBounds());
        Bounds edge = rail.localToScreen(rail.getLayoutBounds());
        if (at == null || edge == null) {
            return;
        }
        if (!tip.isShowing()) {
            tip.show(entry, edge.getMaxX() + GAP, at.getMinY());
        }
        // Its height is known once it shows; the next layout of its text keeps it, a line of 12 px.
        Rectangle2D place = placement(at, edge.getMaxX(), tip.getWidth(), tip.getHeight());
        tip.setAnchorX(place.getMinX());
        tip.setAnchorY(place.getMinY());
    }

    private void hide() {
        shownFor = null;
        cause = null;
        tip.hide();
    }
}
