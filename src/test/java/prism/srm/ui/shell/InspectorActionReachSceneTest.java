package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static prism.srm.ui.LimitsFixtures.identity;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.application.FakeProcessController;
import prism.srm.domain.ControlAction;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.OperationResult;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.control.InspectorFrame;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Fix round H (PR-03, LO-06): in a low window the actions of an inspector are on screen without
 * scrolling. At every acceptance size of the owner, at 100 % and at 125 % (the scene sizes of a real
 * Windows frame, fix brief K8), on the Processes and the Limits page, in the states with the most lines
 * (every reason shown, a result, an offer that adds a button), each action button of the inspector (or,
 * where the inspector is compact, the labelled menu that holds a group) is visible, wholly inside every
 * scroll viewport and clip around it at the scroll position it has, and in the pinned part of the frame,
 * which does not scroll; no two of them overlap; the lines with the reasons of disabled actions (CAP-2)
 * and the line with the last result are wholly visible too. Where the inspector is a sheet, the sheet is
 * opened first, as the user would.
 *
 * <p>Each case also writes a {@code -reach-real} render, named by the outer window size and the scale.
 */
@Tag("ui-snapshot")
class InspectorActionReachSceneTest {

    /** A scene size of a real window: its outer size, the scale, and the scene Windows leaves of it. */
    record RealSize(int outerWidth, int outerHeight, int scale, int width, int height) {
        String label() {
            return outerWidth + "x" + outerHeight + "-" + scale;
        }

        @Override
        public String toString() {
            return label() + " (" + width + "x" + height + ")";
        }
    }

    /** The owner's acceptance sizes (fix brief H), at 100 % and at 125 %. */
    static final List<RealSize> SIZES = List.of(
            new RealSize(720, 480, 100, 706, 435),
            new RealSize(720, 480, 125, 705, 442),
            new RealSize(900, 1200, 100, 886, 1155),
            new RealSize(900, 1200, 125, 885, 1162),
            new RealSize(1100, 700, 100, 1086, 655),
            new RealSize(1100, 700, 125, 1085, 662),
            new RealSize(1366, 768, 100, 1352, 723),
            new RealSize(1366, 768, 125, 1352, 730),
            new RealSize(1600, 500, 100, 1586, 455),
            new RealSize(1600, 500, 125, 1585, 462));

    /** Processes: managed with a result; every action disabled; not managed. */
    private static Stream<Arguments> processes() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (RealSize size : SIZES) {
            cases.add(Arguments.of("result", size, (Consumer<InspectorRealSizeSnapshotTest.ProcessesState>) state -> {
                state.selectedAndManaged();
                state.result(
                        ControlAction.END_TASK,
                        OperationResult.SUCCESS,
                        "Close request sent to audiodg.exe (PID 268).",
                        "The application may ignore it.");
            }));
            cases.add(Arguments.of("disabled", size, (Consumer<InspectorRealSizeSnapshotTest.ProcessesState>) state -> {
                state.selectedAndManaged();
                state.capabilities(FakeProcessController.noneAvailable());
            }));
            cases.add(Arguments.of("unmanaged", size, (Consumer<InspectorRealSizeSnapshotTest.ProcessesState>)
                    InspectorRealSizeSnapshotTest.ProcessesState::selectedNotManaged));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "processes {0} {1}")
    @MethodSource("processes")
    void theActionsOfTheProcessInspectorAreOnScreen(
            String state, RealSize size, Consumer<InspectorRealSizeSnapshotTest.ProcessesState> setUp) {
        java.util.function.Supplier<Parent> content = () -> {
            InspectorRealSizeSnapshotTest.ProcessesState shown = new InspectorRealSizeSnapshotTest.ProcessesState();
            setUp.accept(shown);
            return shown.shell();
        };
        Path png = SnapshotHarness.render(
                "processes-reach-" + state + "-real-" + size.label(),
                Theme.LIGHT,
                size.width(),
                size.height(),
                content,
                root -> openSheet((Shell) root, ".process-details-open"));
        assertThat(png).isRegularFile();

        List<String> problems = SnapshotHarness.inspect(Theme.LIGHT, size.width(), size.height(), content, root -> {
            Shell shell = (Shell) root;
            openSheet(shell, ".process-details-open");
            settle(shell);
            Node inspector = shell.lookup(".process-details");
            java.util.Set<Node> actions = new java.util.LinkedHashSet<>();
            for (Node node : inspector.lookupAll(".process-action")) {
                actions.add(node);
            }
            for (Node node : inspector.lookupAll(".inspector-limit-action")) {
                actions.add(node);
            }
            for (Node node : inspector.lookupAll(".inspector-limits-menu")) {
                actions.add(node);
            }
            List<Node> lines = new ArrayList<>();
            lines.addAll(inspector.lookupAll(".inspector-limits-reasons"));
            lines.addAll(inspector.lookupAll(".process-action-message"));
            return problems(shell, inspector, actions, lines, List.of("Suspend", "End task…", "Force kill…"));
        });

        assertThat(problems).as("processes " + state + " at " + size).isEmpty();
    }

    /** Limits: no CPU limit on a system with a native memory cap (every offer shown); a result; nothing selected. */
    private static Stream<Arguments> limits() {
        Stream.Builder<Arguments> cases = Stream.builder();
        for (RealSize size : SIZES) {
            cases.add(Arguments.of("offers", size, (Consumer<Shell>) shell -> {
                shell.showLimits(LimitsFixtures.nativeSnapshot(
                        List.of(LimitsFixtures.enforced(identity(23_816), "Adobe Desktop Service", 0, 0))));
                selectLimit(shell);
            }));
            cases.add(Arguments.of("result", size, (Consumer<Shell>) shell -> {
                shell.showLimits(LimitsFixtures.nativeSnapshot(
                        List.of(LimitsFixtures.enforced(identity(30_324), "java.exe", 30, 512))));
                selectLimit(shell);
                pressLimit(shell, "Disable");
                shell.showLimitResult(new LimitResult(
                        LimitRequest.of(LimitAction.DISABLE, identity(30_324), "java.exe"),
                        prism.srm.domain.ControlOutcome.success(),
                        "The limits of java.exe (PID 30324) are disabled.",
                        "Prism no longer stops it."));
            }));
            cases.add(Arguments.of("none", size, (Consumer<Shell>)
                    shell -> shell.showLimits(LimitsFixtures.nativeSnapshot(
                            List.of(LimitsFixtures.enforced(identity(23_816), "Adobe Desktop Service", 0, 0))))));
        }
        return cases.build();
    }

    @ParameterizedTest(name = "limits {0} {1}")
    @MethodSource("limits")
    void theActionsOfTheLimitsPanelAreOnScreen(String state, RealSize size, Consumer<Shell> setUp) {
        java.util.function.Supplier<Parent> content = () -> {
            Shell shell = limitsShell();
            setUp.accept(shell);
            return shell;
        };
        Path png = SnapshotHarness.render(
                "limits-reach-" + state + "-real-" + size.label(),
                Theme.LIGHT,
                size.width(),
                size.height(),
                content,
                root -> openSheet((Shell) root, ".limits-details"));
        assertThat(png).isRegularFile();

        List<String> problems = SnapshotHarness.inspect(Theme.LIGHT, size.width(), size.height(), content, root -> {
            Shell shell = (Shell) root;
            openSheet(shell, ".limits-details");
            settle(shell);
            Node panel = shell.lookup(".limit-editor");
            java.util.Set<Node> actions = new java.util.LinkedHashSet<>();
            for (Node node : panel.lookupAll(".button")) {
                if (node.getStyleClass().contains("sheet-close")) {
                    continue;
                }
                actions.add(node);
            }
            List<Node> lines = new ArrayList<>();
            lines.addAll(panel.lookupAll(".limit-editor-reasons"));
            lines.addAll(panel.lookupAll(".limits-action-message"));
            return problems(shell, panel, actions, lines, List.of("Unmanage"));
        });

        assertThat(problems).as("limits " + state + " at " + size).isEmpty();
    }

    /**
     * What is wrong: an action not on screen whole, two actions that overlap, a line of reasons or of the
     * result not on screen whole, an action the case expects that is not there, or a frame that does not
     * keep its pinned part out of the scrolling.
     */
    private static List<String> problems(
            Shell shell, Node inspector, java.util.Collection<Node> actions, List<Node> lines, List<String> expected) {
        List<String> problems = new ArrayList<>();
        if (!shown(inspector)) {
            problems.add("the inspector is not shown");
            return problems;
        }
        InspectorFrame frame = (InspectorFrame) inspector.lookup(".inspector-frame");
        if (!frame.isPinned()) {
            problems.add("the actions scroll with the details: the frame has no room to pin them");
        }
        List<String> names = new ArrayList<>();
        List<Bounds> placed = new ArrayList<>();
        List<String> placedNames = new ArrayList<>();
        for (Node action : actions) {
            if (!action.isManaged() || !shown(action)) {
                continue;
            }
            String name = name(action);
            names.add(name);
            Bounds bounds = action.localToScene(action.getLayoutBounds());
            Bounds visible = visibleArea(action);
            if (!inside(bounds, visible)) {
                problems.add(name + " is not on screen whole: " + describe(bounds) + " in " + describe(visible));
            }
            for (int index = 0; index < placed.size(); index++) {
                if (overlap(placed.get(index), bounds)) {
                    problems.add(name + " overlaps " + placedNames.get(index));
                }
            }
            placed.add(bounds);
            placedNames.add(name);
        }
        for (String name : expected) {
            if (!names.contains(name)) {
                problems.add(name + " is not there");
            }
        }
        for (Node line : lines) {
            if (!line.isManaged() || !shown(line) || line.getLayoutBounds().getHeight() <= 0) {
                continue;
            }
            Bounds bounds = line.localToScene(line.getLayoutBounds());
            if (!inside(bounds, visibleArea(line))) {
                problems.add("the line " + line.getStyleClass() + " is not on screen whole: " + describe(bounds));
            }
            for (int index = 0; index < placed.size(); index++) {
                if (overlap(placed.get(index), bounds)) {
                    problems.add("the line " + line.getStyleClass() + " overlaps " + placedNames.get(index));
                }
            }
        }
        if (shell.getScene() == null) {
            problems.add("no scene");
        }
        return problems;
    }

    private static String name(Node action) {
        String text = action instanceof Labeled labeled ? labeled.getText() : null;
        if (action instanceof ButtonBase button && button.getAccessibleText() != null) {
            return button.getAccessibleText();
        }
        return text == null ? action.toString() : text;
    }

    /** The scene, less what every scroll viewport and clip around the node leaves of it. */
    private static Bounds visibleArea(Node node) {
        double minX = 0;
        double minY = 0;
        double maxX = node.getScene().getWidth();
        double maxY = node.getScene().getHeight();
        for (Parent at = node.getParent(); at != null; at = at.getParent()) {
            Bounds limit = null;
            if (at instanceof ScrollPane scroll) {
                Node viewport = scroll.lookup(".viewport");
                if (viewport != null) {
                    limit = viewport.localToScene(viewport.getLayoutBounds());
                }
            } else if (at.getClip() != null) {
                limit = at.localToScene(at.getClip().getBoundsInParent());
            }
            if (limit != null) {
                minX = Math.max(minX, limit.getMinX());
                minY = Math.max(minY, limit.getMinY());
                maxX = Math.min(maxX, limit.getMaxX());
                maxY = Math.min(maxY, limit.getMaxY());
            }
        }
        return new BoundingBox(minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY));
    }

    private static boolean inside(Bounds inner, Bounds outer) {
        double slack = 0.5;
        return inner.getMinX() >= outer.getMinX() - slack
                && inner.getMinY() >= outer.getMinY() - slack
                && inner.getMaxX() <= outer.getMaxX() + slack
                && inner.getMaxY() <= outer.getMaxY() + slack;
    }

    private static boolean overlap(Bounds a, Bounds b) {
        double width = Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX());
        double height = Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY());
        return width > 0.5 && height > 0.5;
    }

    private static String describe(Bounds bounds) {
        return String.format(
                Locale.ROOT,
                "[%.1f, %.1f .. %.1f, %.1f]",
                bounds.getMinX(),
                bounds.getMinY(),
                bounds.getMaxX(),
                bounds.getMaxY());
    }

    private static boolean shown(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible() || at.getOpacity() <= 0) {
                return false;
            }
        }
        return node.getScene() != null;
    }

    private static void openSheet(Shell shell, String opener) {
        Node button = shell.toolbar().lookup(opener);
        if (button instanceof Button details && details.isVisible() && !details.isDisabled()) {
            details.fire();
        }
    }

    private static void settle(Parent root) {
        for (int pass = 0; pass < 3; pass++) {
            root.applyCss();
            root.layout();
        }
    }

    private static Shell limitsShell() {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                "C:\\logs",
                identity -> {},
                request -> {},
                request -> {},
                request -> {},
                () -> {});
        shell.navigation().entry(Destination.LIMITS).fire();
        return shell;
    }

    private static void selectLimit(Shell shell) {
        TableView<?> table = (TableView<?>) shell.pageArea().getContent().lookup(".limits-table");
        table.getSelectionModel().select(0);
        table.requestFocus();
    }

    private static void pressLimit(Shell shell, String text) {
        shell.pageArea().getContent().lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .fire();
    }
}
