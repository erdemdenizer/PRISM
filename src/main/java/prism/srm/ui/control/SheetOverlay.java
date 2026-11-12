package prism.srm.ui.control;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import prism.srm.ui.Motion;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;

/**
 * A sheet over a page, from its right, with a scrim behind it (UI lab v2, LAB_DESIGN_SPEC sections 2
 * and 7): where a window has no room for an inspector beside or under its table, the inspector is
 * shown in one of these, opened by the user and closed by Esc, its close button, or a click anywhere
 * outside it. One design for every page that has one (Processes, Limits).
 *
 * <p>While it is open it is modal to the window: the keyboard stays in it (Tab and Shift+Tab go round
 * its controls), Esc closes it from wherever the focus is, and a press of the mouse outside it closes it
 * and does nothing else. When it closes, the focus goes back to the control that opened it, or, where
 * that can no longer take the focus, to the fallback the page names (its table). A control in it that
 * becomes disabled while it has the focus gives the focus to the close button, so the keyboard is never
 * left on a control that cannot act. A page that leaves the window (another page is shown) takes its
 * sheet with it: it is closed at once, without moving the focus.
 *
 * <p>Layout: the overlay takes no room. The page adds it as its last child, unmanaged, and gives it its
 * whole area in its own layout; it is invisible, and lets every event through, while it is closed. It
 * moves in over {@link Motion#PANEL_MS} and away over {@link Motion#EXIT_MS}; with reduced motion it is
 * there and gone at once. No shadow on it: what it shows changes every second (LAB_DESIGN_SPEC 0.4).
 */
public final class SheetOverlay extends Region {

    /**
     * The width of the sheet, where the page is wide enough. Fix round H (LO-06, PR-03): 400, within the
     * 320 to 440 px of an inspector, so that the compact inspectors of a low window keep each group of
     * actions on one line; at 720 x 480 it still leaves the page about 240 px at its left.
     */
    public static final double WIDTH = 400;

    /** The sheet leaves at least this much of the page visible at its left, so the user sees where it is. */
    private static final double MARGIN = 48;

    /**
     * Fix round H: 16 px, the size of an icon in a control (docs/UI_GUIDE.md); 14 px was off the icon
     * scale (12 for chevrons and the clear button inside a field, 16 in controls, 20 in the rail).
     */
    private static final double CLOSE_ICON_SIZE = 16;

    /** The close button from the top and the right edge of the sheet. */
    private static final double CLOSE_INSET = 8;

    private final Region scrim = new Region();
    private final StackPane sheet = new StackPane();
    private final StackPane body = new StackPane();
    private final Button close = new Button();
    private final Supplier<Node> fallback;
    private final ParallelTransition motion = new ParallelTransition();
    private final TranslateTransition slide = new TranslateTransition(Duration.ZERO, sheet);
    private final FadeTransition fade = new FadeTransition(Duration.ZERO, sheet);
    private final FadeTransition dim = new FadeTransition(Duration.ZERO, scrim);
    private final EventHandler<KeyEvent> keys = this::keyPressed;
    private final EventHandler<MouseEvent> presses = this::mousePressed;
    private final InvalidationListener ownerDisabled = observable -> leaveDisabledOwner();
    private final ChangeListener<Node> ownerChange = (observable, before, after) -> focusMoved(before, after);
    private Scene listening;
    // Weak: a page that removes its opener from the scene does not keep it alive through the sheet.
    private WeakReference<Node> opener = new WeakReference<>(null);
    private boolean open;

    /**
     * @param caption what the sheet is, for a screen reader (its role description); no bar shows it
     * @param name what a screen reader calls the sheet; the close button says it as its help
     * @param fallback where the focus goes when the sheet closes and its opener cannot take it
     */
    public SheetOverlay(String caption, String name, Supplier<Node> fallback) {
        Objects.requireNonNull(caption, "caption");
        Objects.requireNonNull(name, "name");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        getStyleClass().add("sheet-overlay");
        setManaged(false);
        setVisible(false);
        setPickOnBounds(false);

        scrim.getStyleClass().add("sheet-scrim");
        scrim.setManaged(false);

        close.setGraphic(new IconView(Icon.X, CLOSE_ICON_SIZE));
        close.getStyleClass().addAll("quiet", "icon-only", "sheet-close");
        close.setAccessibleText("Close details");
        // The focus lands on it when the sheet opens: its help says what it closes.
        close.setAccessibleHelp(name);
        close.setTooltip(new Tooltip("Close    Esc"));
        close.setOnAction(event -> close());
        // Fix round G (LO-25): no bar of its own over the inspector, whose first line names the process;
        // the close button stands at the right of that line. The caption is what the sheet is to a
        // screen reader.
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        StackPane.setMargin(close, new Insets(CLOSE_INSET, CLOSE_INSET, 0, 0));

        body.getStyleClass().add("sheet-body");
        body.setMinHeight(0);
        sheet.getStyleClass().add("sheet");
        sheet.setAccessibleText(name);
        sheet.setAccessibleRoleDescription(caption);
        sheet.setManaged(false);
        // The close button after the content: it is drawn over the content's first line, and its focus stop
        // is still the first (focusStops puts it first).
        sheet.getChildren().setAll(body, close);
        getChildren().setAll(scrim, sheet);
        // While it is open the page under the scrim does not scroll: a wheel turn the sheet's own details
        // do not take stops here (UI audit SH-06).
        addEventHandler(ScrollEvent.ANY, event -> {
            if (open) {
                event.consume();
            }
        });

        motion.getChildren().setAll(slide, fade, dim);
        sceneProperty().addListener((observable, before, after) -> {
            // The page left the window, as when another page is shown: the sheet goes with it.
            if (before != null && open) {
                dismiss();
            }
            unlisten();
        });
    }

    /** What the sheet shows, under its close button; {@code null} for nothing. The node moves here from where it was. */
    public void setContent(Node content) {
        if (content == null) {
            body.getChildren().clear();
        } else {
            body.getChildren().setAll(content);
        }
    }

    /**
     * Adds a page's own style classes to the sheet, its scrim and its close button; the parts stay the
     * overlay's (tests reach them by their classes "sheet", "sheet-scrim" and "sheet-close").
     */
    public void addStyleClasses(String sheetClass, String scrimClass, String closeClass) {
        sheet.getStyleClass().add(Objects.requireNonNull(sheetClass, "sheetClass"));
        scrim.getStyleClass().add(Objects.requireNonNull(scrimClass, "scrimClass"));
        close.getStyleClass().add(Objects.requireNonNull(closeClass, "closeClass"));
    }

    /** Moves the focus to the close button of the sheet, where an action in it leaves the focus. */
    public void focusClose() {
        close.requestFocus();
    }

    /** Whether a node is the sheet or inside it. */
    public boolean holds(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == sheet) {
                return true;
            }
        }
        return false;
    }

    /** Whether the sheet is open. */
    public boolean isOpen() {
        return open;
    }

    /**
     * Opens the sheet and moves the focus to its close button; nothing while it is open already or
     * while the overlay is in no scene.
     *
     * @param from where the focus goes back to when the sheet closes
     */
    public void open(Node from) {
        if (open || getScene() == null) {
            return;
        }
        open = true;
        opener = new WeakReference<>(from);
        setVisible(true);
        sheet.setVisible(true);
        scrim.setVisible(true);
        listen(getScene());
        if (getParent() != null) {
            getParent().requestLayout();
        }
        Duration duration = Motion.of(this, Motion.PANEL_MS);
        motion.stop();
        motion.setOnFinished(null);
        if (duration.equals(Duration.ZERO)) {
            settle();
        } else {
            play(duration, Interpolator.SPLINE(0.2, 0.8, 0.2, 1), WIDTH, 0, 0, 1);
        }
        close.requestFocus();
    }

    /** Closes the sheet and gives the focus back to its opener, or to the fallback; nothing while it is closed. */
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        unlisten();
        Duration duration = Motion.of(this, Motion.EXIT_MS);
        motion.stop();
        if (duration.equals(Duration.ZERO)) {
            hideNodes();
        } else {
            play(duration, Interpolator.EASE_IN, 0, WIDTH, 1, 0);
            motion.setOnFinished(event -> hideNodes());
        }
        Node from = opener.get();
        Node back = canTakeFocus(from) && from.getScene() == getScene() ? from : fallback.get();
        opener = new WeakReference<>(null);
        if (back != null) {
            back.requestFocus();
        }
    }

    /**
     * Closes the sheet at once and leaves the focus alone: for a page that moves its content elsewhere
     * (the window grew), leaves the window, or for a focus that went elsewhere while the sheet was open.
     */
    public void dismiss() {
        open = false;
        opener = new WeakReference<>(null);
        unlisten();
        motion.stop();
        motion.setOnFinished(null);
        hideNodes();
    }

    /** Whether a node is inside the sheet. */
    public boolean contains(Node node) {
        return isInside(node, sheet);
    }

    /**
     * Whether a node can be given the focus now: it is in a scene, enabled, and it and every parent
     * of it are visible.
     */
    public static boolean canTakeFocus(Node node) {
        if (node == null || node.getScene() == null || node.isDisabled()) {
            return false;
        }
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** The controls of the sheet the keyboard reaches, in the order on screen (close first); for tests as well. */
    public List<Node> focusStops() {
        List<Node> stops = new ArrayList<>();
        if (!close.isDisabled() && close.isVisible()) {
            stops.add(close);
        }
        collectStops(body, stops);
        return stops;
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        scrim.resizeRelocate(0, 0, width, height);
        double side = Math.min(WIDTH, Math.max(0, width - MARGIN));
        sheet.resizeRelocate(width - side, 0, side, height);
    }

    private void play(
            Duration duration, Interpolator curve, double fromX, double toX, double fromOpacity, double toOpacity) {
        slide.setDuration(duration);
        fade.setDuration(duration);
        dim.setDuration(duration);
        slide.setInterpolator(curve);
        fade.setInterpolator(curve);
        dim.setInterpolator(curve);
        slide.setFromX(fromX);
        slide.setToX(toX);
        fade.setFromValue(fromOpacity);
        fade.setToValue(toOpacity);
        dim.setFromValue(fromOpacity);
        dim.setToValue(toOpacity);
        // The first frame by hand: a transition applies it only with the next pulse.
        sheet.setTranslateX(fromX);
        sheet.setOpacity(fromOpacity);
        scrim.setOpacity(fromOpacity);
        motion.playFromStart();
    }

    private void settle() {
        sheet.setTranslateX(0);
        sheet.setOpacity(1);
        scrim.setOpacity(1);
    }

    private void hideNodes() {
        if (open) {
            return;
        }
        settle();
        sheet.setVisible(false);
        scrim.setVisible(false);
        setVisible(false);
    }

    private void listen(Scene scene) {
        unlisten();
        listening = scene;
        scene.addEventFilter(KeyEvent.KEY_PRESSED, keys);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, presses);
        scene.focusOwnerProperty().addListener(ownerChange);
        followOwner(null, scene.getFocusOwner());
    }

    private void unlisten() {
        if (listening == null) {
            return;
        }
        listening.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
        listening.removeEventFilter(MouseEvent.MOUSE_PRESSED, presses);
        listening.focusOwnerProperty().removeListener(ownerChange);
        followOwner(listening.getFocusOwner(), null);
        listening = null;
    }

    private void followOwner(Node before, Node after) {
        if (before != null) {
            before.disabledProperty().removeListener(ownerDisabled);
        }
        if (after != null) {
            after.disabledProperty().addListener(ownerDisabled);
        }
    }

    /**
     * The sheet holds the keyboard while it is open; something else took the focus from it all the same
     * (the navigation, a shortcut of the window): the sheet gives way, and closes at once.
     */
    private void focusMoved(Node before, Node after) {
        followOwner(before, after);
        if (open && after != null && !contains(after)) {
            dismiss();
        }
    }

    /** The control with the focus became disabled while the sheet is open: the close button takes it. */
    private void leaveDisabledOwner() {
        Node owner = listening == null ? null : listening.getFocusOwner();
        if (open && owner != null && owner.isDisabled() && contains(owner)) {
            close.requestFocus();
        }
    }

    /** Esc closes the sheet, wherever the focus is; Tab and Shift+Tab go round inside it. */
    private void keyPressed(KeyEvent event) {
        if (!open || event.isShortcutDown() || event.isAltDown()) {
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            close();
            event.consume();
        } else if (event.getCode() == KeyCode.TAB) {
            cycle(!event.isShiftDown());
            event.consume();
        }
    }

    /** A press of the mouse outside the sheet closes it and goes no further. */
    private void mousePressed(MouseEvent event) {
        if (!open || !(event.getTarget() instanceof Node target) || contains(target)) {
            return;
        }
        close();
        event.consume();
    }

    private void cycle(boolean forward) {
        List<Node> stops = focusStops();
        if (stops.isEmpty()) {
            return;
        }
        Node owner = listening == null ? null : listening.getFocusOwner();
        int at = -1;
        for (int index = 0; index < stops.size(); index++) {
            if (owner != null && isInside(owner, stops.get(index))) {
                at = index;
                break;
            }
        }
        int next;
        if (at < 0) {
            next = forward ? 0 : stops.size() - 1;
        } else {
            next = Math.floorMod(at + (forward ? 1 : -1), stops.size());
        }
        stops.get(next).requestFocus();
    }

    private static void collectStops(Node node, List<Node> stops) {
        if (!node.isVisible() || node.isDisabled()) {
            return;
        }
        if (node.isFocusTraversable()) {
            stops.add(node);
            return;
        }
        // The content of a scroll pane, whether its skin exists yet or not.
        if (node instanceof ScrollPane pane) {
            if (pane.getContent() != null) {
                collectStops(pane.getContent(), stops);
            }
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectStops(child, stops);
            }
        }
    }

    private static boolean isInside(Node node, Node container) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == container) {
                return true;
            }
        }
        return false;
    }
}
