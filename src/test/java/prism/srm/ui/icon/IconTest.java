package prism.srm.ui.icon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** Every icon has path data that JavaFX accepts, fits its grid, and takes its color from the theme. */
class IconTest {

    /** SVG path data: commands of the path grammar with numbers, nothing else; JavaFX would draw nothing for a typo without a word. */
    private static final java.util.regex.Pattern PATH_DATA = java.util.regex.Pattern.compile(
            "[Mm](?:[ ,]*-?(?:\\d+\\.?\\d*|\\.\\d+)){2}(?:[ ,]*(?:[MmLlHhVvCcSsQqTtAaZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)))*");

    @Test
    void everyIconHasAtLeastOneStrokeAndEveryStrokeIsWellFormedPathData() {
        for (Icon icon : Icon.values()) {
            assertThat(icon.paths()).as(icon.name()).isNotEmpty();
            for (String path : icon.paths()) {
                assertThat(path)
                        .as(icon.name())
                        .isNotBlank()
                        .doesNotContain("\n")
                        .matches(PATH_DATA);
            }
        }
    }

    /** A view is as large as asked, and its strokes stay inside that square. */
    @Test
    @Tag("ui-snapshot")
    void aViewIsTheSizeAskedForAndItsStrokesLieInsideIt() {
        List<String> outside = SnapshotHarness.inspect(Theme.LIGHT, 200, 200, StackPane::new, root -> {
            List<String> complaints = new ArrayList<>();
            for (Icon icon : Icon.values()) {
                IconView view = new IconView(icon, 16);
                ((StackPane) root).getChildren().setAll(view);
                root.applyCss();
                root.layout();
                if (view.getWidth() != 16 || view.getHeight() != 16) {
                    complaints.add(icon + " is " + view.getWidth() + " x " + view.getHeight());
                }
                Bounds strokes = view.getChildrenUnmodifiable().getFirst().getBoundsInParent();
                // A row of dots (ELLIPSIS, UI lab) is a flat glyph by design: wide, not tall.
                boolean flat = icon == Icon.ELLIPSIS && strokes.getWidth() >= 4;
                if (strokes.isEmpty() || strokes.getWidth() < 4 || (strokes.getHeight() < 4 && !flat)) {
                    complaints.add(icon + " draws nothing: " + strokes);
                }
                for (Node stroke :
                        ((javafx.scene.Group) view.getChildrenUnmodifiable().getFirst()).getChildren()) {
                    Bounds one = stroke.getLayoutBounds();
                    if (one.isEmpty() || (one.getWidth() == 0 && one.getHeight() == 0)) {
                        complaints.add(icon + " has a stroke that draws nothing");
                    }
                }
                // Round caps and joins reach a stroke width beyond the grid; nothing more.
                if (strokes.getMinX() < -1
                        || strokes.getMinY() < -1
                        || strokes.getMaxX() > 17
                        || strokes.getMaxY() > 17) {
                    complaints.add(icon + " strokes " + strokes);
                }
            }
            return complaints;
        });

        assertThat(outside).isEmpty();
    }

    /** The stroke is set in screen units (UI lab v2): 1.5 px at 16, 1.6 at 20, whatever the grid. */
    @Test
    void theStrokeWidthIsFixedInScreenUnitsPerSize() {
        assertThat(IconView.strokeInUnits(16) * 16 / Icon.GRID)
                .isCloseTo(1.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(IconView.strokeInUnits(20) * 20 / Icon.GRID)
                .isCloseTo(1.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(IconView.strokeInUnits(12) * 12 / Icon.GRID)
                .isCloseTo(1.25, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(IconView.strokeInUnits(24) * 24 / Icon.GRID)
                .isCloseTo(1.75, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** The color is the token the context sets through -srm-icon, in both themes. */
    @Test
    @Tag("ui-snapshot")
    void theStrokesTakeTheColorOfTheirContextInBothThemes() {
        for (Theme theme : Theme.values()) {
            Color stroke = SnapshotHarness.inspect(
                    theme,
                    64,
                    64,
                    () -> {
                        StackPane context = new StackPane(new IconView(Icon.SEARCH, 16));
                        context.setStyle("-srm-icon: -srm-accent;");
                        context.getStylesheets().add(Stylesheets.app());
                        return context;
                    },
                    root -> {
                        Node shape = root.lookup(".icon-shape");
                        return (Color) ((SVGPath) shape).getStroke();
                    });

            assertThat(stroke).as(theme.name()).isEqualTo(Color.web(theme == Theme.DARK ? "#7c8cff" : "#4f5bd5"));
        }
    }
}
