package prism.srm.ui.control;

import java.util.Objects;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.skin.ScrollPaneSkin;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The body of an inspector (fix round G, owner decision K2): a part pinned at its top that never scrolls
 * away (who is described, the actions on it, why some are disabled and what the last one came to), and
 * under it the details, which scroll inside the frame where they are taller than the room. One scroll
 * region, never one inside another.
 *
 * <p>Where the frame is too low for the pinned part and {@link #LEAST_DETAILS} of details, as beside the
 * table in a low wide window or in the sheet of the smallest window, a pinned part that has a compact
 * arrangement takes it (fix round H, PR-03, LO-06: the same actions on fewer lines, never fewer actions,
 * and still pinned). Only where even that does not fit does the pinned part scroll with the details,
 * still first: a part that cannot be shown whole is never cut where it stands. The least of the details
 * is never more than the details have: a frame whose details are short or empty keeps its part pinned.
 *
 * <p>The scroll bar stays inside the frame on a visible track, and while there is more to see than the
 * room shows, a hairline at the edge of the room says on which side (no gradient and no shadow:
 * docs/UI_GUIDE.md). Layout only; what the parts show is theirs.
 */
public final class InspectorFrame extends Region {

    /** The least height of the details under a pinned part; with less room the pinned part scrolls too. */
    public static final double LEAST_DETAILS = 72;

    private final Region pinned;
    private final VBox flow = new VBox();
    private final ScrollPane scroll = new ScrollPane(flow);
    private final Region moreAbove = new Region();
    private final Region moreBelow = new Region();
    private boolean fixed = true;
    /**
     * What turns the pinned part compact (fix round H): the same actions, each still a labelled control,
     * on fewer lines. The frame turns it compact where the whole part and the least of the details do not
     * fit, and whole again where they do. Given {@code true} for compact, {@code false} for whole.
     */
    private java.util.function.Consumer<Boolean> compactable;

    private boolean compact;
    /** The height of the whole pinned part when it was last measured, and at what width; NaN until then. */
    private double wholePinned = Double.NaN;

    private double wholePinnedWidth = Double.NaN;

    /**
     * @param pinned what never scrolls away while the frame has room for it
     * @param details what scrolls under it
     */
    public InspectorFrame(Region pinned, Node details) {
        this.pinned = Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(details, "details");
        getStyleClass().add("inspector-frame");
        pinned.getStyleClass().add("inspector-pinned");
        flow.getStyleClass().add("inspector-flow");
        flow.getChildren().setAll(details);
        scroll.getStyleClass().add("inspector-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        scroll.setMinHeight(0);
        // The skin at once: the details are in the scene graph from the start, so that a lookup finds
        // them and their bindings are live while the frame has not been laid out yet.
        scroll.setSkin(new ScrollPaneSkin(scroll));
        for (Region edge : new Region[] {moreAbove, moreBelow}) {
            edge.getStyleClass().add("inspector-edge");
            edge.setManaged(false);
            edge.setMouseTransparent(true);
            edge.setVisible(false);
        }
        moreAbove.getStyleClass().add("inspector-edge-above");
        moreBelow.getStyleClass().add("inspector-edge-below");
        getChildren().setAll(pinned, scroll, moreAbove, moreBelow);
        scroll.vvalueProperty().addListener(observable -> showEdges());
        scroll.viewportBoundsProperty().addListener(observable -> showEdges());
        flow.heightProperty().addListener(observable -> showEdges());
    }

    /** What turns the pinned part compact where the frame is low; none by default. */
    public void setCompactable(java.util.function.Consumer<Boolean> value) {
        if (compactable != null && compact) {
            compactable.accept(false);
        }
        compactable = value;
        compact = false;
        wholePinned = Double.NaN;
        requestLayout();
    }

    /** Whether the pinned part is in its compact arrangement now; for the page and for tests. */
    public boolean isCompact() {
        return compact;
    }

    /** Whether the pinned part stands above the scrolling details (else it scrolls with them); for tests. */
    public boolean isPinned() {
        return fixed;
    }

    /** Scrolls the details to their end; for tests, which reach the viewport by its style class. */
    public void scrollDetailsToEnd() {
        scroll.setVvalue(scroll.getVmax());
    }

    /** Whether the room shows less than all of the details, below or above it; for tests. */
    public boolean showsMoreCue() {
        return moreBelow.isVisible() || moreAbove.isVisible();
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + Math.max(pinned.minWidth(-1), flowMinWidth()) + snappedRightInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + Math.max(pinned.prefWidth(-1), flow.prefWidth(-1)) + snappedRightInset();
    }

    /** Its least height is the frame's: a page sets the height it has. */
    @Override
    protected double computeMinHeight(double width) {
        return snappedTopInset() + snappedBottomInset();
    }

    /**
     * All of it, pinned part and details, without scrolling; with the pinned part whole even while it is
     * compact, so that a page that has the room gives the frame what makes it whole again.
     */
    @Override
    protected double computePrefHeight(double width) {
        double inner = width < 0 ? -1 : Math.max(0, width - snappedLeftInset() - snappedRightInset());
        double pinnedPart = compact && !Double.isNaN(wholePinned) ? wholePinned : pinned.prefHeight(inner);
        return snappedTopInset() + pinnedPart + detailsHeight(inner) + snappedBottomInset();
    }

    /** The details whole at a width, without the pinned part. */
    private double detailsHeight(double inner) {
        double details = 0;
        for (Node child : flow.getChildren()) {
            if (child != pinned && child.isManaged()) {
                details += child.prefHeight(inner);
            }
        }
        return details + flow.snappedTopInset() + flow.snappedBottomInset();
    }

    /**
     * The least of the details the frame keeps in view under its pinned part: {@link #LEAST_DETAILS}, or
     * all of them where they are shorter.
     */
    private double leastDetails(double inner) {
        return Math.min(LEAST_DETAILS, detailsHeight(inner));
    }

    /** The height of the pinned part at a width; a page keeps at least this much for the frame where it can. */
    public double pinnedHeight(double width) {
        double inner = width < 0 ? -1 : Math.max(0, width - snappedLeftInset() - snappedRightInset());
        return snappedTopInset() + pinned.prefHeight(inner) + snappedBottomInset();
    }

    /**
     * What the frame needs at a width to keep its part pinned, as it is arranged now: the pinned part and
     * the least of the details. A page keeps this much for the frame where it can.
     */
    public double leastPinnedHeight(double width) {
        double inner = width < 0 ? -1 : Math.max(0, width - snappedLeftInset() - snappedRightInset());
        return snappedTopInset() + pinned.prefHeight(inner) + leastDetails(inner) + snappedBottomInset();
    }

    private double flowMinWidth() {
        double least = 0;
        for (Node child : flow.getChildren()) {
            if (child != pinned) {
                least = Math.max(least, child.minWidth(-1));
            }
        }
        return least + flow.snappedLeftInset() + flow.snappedRightInset();
    }

    @Override
    protected void layoutChildren() {
        double x = snappedLeftInset();
        double y = snappedTopInset();
        double width = getWidth() - x - snappedRightInset();
        double height = getHeight() - y - snappedBottomInset();
        if (width <= 0 || height <= 0) {
            return;
        }
        double least = leastDetails(width);
        double pinnedHeight = choosePinned(width, height, least);
        boolean wanted = height >= pinnedHeight + least;
        if (wanted != fixed) {
            pin(wanted);
        }
        if (fixed) {
            pinned.resizeRelocate(x, y, width, pinnedHeight);
            scroll.resizeRelocate(x, y + pinnedHeight, width, height - pinnedHeight);
        } else {
            scroll.resizeRelocate(x, y, width, height);
        }
        double top = scroll.getLayoutY();
        double viewport = scroll.getViewportBounds().getWidth() > 0
                ? Math.min(width, scroll.getViewportBounds().getWidth())
                : width;
        moreAbove.resizeRelocate(x, top, viewport, 1);
        moreBelow.resizeRelocate(x, top + scroll.getHeight() - 1, viewport, 1);
        showEdges();
    }

    /**
     * Whole where the whole pinned part and the least of the details fit, else compact; returns the
     * height of the pinned part as it is then. While compact, the whole part is tried again only where
     * the width changed or the frame grew past what it needed when last measured.
     */
    private double choosePinned(double width, double height, double least) {
        if (compactable == null) {
            return pinned.prefHeight(width);
        }
        if (compact && (Math.abs(width - wholePinnedWidth) > 0.5 || height >= wholePinned + least)) {
            compact(false);
        }
        double pinnedHeight = pinned.prefHeight(width);
        if (!compact) {
            wholePinned = pinnedHeight;
            wholePinnedWidth = width;
            if (height < pinnedHeight + least) {
                compact(true);
                pinnedHeight = pinned.prefHeight(width);
            }
        }
        return pinnedHeight;
    }

    private void compact(boolean value) {
        compact = value;
        compactable.accept(value);
    }

    /** Moves the pinned part above the details or into their flow, keeping the focus where it was. */
    private void pin(boolean value) {
        Scene scene = getScene();
        Node focused = scene == null ? null : scene.getFocusOwner();
        boolean focusInside = focused != null && isInside(focused, pinned);
        fixed = value;
        if (value) {
            flow.getChildren().remove(pinned);
            getChildren().add(0, pinned);
        } else {
            getChildren().remove(pinned);
            flow.getChildren().add(0, pinned);
        }
        if (focusInside && focused.getScene() != null && focused.getScene().getFocusOwner() != focused) {
            focused.requestFocus();
        }
    }

    /** A hairline at the edge of the room on each side where there is more to see. */
    private void showEdges() {
        double viewport = scroll.getViewportBounds().getHeight();
        double content = flow.getHeight();
        boolean taller = viewport > 0 && content > viewport + 0.5;
        double at = scroll.getVvalue();
        moreAbove.setVisible(taller && at > scroll.getVmin() + 0.001);
        moreBelow.setVisible(taller && at < scroll.getVmax() - 0.001);
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
