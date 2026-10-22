package prism.srm.ui.graph;

import java.util.ArrayList;
import java.util.List;
import javafx.css.CssMetaData;
import javafx.css.SimpleStyleableDoubleProperty;
import javafx.css.SimpleStyleableObjectProperty;
import javafx.css.StyleConverter;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import prism.srm.ui.Stylesheets;

/**
 * A Region that draws on one Canvas: the single node of a graph, however much it shows.
 *
 * <p>It is redrawn only when the subclass asks for it, when its size changes, or when the
 * stylesheet gives it another color, as a theme change does; never on a timer.
 *
 * <p>What it draws with comes from the stylesheet through the properties {@code -srm-graph-line},
 * {@code -srm-graph-secondary}, {@code -srm-graph-grid}, {@code -srm-graph-surface}, {@code
 * -srm-graph-label}, {@code -srm-graph-value} and {@code -srm-graph-label-size}, so a graph
 * follows the theme like any other control. A subclass uses the ones it needs.
 */
abstract class CanvasGraph extends Region {

    private static final CssMetaData<CanvasGraph, Color> LINE_COLOR = new ColorMetaData("-srm-graph-line") {
        @Override
        public StyleableProperty<Color> getStyleableProperty(CanvasGraph graph) {
            return graph.lineColor;
        }
    };
    private static final CssMetaData<CanvasGraph, Color> SECONDARY_COLOR = new ColorMetaData("-srm-graph-secondary") {
        @Override
        public StyleableProperty<Color> getStyleableProperty(CanvasGraph graph) {
            return graph.secondaryColor;
        }
    };
    private static final CssMetaData<CanvasGraph, Color> GRID_COLOR = new ColorMetaData("-srm-graph-grid") {
        @Override
        public StyleableProperty<Color> getStyleableProperty(CanvasGraph graph) {
            return graph.gridColor;
        }
    };
    private static final CssMetaData<CanvasGraph, Color> SURFACE_COLOR = new ColorMetaData("-srm-graph-surface") {
        @Override
        public StyleableProperty<Color> getStyleableProperty(CanvasGraph graph) {
            return graph.surfaceColor;
        }
    };
    private static final CssMetaData<CanvasGraph, Color> LABEL_COLOR = new ColorMetaData("-srm-graph-label") {
        @Override
        public StyleableProperty<Color> getStyleableProperty(CanvasGraph graph) {
            return graph.labelColor;
        }
    };
    private static final CssMetaData<CanvasGraph, Color> VALUE_COLOR = new ColorMetaData("-srm-graph-value") {
        @Override
        public StyleableProperty<Color> getStyleableProperty(CanvasGraph graph) {
            return graph.valueColor;
        }
    };
    private static final CssMetaData<CanvasGraph, Number> LABEL_SIZE =
            new CssMetaData<>("-srm-graph-label-size", StyleConverter.getSizeConverter(), 0) {
                @Override
                public boolean isSettable(CanvasGraph graph) {
                    return true;
                }

                @Override
                public StyleableProperty<Number> getStyleableProperty(CanvasGraph graph) {
                    return graph.labelSize;
                }
            };
    private static final List<CssMetaData<? extends Styleable, ?>> CSS_META_DATA = List.copyOf(withRegionMetaData(
            List.of(LINE_COLOR, SECONDARY_COLOR, GRID_COLOR, SURFACE_COLOR, LABEL_COLOR, VALUE_COLOR, LABEL_SIZE)));

    private final Canvas canvas = new Canvas();
    private final StyleableObjectProperty<Color> lineColor = new ColorProperty(LINE_COLOR, "lineColor");
    private final StyleableObjectProperty<Color> secondaryColor = new ColorProperty(SECONDARY_COLOR, "secondaryColor");
    private final StyleableObjectProperty<Color> gridColor = new ColorProperty(GRID_COLOR, "gridColor");
    private final StyleableObjectProperty<Color> surfaceColor = new ColorProperty(SURFACE_COLOR, "surfaceColor");
    private final StyleableObjectProperty<Color> labelColor = new ColorProperty(LABEL_COLOR, "labelColor");
    private final StyleableObjectProperty<Color> valueColor = new ColorProperty(VALUE_COLOR, "valueColor");
    private final StyleableDoubleProperty labelSize = new SimpleStyleableDoubleProperty(LABEL_SIZE, this, "labelSize") {
        @Override
        protected void invalidated() {
            labelSizeChanged();
            redraw();
        }
    };

    /** @param styleClass the style class of the graph, which gives it its colors */
    CanvasGraph(String styleClass) {
        getStyleClass().add(styleClass);
        getStylesheets().add(Stylesheets.graph());
        setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        getChildren().add(canvas);
    }

    /**
     * Draws the graph on a cleared Canvas of the given size. Not called while the Canvas is too
     * small to show anything.
     */
    abstract void draw(GraphicsContext graphics, double width, double height);

    /** Clears the Canvas and draws the graph again. */
    final void redraw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, width, height);
        if (width < 2 || height < 2) {
            return;
        }
        graphics.setLineWidth(1);
        draw(graphics, width, height);
    }

    final Color lineColor() {
        return lineColor.get();
    }

    final Color secondaryColor() {
        return secondaryColor.get();
    }

    final Color gridColor() {
        return gridColor.get();
    }

    final Color surfaceColor() {
        return surfaceColor.get();
    }

    final Color labelColor() {
        return labelColor.get();
    }

    final Color valueColor() {
        return valueColor.get();
    }

    final double labelSize() {
        return labelSize.get();
    }

    /** Called when the stylesheet gives the text another size, before the graph is redrawn. */
    void labelSizeChanged() {}

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return CSS_META_DATA;
    }

    @Override
    protected void layoutChildren() {
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double width = Math.max(0, snapSizeX(getWidth() - left - snappedRightInset()));
        double height = Math.max(0, snapSizeY(getHeight() - top - snappedBottomInset()));
        canvas.relocate(left, top);
        if (width != canvas.getWidth() || height != canvas.getHeight()) {
            canvas.setWidth(width);
            canvas.setHeight(height);
            redraw();
        }
    }

    // The Canvas does not resize itself, so the size of the graph must not follow it: without
    // these, and the minimum and preferred sizes a subclass computes, the graph could grow with
    // the window but never shrink again.

    @Override
    protected double computeMaxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight(double width) {
        return Double.MAX_VALUE;
    }

    private static List<CssMetaData<? extends Styleable, ?>> withRegionMetaData(List<CssMetaData<CanvasGraph, ?>> own) {
        List<CssMetaData<? extends Styleable, ?>> all = new ArrayList<>(Region.getClassCssMetaData());
        all.addAll(own);
        return all;
    }

    /** A color the stylesheet sets; until it does, the color is transparent and draws nothing. */
    private abstract static class ColorMetaData extends CssMetaData<CanvasGraph, Color> {

        ColorMetaData(String property) {
            super(property, StyleConverter.getColorConverter(), Color.TRANSPARENT);
        }

        @Override
        public boolean isSettable(CanvasGraph graph) {
            return true;
        }
    }

    /** Redraws the graph when the stylesheet gives it another color, as a theme change does. */
    private final class ColorProperty extends SimpleStyleableObjectProperty<Color> {

        ColorProperty(CssMetaData<CanvasGraph, Color> metaData, String name) {
            super(metaData, CanvasGraph.this, name, Color.TRANSPARENT);
        }

        @Override
        protected void invalidated() {
            redraw();
        }
    }
}
