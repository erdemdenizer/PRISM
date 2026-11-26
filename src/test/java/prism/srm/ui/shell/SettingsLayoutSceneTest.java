package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.ThemeMode;
import prism.srm.ui.settings.SettingsPage;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The Settings page at the scene sizes of real windows, at 100 % and at 125 % (fix brief K8), laid
 * out as a window of that scale lays it out (UI audit LS-17, LS-06; fix round H): in a low and wide
 * window the summary of what the system offers stands beside the other sections, its headers in view
 * without scrolling; in a tall window the sections stay under one another; the cells of the summary
 * are never cut and nothing reaches past the right of the page.
 */
@Tag("ui-snapshot")
class SettingsLayoutSceneTest {

    /** A scene size, the scale of the window and the outer size of that window. */
    record Case(double width, double height, double scale, String window) {
        @Override
        public String toString() {
            return window + " at " + Math.round(scale * 100) + " %";
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> all = new ArrayList<>();
        for (RealSceneSizes.Size size : RealSceneSizes.ALL) {
            all.add(Arguments.of(new Case(size.width(), size.height(), 1.0, size.window())));
        }
        for (ScaledScenes.Size size : ScaledScenes.AT_125) {
            all.add(Arguments.of(new Case(size.width(), size.height(), ScaledScenes.SCALE_125, size.window())));
        }
        return all.stream();
    }

    private record Measured(
            boolean besideFirst,
            double summaryTop,
            double firstTop,
            double headersBottom,
            double viewportBottom,
            double rightmost,
            double viewportRight,
            List<String> cut) {}

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theSummaryUsesTheWidthOfALowWindowAndIsNeverCut(Case size) {
        String home = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager";
        Measured seen = ScaledScenes.inspect(
                Theme.DARK,
                size.width(),
                size.height(),
                size.scale(),
                () -> {
                    Shell shell = SettingsSnapshotTest.shell(new SettingsPage.Hooks(
                            ThemeMode.DARK,
                            mode -> {},
                            home + "\\logs",
                            home + "\\settings",
                            () -> {},
                            () -> {},
                            Optional.empty()));
                    shell.navigation().entry(Destination.SETTINGS).fire();
                    SettingsSnapshotTest.all(shell);
                    return shell;
                },
                SettingsLayoutSceneTest::measure);

        boolean low = size.window().equals("1600x500");
        boolean tall = size.window().equals("1920x1032")
                || size.window().equals("1920x1080")
                || size.window().equals("900x1200");
        SoftAssertions.assertSoftly(soft -> {
            if (low) {
                soft.assertThat(seen.besideFirst())
                        .as("LS-17: in a low and wide window the summary stands beside the other sections")
                        .isTrue();
                soft.assertThat(seen.headersBottom())
                        .as("LS-17: the headers of the summary are in view without scrolling")
                        .isLessThanOrEqualTo(seen.viewportBottom());
            }
            if (tall) {
                soft.assertThat(seen.besideFirst())
                        .as("LS-17: in a tall window the sections stay under one another")
                        .isFalse();
            }
            if (seen.besideFirst()) {
                soft.assertThat(seen.summaryTop())
                        .as("LS-17: the two columns begin on one line")
                        .isCloseTo(seen.firstTop(), within(0.5));
            }
            soft.assertThat(seen.rightmost())
                    .as("nothing reaches past the right of the page")
                    .isLessThanOrEqualTo(seen.viewportRight() + 0.5);
            soft.assertThat(seen.cut())
                    .as("LS-06: the cells of the fixed columns are drawn whole")
                    .isEmpty();
        });
    }

    private static Measured measure(Parent root) {
        Shell shell = (Shell) root;
        ScrollPane area = shell.pageArea();
        Node page = area.getContent();
        Node columns = page.lookup(".settings-columns");
        Parent parent = (Parent) columns;
        Node first = parent.getChildrenUnmodifiable().get(0);
        Node summary = parent.getChildrenUnmodifiable().get(1);
        Bounds firstBounds = first.localToScene(first.getLayoutBounds());
        Bounds summaryBounds = summary.localToScene(summary.getLayoutBounds());
        Node headers = page.lookup(".settings-capability-headers");
        Bounds headerBounds = headers.localToScene(headers.getLayoutBounds());
        Node viewport = area.lookup(".viewport");
        Bounds viewportBounds = viewport.localToScene(viewport.getLayoutBounds());
        double rightmost = 0;
        for (Node section : page.lookupAll(".settings-section")) {
            rightmost = Math.max(
                    rightmost, section.localToScene(section.getLayoutBounds()).getMaxX());
        }
        List<String> cut = new ArrayList<>();
        for (String style : List.of(".settings-capability-header", ".settings-capability")) {
            for (Node node : page.lookupAll(style)) {
                Label cell = (Label) node;
                if (cell.isWrapText()) {
                    continue;
                }
                Node text = cell.lookup(".text");
                String drawn = text instanceof Text shown ? shown.getText() : "";
                if (!drawn.equals(cell.getText())) {
                    cut.add(drawn + " ≠ " + cell.getText());
                }
            }
        }
        return new Measured(
                summaryBounds.getMinX() > firstBounds.getMaxX(),
                summaryBounds.getMinY(),
                firstBounds.getMinY(),
                headerBounds.getMaxY(),
                viewportBounds.getMaxY(),
                rightmost,
                viewportBounds.getMaxX(),
                cut);
    }
}
