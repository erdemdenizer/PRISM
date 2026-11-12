package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.application.FakeProcessController;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.OomManagement;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.control.SheetOverlay;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI lab v2: where the inspector of the selected process is for the size of the window, and how
 * the sheet over the page opens, keeps the keyboard and closes (lab-docs/LAB_DESIGN_SPEC.md,
 * sections 2 and 7). The rules of the inspector itself are those of {@link ProcessActionsSceneTest}.
 */
@Tag("ui-snapshot")
class ProcessInspectorSceneTest {

    private static final String LOG_FOLDER = "C:\\logs";

    /**
     * One rule for both pages (Breakpoints), on the room of the page, not the window (fix round G): the
     * navigation beside the page may be wide or narrow. Beside the table from a page 1260 px wide, at any
     * height; under it from 960 px where the page is at least 500 px high, or from 640 px where it is at
     * least 800 px high; a sheet everywhere else. Under the table the sections stand in columns from 960 px.
     */
    @Test
    void theModeFollowsTheRoomOfThePage() {
        assertThat(List.of(
                        Breakpoints.inspectorFor(660, 400),
                        Breakpoints.inspectorFor(639, 1200),
                        Breakpoints.inspectorFor(640, 799),
                        Breakpoints.inspectorFor(640, 800),
                        Breakpoints.inspectorFor(959, 700),
                        Breakpoints.inspectorFor(960, 500),
                        Breakpoints.inspectorFor(960, 499),
                        Breakpoints.inspectorFor(1259, 900),
                        Breakpoints.inspectorFor(1260, 360),
                        Breakpoints.inspectorFor(1500, 400),
                        Breakpoints.inspectorFor(3380, 1400)))
                .containsExactly(
                        Breakpoints.Inspector.SHEET,
                        Breakpoints.Inspector.SHEET,
                        Breakpoints.Inspector.SHEET,
                        Breakpoints.Inspector.BOTTOM,
                        Breakpoints.Inspector.SHEET,
                        Breakpoints.Inspector.BOTTOM,
                        Breakpoints.Inspector.SHEET,
                        Breakpoints.Inspector.BOTTOM,
                        Breakpoints.Inspector.SIDE,
                        Breakpoints.Inspector.SIDE,
                        Breakpoints.Inspector.SIDE);
        assertThat(List.of(
                        Breakpoints.inspectorInColumns(959),
                        Breakpoints.inspectorInColumns(960),
                        Breakpoints.inspectorInColumns(1259)))
                .containsExactly(false, true, true);
    }

    /**
     * Beside the table: with a divider the user moves, the inspector between 320 and 440 px. Fix round H:
     * the dock of the page holds the table and the inspector in every mode (no split pane, which made the
     * table and the inspector move between parents at every crossing); its divider is shown beside the table.
     */
    @Test
    void besideTheTableTheInspectorIsInASplitWithinItsBoundsAndTheTableDoesNotScrollSideways() {
        List<Object> seen = inPage(1600, 900, page -> {
            ProcessDock dock = page.dock();
            Node inspector = page.lookup(".process-inspector");
            double width = inspector.getLayoutBounds().getWidth();
            return List.of(
                    page.mode(),
                    table(page).getParent() == dock
                            && dock.holdsInspector()
                            && dock.divider().isVisible(),
                    width >= ProcessesPage.INSPECTOR_MIN && width <= ProcessesPage.INSPECTOR_MAX,
                    details(page).isNarrow(),
                    details(page).lookup(".inspector-limits") != null,
                    detailsButton(page).isManaged());
        });

        assertThat(seen).containsExactly(Breakpoints.Inspector.SIDE, true, true, true, true, false);
    }

    /** A wide and low window docks the inspector beside the table too; it scrolls inside (M4). */
    @Test
    void aWideLowWindowDocksTheInspectorBesideTheTable() {
        List<Object> seen = inPage(
                1600,
                500,
                page -> List.of(
                        page.mode(),
                        page.dock().divider().isVisible() && page.dock().holdsInspector(),
                        detailsButton(page).isVisible()));

        assertThat(seen).containsExactly(Breakpoints.Inspector.SIDE, true, false);
    }

    /**
     * Fix round G (K2, PR-04): under the table the inspector is as high as its content, up to what the
     * table leaves when it keeps five rows; its pinned part (the name, every action, the line about them)
     * is always whole and in the page, and the table keeps at least its five rows. A filter that leaves one
     * row gives the inspector the room: then it shows all of its content and does not scroll.
     */
    @Test
    void underTheTableTheInspectorFitsItsContentAndTheTableKeepsItsRows() {
        // {width, height, rows the table keeps}: in the lowest page under the table, the pinned part and
        // the least of the details come first, and the table keeps its least rows.
        for (int[] window : new int[][] {
            {1026, 579, ProcessesPage.TABLE_ROWS_LEAST},
            {1100, 700, ProcessesPage.TABLE_ROWS_KEPT},
            {1103, 663, ProcessesPage.TABLE_ROWS_KEPT},
            {1240, 1000, ProcessesPage.TABLE_ROWS_KEPT}
        }) {
            List<Object> seen = inPage(window[0], window[1], page -> {
                ProcessDetailsPane pane = details(page);
                prism.srm.ui.control.InspectorFrame frame = pane.frame();
                double bottom = pane.getBoundsInParent().getMaxY();
                double rows = (table(page).getHeight() - 34) / table(page).getFixedCellSize();
                return List.of(
                        page.mode(),
                        frame.isPinned(),
                        bottom <= page.getHeight() + 0.5,
                        rows >= window[2] - 0.01,
                        pane.getHeight() <= Math.ceil(pane.wholeHeight(pane.getWidth())) + 0.5);
            });
            assertThat(seen)
                    .as("window %dx%d", window[0], window[1])
                    .containsExactly(Breakpoints.Inspector.BOTTOM, true, true, true, true);
        }
        List<Object> filtered = inPage(1100, 700, page -> {
            ProcessesPage.class.cast(page).show(ProcessListings.windows(1, 0));
            table(page).getSelectionModel().select(0);
            settle(page);
            ProcessDetailsPane pane = details(page);
            return List.of(
                    pane.getHeight() >= pane.wholeHeight(pane.getWidth()) - 0.5,
                    pane.frame().showsMoreCue(),
                    table(page).getHeight() >= table(page).getFixedCellSize() + 34);
        });
        assertThat(filtered).as("one row left").containsExactly(true, false, true);
    }
    /**
     * Under the table: the same sections as beside it (M3), in columns. Fix round G (K2): the action bar
     * with Control, Limits & protection and End process stands first, pinned at the top of the inspector,
     * and Now and Executable stand under it in two columns, in the part that scrolls; nothing of them is
     * cut, no split, no sheet and no Details.
     */
    @Test
    void underTheTableTheSectionsStandInColumnsAndDetailsIsNotThere() {
        List<Object> seen = inPage(1100, 700, page -> {
            ProcessDetailsPane pane = details(page);
            Region columns = (Region) pane.lookup(".process-details-columns");
            List<String> captions = pane.lookupAll(".inspector-section-caption").stream()
                    .filter(Node::isVisible)
                    .map(node -> ((javafx.scene.control.Label) node).getText())
                    .toList();
            return List.of(
                    page.mode(),
                    !page.dock().divider().isVisible(),
                    pane.isNarrow(),
                    // In the page's dock, the actions pinned: no scroll pane around the inspector.
                    pane.getParent() == page.dock() && pane.frame().isPinned(),
                    detailsButton(page).isManaged() || detailsButton(page).isVisible(),
                    columns != null && columns.getChildrenUnmodifiable().size() == 2,
                    captions,
                    pane.minWidth(-1) <= pane.getWidth(),
                    ((ProcessActionBar) pane.lookup(".process-actions")).isStacked());
        });

        assertThat(seen)
                .containsExactly(
                        Breakpoints.Inspector.BOTTOM,
                        true,
                        false,
                        true,
                        false,
                        true,
                        // The order of the nodes: the action bar (Limits & protection between Control and
                        // End process on screen, its captions after its buttons), then the facts.
                        List.of("Limits & protection", "End process", "Control", "Now", "Executable"),
                        true,
                        false);
    }

    /**
     * In the smallest window selecting a row does not cover the table; Enter opens the sheet and
     * the focus goes into it; Escape closes it and the focus is back on the table, the row still
     * selected.
     */
    @Test
    void inTheSmallestWindowEnterOpensTheSheetAndEscapeClosesItBackToTheRow() {
        List<Object> seen = inPage(720, 480, page -> {
            List<Object> facts = new ArrayList<>();
            TableView<ProcessRow> table = table(page);
            table.requestFocus();
            table.getSelectionModel().select(3);
            settle(page);
            facts.add(page.mode());
            facts.add(page.sheetOpen());
            facts.add(page.lookup(".process-sheet").isVisible());
            press(table, KeyCode.ENTER);
            facts.add(page.sheetOpen());
            facts.add(page.lookup(".process-sheet").isVisible());
            facts.add(insideSheet(page, page.getScene().getFocusOwner()));
            press(page.getScene().getFocusOwner(), KeyCode.ESCAPE);
            facts.add(page.sheetOpen());
            facts.add(page.lookup(".process-sheet").isVisible());
            facts.add(page.getScene().getFocusOwner() == table);
            facts.add(table.getSelectionModel().getSelectedIndex());
            return facts;
        });

        assertThat(seen)
                .containsExactly(Breakpoints.Inspector.SHEET, false, false, true, true, true, false, false, true, 3);
    }

    /**
     * Details opens the sheet; Tab visits every control of it in order and wraps, Shift+Tab visits them
     * in the opposite order (F8); × closes it and the focus goes back to Details, which opened it.
     * UI lab v2 change: the focus used to go to the table; both pages now give it back to the opener.
     */
    @Test
    void detailsOpensTheSheetTabGoesRoundItInOrderAndTheCloseButtonGivesTheFocusBack() {
        List<Object> seen = inActivePage(800, 600, page -> {
            List<Object> facts = new ArrayList<>();
            table(page).getSelectionModel().select(1);
            settle(page);
            facts.add(detailsButton(page).isVisible() && !detailsButton(page).isDisabled());
            detailsButton(page).requestFocus();
            detailsButton(page).fire();
            settle(page);
            facts.add(page.sheetOpen());
            List<Node> forward = walk(page.getScene(), false);
            List<Node> backward = walk(page.getScene(), true);
            facts.add(names(forward));
            List<Node> reversed = new ArrayList<>(forward.subList(1, forward.size()));
            java.util.Collections.reverse(reversed);
            reversed.add(0, forward.get(0));
            facts.add(backward.equals(reversed));
            facts.add(forward.stream().allMatch(node -> insideSheet(page, node)));
            ((Button) page.lookup(".process-sheet-close")).fire();
            settle(page);
            facts.add(page.sheetOpen());
            facts.add(page.getScene().getFocusOwner() == detailsButton(page));
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        true,
                        true,
                        List.of(
                                "Close details",
                                "Suspend",
                                "Priority, current: unknown",
                                "Manage",
                                "Set CPU limit…",
                                "Add to OOM management",
                                "End task…",
                                "Force kill…"),
                        true,
                        true,
                        false,
                        true);
    }

    /** Details is disabled while no process is selected: there is nothing to show. */
    @Test
    void detailsIsDisabledWithoutASelection() {
        boolean disabled = inPage(720, 480, page -> detailsButton(page).isDisabled());

        assertThat(disabled).isTrue();
    }

    /** Ctrl+F goes to the filter from wherever the focus is, and closes the sheet first. Control and Meta are both down, so that the shortcut holds on macOS too (F10). */
    @Test
    void controlFGoesToTheFilter() {
        List<Object> seen = inPage(720, 480, page -> {
            table(page).getSelectionModel().select(1);
            settle(page);
            page.openInspector();
            settle(page);
            Event.fireEvent(
                    page.getScene().getFocusOwner(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, true, false, true));
            settle(page);
            return List.of(page.sheetOpen(), page.getScene().getFocusOwner() == page.lookup(".process-filter"));
        });

        assertThat(seen).containsExactly(false, true);
    }

    /** A press of the mouse outside the sheet, on the scrim or on the title line, closes it and does nothing else (A-L6). */
    @Test
    void aPressOutsideTheSheetClosesItAndGoesNoFurther() {
        List<Object> seen = inPage(720, 480, page -> {
            List<Object> facts = new ArrayList<>();
            table(page).getSelectionModel().select(1);
            table(page).requestFocus();
            page.openInspector();
            settle(page);
            Event.fireEvent(page.sheet().lookup(".sheet-scrim"), mousePressed());
            facts.add(page.sheetOpen());
            facts.add(page.getScene().getFocusOwner() == table(page));
            page.openInspector();
            Node filter = page.lookup(".process-filter");
            boolean[] reached = {false};
            filter.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> reached[0] = true);
            Event.fireEvent(filter, mousePressed());
            facts.add(page.sheetOpen());
            facts.add(reached[0]);
            return facts;
        });

        assertThat(seen).containsExactly(false, true, false, false);
    }

    /** While the sheet is open a wheel turn on its scrim does not reach the page under it (SH-06). */
    @Test
    void aWheelTurnOnTheScrimDoesNotScrollThePage() {
        List<Object> seen = inPage(720, 480, page -> {
            List<Object> facts = new ArrayList<>();
            boolean[] reached = {false};
            page.addEventHandler(ScrollEvent.ANY, event -> reached[0] = true);
            table(page).getSelectionModel().select(1);
            page.openInspector();
            settle(page);
            Event.fireEvent(page.sheet().lookup(".sheet-scrim"), wheel());
            facts.add(page.sheetOpen());
            facts.add(reached[0]);
            page.sheet().close();
            settle(page);
            Event.fireEvent(page.lookup(".process-filter"), wheel());
            facts.add(reached[0]);
            return facts;
        });

        assertThat(seen).containsExactly(true, false, true);
    }

    private static ScrollEvent wheel() {
        return new ScrollEvent(
                ScrollEvent.SCROLL,
                10,
                10,
                10,
                10,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                -40,
                0,
                -40,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                null);
    }

    /** In a title line too narrow for 160 px of filter, it folds into its button, which unfolds it. */
    @Test
    void inANarrowTitleLineTheFilterFoldsIntoItsButton() {
        List<Object> seen = inPage(420, 600, page -> {
            List<Object> facts = new ArrayList<>();
            facts.add(page.filterFolded());
            Button unfold = (Button) page.lookup(".process-filter-unfold");
            facts.add(unfold.isVisible());
            unfold.fire();
            settle(page);
            facts.add(page.lookup(".filter-field").isVisible());
            facts.add(page.getScene().getFocusOwner() == page.lookup(".process-filter"));
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true);
    }

    /** The sheet slides in from the right and fades in over 200 ms; with reduced motion it is there at once. */
    @Test
    void theSheetMovesInFromTheRightUnlessTheUserPrefersReducedMotion() {
        List<Object> seen = inPage(720, 480, page -> {
            table(page).getSelectionModel().select(1);
            Node sheet = page.lookup(".process-sheet");
            page.getScene().getPreferences().setReducedMotion(false);
            page.openInspector();
            List<Object> facts = new ArrayList<>(List.of(sheet.getTranslateX(), sheet.getOpacity()));
            page.closeInspector();
            page.getScene().getPreferences().setReducedMotion(true);
            page.openInspector();
            facts.add(sheet.getTranslateX());
            facts.add(sheet.getOpacity());
            return facts;
        });

        assertThat(seen).containsExactly(SheetOverlay.WIDTH, 0.0, 0.0, 1.0);
    }

    /** The sheet takes no room in the page: the least height of the page is that of the table and the title. */
    @Test
    void theSheetDoesNotRaiseTheLeastHeightOfThePage() {
        List<Object> seen = inPage(704, 441, page -> {
            table(page).getSelectionModel().select(1);
            page.openInspector();
            settle(page);
            return List.of(
                    page.minHeight(page.getWidth()) <= 441,
                    page.lookup(".process-sheet").isManaged());
        });

        assertThat(seen).containsExactly(true, false);
    }

    /**
     * F4, docked to sheet: the window becomes too small while the focus is on a control of the docked
     * inspector. The sheet stays closed, and the focus goes to the table: it cannot stay on a control
     * of a closed sheet.
     */
    @Test
    void dockedToSheetTheFocusGoesToTheTable() {
        List<Object> seen = inActivePage(1600, 900, page -> {
            table(page).getSelectionModel().select(1);
            settle(page);
            ButtonBase suspend = control(page, "Suspend");
            suspend.requestFocus();
            boolean before = page.getScene().getFocusOwner() == suspend;
            moveTo(page, 720, 480);
            return List.of(
                    before, page.mode(), page.sheetOpen(), page.getScene().getFocusOwner() == table(page));
        });

        assertThat(seen).containsExactly(true, Breakpoints.Inspector.SHEET, false, true);
    }

    /**
     * F4, sheet to docked: the window grows while the focus is on a control in the open sheet. The sheet
     * goes, and the focus stays on the same control, now in the docked inspector.
     */
    @Test
    void sheetToDockedTheFocusStaysOnTheSameControl() {
        List<Object> seen = inActivePage(720, 480, page -> {
            table(page).getSelectionModel().select(1);
            settle(page);
            page.openInspector();
            ButtonBase suspend = control(page, "Suspend");
            suspend.requestFocus();
            boolean inSheet = insideSheet(page, page.getScene().getFocusOwner());
            moveTo(page, 1600, 900);
            return List.of(
                    inSheet, page.mode(), page.sheetOpen(), page.getScene().getFocusOwner() == suspend);
        });

        assertThat(seen).containsExactly(true, Breakpoints.Inspector.SIDE, false, true);
    }

    /**
     * F5: the selected process leaves the listing while the sheet is open. The sheet stays open and says
     * that no process is selected, its actions disabled with their reasons; the focus, which was on an
     * action that is disabled now, goes to the close button; Esc still closes the sheet, and the focus
     * goes to the table, since Details, which opened it, is disabled now.
     */
    @Test
    void theSelectedProcessLeavesWhileTheSheetIsOpen() {
        List<Object> seen = inActivePage(720, 480, page -> {
            List<Object> facts = new ArrayList<>();
            List<ProcessSnapshot> listing = ProcessListings.windows(120, 0);
            table(page).getSelectionModel().select(1);
            ProcessRow selected = table(page).getSelectionModel().getSelectedItem();
            settle(page);
            detailsButton(page).requestFocus();
            detailsButton(page).fire();
            control(page, "Suspend").requestFocus();
            page.show(listing.stream()
                    .filter(process -> !process.identity().equals(selected.identity()))
                    .toList());
            settle(page);
            facts.add(page.sheetOpen());
            facts.add(control(page, "Suspend").isDisabled());
            facts.add(page.getScene().getFocusOwner() == page.sheet().lookup(".sheet-close"));
            press(page.getScene().getFocusOwner(), KeyCode.ESCAPE);
            facts.add(page.sheetOpen());
            facts.add(page.getScene().getFocusOwner() == table(page));
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, false, true);
    }

    /**
     * F9 at the smallest window with the sheet open: Force kill… declined leaves the sheet open, the
     * focus on Force kill… and nothing sent; accepted sends the request and puts the focus on the close
     * button of the sheet.
     */
    @Test
    void forceKillInTheSheetDeclinedAndAccepted() {
        List<Object> declined = forceKillInSheet(false);
        List<Object> accepted = forceKillInSheet(true);

        assertThat(declined).containsExactly(1, 0, true, "Force kill…");
        assertThat(accepted).containsExactly(1, 1, true, "Close details");
    }

    private static List<Object> forceKillInSheet(boolean confirm) {
        List<ControlRequest> sent = new ArrayList<>();
        List<ControlRequest> asked = new ArrayList<>();
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                720,
                480,
                () -> {
                    ProcessesPage page = new ProcessesPage(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            LOG_FOLDER,
                            identity -> {},
                            sent::add,
                            (request, mechanism, owner) -> {
                                asked.add(request);
                                return confirm;
                            });
                    page.show(ProcessListings.windows(20, 0));
                    page.showControlProfile(new ControlProfile(FakeProcessController.windowsLike(), Map.of()));
                    return page;
                },
                root -> {
                    ProcessesPage page = (ProcessesPage) root;
                    settle(page);
                    table(page).getSelectionModel().select(2);
                    table(page).requestFocus();
                    page.openInspector();
                    settle(page);
                    ButtonBase kill = control(page, "Force kill…");
                    kill.requestFocus();
                    kill.fire();
                    settle(page);
                    Node owner = page.getScene().getFocusOwner();
                    return List.of(
                            asked.size(),
                            (int) sent.stream()
                                    .filter(request -> request.action() == ControlAction.FORCE_KILL)
                                    .count(),
                            page.sheetOpen(),
                            name(owner));
                });
    }

    /** F6: on Prism's own row every button of Limits & protection is disabled, with one line of reasons, each once. */
    @Test
    void onPrismsOwnRowTheLimitsButtonsAreDisabledWithTheirReasonsOnce() {
        for (int[] size : new int[][] {{1600, 900}, {720, 480}}) {
            List<Object> seen = inLimitsPage(size[0], size[1], new ArrayList<>(), (page, dialog) -> {
                page.showOwnProcess(ProcessListings.identity(12).pid());
                selectPid(page, 12);
                page.openInspector();
                settle(page);
                LimitsSection section = (LimitsSection) page.lookup(".inspector-limits");
                List<ButtonBase> buttons = limitButtons(page);
                String reasons = section.reasons().getText();
                return List.of(
                        buttons.size(),
                        buttons.stream().allMatch(ButtonBase::isDisabled),
                        section.reasons().isVisible(),
                        reasons,
                        reasons.split(java.util.regex.Pattern.quote(ProcessActions.OWN_PROCESS), -1).length - 1);
            });

            assertThat(seen).as(size[0] + "x" + size[1]).element(0).isEqualTo(5);
            assertThat(seen).as(size[0] + "x" + size[1]).element(1).isEqualTo(true);
            assertThat(seen).as(size[0] + "x" + size[1]).element(2).isEqualTo(true);
            assertThat((String) seen.get(3)).as(size[0] + "x" + size[1]).isNotBlank();
            assertThat(seen).as(size[0] + "x" + size[1]).element(4).isEqualTo(1);
        }
    }

    /**
     * F6: on another row each button of Limits & protection does what its choice does, through the
     * requests the page sends (a recording fake of the limit service and of the CPU limit dialog).
     */
    @Test
    void onAnotherRowEachLimitsButtonFiresItsChoice() {
        for (int[] size : new int[][] {{1600, 900}, {720, 480}}) {
            List<LimitRequest> sent = new ArrayList<>();
            List<String> seen = inLimitsPage(size[0], size[1], sent, (page, dialog) -> {
                List<String> facts = new ArrayList<>();
                selectPid(page, 12);
                page.openInspector();
                settle(page);
                for (String name : List.of("Manage", "Set CPU limit…", "Add to OOM management")) {
                    ButtonBase button = limitButton(page, name);
                    boolean enabled = !button.isDisabled();
                    sent.clear();
                    dialog.clear();
                    button.fire();
                    facts.add(name + " " + enabled + " -> " + actions(sent) + " dialog " + dialog.size());
                    // Each request answered, so that the next choice is not blocked by one in progress.
                    answer(page, sent);
                }
                return facts;
            });

            assertThat(seen)
                    .as(size[0] + "x" + size[1])
                    .containsExactly(
                            "Manage true -> [MANAGE] dialog 0",
                            "Set CPU limit… true -> [MANAGE, SET_CPU_LIMIT] dialog 1",
                            "Add to OOM management true -> [MANAGE, SET_OOM] dialog 0");
        }
    }

    /** F6: Unmanage and Remove from OOM management on a process that is managed and under OOM management. */
    @Test
    void onAManagedRowUnmanageAndRemoveFireTheirChoices() {
        List<LimitRequest> sent = new ArrayList<>();
        List<String> seen = inLimitsPage(1600, 900, sent, (page, dialog) -> {
            List<String> facts = new ArrayList<>();
            page.showLimits(LimitsFixtures.snapshot(oomManaged(ProcessListings.identity(12))));
            selectPid(page, 12);
            settle(page);
            for (String name : List.of("Remove from OOM management", "Unmanage")) {
                ButtonBase button = limitButton(page, name);
                boolean enabled = !button.isDisabled();
                sent.clear();
                button.fire();
                facts.add(name + " " + enabled + " -> " + actions(sent));
                answer(page, sent);
            }
            return facts;
        });

        assertThat(seen).containsExactly("Remove from OOM management true -> [SET_OOM]", "Unmanage true -> [UNMANAGE]");
    }

    /**
     * Fix round G (PR-12): beside the table the inspector is 320 to 440 px wide, too narrow for Now in two
     * columns of caption and value; there Now is one column, and no value is cut. Under the table, stacked
     * in a tall narrow page, Now keeps its two columns.
     */
    @Test
    void besideTheTableNowIsOneColumnAndNoValueIsCut() {
        List<Object> side = inPage(1352, 723, page -> {
            table(page).getSelectionModel().select(3);
            settle(page);
            ProcessDetailsPane pane = details(page);
            List<String> cut = pane.lookupAll(".inspector-fact-value").stream()
                    .filter(Node::isVisible)
                    .map(javafx.scene.control.Label.class::cast)
                    .filter(value -> value.getWidth() + 0.5 < value.prefWidth(-1))
                    .map(javafx.scene.control.Label::getText)
                    .toList();
            return List.of(page.mode(), pane.nowInTwoColumns(), cut);
        });
        List<Object> tall = inPage(860, 1100, page -> {
            table(page).getSelectionModel().select(3);
            settle(page);
            return List.of(page.mode(), details(page).isNarrow(), details(page).nowInTwoColumns());
        });

        assertThat(side).containsExactly(Breakpoints.Inspector.SIDE, false, List.of());
        assertThat(tall).containsExactly(Breakpoints.Inspector.BOTTOM, true, true);
    }

    /**
     * Fix round G (PR-15): with no process selected the line about its being managed says nothing about a
     * process; selected, it says whether Prism manages it.
     */
    @Test
    void withNoSelectionTheManagedLineSaysNothingAboutAProcess() {
        List<String> seen = inPage(1100, 700, page -> {
            javafx.scene.control.Label state = (javafx.scene.control.Label) page.lookup(".inspector-limits-state");
            String none = state.getText();
            table(page).getSelectionModel().select(3);
            settle(page);
            return List.of(none, state.getText());
        });

        assertThat(seen).containsExactly("–", "Not managed by Prism.");
    }

    /**
     * Fix round G (PR-10): under the table the columns of the action bar stand where they stood when the
     * selection moves from a managed process to one that is not: what the Limits section says of it does
     * not set the width of its column.
     */
    @Test
    void underTheTableTheColumnsOfTheActionsDoNotMoveWithTheSelection() {
        List<LimitRequest> sent = new ArrayList<>();
        List<List<Double>> seen = inLimitsPage(1100, 700, sent, (page, dialog) -> {
            page.showLimits(LimitsFixtures.snapshot(oomManaged(ProcessListings.identity(12))));
            List<List<Double>> columns = new ArrayList<>();
            for (long pid : new long[] {12, 16, 12}) {
                selectPid(page, pid);
                settle(page);
                columns.add(page.lookup(".process-actions").lookupAll(".inspector-section-caption").stream()
                        .filter(Node::isVisible)
                        .map(caption ->
                                caption.localToScene(caption.getLayoutBounds()).getMinX())
                        .toList());
            }
            return columns;
        });

        assertThat(seen.get(0)).hasSize(3);
        assertThat(seen.get(1)).as("not managed").isEqualTo(seen.get(0));
        assertThat(seen.get(2)).as("managed again").isEqualTo(seen.get(0));
    }

    private interface LimitsScenario<T> {
        T run(ProcessesPage page, List<String> dialog);
    }

    private static <T> T inLimitsPage(int width, int height, List<LimitRequest> sent, LimitsScenario<T> scenario) {
        List<String> dialog = new ArrayList<>();
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> {
                    ProcessesPage page = new ProcessesPage(
                            new ValueFormatter(Locale.US),
                            ZoneOffset.UTC,
                            LOG_FOLDER,
                            identity -> {},
                            request -> {},
                            sent::add,
                            request -> {},
                            (request, mechanism, owner) -> true,
                            (name, pid, current, means, owner) -> {
                                dialog.add(name);
                                return OptionalInt.of(50);
                            },
                            (entry, resume, owner) -> true);
                    page.show(ProcessListings.windows(20, 0));
                    page.showControlProfile(new ControlProfile(FakeProcessController.windowsLike(), Map.of()));
                    page.showLimits(new LimitsSnapshot(List.of(), LimitsFixtures.NATIVE, Optional.empty()));
                    return page;
                },
                root -> {
                    ProcessesPage page = (ProcessesPage) root;
                    settle(page);
                    return scenario.run(page, dialog);
                });
    }

    /** Each request answered, as the limit service does, so that none is left in progress. */
    private static void answer(ProcessesPage page, List<LimitRequest> requests) {
        for (LimitRequest request : List.copyOf(requests)) {
            page.showLimitResult(new LimitResult(request, ControlOutcome.success(), "Done.", ""));
        }
    }

    /** A managed process under OOM management. */
    private static ManagedProcessView oomManaged(ProcessIdentity identity) {
        ManagedProcessView plain = LimitsFixtures.managed(identity, "x");
        return new ManagedProcessView(
                new ManagedProcess(identity, "x", ManagedSettings.NONE.withOom(OomManagement.ON)), plain.cpu());
    }

    /** The name a screen reader reads for a control: its accessible text, else its text. */
    private static String name(Node node) {
        if (node.getAccessibleText() != null && !node.getAccessibleText().isBlank()) {
            return node.getAccessibleText();
        }
        return node instanceof Labeled labeled ? labeled.getText() : String.valueOf(node);
    }

    private static List<String> actions(List<LimitRequest> requests) {
        return requests.stream().map(request -> request.action().name()).toList();
    }

    private static void selectPid(ProcessesPage page, long pid) {
        TableView<ProcessRow> table = table(page);
        for (ProcessRow row : table.getItems()) {
            if (row.identity().pid() == pid) {
                table.getSelectionModel().select(row);
            }
        }
        settle(page);
    }

    private static List<ButtonBase> limitButtons(ProcessesPage page) {
        return page.lookup(".inspector-limits").lookupAll(".inspector-limit-action").stream()
                .map(ButtonBase.class::cast)
                .toList();
    }

    private static ButtonBase limitButton(ProcessesPage page, String name) {
        return limitButtons(page).stream()
                .filter(button -> name.equals(button.getAccessibleText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + name));
    }

    private static ButtonBase control(ProcessesPage page, String text) {
        return page.lookupAll(".process-action").stream()
                .map(ButtonBase.class::cast)
                .filter(control -> text.equals(control.getText()))
                .findFirst()
                .orElseThrow();
    }

    /** Moves the page into a new scene of another size, as a window that is resized past a breakpoint. */
    static void moveTo(Parent root, int width, int height) {
        Scene before = root.getScene();
        before.setRoot(new Region());
        Scene after = new Scene(root, width, height);
        after.getPreferences().setReducedMotion(true);
        after.getPreferences().setColorScheme(before.getPreferences().getColorScheme());
        after.getStylesheets().setAll(before.getStylesheets());
        root.applyCss();
        root.layout();
        root.applyCss();
        root.layout();
    }

    /** Tab (or Shift+Tab) from the focus owner until it comes back to it: the controls visited, in order. */
    static List<Node> walk(Scene scene, boolean backwards) {
        Node start = scene.getFocusOwner();
        List<Node> visited = new ArrayList<>(List.of(start));
        for (int step = 0; step < 40; step++) {
            Event.fireEvent(
                    scene.getFocusOwner(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, backwards, false, false, false));
            Node next = scene.getFocusOwner();
            if (next == start) {
                return visited;
            }
            if (visited.contains(next)) {
                throw new AssertionError("Tab went round without coming back to " + start + ": " + visited);
            }
            visited.add(next);
        }
        throw new AssertionError("Tab never came back: " + visited);
    }

    private static List<String> names(List<Node> nodes) {
        return nodes.stream().map(ProcessInspectorSceneTest::name).toList();
    }

    private static boolean insideSheet(ProcessesPage page, Node node) {
        Node sheet = page.lookup(".process-sheet");
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == sheet) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked") // The table of the Processes page is a table of process rows.
    private static TableView<ProcessRow> table(ProcessesPage page) {
        return (TableView<ProcessRow>) page.lookup(".process-table");
    }

    private static ProcessDetailsPane details(ProcessesPage page) {
        return (ProcessDetailsPane) page.lookup(".process-details");
    }

    private static Button detailsButton(ProcessesPage page) {
        return (Button) page.lookup(".process-details-open");
    }

    private static void settle(ProcessesPage page) {
        page.applyCss();
        page.layout();
        page.applyCss();
        page.layout();
    }

    private static void press(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
    }

    private static MouseEvent mousePressed() {
        return new MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                1,
                1,
                1,
                1,
                MouseButton.PRIMARY,
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

    private static <T> T inPage(int width, int height, Function<ProcessesPage, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> {
                    ProcessesPage page = new ProcessesPage(
                            new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, identity -> {});
                    page.show(ProcessListings.windows(120, 0));
                    return page;
                },
                root -> {
                    ProcessesPage page = (ProcessesPage) root;
                    settle(page);
                    return scenario.apply(page);
                });
    }

    /** A page whose actions can be used: a control profile, and limits that are known. */
    private static <T> T inActivePage(int width, int height, Function<ProcessesPage, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> {
                    ProcessesPage page = new ProcessesPage(
                            new ValueFormatter(Locale.US), ZoneOffset.UTC, LOG_FOLDER, identity -> {});
                    page.show(ProcessListings.windows(120, 0));
                    page.showControlProfile(new ControlProfile(FakeProcessController.windowsLike(), Map.of()));
                    page.showLimits(new LimitsSnapshot(List.of(), LimitsFixtures.NATIVE, Optional.empty()));
                    return page;
                },
                root -> {
                    ProcessesPage page = (ProcessesPage) root;
                    settle(page);
                    return scenario.apply(page);
                });
    }
}
