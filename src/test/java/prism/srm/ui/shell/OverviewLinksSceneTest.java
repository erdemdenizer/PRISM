package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.graph.HistoryGraph;
import prism.srm.ui.overview.OverviewPage;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.performance.PerformancePage;
import prism.srm.ui.performance.Section;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI-1 and owner decision K4: each part of the Overview leads to its page, with the mouse and with
 * the keyboard; K5: the arrow keys on a part read the past values of its graph instead; and the
 * header of the metric panels, read from a real scene at the scene sizes of real windows (K8).
 */
@Tag("ui-snapshot")
class OverviewLinksSceneTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";

    /** Where each part leads: the page, and for a metric the section of the Performance page. */
    private static final Map<String, List<Object>> TARGETS = Map.of(
            ".metric-cpu", List.of(Destination.PERFORMANCE, Section.CPU),
            ".metric-memory", List.of(Destination.PERFORMANCE, Section.MEMORY),
            ".metric-disk", List.of(Destination.PERFORMANCE, Section.DISK),
            ".metric-network", List.of(Destination.PERFORMANCE, Section.NETWORK),
            "Processes", List.of(Destination.PROCESSES),
            "Left suspended", List.of(Destination.PROCESSES),
            "Managed", List.of(Destination.LIMITS),
            "OOM Guard", List.of(Destination.OOM_GUARD));

    private static Stream<String> parts() {
        return TARGETS.keySet().stream().sorted();
    }

    @ParameterizedTest
    @MethodSource("parts")
    void aClickOnAPartOpensItsPage(String part) {
        List<Object> seen = inShell(1086, 655, shell -> {
            // From another section, so that the section is chosen and not merely kept.
            performancePage(shell).select(Section.NETWORK.equals(sectionOf(part)) ? Section.CPU : Section.NETWORK);
            Event.fireEvent(part(shell, part), click());
            return shown(shell, part);
        });

        assertThat(seen).isEqualTo(TARGETS.get(part));
    }

    @ParameterizedTest
    @MethodSource("parts")
    void enterOrSpaceOnAFocusedPartOpensItsPage(String part) {
        List<Object> seen = inShell(1086, 655, shell -> {
            List<Object> facts = new ArrayList<>();
            for (KeyCode key : new KeyCode[] {KeyCode.ENTER, KeyCode.SPACE}) {
                shell.navigation().entry(Destination.OVERVIEW).fire();
                performancePage(shell).select(Section.NETWORK.equals(sectionOf(part)) ? Section.CPU : Section.NETWORK);
                Node node = part(shell, part);
                node.requestFocus();
                press(node, key, false);
                facts.add(shown(shell, part));
            }
            return facts;
        });

        assertThat(seen).containsExactly(TARGETS.get(part), TARGETS.get(part));
    }

    /** A part is a button for the keyboard, the pointer and an assistive technology, and says where it leads. */
    @ParameterizedTest
    @MethodSource("parts")
    void aPartIsAButtonThatSaysWhereItLeads(String part) {
        List<Object> seen = inShell(1086, 655, shell -> {
            Node node = part(shell, part);
            return List.of(
                    node.isFocusTraversable(),
                    node.getAccessibleRole(),
                    node.getCursor(),
                    node.getStyleClass().contains("openable"),
                    node.getAccessibleText().contains(". Open "));
        });

        assertThat(seen).containsExactly(true, AccessibleRole.BUTTON, Cursor.HAND, true, true);
    }

    /** A click that was a drag, and a click of another button, open nothing. */
    @Test
    void aDragOrAnotherButtonOpensNothing() {
        List<Object> seen = inShell(1086, 655, shell -> {
            Node cpu = part(shell, ".metric-cpu");
            Event.fireEvent(cpu, mouseClick(MouseButton.PRIMARY, false));
            Event.fireEvent(cpu, mouseClick(MouseButton.SECONDARY, true));
            return List.of(shell.navigation().selected());
        });

        assertThat(seen).containsExactly(Destination.OVERVIEW);
    }

    /**
     * OP-03: the Overview is walked with Tab, its parts in the order of the page: the four metrics, then
     * the facts that lead somewhere. The system name and the uptime are on no other page and are no stop.
     */
    @Test
    void tabWalksThePartsInTheOrderOfThePage() {
        List<String> seen = inShell(1086, 655, shell -> {
            shell.navigationStart().requestFocus();
            List<String> stops = new ArrayList<>();
            for (int step = 0; step < 8; step++) {
                press(shell.getScene().getFocusOwner(), KeyCode.TAB, false);
                Node owner = shell.getScene().getFocusOwner();
                stops.add(
                        owner.getAccessibleText() == null
                                ? ""
                                : owner.getAccessibleText().split(":")[0]);
            }
            return stops;
        });

        assertThat(seen.subList(0, 7))
                .containsExactly("CPU", "Memory", "Disk", "Network", "Processes", "Managed", "OOM Guard");
    }

    /**
     * K5 on the Overview: Left and Right on a focused panel read its past values in the axis line and
     * in the words for a screen reader; they open nothing, and Escape ends the reading, then the
     * caption is back.
     */
    @Test
    void theArrowKeysOnAFocusedPanelReadItsPastValues() {
        List<Object> seen = inShell(1086, 655, shell -> {
            List<Object> facts = new ArrayList<>();
            Node network = part(shell, ".metric-network");
            network.requestFocus();
            press(network, KeyCode.LEFT, false);
            facts.add(axisLine(network));
            for (int step = 0; step < 23; step++) {
                press(network, KeyCode.LEFT, false);
            }
            facts.add(axisLine(network));
            facts.add(network.getAccessibleText());
            press(network, KeyCode.HOME, false);
            facts.add(axisLine(network));
            press(network, KeyCode.ESCAPE, false);
            facts.add(axisLine(network));
            facts.add(shell.navigation().selected());
            return facts;
        });

        // Snapshots.live(): download 150 KiB/s * (1 + sin(i / 6)) + 90 KiB/s after the 40th sample,
        // upload 12 KiB/s * (1 + cos(i / 2)); the newest sample is the 60th (index 59).
        assertThat(seen.get(0)).isEqualTo("Now 180.4 KiB/s 7.9 KiB/s 500 KiB/s");
        assertThat(seen.get(1)).isEqualTo("23 s ago 108.1 KiB/s 19.9 KiB/s 500 KiB/s");
        assertThat((String) seen.get(2))
                .startsWith("Network: Download 240.0 KiB/s, Upload 12.0 KiB/s. 23 seconds ago: Download 108.1 KiB/s,"
                        + " Upload 19.9 KiB/s. Open Performance, Network");
        assertThat(seen.get(3)).isEqualTo("59 s ago 150.0 KiB/s 24.0 KiB/s 500 KiB/s");
        assertThat(seen.get(4)).isEqualTo("Transfer rate, last 60 s 500 KiB/s");
        assertThat(seen.get(5)).isEqualTo(Destination.OVERVIEW);
    }

    /** A cursor of the keyboard goes with the focus: Tab to the next part ends the reading. */
    @Test
    void theReadingEndsWhenThePanelLosesTheFocus() {
        List<Object> seen = inShell(1086, 655, shell -> {
            Node cpu = part(shell, ".metric-cpu");
            cpu.requestFocus();
            press(cpu, KeyCode.LEFT, false);
            HistoryGraph graph = (HistoryGraph) cpu.lookup(".history-graph");
            int during = graph.cursorAgeProperty().get();
            press(cpu, KeyCode.TAB, false);
            return List.of(during, graph.cursorAgeProperty().get());
        });

        assertThat(seen).containsExactly(0, HistoryGraph.NO_CURSOR);
    }

    /**
     * K5 on the Performance page: the graph is a stop of its own (no panel there leads anywhere), and
     * the keys read it as on the Overview.
     */
    @Test
    void onThePerformancePageTheGraphIsTheStopThatIsRead() {
        List<Object> seen = inShell(1086, 655, shell -> {
            performancePage(shell).select(Section.CPU);
            shell.navigation().entry(Destination.PERFORMANCE).fire();
            shell.applyCss();
            shell.layout();
            Node graph = shell.pageArea().getContent().lookup(".history-graph");
            graph.requestFocus();
            press(graph, KeyCode.LEFT, false);
            press(graph, KeyCode.LEFT, true);
            Node panel = graph.getParent();
            return List.of(
                    graph.isFocusTraversable(), panel.isFocusTraversable(), axisLine(panel), graph.getAccessibleText());
        });

        assertThat(seen.get(0)).isEqualTo(true);
        assertThat(seen.get(1)).isEqualTo(false);
        // Snapshots.live(): CPU 14 + 9 * sin(i / 5), 30 more where i % 17 == 0; index 49 is 10 s old: 10.7.
        assertThat(seen.get(2)).isEqualTo("10 s ago 10.7 % 100 %");
        assertThat((String) seen.get(3)).isEqualTo("Total CPU: 12.4 %. 10 seconds ago: 10.7 %. Utilization, last 60 s");
    }

    /**
     * OP-04: no legend of a series is cut, at any of the scene sizes of real windows: each label is as
     * high as its text, so the descenders of "Upload" are not cut off.
     */
    @ParameterizedTest
    @MethodSource("realSizes")
    void noLegendIsCut(int width, int height) {
        List<String> cut = inShell(width, height, shell -> {
            List<String> found = new ArrayList<>();
            for (Node page : List.of(shell.pageArea().getContent())) {
                for (Node node : page.lookupAll(".series-legend")) {
                    Label legend = (Label) node;
                    Text text = (Text) legend.lookup(".text");
                    if (text.getLayoutBounds().getHeight() > legend.getHeight() + 0.01 || text.getClip() != null) {
                        found.add(legend.getText() + " " + legend.getHeight() + " < "
                                + text.getLayoutBounds().getHeight());
                    }
                }
            }
            return found;
        });

        assertThat(cut).isEmpty();
    }

    /**
     * OP-06: the names of the panels in one row stand on one line, whether a panel has one value or
     * two; OP-05: the two panels of facts below share the edges of the metrics above.
     */
    @ParameterizedTest
    @MethodSource("realSizes")
    void theNamesOfAPanelRowShareTheirLineAndTheFactsShareTheEdgesOfTheMetrics(int width, int height) {
        List<String> problems = inShell(width, height, shell -> {
            List<String> found = new ArrayList<>();
            Parent page = (Parent) shell.pageArea().getContent();
            List<Node> panels = new ArrayList<>(page.lookupAll(".metric-panel"));
            for (Node a : panels) {
                for (Node b : panels) {
                    double topA = a.localToScene(a.getLayoutBounds()).getMinY();
                    double topB = b.localToScene(b.getLayoutBounds()).getMinY();
                    if (Math.abs(topA - topB) < 0.5 && Math.abs(baseline(a) - baseline(b)) > 0.5) {
                        found.add("baseline " + name(a) + " " + baseline(a) + " / " + name(b) + " " + baseline(b));
                    }
                }
            }
            Bounds system = scene(page.lookup(".overview-system"));
            Bounds prism = scene(page.lookup(".overview-prism"));
            double left =
                    panels.stream().mapToDouble(p -> scene(p).getMinX()).min().orElseThrow();
            double right =
                    panels.stream().mapToDouble(p -> scene(p).getMaxX()).max().orElseThrow();
            if (Math.abs(system.getMinX() - left) > 0.5 || Math.abs(prism.getMaxX() - right) > 0.5) {
                found.add("outer edges " + system + " " + prism + " " + left + " " + right);
            }
            // Where the metrics stand in columns, the inner edge of the facts is that of a column.
            boolean edgeOfAColumn = panels.stream().anyMatch(p -> Math.abs(scene(p).getMinX() - prism.getMinX()) < 0.5);
            boolean oneColumn = panels.stream().allMatch(p -> Math.abs(scene(p).getMinX() - left) < 0.5);
            if (!oneColumn && !edgeOfAColumn) {
                found.add("inner edge " + prism.getMinX() + " "
                        + panels.stream()
                                .map(p -> scene(p).getMinX() + "-" + scene(p).getMaxX())
                                .toList());
            }
            return found;
        });

        assertThat(problems).isEmpty();
    }

    static Stream<Arguments> realSizes() {
        return RealSceneSizes.ALL.stream().map(size -> Arguments.of(size.width(), size.height()));
    }

    private static Bounds scene(Node node) {
        return node.localToScene(node.getLayoutBounds());
    }

    private static double baseline(Node panel) {
        Label title = (Label) panel.lookup(".text-section-title");
        return title.localToScene(0, title.getBaselineOffset()).getY();
    }

    private static String name(Node panel) {
        return ((Label) panel.lookup(".text-section-title")).getText();
    }

    /** The visible texts of the axis line of a panel, joined by spaces. */
    private static String axisLine(Node panel) {
        List<String> texts = new ArrayList<>();
        collect(panel.lookup(".metric-axis"), texts);
        return String.join(" ", texts);
    }

    private static void collect(Node node, List<String> texts) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Label label) {
            if (!label.getText().isEmpty()) {
                texts.add(label.getText());
            }
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, texts);
            }
        }
    }

    private static Section sectionOf(String part) {
        List<Object> target = TARGETS.get(part);
        return target.size() > 1 ? (Section) target.get(1) : null;
    }

    /** The page on screen, and for the Performance page the section it shows. */
    private static List<Object> shown(Shell shell, String part) {
        Destination selected = shell.navigation().selected();
        Node page = shell.pageArea().getContent();
        if (selected == Destination.PERFORMANCE && page instanceof PerformancePage performance) {
            return List.of(selected, performance.selected());
        }
        return List.of(selected);
    }

    private static PerformancePage performancePage(Shell shell) {
        shell.navigation().entry(Destination.PERFORMANCE).fire();
        PerformancePage page = (PerformancePage) shell.pageArea().getContent();
        shell.navigation().entry(Destination.OVERVIEW).fire();
        return page;
    }

    /** A part of the Overview: a metric panel by its style class, a fact by its caption. */
    private static Node part(Shell shell, String part) {
        Node page = shell.pageArea().getContent();
        assertThat(page).isInstanceOf(OverviewPage.class);
        if (part.startsWith(".")) {
            return page.lookup(part);
        }
        return page.lookupAll(".fact").stream()
                .filter(fact -> ((Label)
                                ((Parent) fact).getChildrenUnmodifiable().getFirst())
                        .getText()
                        .equals(part))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fact " + part));
    }

    private static <T> T inShell(int width, int height, Function<Shell, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> {
                    Shell shell = new Shell(
                            new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, id -> {});
                    shell.showSnapshot(Snapshots.live());
                    shell.showLimits(LimitsFixtures.snapshot(LimitsFixtures.managed(LimitsFixtures.identity(10), "a")));
                    // One process an earlier session left suspended: the fact is shown.
                    shell.showRecovered(List.of(LimitsFixtures.userLeft(LimitsFixtures.identity(30), "java.exe")));
                    return shell;
                },
                root -> scenario.apply((Shell) root));
    }

    private static MouseEvent click() {
        return mouseClick(MouseButton.PRIMARY, true);
    }

    private static MouseEvent mouseClick(MouseButton button, boolean stillSincePress) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                20,
                20,
                20,
                20,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                false,
                false,
                stillSincePress,
                null);
    }

    private static void press(Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, false, false, false));
    }
}
