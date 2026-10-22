package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.DoublePropertyBase;
import javafx.beans.property.SimpleDoubleProperty;
import org.junit.jupiter.api.Test;

/**
 * Needs no JavaFX toolkit: properties only. The window is stood in for by {@link StageLike}, which
 * does with its two properties what a stage does with {@code minWidth} and {@code minHeight}. A
 * real change of scale, a window moved between two monitors, is verified by hand on Windows.
 */
class MinimumWindowSizeTest {

    /** A size the native window was told, in pixels. */
    private record Pixels(int width, int height) {}

    /**
     * The part of a stage this is about, as read from JavaFX 25: when one of the two properties
     * changes its value, the stage tells the native window both sizes, in pixels of the scale the
     * window has at that moment. A change of scale alone tells the window nothing.
     */
    private static final class StageLike {

        final SimpleDoubleProperty scaleX = new SimpleDoubleProperty(1);
        final SimpleDoubleProperty scaleY = new SimpleDoubleProperty(1);
        final List<Pixels> told = new ArrayList<>();
        final DoubleProperty minWidth = new SizeProperty();
        final DoubleProperty minHeight = new SizeProperty();

        Pixels lastTold() {
            return told.getLast();
        }

        private final class SizeProperty extends DoublePropertyBase {

            @Override
            protected void invalidated() {
                told.add(new Pixels((int) Math.ceil(Math.ceil(minWidth.get()) * scaleX.get()), (int)
                        Math.ceil(Math.ceil(minHeight.get()) * scaleY.get())));
            }

            @Override
            public Object getBean() {
                return StageLike.this;
            }

            @Override
            public String getName() {
                return "size";
            }
        }
    }

    private final StageLike stage = new StageLike();

    private void keep(double width, double height) {
        new MinimumWindowSize(stage.minWidth, stage.minHeight, width, height).keep(stage.scaleX, stage.scaleY);
    }

    private void moveToAMonitorScaledBy(double scale) {
        stage.scaleX.set(scale);
        stage.scaleY.set(scale);
    }

    /** The sizes of docs/UI_GUIDE.md, in JavaFX units: the default, and the accepted minimum. */
    @Test
    void theApplicationWindowHasTheSizesOfTheGuide() {
        assertThat(MainWindow.MIN_WIDTH).isEqualTo(720);
        assertThat(MainWindow.MIN_HEIGHT).isEqualTo(480);
        assertThat(MainWindow.DEFAULT_WIDTH).isEqualTo(1100);
        assertThat(MainWindow.DEFAULT_HEIGHT).isEqualTo(700);
    }

    @Test
    void theSmallestSizeIsSetAtOnceInUnits() {
        keep(720, 480);

        assertThat(stage.minWidth.get()).isEqualTo(720);
        assertThat(stage.minHeight.get()).isEqualTo(480);
        assertThat(stage.lastTold()).isEqualTo(new Pixels(720, 480));
    }

    /** The defect this class is there for: 720 x 480 pixels are 576 x 384 units at 125 %. */
    @Test
    void afterAMoveToAScaledMonitorTheWindowIsToldThePixelsOfThatScale() {
        keep(720, 480);

        moveToAMonitorScaledBy(1.25);

        assertThat(stage.lastTold()).isEqualTo(new Pixels(900, 600));
        assertThat(stage.minWidth.get()).isEqualTo(720);
        assertThat(stage.minHeight.get()).isEqualTo(480);
    }

    /** The other direction: 900 x 600 pixels would be 900 x 600 units at 100 %. */
    @Test
    void afterAMoveBackTheWindowIsToldThePixelsOfTheFirstScaleAgain() {
        keep(720, 480);
        moveToAMonitorScaledBy(1.25);

        moveToAMonitorScaledBy(1);

        assertThat(stage.lastTold()).isEqualTo(new Pixels(720, 480));
    }

    @Test
    void everyFurtherMoveKeepsTheSameUnits() {
        keep(720, 480);

        for (double scale : new double[] {1.25, 1, 1.5, 2, 1.75, 1}) {
            moveToAMonitorScaledBy(scale);

            assertThat(stage.lastTold())
                    .as("at a scale of %s", scale)
                    .isEqualTo(new Pixels((int) Math.ceil(720 * scale), (int) Math.ceil(480 * scale)));
            assertThat(stage.minWidth.get()).isEqualTo(720);
            assertThat(stage.minHeight.get()).isEqualTo(480);
        }
    }

    /**
     * A stage makes its window larger at once when the minimum it is told exceeds the size of the
     * window. Handing the size over again must therefore never pass through a larger one.
     */
    @Test
    void handingTheSizeOverAgainNeverTellsTheWindowALargerSize() {
        keep(720, 480);
        int before = stage.told.size();

        moveToAMonitorScaledBy(1.25);

        assertThat(stage.told.subList(before, stage.told.size())).isNotEmpty().allSatisfy(pixels -> {
            assertThat(pixels.width()).isLessThanOrEqualTo(900);
            assertThat(pixels.height()).isLessThanOrEqualTo(600);
        });
    }

    /** Monitors can be scaled differently in the two directions; either change counts. */
    @Test
    void aChangeOfOneScaleAloneHandsTheSizeOverAgain() {
        keep(720, 480);

        stage.scaleY.set(1.5);
        assertThat(stage.lastTold()).isEqualTo(new Pixels(720, 720));

        stage.scaleX.set(1.5);
        assertThat(stage.lastTold()).isEqualTo(new Pixels(1080, 720));
    }

    @Test
    void whileTheScaleStaysTheWindowIsToldNothingAgain() {
        keep(720, 480);
        int before = stage.told.size();

        // Setting a scale to the value it has is no change.
        stage.scaleX.set(1);
        stage.scaleY.set(1);

        assertThat(stage.told).hasSize(before);
    }

    /** What happens without this class, so that the tests above are known to be able to fail. */
    @Test
    void aSizeThatIsOnlySetOnceStaysInThePixelsOfTheFirstScale() {
        stage.minWidth.set(720);
        stage.minHeight.set(480);

        moveToAMonitorScaledBy(1.25);

        assertThat(stage.lastTold()).isEqualTo(new Pixels(720, 480));
    }

    @Test
    void aSizeThatLeavesNoRoomBelowItIsRefused() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MinimumWindowSize(stage.minWidth, stage.minHeight, 1, 480));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MinimumWindowSize(stage.minWidth, stage.minHeight, 720, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MinimumWindowSize(stage.minWidth, stage.minHeight, Double.NaN, 480));
        assertThatNullPointerException().isThrownBy(() -> new MinimumWindowSize(null, stage.minHeight, 720, 480));
        assertThatNullPointerException().isThrownBy(() -> new MinimumWindowSize(stage.minWidth, null, 720, 480));
    }
}
