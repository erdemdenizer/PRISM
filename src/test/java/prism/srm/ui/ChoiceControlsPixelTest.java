package prism.srm.ui;

import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The radio button and the check box as drawn (UI audit LO-03, LO-04): the dot sits in the middle of
 * its ring and the tick in the middle of its box, and the box is square, in both themes, at render
 * scales 1.0 and 1.25, selected, unselected and disabled. Measured in the pixels of a snapshot of the
 * control taken through a scale transform, and in its layout.
 *
 * <p>A scale transform is not a window at 125 %: the layout snaps to whole logical pixels here and
 * to 0.8 px steps there. It does show what an inset of a whole pixel does at both scales.
 */
@Tag("ui-snapshot")
class ChoiceControlsPixelTest {

    private static final double TOLERANCE = 0.25;

    static Stream<Arguments> cases() {
        List<Arguments> all = new ArrayList<>();
        for (Theme theme : Theme.values()) {
            for (double scale : new double[] {1.0, 1.25}) {
                for (State state : State.values()) {
                    all.add(Arguments.of(theme, scale, state));
                }
            }
        }
        return all.stream();
    }

    enum State {
        SELECTED(true, false),
        UNSELECTED(false, false),
        DISABLED_SELECTED(true, true),
        DISABLED(false, true);

        final boolean selected;
        final boolean disabled;

        State(boolean selected, boolean disabled) {
            this.selected = selected;
            this.disabled = disabled;
        }
    }

    @ParameterizedTest(name = "{0} x{1} {2}")
    @MethodSource("cases")
    void theDotSitsInTheMiddleOfItsRing(Theme theme, double scale, State state) {
        Measured radio =
                SnapshotHarness.inspect(theme, 120, 60, () -> holder(new RadioButton("Choice"), state), root -> {
                    Region ring = (Region) root.lookup(".radio");
                    Region dot = (Region) root.lookup(".dot");
                    return measure(ring, dot, scale, state.selected, false);
                });

        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(radio.ringWidth)
                    .as("the ring is round: as wide as it is high")
                    .isCloseTo(radio.ringHeight, within(TOLERANCE));
            soft.assertThat(radio.layoutOffset)
                    .as("the dot in the middle of the ring, in the layout")
                    .isLessThanOrEqualTo(TOLERANCE);
            soft.assertThat(radio.insetsZero)
                    .as("the dot drawn without Modena's 1 px offset")
                    .isTrue();
            if (state.selected) {
                soft.assertThat(radio.pixelDx)
                        .as("dot centre minus ring centre, x, in pixels")
                        .isBetween(-TOLERANCE, TOLERANCE);
                soft.assertThat(radio.pixelDy)
                        .as("dot centre minus ring centre, y, in pixels")
                        .isBetween(-TOLERANCE, TOLERANCE);
            }
        });
    }

    @ParameterizedTest(name = "{0} x{1} {2}")
    @MethodSource("cases")
    void theCheckBoxIsSquareAndItsTickSitsInTheMiddle(Theme theme, double scale, State state) {
        Measured box = SnapshotHarness.inspect(theme, 120, 60, () -> holder(new CheckBox("Choice"), state), root -> {
            Region square = (Region) root.lookup(".box");
            Region mark = (Region) root.lookup(".mark");
            return measure(square, mark, scale, state.selected, true);
        });

        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(box.layoutWidth).as("the box is 16 px wide").isEqualTo(16);
            soft.assertThat(box.layoutHeight).as("the box is 16 px high").isEqualTo(16);
            soft.assertThat(box.ringWidth).as("the drawn box is square").isCloseTo(box.ringHeight, within(TOLERANCE));
            soft.assertThat(box.layoutOffset)
                    .as("the tick in the middle of the box, in the layout")
                    .isLessThanOrEqualTo(TOLERANCE);
            soft.assertThat(box.insetsZero)
                    .as("the tick drawn without Modena's 1 px offset")
                    .isTrue();
            if (state.selected) {
                // The tick is not symmetric, so its drawn extent is compared, not its centroid; an edge
                // pixel half covered moves the extent by up to half a pixel at 1.25.
                soft.assertThat(box.pixelDx)
                        .as("tick centre minus box centre, x")
                        .isBetween(-0.5, 0.5);
                soft.assertThat(box.pixelDy)
                        .as("tick centre minus box centre, y")
                        .isBetween(-0.5, 0.5);
            }
        });
    }

    private static VBox holder(javafx.scene.control.ButtonBase control, State state) {
        if (control instanceof RadioButton radio) {
            radio.setSelected(state.selected);
        } else if (control instanceof CheckBox box) {
            box.setSelected(state.selected);
        }
        control.setDisable(state.disabled);
        VBox holder = new VBox(control);
        holder.setStyle("-fx-padding: 8;");
        return holder;
    }

    /** What a control's outer part (ring or box) and inner part (dot or tick) measure. */
    private record Measured(
            double layoutWidth,
            double layoutHeight,
            double layoutOffset,
            boolean insetsZero,
            double ringWidth,
            double ringHeight,
            double pixelDx,
            double pixelDy) {}

    private static Measured measure(Region outer, Region inner, double scale, boolean selected, boolean extent) {
        Bounds outerBounds = outer.getLayoutBounds();
        Bounds innerInOuter = inner.getBoundsInParent();
        double layoutOffset = Math.max(
                Math.abs(innerInOuter.getCenterX() - outerBounds.getCenterX()),
                Math.abs(innerInOuter.getCenterY() - outerBounds.getCenterY()));
        boolean insetsZero = inner.getBackground() == null
                || inner.getBackground().getFills().stream()
                        .map(BackgroundFill::getInsets)
                        .allMatch(insets -> insets.getTop() == 0
                                && insets.getRight() == 0
                                && insets.getBottom() == 0
                                && insets.getLeft() == 0);

        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        parameters.setTransform(Transform.scale(scale, scale));
        WritableImage image = outer.snapshot(parameters, null);
        PixelReader pixels = image.getPixelReader();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();

        // The outer part: every pixel it covers, weighted by its coverage.
        double sum = 0;
        double sumX = 0;
        double sumY = 0;
        int minY = height;
        double[] rows = new double[height];
        double[] columns = new double[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double alpha = pixels.getColor(x, y).getOpacity();
                if (alpha > 0) {
                    sum += alpha;
                    sumX += alpha * (x + 0.5);
                    sumY += alpha * (y + 0.5);
                    rows[y] += alpha;
                    columns[x] += alpha;
                }
                if (alpha >= 0.5) {
                    minY = Math.min(minY, y);
                }
            }
        }
        double centreX = sumX / sum;
        double centreY = sumY / sum;
        // The drawn size, with the coverage of the edge pixels: the widest row and the highest column.
        // A whole-pixel count would differ by one where the snapshot cuts an edge in half.
        double ringWidth = java.util.Arrays.stream(rows).max().orElse(0) / scale;
        double ringHeight = java.util.Arrays.stream(columns).max().orElse(0) / scale;
        if (!selected) {
            return new Measured(
                    outerBounds.getWidth(),
                    outerBounds.getHeight(),
                    layoutOffset,
                    insetsZero,
                    ringWidth,
                    ringHeight,
                    0,
                    0);
        }

        // The fill between the edge and the inner part, 2.5 px in from the top. The inner part is what
        // differs from the fill near the centre, in shares of the most any pixel there differs.
        Color fill = pixels.getColor((int) centreX, (int) Math.floor(minY + 2.5 * scale));
        double radius = 5.5 * scale;
        double full = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (Math.hypot(x + 0.5 - centreX, y + 0.5 - centreY) <= radius) {
                    full = Math.max(full, distance(pixels.getColor(x, y), fill));
                }
            }
        }
        double weight = 0;
        double weightX = 0;
        double weightY = 0;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        double left = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                if (Math.hypot(px - centreX, py - centreY) > radius) {
                    continue;
                }
                double share = Math.min(1, distance(pixels.getColor(x, y), fill) / full);
                weight += share;
                weightX += share * px;
                weightY += share * py;
                if (share >= 0.5) {
                    low = Math.min(low, y);
                    high = Math.max(high, y + 1);
                    left = Math.min(left, x);
                    right = Math.max(right, x + 1);
                }
            }
        }
        double dx;
        double dy;
        if (extent) {
            dx = (left + right) / 2 - centreX;
            dy = (low + high) / 2 - centreY;
        } else {
            dx = weightX / weight - centreX;
            dy = weightY / weight - centreY;
        }
        return new Measured(
                outerBounds.getWidth(),
                outerBounds.getHeight(),
                layoutOffset,
                insetsZero,
                ringWidth,
                ringHeight,
                dx,
                dy);
    }

    private static double distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed())
                + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue());
    }
}
