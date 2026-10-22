package prism.srm.app;

import java.util.Objects;
import javafx.beans.property.DoubleProperty;
import javafx.beans.value.ObservableValue;

/**
 * Keeps the smallest size of a window at the same number of JavaFX units on every monitor.
 *
 * <p>Setting {@code minWidth} and {@code minHeight} of a stage once is not enough on Windows. The
 * stage hands its minimum size to the native window in pixels: the units multiplied by the scale
 * of the monitor the window is on at that moment. It does so when the window is created and when
 * one of the two properties changes, and at no other time. A window that is moved to a monitor
 * with another scale therefore keeps the pixels of the first monitor: set to 720 x 480 units on a
 * monitor at 100 %, it could be made 576 x 384 units small on one at 125 %, and started on the
 * monitor at 125 % it could not be made smaller than 900 x 600 units on the one at 100 %. Read
 * from JavaFX 25.0.4 ({@code Stage.minWidthProperty}, {@code WindowStage.setMinimumSize}) and
 * measured on Windows 11.
 *
 * <p>So the size is handed over again whenever the output scale of the window changes. The window
 * is told only when a property changes its value, which is why each property is set one unit lower
 * first and then to the size: lower, so that the window is never made larger for a moment.
 *
 * <p>Two things this rests on, to be looked at again when the JavaFX version changes, with the
 * window moved between two monitors of different scale. The output scale is what a stage lets
 * its users observe; what the stage multiplies by is the platform scale. The two change together
 * unless the render scale is fixed from outside ({@code -Dglass.win.renderScale}), which this
 * application does not do. And the stage has already taken the new scale when the listeners run.
 *
 * <p>Used on the JavaFX Application Thread only.
 */
final class MinimumWindowSize {

    private final DoubleProperty minWidth;
    private final DoubleProperty minHeight;
    private final double width;
    private final double height;

    /**
     * @param minWidth the {@code minWidth} property of the stage
     * @param minHeight the {@code minHeight} property of the stage
     * @param width the smallest width of the window, in JavaFX units, more than one
     * @param height the smallest height of the window, in JavaFX units, more than one
     */
    MinimumWindowSize(DoubleProperty minWidth, DoubleProperty minHeight, double width, double height) {
        this.minWidth = Objects.requireNonNull(minWidth, "minWidth");
        this.minHeight = Objects.requireNonNull(minHeight, "minHeight");
        // Written so that NaN is rejected too.
        if (!(width > 1 && height > 1)) {
            throw new IllegalArgumentException("Expected a size of more than one unit: " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
    }

    /**
     * Sets the smallest size now and keeps it whenever one of the scales changes.
     *
     * @param outputScaleX the {@code outputScaleX} property of the stage
     * @param outputScaleY the {@code outputScaleY} property of the stage
     */
    void keep(ObservableValue<Number> outputScaleX, ObservableValue<Number> outputScaleY) {
        minWidth.set(width);
        minHeight.set(height);
        outputScaleX.addListener((scale, before, after) -> handOverAgain());
        outputScaleY.addListener((scale, before, after) -> handOverAgain());
    }

    private void handOverAgain() {
        minWidth.set(width - 1);
        minWidth.set(width);
        minHeight.set(height - 1);
        minHeight.set(height);
    }
}
