package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;
import static prism.srm.ui.LimitsFixtures.managed;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Fix round G (LO-01, LO-02): in the panel of the Limits page, wherever it is, no label is squeezed
 * until it wraps a character a line, and no two buttons overlap. The audit saw both at 1163 x 739, the
 * scene of a 1177 x 784 window, with the panel under the table in columns: the scene widths from 1086 to
 * 1320 and every real scene size are tried, with a managed process that has no CPU limit (the longest
 * texts) and one that is throttled under an emulated limit.
 */
@Tag("ui-snapshot")
class LimitEditorFitSceneTest {

    /** A label narrower than this, or than all of its text where that is shorter, is squeezed. */
    private static final double LEAST_LABEL = 80;

    private static Stream<Arguments> sizes() {
        Stream.Builder<Arguments> sizes = Stream.builder();
        for (int width = 1086; width <= 1320; width += 26) {
            for (int height : new int[] {655, 739}) {
                sizes.add(Arguments.of(width, height));
            }
        }
        // The scenes of real windows (fix brief, K8): 720 x 480 to 1920 x 1032 outside.
        for (int[] scene : new int[][] {
            {706, 435}, {746, 515}, {1086, 655}, {1163, 739}, {1352, 723}, {1426, 855}, {1586, 455}, {1906, 987}
        }) {
            sizes.add(Arguments.of(scene[0], scene[1]));
        }
        return sizes.build();
    }

    @ParameterizedTest(name = "{0} x {1}")
    @MethodSource("sizes")
    void noLabelOfThePanelIsSqueezedAndNoButtonsOverlap(int width, int height) {
        List<String> seen = new ArrayList<>();
        for (boolean throttled : new boolean[] {false, true}) {
            seen.addAll(SnapshotHarness.inspect(Theme.LIGHT, width, height, () -> shell(throttled), root -> {
                Shell shell = (Shell) root;
                TableView<?> table =
                        (TableView<?>) shell.pageArea().getContent().lookup(".limits-table");
                table.getSelectionModel().select(0);
                settle(shell);
                // The whole shell: in a narrow window the button stands in the shell's toolbar, and the
                // panel is in the sheet only once the sheet opens (round G).
                shell.lookupAll(".button").stream()
                        .map(Button.class::cast)
                        .filter(button -> "Details and actions".equals(button.getText()))
                        .filter(Node::isVisible)
                        .findFirst()
                        .ifPresent(Button::fire);
                settle(shell);
                Node panel = shell.lookup(".limit-editor");
                return problems(panel, throttled ? "throttled" : "no CPU limit");
            }));
        }

        assertThat(seen).as("squeezed labels and overlapping buttons").isEmpty();
    }

    private static List<String> problems(Node panel, String state) {
        List<String> problems = new ArrayList<>();
        if (panel == null || panel.getScene() == null || !panel.isVisible()) {
            problems.add(state + ": no panel on screen");
            return problems;
        }
        List<Label> labels = new ArrayList<>();
        List<Button> buttons = new ArrayList<>();
        collect(panel, labels, buttons);
        for (Label label : labels) {
            String text = label.getText();
            double needs = Math.min(LEAST_LABEL, label.prefWidth(-1));
            if (text != null && !text.isBlank() && label.getWidth() + 0.5 < needs) {
                problems.add(state + ": label \"" + text + "\" is " + label.getWidth() + " px wide");
            }
        }
        for (int i = 0; i < buttons.size(); i++) {
            Bounds one = shrunk(buttons.get(i));
            for (int j = i + 1; j < buttons.size(); j++) {
                if (one.intersects(shrunk(buttons.get(j)))) {
                    problems.add(state + ": \"" + buttons.get(i).getText() + "\" overlaps \""
                            + buttons.get(j).getText() + "\"");
                }
            }
        }
        return problems;
    }

    /** The visible labels and buttons in a part of the scene; a button's own label is part of it. */
    private static void collect(Node node, List<Label> labels, List<Button> buttons) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Button button) {
            buttons.add(button);
            return;
        }
        if (node instanceof Label label) {
            labels.add(label);
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, labels, buttons);
            }
        }
    }

    /** Its bounds in the scene, half a pixel in at each side: buttons that only touch do not overlap. */
    private static Bounds shrunk(Node node) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        return new javafx.geometry.BoundingBox(
                bounds.getMinX() + 0.5,
                bounds.getMinY() + 0.5,
                Math.max(0, bounds.getWidth() - 1),
                Math.max(0, bounds.getHeight() - 1));
    }

    private static Shell shell(boolean throttled) {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs",
                identity -> {},
                request -> {},
                request -> {},
                request -> {},
                () -> {});
        shell.navigation().entry(Destination.LIMITS).fire();
        shell.showLimits(
                throttled
                        ? new LimitsSnapshot(
                                List.of(LimitsFixtures.throttling(identity(30_324), "java.exe", 30, 29.6)),
                                LimitsFixtures.EMULATED,
                                Optional.empty())
                        : new LimitsSnapshot(
                                List.of(managed(identity(23_816), "Adobe Desktop Service")),
                                LimitsFixtures.NATIVE,
                                Optional.empty()));
        return shell;
    }

    private static void settle(Shell shell) {
        for (int pass = 0; pass < 3; pass++) {
            shell.applyCss();
            shell.layout();
        }
    }
}
