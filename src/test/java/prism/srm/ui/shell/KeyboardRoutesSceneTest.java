package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javafx.event.Event;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.domain.PriorityLevel;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.overview.Snapshots;
import prism.srm.ui.processes.ProcessListings;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * WP5: the keyboard route of every page, walked with Tab key events in a scene with values on every
 * page. Each stop must have a name a screen reader can say, the route must come back to where it
 * started without a trap, and Shift+Tab must walk it backwards.
 *
 * <p>Whether the focus can be seen is not checked here: a scene without a focused window never has a
 * focused node. {@code verify-image.ps1} presses Tab in the packaged application and compares images
 * of the window, and reads the name of each stop through UI Automation.
 */
@Tag("ui-snapshot")
class KeyboardRoutesSceneTest {

    private static final String LOG_FOLDER = "C:\\Users\\alice\\AppData\\Local\\Prism\\SystemResourceManager\\logs";
    private static final int MOST_STOPS = 80;

    /** A stop on the route: what kind of control it is and what a screen reader would call it. */
    record Stop(String kind, String name) {
        @Override
        public String toString() {
            return kind + " \"" + name + "\"";
        }
    }

    /** The route of every page with a row selected wherever there is a table: names, in Tab order. */
    private static final Map<Destination, List<String>> ROUTES = routes();

    private static final Stop BACK_TO_START = new Stop("back to start", "");
    private static final String PAGE_AREA = "Page";
    private static int opened;

    private static Map<Destination, List<String>> routes() {
        Map<Destination, List<String>> routes = new EnumMap<>(Destination.class);
        // Round G (UI-1, K4, OP-03): every part of the Overview that leads to a page is a button on the
        // route, in reading order; the facts of the system lead nowhere and are not on it.
        routes.put(
                Destination.OVERVIEW,
                List.of(
                        "Overview",
                        "CPU: 12.4 %. Open Performance, CPU",
                        "Memory: 11.2 GiB, 35.0 %. Open Performance, Memory",
                        "Disk: Read 1.5 MiB/s, Write 512.0 KiB/s. Open Performance, Disk",
                        "Network: Download 240.0 KiB/s, Upload 12.0 KiB/s. Open Performance, Network",
                        "Processes: 392. Open Processes",
                        "Managed: 2. Open Limits",
                        "OOM Guard: Normal. Open OOM Guard"));
        routes.put(
                Destination.PROCESSES,
                List.of(
                        "Processes",
                        "Filter processes by name, PID or user",
                        "Processes",
                        "Suspend",
                        "Priority, current: Normal",
                        // UI lab v2: at 1100 x 700 the inspector is under the table, with Limits &
                        // protection between Control and End process, as beside the table and in the sheet.
                        "Manage",
                        "Set CPU limit\u2026",
                        "Add to OOM management",
                        "End task\u2026",
                        "Force kill\u2026"));
        // Round G (K5): the graph of a section is a stop, where Left, Right, Home and End read its past.
        routes.put(Destination.PERFORMANCE, List.of("Performance", "CPU", "Total CPU: 12.4 %. Utilization, last 60 s"));
        routes.put(
                Destination.LIMITS,
                List.of("Limits", "Managed processes and their limits", "Set CPU limit\u2026", "Disable", "Unmanage"));
        routes.put(
                Destination.OOM_GUARD,
                List.of(
                        "OOM Guard",
                        "Monitor memory pressure",
                        "Change the thresholds",
                        "Lower priority at Critical",
                        "Suspend at Emergency",
                        "Terminate at Emergency (force kill)",
                        "Managed processes and their emergency actions",
                        "OOM management\u2026",
                        "Recent automatic actions of OOM Guard"));
        // With a row selected, the detail pane under the table: its Copy button (head row), then the
        // whole message, read-only and selectable (LS-01, K3).
        routes.put(
                Destination.LOGS,
                List.of("Logs", "Filter the log", "Open log folder", "Activity log", "Copy", "Entry details"));
        routes.put(
                Destination.SETTINGS,
                List.of(
                        "Settings",
                        "Appearance: System",
                        // A folder is plain text that takes the keyboard for Ctrl+C (LS-03, LS-05), named
                        // with its path; this shell names no settings folder, so that row has no stop.
                        "Logs folder: " + LOG_FOLDER,
                        "Open log folder",
                        "Open settings folder"));
        return routes;
    }

    /**
     * From the selected entry of the sidebar, Tab visits every control of the page in reading order
     * and comes back; Shift+Tab visits them in the opposite order. Every stop has a name.
     */
    @Test
    void tabWalksEveryPageInReadingOrderAndShiftTabWalksItBack() {
        Map<Destination, List<List<Stop>>> walked = inShell(1100, 700, shell -> {
            Map<Destination, List<List<Stop>>> all = new EnumMap<>(Destination.class);
            for (Destination destination : Destination.values()) {
                open(shell, destination);
                List<Stop> forward = route(shell.getScene(), false);
                List<Stop> backward = route(shell.getScene(), true);
                all.put(destination, List.of(forward, backward));
            }
            return all;
        });

        for (Destination destination : Destination.values()) {
            List<Stop> forward = walked.get(destination).get(0);
            List<Stop> backward = walked.get(destination).get(1);
            List<String> expected = ROUTES.get(destination);
            assertThat(forward).as(destination + " forward").last().isEqualTo(BACK_TO_START);
            assertThat(names(forward)).as(destination + " forward").isEqualTo(expected);
            List<String> reversed = new ArrayList<>(expected.subList(1, expected.size()));
            Collections.reverse(reversed);
            reversed.add(0, expected.get(0));
            assertThat(backward).as(destination + " backward").last().isEqualTo(BACK_TO_START);
            assertThat(names(backward)).as(destination + " backward").isEqualTo(reversed);
        }
    }

    /**
     * UI-8 at the minimum size: the page scrolls, and the route still reaches every control and comes back.
     * The smallest window: the rail with names is at the left there too (owner decision after fix round H),
     * so every route begins at the entry of the page, as in a wider window.
     */
    @Test
    void theRoutesHoldAtTheSmallestWindow() {
        Map<Destination, List<Stop>> walked = inShell(704, 441, shell -> {
            Map<Destination, List<Stop>> all = new EnumMap<>(Destination.class);
            for (Destination destination : Destination.values()) {
                open(shell, destination);
                all.put(destination, route(shell.getScene(), false));
            }
            return all;
        });

        for (Destination destination : Destination.values()) {
            List<Stop> forward = walked.get(destination);
            assertThat(forward).as(destination.name()).last().isEqualTo(BACK_TO_START);
            // The page area is a stop of its own where the page is larger than the window: it scrolls.
            List<String> expected = new ArrayList<>(ROUTES.get(destination));
            if (destination == Destination.PROCESSES) {
                // UI lab v2: here the inspector is a sheet over the page, opened by Details (or
                // Enter on a row); its actions are reached in it, not after the table.
                expected = List.of(
                        "Processes",
                        "Filter processes by name, PID or user",
                        "Details of the selected process",
                        "Processes");
            }
            if (destination == Destination.LIMITS) {
                // UI lab v2: the Limits panel is a sheet here too, opened by "Details and actions" (or Enter
                // on a row); its actions are reached inside it (LimitsPanelSceneTest walks that Tab trap).
                expected = List.of("Limits", "Details and actions", "Managed processes and their limits");
            }
            assertThat(names(forward).stream()
                            .filter(name -> !name.equals(PAGE_AREA))
                            .toList())
                    .as(destination.name())
                    .isEqualTo(expected);
        }
    }

    /**
     * F1: in the smallest window the sheet of each page, opened from its button, is walked with Tab: the
     * exact controls, in order, and back to the close button; then Esc gives the focus back to the button.
     * Processes: the order of the inspector, Control, then Limits &amp; protection, then End process; the
     * danger zone comes last, as beside the table.
     *
     * <p>Fix round H (PR-03): the sheet of the smallest window is too low for the inspector whole, so its
     * actions are compact and stay pinned: "Limits &amp; protection" is one labelled menu with the five
     * choices, one stop in place of the enabled buttons it holds (changed on purpose).
     */
    @Test
    void inTheSmallestWindowTheSheetOfEachPageIsWalkedAndWraps() {
        Map<Destination, List<Object>> walked = inShell(704, 441, shell -> {
            Map<Destination, List<Object>> all = new EnumMap<>(Destination.class);
            for (Destination destination : List.of(Destination.PROCESSES, Destination.LIMITS)) {
                open(shell, destination);
                Node opener = button(shell, destination == Destination.PROCESSES ? "Details" : "Details and actions");
                opener.requestFocus();
                press(opener, KeyCode.ENTER, false);
                shell.applyCss();
                shell.layout();
                List<Stop> forward = route(shell.getScene(), false);
                press(shell.getScene().getFocusOwner(), KeyCode.ESCAPE, false);
                all.put(
                        destination,
                        List.of(
                                names(forward),
                                forward.getLast(),
                                shell.getScene().getFocusOwner() == opener));
            }
            return all;
        });

        assertThat(walked.get(Destination.PROCESSES))
                .containsExactly(
                        List.of(
                                "Close details",
                                "Suspend",
                                "Priority, current: Normal",
                                "Limits & protection: Manage, Set CPU limit, OOM Guard",
                                "End task…",
                                "Force kill…"),
                        BACK_TO_START,
                        true);
        assertThat(walked.get(Destination.LIMITS))
                .containsExactly(
                        List.of("Close details", "Set CPU limit…", "Disable", "Unmanage"), BACK_TO_START, true);
    }

    /**
     * A-M2: after a narrow window and a wide one again, the rail is the left of the shell again, and the
     * route from its entry, Tab after Tab and back to it, is the one of a window that was always wide:
     * the rail comes right before the page. (The rail is the last child of the shell then, which in a
     * cycle of Tab stops is the same order; moving it to the front would make the border pane forget it.)
     */
    @Test
    void afterANarrowWindowTheRailIsBackAtTheFrontOfTheTabOrder() {
        List<Object> seen = inShell(704, 441, shell -> {
            Scene before = shell.getScene();
            before.setRoot(new javafx.scene.layout.Region());
            Scene after = new Scene(shell, 1100, 700);
            after.getPreferences().setReducedMotion(true);
            after.getStylesheets().setAll(before.getStylesheets());
            shell.applyCss();
            shell.layout();
            open(shell, Destination.LOGS);
            return List.of(shell.getLeft() == sidebar(shell), names(route(after, false)));
        });

        assertThat(seen).containsExactly(true, ROUTES.get(Destination.LOGS));
    }

    /**
     * A-M3: a shortcut of the window with a sheet open. Ctrl+4 shows Limits: the sheet of Processes
     * closes with its page and the focus starts again at the selected entry of the navigation. Ctrl+2
     * back to Processes leaves its sheet closed.
     */
    @Test
    void theShortcutsOfTheWindowCloseAnOpenSheet() {
        List<Object> seen = inShell(704, 441, shell -> {
            List<Object> facts = new ArrayList<>();
            open(shell, Destination.PROCESSES);
            Node sheet = shell.getCenter().lookup(".process-sheet");
            Node details = button(shell, "Details");
            details.requestFocus();
            press(details, KeyCode.ENTER, false);
            facts.add(sheet.isVisible());
            shortcut(shell.getScene().getFocusOwner(), KeyCode.DIGIT4);
            facts.add(sidebar(shell).selected());
            facts.add(sheet.isVisible());
            facts.add(name(shell.getScene().getFocusOwner()));
            shortcut(shell.getScene().getFocusOwner(), KeyCode.DIGIT2);
            facts.add(sheet.isVisible());
            return facts;
        });

        assertThat(seen).containsExactly(true, Destination.LIMITS, false, "Limits", false);
    }

    /**
     * A-M7: in the rail an entry shows its icon only; the keyboard that reaches it shows its name at once,
     * a press of the mouse does not.
     */
    @Test
    void anEntryOfTheRailShowsItsNameWhenTheKeyboardReachesIt() {
        List<Object> seen = inShell(1100, 700, shell -> {
            List<Object> facts = new ArrayList<>();
            open(shell, Destination.OVERVIEW);
            Sidebar rail = sidebar(shell);
            press(rail.entry(Destination.OVERVIEW), KeyCode.DOWN, false);
            facts.add(rail.nameShownForKeyboard());
            Node performance = rail.entry(Destination.PERFORMANCE);
            Event.fireEvent(performance, mousePressed());
            facts.add(shell.getScene().getFocusOwner() == performance);
            facts.add(rail.nameShownForKeyboard());
            return facts;
        });

        assertThat(seen).containsExactly(Optional.of(Destination.PROCESSES), true, Optional.empty());
    }

    /**
     * A-M7: focus that reaches the rail without a key (as the window opens, or through UI Automation)
     * shows no name: in the real window the name stayed over the page then; a key shows it. (That a move
     * or a new size of the window hides it needs a real window, which this harness has not: the real-window
     * matrix of Phase F shows it.)
     */
    @Test
    void anEntryShowsNoNameForAFocusNoKeyBroughtAndHidesItWhenTheWindowChanges() {
        List<Object> seen = inShell(1100, 700, shell -> {
            List<Object> facts = new ArrayList<>();
            open(shell, Destination.OVERVIEW);
            Sidebar rail = sidebar(shell);
            rail.entry(Destination.SETTINGS).requestFocus();
            facts.add(rail.nameShownForKeyboard());
            press(rail.entry(Destination.OVERVIEW), KeyCode.DOWN, false);
            facts.add(rail.nameShownForKeyboard());
            return facts;
        });

        assertThat(seen).containsExactly(Optional.empty(), Optional.of(Destination.PROCESSES));
    }

    private static javafx.scene.input.MouseEvent mousePressed() {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                1,
                1,
                1,
                1,
                javafx.scene.input.MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    /** Ctrl (and Meta, so that it is the shortcut key on macOS too) with a key. */
    private static void shortcut(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, true));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, true, false, true));
    }

    /** Space and Enter press a focused button; Space turns a check box; Escape clears a filter. */
    @Test
    void spaceEnterAndEscapeDoWhatTheyDoInWindows() {
        List<String> seen = SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, KeyboardRoutesSceneTest::counting, root -> {
            Shell shell = (Shell) root;
            List<String> facts = new ArrayList<>();
            open(shell, Destination.LOGS);
            Node open = button(shell, "Open log folder");
            open.requestFocus();
            press(open, KeyCode.SPACE, false);
            facts.add("space opens: " + opened);
            press(open, KeyCode.ENTER, false);
            facts.add("enter opens: " + opened);

            TextInputControl filter = (TextInputControl) shell.getCenter().lookup(".logs-filter");
            filter.requestFocus();
            filter.setText("prism");
            press(filter, KeyCode.ESCAPE, false);
            facts.add("escape leaves [" + filter.getText() + "]");

            open(shell, Destination.OOM_GUARD);
            CheckBox lower = shell.getCenter().lookupAll(".check-box").stream()
                    .map(CheckBox.class::cast)
                    .filter(box -> box.getText().equals("Lower priority at Critical"))
                    .findFirst()
                    .orElseThrow();
            boolean before = lower.isSelected();
            lower.requestFocus();
            press(lower, KeyCode.SPACE, false);
            facts.add("space turns the check box: " + (lower.isSelected() != before));

            open(shell, Destination.OVERVIEW);
            press(sidebar(shell).entry(Destination.OVERVIEW), KeyCode.DOWN, false);
            Node processes = shell.getScene().getFocusOwner();
            press(processes, KeyCode.SPACE, false);
            facts.add("down then space shows " + sidebar(shell).selected());
            press(processes, KeyCode.DOWN, false);
            press(shell.getScene().getFocusOwner(), KeyCode.ENTER, false);
            facts.add("down then enter shows " + sidebar(shell).selected());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        "space opens: 1",
                        "enter opens: 2",
                        "escape leaves []",
                        "space turns the check box: true",
                        "down then space shows PROCESSES",
                        "down then enter shows PERFORMANCE");
    }

    private static Shell counting() {
        opened = 0;
        Shell shell = new Shell(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                Snapshots.CAPACITY,
                LOG_FOLDER,
                identity -> {},
                request -> {},
                () -> opened++);
        fill(shell);
        return shell;
    }

    private static Node button(Shell shell, String text) {
        return shell.getCenter().lookupAll(".button").stream()
                .filter(node -> node instanceof Labeled labeled && text.equals(labeled.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    /** Shows a page with the first row of each of its tables selected, the focus on its sidebar entry. */
    private static void open(Shell shell, Destination destination) {
        sidebar(shell).entry(destination).fire();
        shell.applyCss();
        shell.layout();
        for (Node table : shell.getCenter().lookupAll(".table-view")) {
            ((TableView<?>) table).getSelectionModel().select(0);
        }
        shell.applyCss();
        shell.layout();
        // UI lab v2: the route begins at the selected entry of the navigation, at every width.
        shell.navigationStart().requestFocus();
    }

    private static void press(Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, false, false, false));
    }

    private static List<String> names(List<Stop> stops) {
        return stops.stream()
                .filter(stop -> !stop.equals(BACK_TO_START))
                .map(Stop::name)
                .toList();
    }

    /** Tab (or Shift+Tab) from the focus owner until the focus comes back to it, or {@link #MOST_STOPS}. */
    static List<Stop> route(Scene scene, boolean backwards) {
        Node start = scene.getFocusOwner();
        List<Stop> stops = new ArrayList<>();
        stops.add(stop(start));
        for (int step = 0; step < MOST_STOPS; step++) {
            Node owner = scene.getFocusOwner();
            press(owner, KeyCode.TAB, backwards);
            Node next = scene.getFocusOwner();
            if (next == start) {
                stops.add(BACK_TO_START);
                return stops;
            }
            if (next == owner) {
                stops.add(new Stop("stuck", ""));
                return stops;
            }
            stops.add(stop(next));
        }
        stops.add(new Stop("no way back", ""));
        return stops;
    }

    static Stop stop(Node node) {
        String kind = node.getClass().getSimpleName() + node.getStyleClass();
        return new Stop(kind, name(node));
    }

    /**
     * What a screen reader says for a node. A text field is named by its label alone, and its accessible
     * text would be read as its value instead of what it holds (the Windows bridge of JavaFX 25: the name
     * of a text field comes from LABELED_BY, its value from TEXT, and TextInputControl answers TEXT with
     * the accessible text first). Any other node: its accessible text, else its text, else its label.
     */
    static String name(Node node) {
        if (node instanceof TextInputControl input) {
            if (input.getAccessibleText() != null) {
                return "(value hidden by the accessible text) " + input.getAccessibleText();
            }
            // The bridge reads the label's TEXT attribute: its accessible text first, then its text.
            Object labelledBy = node.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY);
            Object spoken =
                    labelledBy instanceof Label label ? label.queryAccessibleAttribute(AccessibleAttribute.TEXT) : null;
            return spoken instanceof String said && !said.isBlank() ? said : "(no label) " + input.getPromptText();
        }
        if (node.getAccessibleText() != null && !node.getAccessibleText().isBlank()) {
            return node.getAccessibleText();
        }
        if (node instanceof Labeled labeled
                && labeled.getText() != null
                && !labeled.getText().isBlank()) {
            return labeled.getText();
        }
        Object labelledBy = node.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY);
        if (labelledBy instanceof Label label && label.getText() != null) {
            return label.getText();
        }
        Object text = node.queryAccessibleAttribute(AccessibleAttribute.TEXT);
        return text == null ? "" : "(content) " + text;
    }

    /** A shell with values on every page. */
    static <T> T inShell(int width, int height, Function<Shell, T> scenario) {
        return SnapshotHarness.inspect(Theme.LIGHT, width, height, KeyboardRoutesSceneTest::populated, root -> {
            return scenario.apply((Shell) root);
        });
    }

    static Shell populated() {
        Shell shell = new Shell(
                new ValueFormatter(Locale.US), ZoneOffset.UTC, Snapshots.CAPACITY, LOG_FOLDER, identity -> {});
        fill(shell);
        return shell;
    }

    static void fill(Shell shell) {
        shell.showSnapshot(Snapshots.live());
        shell.showControlProfile(OomFixtures.windows());
        shell.showProcesses(ProcessListings.windows(40, 0));
        shell.showLimits(new LimitsSnapshot(
                List.of(
                        LimitsFixtures.managed(LimitsFixtures.identity(30_324), "java.exe"),
                        LimitsFixtures.enforced(LimitsFixtures.identity(7_004), "node.exe", 30, 256)),
                LimitsFixtures.NATIVE,
                Optional.empty()));
        OomSettings settings = OomFixtures.settings(true, true, false);
        shell.showOom(OomFixtures.view(
                settings,
                OomState.NORMAL,
                Optional.of(OomFixtures.reading(46)),
                List.of(OomFixtures.candidate(
                        30_324,
                        "java.exe",
                        new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND),
                        settings,
                        OomHold.NONE,
                        Optional.of(PriorityLevel.NORMAL),
                        3_712,
                        Optional.empty())),
                List.of()));
        shell.showActivity(List.of(
                new ActivityEntry(Instant.parse("2026-10-08T07:00:00Z"), ActivitySeverity.INFO, "Prism started.")));
    }

    static Sidebar sidebar(Shell shell) {
        return shell.navigation();
    }
}
