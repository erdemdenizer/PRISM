package prism.srm.ui.shell;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Manual measurement (fix round H, perf): what a resize of the window costs on the JavaFX Application
 * Thread, split by phase, for a populated shell kept in ONE scene, as a window resize keeps it.
 *
 * <p>The sizes are the scene sizes of the real measurement (lab-spikes/perf/perf-run.ps1, "resize-40")
 * at 125 %: outer 1366x768, 1100x700, 800x600, 1440x900 and 720x480 are scenes of 1352x730.4,
 * 1085.6x662.4, 785.6x562.4, 1425.6x862.4 and 705.6x442.4 (FIX_BRIEF_H, K8). Each step crosses a line
 * of the layout. A second sequence stays in one layout (all sizes 1330 to 1426 wide, 730 to 862 high:
 * the rail, the inspector beside the table) and gives what a plain resize costs.
 *
 * <p>A step is what a window resize does: the scene resizes its root at once (the size listeners run
 * here, "resize"), then a pulse runs the scene's CSS pass and layout pass and syncs the scene ("pulse",
 * done as the snapshot of a one-pixel node, which runs those passes as a pulse does: only what is dirty;
 * {@code Node.applyCss()} would force a CSS update of the whole tree and measure that instead); a layout
 * that left work for the next pulse is done by two more such passes ("more"). With {@code -Dprobe.render=true} a
 * snapshot of the scene follows, which syncs and renders the whole scene (an upper bound of the render,
 * which on screen paints the dirty region only). The figures are the median per step of each round and
 * the CPU time of the JavaFX Application Thread per 40 steps, the unit of the real measurement.
 *
 * <p>Sequences besides "crossing" and "still", none of them timed per step: "count" counts per crossing
 * step the nodes made for the first time and the nodes that changed their parent; "dirty" lists the nodes
 * a crossing step leaves for the next pulse; "parts" times single mechanisms at one size (a pseudo-class
 * of the shell turned on and off, the table or the navigation taken out and put back) and counts what each
 * leaves for the next pulse.
 *
 * <p>The class name keeps it out of every build. Run it with {@code ./mvnw -o -Pui-snapshots test
 * -Dtest=BreakpointCrossingProbe}; it needs a display. {@code -Dprobe.pages=Processes,Limits} chooses the
 * pages (by the name of their entry), {@code -Dprobe.rounds=5} the measured rounds of 40 steps, {@code
 * -Dprobe.sequences=crossing,still,count,dirty,parts} the sequences, {@code -Dprobe.select=true} selects
 * the first row of a table before measuring. The source compiles unchanged against the classes of the
 * product baseline (19b7fbe) too, which is how the two are compared: lab-spikes/perf/breakpoint-probe.sh
 * runs it outside Maven against any tree, and docs/ui-lab/fix-h/PERF_DIAGNOSIS_TR.md says how.
 */
@Tag("ui-snapshot")
class BreakpointCrossingProbe {

    /** The scene sizes of the real "resize-40" measurement at 125 %. */
    private static final double[][] CROSSING = {
        {1352, 730.4}, {1085.6, 662.4}, {785.6, 562.4}, {1425.6, 862.4}, {705.6, 442.4}
    };

    /** Sizes in one layout: the rail, the inspector beside the table, wide, not low. */
    private static final double[][] STILL = {{1352, 730.4}, {1330, 760}, {1425.6, 862.4}, {1340, 800}, {1400, 740}};

    private static final int STEPS = 40;
    private static final int WARM_UP_ROUNDS = 3;
    private static final int ROUNDS = Integer.getInteger("probe.rounds", 5);
    private static final boolean RENDER = Boolean.getBoolean("probe.render");
    private static final boolean SELECT = Boolean.getBoolean("probe.select");
    private static final List<String> PAGES =
            List.of(System.getProperty("probe.pages", "Processes,Overview,Performance,Limits")
                    .split(","));
    private static final List<String> SEQUENCES = List.of(
            System.getProperty("probe.sequences", "crossing,still,count").split(","));
    private static final String[] PHASES = {"resize", "pulse", "more", "render"};

    /**
     * The same outside Maven, for JFR or for the classes of another tree: {@code java -cp <test classes>;<classes>;
     * <dependencies> prism.srm.ui.shell.BreakpointCrossingProbe}.
     */
    public static void main(String[] args) throws Exception {
        new BreakpointCrossingProbe().printTheCostOfCrossingTheLines();
        Platform.exit();
        System.exit(0);
    }

    @Test
    void printTheCostOfCrossingTheLines() throws Exception {
        // The harness starts the toolkit and loads the typeface, and builds the scene as every snapshot does;
        // the measurement itself runs in a task of its own, which may take longer than the harness waits.
        Parent root = SnapshotHarness.inspect(Theme.DARK, 1426, 863, BreakpointCrossingProbe::root, built -> built);
        Parent shell = (Parent) root.getChildrenUnmodifiable().get(0);
        for (String page : PAGES) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    size(shell, CROSSING[0]);
                    go(shell, page);
                    pulse(shell, new long[PHASES.length]);
                    if (SELECT) {
                        selectFirstRow(shell);
                    }
                    if (SEQUENCES.contains("crossing")) {
                        System.out.print(measure(shell, page, "crossing", CROSSING));
                    }
                    if (SEQUENCES.contains("still")) {
                        System.out.print(measure(shell, page, "still", STILL));
                    }
                    if (SEQUENCES.contains("count")) {
                        System.out.print(count(shell, page));
                    }
                    if (SEQUENCES.contains("dirty")) {
                        System.out.print(dirty(shell, page));
                    }
                    if (SEQUENCES.contains("parts")) {
                        System.out.print(parts(shell, page));
                    }
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            });
            done.get(30, TimeUnit.MINUTES);
        }
    }

    /** The shell, unmanaged in a pane: the probe gives it its size as the scene gives its root one. */
    private static Parent root() {
        Shell shell = KeyboardRoutesSceneTest.populated();
        shell.setManaged(false);
        Region marker = new Region();
        marker.setManaged(false);
        marker.resizeRelocate(0, 0, 1, 1);
        return new Pane(shell, marker);
    }

    private static String measure(Parent shell, String page, String name, double[][] sizes) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (int round = 0; round < WARM_UP_ROUNDS; round++) {
            steps(shell, sizes, new long[PHASES.length][STEPS]);
        }
        List<Double> cpu = new ArrayList<>();
        long[][] medians = new long[PHASES.length][ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
            long[][] perStep = new long[PHASES.length][STEPS];
            long before = threads.getCurrentThreadCpuTime();
            steps(shell, sizes, perStep);
            cpu.add((threads.getCurrentThreadCpuTime() - before) / 1e6);
            for (int phase = 0; phase < PHASES.length; phase++) {
                medians[phase][round] = median(perStep[phase]);
            }
        }
        StringBuilder line = new StringBuilder(String.format(
                Locale.ROOT,
                "PROBE %-11s %-8s FX CPU per %d steps: median %.0f ms, rounds %s; median per step (us):",
                page,
                name,
                STEPS,
                medianOf(cpu),
                cpu.stream().map(v -> String.format(Locale.ROOT, "%.0f", v)).toList()));
        for (int phase = 0; phase < PHASES.length; phase++) {
            if (phase == PHASES.length - 1 && !RENDER) {
                continue;
            }
            line.append(String.format(Locale.ROOT, " %s %d", PHASES[phase], median(medians[phase]) / 1000));
        }
        return line.append(System.lineSeparator()).toString();
    }

    private static void steps(Parent shell, double[][] sizes, long[][] perStep) {
        long[] phases = new long[PHASES.length];
        for (int step = 0; step < STEPS; step++) {
            Arrays.fill(phases, 0);
            long start = System.nanoTime();
            size(shell, sizes[step % sizes.length]);
            phases[0] = System.nanoTime() - start;
            pulse(shell, phases);
            for (int phase = 0; phase < PHASES.length; phase++) {
                perStep[phase][step] = phases[phase];
            }
        }
    }

    private static void size(Parent shell, double[] size) {
        shell.resize(size[0], size[1]);
    }

    /** A pulse, and the passes a layout that asked for more needs; then the render, if asked. */
    private static void pulse(Parent shell, long[] phases) {
        // Not shell.applyCss(): that forces a CSS update of every node under it, which a pulse never does.
        // A snapshot of a node runs the scene's own CSS pass and layout pass and syncs the scene, as a pulse
        // does; the node is one pixel, so what it renders is nothing.
        Node marker = shell.getParent().getChildrenUnmodifiable().get(1);
        long a = System.nanoTime();
        marker.snapshot(null, null);
        long b = System.nanoTime();
        for (int pass = 0; pass < 2; pass++) {
            marker.snapshot(null, null);
        }
        long c = System.nanoTime();
        phases[1] += b - a;
        phases[2] += c - b;
        if (RENDER) {
            shell.getScene().snapshot(null);
            phases[3] += System.nanoTime() - c;
        }
    }

    /**
     * What single mechanisms cost at one size (the first of the crossing sequence), each done and undone 20
     * times with a pulse after each: a pseudo-class of the shell turned on and off (every selector that
     * starts at the shell is then matched again in the whole scene), the table of the page taken out of
     * its parent and put back at its place (its rows and cells take their styles again), and the
     * navigation taken out of the border pane and put back.
     */
    private static String parts(Parent shell, String page) {
        size(shell, CROSSING[0]);
        pulse(shell, new long[PHASES.length]);
        StringBuilder out = new StringBuilder();
        out.append(part(page, "nothing", () -> {}, () -> {}, shell));
        for (String name : List.of("wide", "narrow", "low", "ultrawide")) {
            javafx.css.PseudoClass state = javafx.css.PseudoClass.getPseudoClass(name);
            boolean on = shell.getPseudoClassStates().contains(state);
            out.append(part(
                    page,
                    "shell :" + name + " flipped",
                    () -> shell.pseudoClassStateChanged(state, !on),
                    () -> {
                        shell.pseudoClassStateChanged(state, on);
                    },
                    shell));
        }
        Node table = shell.lookup(".table-view");
        if (table != null && table.getParent() instanceof Pane holder) {
            int at = holder.getChildren().indexOf(table);
            out.append(part(
                    page,
                    "table out and in",
                    () -> holder.getChildren().remove(table),
                    () -> {
                        holder.getChildren().add(at, table);
                    },
                    shell));
        } else if (table != null) {
            out.append(String.format(Locale.ROOT, "PART  %-11s table in a %s, not moved%n", page, table.getParent()));
        }
        if (shell instanceof javafx.scene.layout.BorderPane border && border.getLeft() != null) {
            Node left = border.getLeft();
            out.append(
                    part(page, "navigation out and in", () -> border.setLeft(null), () -> border.setLeft(left), shell));
        }
        return out.toString();
    }

    private static String part(String page, String name, Runnable change, Runnable undo, Parent shell) {
        long[] costs = new long[40];
        for (int i = 0; i < costs.length; i++) {
            long start = System.nanoTime();
            (i % 2 == 0 ? change : undo).run();
            pulse(shell, new long[PHASES.length]);
            costs[i] = System.nanoTime() - start;
        }
        long[] measured = Arrays.copyOfRange(costs, 4, costs.length);
        // Once more, with one pass only: how many nodes that pass leaves for the next pulse.
        Node marker = shell.getParent().getChildrenUnmodifiable().get(1);
        change.run();
        marker.snapshot(null, null);
        List<String> left = new ArrayList<>();
        deepestDirty(shell, left);
        undo.run();
        pulse(shell, new long[PHASES.length]);
        return String.format(
                Locale.ROOT,
                "PART  %-11s %-24s median %d us per change and pulse [%d..%d], %d nodes left for the next pulse%n",
                page,
                name,
                median(measured) / 1000,
                Arrays.stream(measured).min().orElse(0) / 1000,
                Arrays.stream(measured).max().orElse(0) / 1000,
                left.size());
    }

    /**
     * Which nodes still need a layout after the first pulse of a step of the crossing sequence: the work
     * that a window leaves for the next pulse. Per step, the deepest such nodes, by class and style class.
     */
    private static String dirty(Parent shell, String page) {
        Map<String, Integer> found = new java.util.TreeMap<>();
        Node marker = shell.getParent().getChildrenUnmodifiable().get(1);
        int dirtySteps = 0;
        for (int step = 0; step < STEPS; step++) {
            size(shell, CROSSING[step % CROSSING.length]);
            marker.snapshot(null, null);
            List<String> here = new ArrayList<>();
            deepestDirty(shell, here);
            if (!here.isEmpty()) {
                dirtySteps++;
            }
            for (String name : here) {
                found.merge(name, 1, Integer::sum);
            }
            pulse(shell, new long[PHASES.length]);
        }
        return String.format(
                Locale.ROOT,
                "DIRTY %-11s %d of %d steps leave layout for the next pulse: %s%n",
                page,
                dirtySteps,
                STEPS,
                found);
    }

    private static boolean deepestDirty(Parent parent, List<String> out) {
        boolean below = false;
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Parent inner && deepestDirty(inner, out)) {
                below = true;
            }
        }
        if (!below && parent.isNeedsLayout()) {
            out.add(parent.getClass().getSimpleName() + parent.getStyleClass());
            return true;
        }
        return below || parent.isNeedsLayout();
    }

    /** Counts, per step of the crossing sequence, the nodes made for the first time and those moved. */
    private static String count(Parent shell, String page) {
        Map<Node, Parent> seen = new IdentityHashMap<>();
        walk(shell, seen, new int[2]);
        long[] made = new long[STEPS];
        long[] moved = new long[STEPS];
        long[] inScene = new long[STEPS];
        for (int step = 0; step < STEPS; step++) {
            size(shell, CROSSING[step % CROSSING.length]);
            pulse(shell, new long[PHASES.length]);
            int[] counts = new int[3];
            walk(shell, seen, counts);
            made[step] = counts[0];
            moved[step] = counts[1];
            inScene[step] = counts[2];
        }
        return String.format(
                Locale.ROOT,
                "COUNT %-11s per crossing step: nodes in scene median %d [%d..%d], made median %d (sum %d),"
                        + " moved to another parent median %d (sum %d)%n",
                page,
                median(inScene),
                Arrays.stream(inScene).min().orElse(0),
                Arrays.stream(inScene).max().orElse(0),
                median(made),
                Arrays.stream(made).sum(),
                median(moved),
                Arrays.stream(moved).sum());
    }

    private static void walk(Parent parent, Map<Node, Parent> seen, int[] counts) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (counts.length > 2) {
                counts[2]++;
            }
            if (!seen.containsKey(child)) {
                counts[0]++;
            } else if (seen.get(child) != parent) {
                counts[1]++;
            }
            seen.put(child, parent);
            if (child instanceof Parent inner) {
                walk(inner, seen, counts);
            }
        }
    }

    /** Fires the navigation entry of a page, by its name; the same in the lab and in the product. */
    private static void go(Parent shell, String page) {
        for (Node node : shell.lookupAll(".nav-item")) {
            if (node instanceof ToggleButton entry && page.equals(entry.getText())) {
                entry.fire();
                if (!entry.isSelected()) {
                    entry.fire();
                }
                return;
            }
        }
        throw new IllegalStateException("No navigation entry " + page);
    }

    private static void selectFirstRow(Parent shell) {
        for (Node node : shell.lookupAll(".table-view")) {
            if (node instanceof javafx.scene.control.TableView<?> table && table.isVisible()) {
                table.getSelectionModel().select(0);
                return;
            }
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double medianOf(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }
}
