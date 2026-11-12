package prism.srm.ui.icon;

import java.util.Objects;
import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * Draws one {@link Icon} at a given size: its strokes, scaled from the 24 unit grid, in the color
 * the stylesheet gives through {@code -srm-icon} ({@code .icon-view .icon-shape} in app.css).
 *
 * <p>A few nodes, created once: one shape per stroke of the icon and a group that scales them.
 * Nothing is redrawn; a change of color is a change of style, as for any other node.
 */
public final class IconView extends Region {

    /**
     * UI lab v2: the stroke is set in screen units, not in grid units. Lucide's 2 units on the 24 grid
     * give 1.33 px at 16, which renders soft; a fixed width per size keeps every icon equally crisp:
     * 1.25 at 12 and below, 1.5 at 16, 1.6 at 20, 1.75 above.
     */
    static double strokeInUnits(double size) {
        double px = size <= 12 ? 1.25 : size <= 16 ? 1.5 : size <= 20 ? 1.6 : 1.75;
        return px * Icon.GRID / size;
    }

    private final Icon icon;

    /**
     * @param icon what to draw
     * @param size the width and height in JavaFX units; 16 for navigation and controls
     */
    public IconView(Icon icon, double size) {
        this.icon = Objects.requireNonNull(icon, "icon");
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        getStyleClass().add("icon-view");
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setMouseTransparent(true);

        Group strokes = new Group();
        for (String path : icon.paths()) {
            SVGPath shape = new SVGPath();
            shape.setContent(path);
            shape.getStyleClass().add("icon-shape");
            shape.setStrokeWidth(strokeInUnits(size));
            shape.setStrokeLineCap(StrokeLineCap.ROUND);
            shape.setStrokeLineJoin(StrokeLineJoin.ROUND);
            shape.setFill(null);
            strokes.getChildren().add(shape);
        }
        // The group keeps the origin of the grid at the origin of this region, whatever its
        // strokes cover, and is scaled about it; so it is placed by hand, not by the layout.
        strokes.setManaged(false);
        strokes.getTransforms().add(new Scale(size / Icon.GRID, size / Icon.GRID));
        getChildren().add(strokes);
    }

    public Icon icon() {
        return icon;
    }
}
