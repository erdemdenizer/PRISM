package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.application.FakeProcessController;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.PriorityLevel;
import prism.srm.ui.control.ActionResultLine;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The actions in the page: the controls, their state, the confirmation, the menu. */
@Tag("ui-snapshot")
class ProcessActionsSceneTest {

    /** The page, the requests it sent and the confirmations it asked for, answered by the test. */
    private static final class Scene {
        final ProcessesPage page;
        final List<ControlRequest> sent;
        final List<ControlRequest> confirmations;
        final List<Optional<ControlMechanism>> mechanisms;

        Scene(
                ProcessesPage page,
                List<ControlRequest> sent,
                List<ControlRequest> confirmations,
                List<Optional<ControlMechanism>> mechanisms) {
            this.page = page;
            this.sent = sent;
            this.confirmations = confirmations;
            this.mechanisms = mechanisms;
        }

        @SuppressWarnings("unchecked")
        TableView<ProcessRow> table() {
            return (TableView<ProcessRow>) page.lookup(".table-view");
        }

        ButtonBase control(String text) {
            return page.lookupAll(".process-action").stream()
                    .map(ButtonBase.class::cast)
                    .filter(control -> control.getText().equals(text))
                    .findFirst()
                    .orElseThrow();
        }

        /** The whole line beside the actions, as a screen reader gets it. */
        String message() {
            return page.lookup(".process-action-message").getAccessibleText();
        }

        ActionResultLine line() {
            return (ActionResultLine) page.lookup(".process-action-message");
        }

        ProcessActionBar bar() {
            return (ProcessActionBar) page.lookup(".process-actions");
        }

        /** The texts of the controls of the bar, in the order of the bar, which is the order of Tab. */
        List<String> controls() {
            List<String> texts = new ArrayList<>();
            for (javafx.scene.Node holder : bar().getChildren()) {
                if (holder instanceof javafx.scene.layout.StackPane stack) {
                    texts.add(((ButtonBase) stack.getChildren().get(0)).getText());
                }
            }
            return texts;
        }

        MenuItem menuItem(String text) {
            ContextMenu menu = table().getContextMenu();
            return menu.getItems().stream()
                    .filter(item -> text.equals(item.getText()))
                    .findFirst()
                    .orElseThrow();
        }

        void ready() {
            page.show(List.of(
                    ProcessListings.process(10, "demo").build(),
                    ProcessListings.process(20, "other").build()));
            page.showControlProfile(
                    new prism.srm.domain.ControlProfile(FakeProcessController.windowsLike(), java.util.Map.of()));
            table().getSelectionModel().select(0);
            page.applyCss();
            page.layout();
        }
    }

    private static <T> T inPage(boolean confirm, Function<Scene, T> scenario) {
        return inPage(1100, 700, confirm, scenario);
    }

    private static <T> T inPage(int width, int height, boolean confirm, Function<Scene, T> scenario) {
        List<ControlRequest> sent = new ArrayList<>();
        List<ControlRequest> confirmations = new ArrayList<>();
        List<Optional<ControlMechanism>> mechanisms = new ArrayList<>();
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                width,
                height,
                () -> new ProcessesPage(
                        new ValueFormatter(Locale.US),
                        ZoneOffset.UTC,
                        "logs",
                        identity -> {},
                        sent::add,
                        (request, mechanism, owner) -> {
                            confirmations.add(request);
                            mechanisms.add(mechanism);
                            if (!confirm) {
                                // As a modal dialog does: the focus leaves the page while it is open.
                                owner.lookup(".table-view").requestFocus();
                            }
                            return confirm;
                        }),
                root -> scenario.apply(new Scene((ProcessesPage) root, sent, confirmations, mechanisms)));
    }

    @Test
    void everyActionIsInTheInspectorAndDisabledUntilAProcessIsSelected() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.applyCss();
            scene.page.layout();
            List<Object> facts = new ArrayList<>();
            for (String text : List.of("End task…", "Force kill…", "Suspend", "Resume", "Priority")) {
                facts.add(text + " " + scene.control(text).isDisabled() + " "
                        + scene.control(text).isVisible());
            }
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        "End task… true true",
                        "Force kill… true true",
                        "Suspend true true",
                        "Resume true true",
                        "Priority true true");
    }

    @Test
    void endTaskAsksFirstAndSendsNothingWhenTheUserDeclines() {
        List<Object> seen = inPage(false, scene -> {
            scene.ready();
            scene.control("End task…").fire();
            return List.of(scene.confirmations.size(), scene.sent.size());
        });

        assertThat(seen).containsExactly(1, 0);
    }

    @Test
    void forceKillAsksFirstAndSendsTheRequestWhenConfirmed() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            scene.control("Force kill…").fire();
            return List.of(
                    scene.confirmations.get(0).action(),
                    scene.sent.get(0).action(),
                    scene.sent.get(0).identity().pid(),
                    scene.control("Suspend").isDisabled(),
                    scene.message());
        });

        assertThat(seen)
                .containsExactly(
                        ControlAction.FORCE_KILL,
                        ControlAction.FORCE_KILL,
                        10L,
                        true,
                        "Requesting termination of demo (PID 10)…");
    }

    @Test
    void suspendAndPriorityNeedNoConfirmation() {
        List<Object> seen = inPage(false, scene -> {
            scene.ready();
            scene.control("Suspend").fire();
            return List.of(scene.confirmations.size(), scene.sent.get(0).action());
        });

        assertThat(seen).containsExactly(0, ControlAction.SUSPEND);
    }

    @Test
    void thePriorityMenuOffersTheFiveLevelsAndNoRealtime() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            MenuButton priority = (MenuButton) scene.control("Priority");
            List<String> levels = priority.getItems().stream()
                    .filter(item -> item instanceof javafx.scene.control.RadioMenuItem)
                    .map(MenuItem::getText)
                    .toList();
            priority.getItems().stream()
                    .filter(item -> "High".equals(item.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            return List.of(levels, scene.sent.get(0).priority().orElseThrow());
        });

        assertThat(seen)
                .containsExactly(List.of("Low", "Below normal", "Normal", "Above normal", "High"), PriorityLevel.HIGH);
    }

    /** The keyboard reaches the actions through the context menu of the table. */
    @Test
    void theContextMenuCarriesTheSameActionsWithTheSameConfirmation() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            MenuItem kill = scene.menuItem("Force kill…");
            boolean enabled = !kill.isDisable();
            kill.fire();
            Menu priority = (Menu) scene.menuItem("Priority");
            return List.of(
                    enabled,
                    scene.confirmations.size(),
                    scene.sent.get(0).action(),
                    priority.getItems().stream()
                            .filter(item -> item instanceof javafx.scene.control.RadioMenuItem)
                            .map(MenuItem::getText)
                            .toList());
        });

        assertThat(seen)
                .containsExactly(
                        true,
                        1,
                        ControlAction.FORCE_KILL,
                        List.of("Low", "Below normal", "Normal", "Above normal", "High"));
    }

    /** CAP-2: unavailable actions stay in their place, disabled, and the page says why. */
    @Test
    void unavailableActionsAreShownDisabledWithTheReasonOnThePage() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(List.of(ProcessListings.process(10, "demo").build()));
            scene.page.showControlProfile(
                    new prism.srm.domain.ControlProfile(FakeProcessController.noneAvailable(), java.util.Map.of()));
            scene.table().getSelectionModel().select(0);
            scene.page.applyCss();
            scene.page.layout();
            return List.of(
                    scene.control("End task…").isVisible(),
                    scene.control("End task…").isDisabled(),
                    scene.menuItem("Suspend").isDisable(),
                    scene.message());
        });

        assertThat(seen).containsExactly(true, true, true, ProcessActions.NONE_AVAILABLE);
    }

    /** CTL-8: the dialog names the process and its PID, says what follows, and Cancel is the default. */
    @Test
    void theConfirmationNamesTheProcessAndCancelIsTheDefault() {
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 400, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    ControlRequest request =
                            ControlRequest.of(ControlAction.FORCE_KILL, ProcessListings.identity(10), "demo");
                    Dialog<ButtonType> dialog = Confirmation.build(request);
                    List<ButtonType> buttons = dialog.getDialogPane().getButtonTypes();
                    Button act = (Button) dialog.getDialogPane()
                            .lookupButton(
                                    dialog.getDialogPane().getButtonTypes().get(0));
                    Button cancel = (Button) dialog.getDialogPane()
                            .lookupButton(
                                    dialog.getDialogPane().getButtonTypes().get(1));
                    return List.<Object>of(
                            dialog.getTitle(),
                            ((Label) dialog.getDialogPane().lookup(".confirm-question")).getText(),
                            dialog.getDialogPane().lookup(".confirm-question").getAccessibleText(),
                            ((Label) dialog.getDialogPane().lookup(".confirm-name")).getText(),
                            ((Label) dialog.getDialogPane().lookup(".confirm-pid")).getText(),
                            ((Label) dialog.getDialogPane().lookup(".confirm-consequence")).getText(),
                            act.getText(),
                            act.isDefaultButton(),
                            cancel.getText(),
                            cancel.isDefaultButton(),
                            cancel.isCancelButton(),
                            buttons.size());
                });

        assertThat(seen)
                .containsExactly(
                        "Force kill",
                        "Force this process to quit?",
                        "Force demo (PID 10) to quit?",
                        "demo",
                        "PID 10",
                        "The process is terminated at once. Work it has not saved is lost.",
                        "Force kill",
                        false,
                        "Cancel",
                        true,
                        true,
                        2);
    }

    @Test
    void theEndTaskConfirmationSaysTheApplicationMayDecline() {
        String consequence =
                SnapshotHarness.inspect(Theme.LIGHT, 400, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    Dialog<ButtonType> dialog = Confirmation.build(
                            ControlRequest.of(ControlAction.END_TASK, ProcessListings.identity(10), "demo"));
                    return ((Label) dialog.getDialogPane().lookup(".confirm-consequence")).getText();
                });

        assertThat(consequence).contains("decides whether to close", "ignore the request");
    }

    /** After monitoring stopped, the Resume of a held process on the page sends the request. */
    @Test
    void theResumeButtonOfAHeldProcessSendsTheRequestAfterMonitoringStopped() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(List.of(ProcessListings.process(10, "demo").build()));
            scene.page.showControlProfile(
                    new prism.srm.domain.ControlProfile(FakeProcessController.windowsLike(), java.util.Map.of()));
            scene.page.showHeldSuspensions(
                    List.of(new prism.srm.domain.SuspendedProcess(ProcessListings.identity(10), "demo")));
            scene.page.showMonitoringStopped();
            scene.page.applyCss();
            scene.page.layout();
            Button resume = (Button) scene.page.lookup(".held-suspension-resume");
            resume.fire();
            resume.fire();
            return List.of(
                    resume.isVisible(),
                    scene.control("Resume").isDisabled(),
                    scene.sent.size(),
                    scene.sent.get(0).action(),
                    scene.sent.get(0).identity().pid());
        });

        assertThat(seen).containsExactly(true, true, 1, ControlAction.RESUME, 10L);
    }

    // --- M7.5: order, priority, the line, focus, long names ---

    /**
     * What is undone easily first, the two that end a process last; the context menu in the same order.
     * UI lab v2: at 1100 x 700 the inspector is under the table, its sections in columns, so the two
     * that end a process stand on the row of the control group in a column of their own, at least 24 px
     * to the right of it (before, the bar was one row and the groups were 16 px apart).
     */
    @Test
    void theActionsComeInTheOrderOfTheirConsequences() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            List<String> menu = scene.table().getContextMenu().getItems().stream()
                    .map(item -> item instanceof javafx.scene.control.SeparatorMenuItem ? "---" : item.getText())
                    .limit(6)
                    .toList();
            javafx.scene.Node resume = scene.control("Resume").getParent();
            javafx.scene.Node priority = scene.control("Priority").getParent();
            javafx.scene.Node endTask = scene.control("End task…").getParent();
            javafx.scene.Node forceKill = scene.control("Force kill…").getParent();
            // Layout edges, not visual bounds: the focus ring (UI lab v2) is drawn outside a button
            // and is not part of the gap between two buttons.
            double usualGap = priority.getLayoutX() - right(resume);
            double groupGap = endTask.getLayoutX() - right(priority);
            double lastGap = forceKill.getLayoutX() - right(endTask);
            return List.of(
                    scene.controls(),
                    menu,
                    usualGap,
                    groupGap >= 24,
                    endTask.getLayoutY() == priority.getLayoutY(),
                    lastGap);
        });

        assertThat(seen)
                .containsExactly(
                        List.of("Suspend", "Resume", "Priority", "End task…", "Force kill…"),
                        List.of("Suspend", "Resume", "Priority", "---", "End task…", "Force kill…"),
                        8.0,
                        true,
                        true,
                        8.0);
    }

    private static double right(javafx.scene.Node node) {
        return node.getLayoutX() + node.getLayoutBounds().getMaxX();
    }

    /** The level the listing shows is checked, in the inspector and in the context menu alike. */
    @Test
    void theCurrentLevelIsCheckedInBothMenusAndMovesOnlyWithTheListing() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(
                    List.of(ProcessListings.process(10, "demo").priority(8).build()));
            scene.page.showControlProfile(FakeProcessController.windowsProfile());
            scene.table().getSelectionModel().select(0);
            List<Object> facts = new ArrayList<>();
            facts.add(checked(scene.bar().priorityChoices()));
            facts.add(checkedInContextMenu(scene));
            // Asked for High as the menu does it: the item is checked, then fired. The mark goes back to
            // Normal until a listing shows 13.
            choose(scene.bar().priorityChoices(), PriorityLevel.HIGH);
            facts.add(scene.sent.get(0).priority().orElseThrow());
            facts.add(checked(scene.bar().priorityChoices()));
            scene.page.showControlResult(new prism.srm.domain.ProcessControlResult(
                    scene.sent.get(0), prism.srm.domain.ControlOutcome.success(), "Priority set."));
            facts.add(checked(scene.bar().priorityChoices()));
            scene.page.show(
                    List.of(ProcessListings.process(10, "demo").priority(13).build()));
            facts.add(checked(scene.bar().priorityChoices()));
            facts.add(checkedInContextMenu(scene));
            facts.add(((MenuButton) scene.control("Priority")).getAccessibleText());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        List.of("Normal"),
                        List.of("Normal"),
                        PriorityLevel.HIGH,
                        List.of("Normal"),
                        List.of("Normal"),
                        List.of("High"),
                        List.of("High"),
                        "Priority, current: High");
    }

    /** A number that stands for no level checks none and says which number it is; no Realtime anywhere. */
    @Test
    void aPriorityOfNoLevelChecksNoneAndSaysWhatItIs() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(
                    List.of(ProcessListings.process(10, "demo").priority(9).build()));
            scene.page.showControlProfile(FakeProcessController.windowsProfile());
            scene.table().getSelectionModel().select(0);
            MenuButton priority = (MenuButton) scene.control("Priority");
            List<String> visible = priority.getItems().stream()
                    .filter(MenuItem::isVisible)
                    .map(item -> item instanceof javafx.scene.control.SeparatorMenuItem ? "---" : item.getText())
                    .toList();
            return List.of(checked(scene.bar().priorityChoices()), visible, priority.getAccessibleText());
        });

        assertThat(seen)
                .containsExactly(
                        List.of(),
                        List.of(
                                "Current: 9, not one of the five levels",
                                "---",
                                "Low",
                                "Below normal",
                                "Normal",
                                "Above normal",
                                "High"),
                        "Priority, current: unknown (9)");
    }

    @Test
    void choosingTheLevelTheProcessHasSendsNothing() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(
                    List.of(ProcessListings.process(10, "demo").priority(8).build()));
            scene.page.showControlProfile(FakeProcessController.windowsProfile());
            scene.table().getSelectionModel().select(0);
            choose(scene.bar().priorityChoices(), PriorityLevel.NORMAL);
            return List.of(scene.sent.size(), checked(scene.bar().priorityChoices()));
        });

        assertThat(seen).containsExactly(0, List.of("Normal"));
    }

    /** Once the request is on its way the actions are disabled; the focus goes to the table, not anywhere. */
    @Test
    void theFocusGoesToTheTableWhenTheActionThatHadItIsDisabled() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            ButtonBase suspend = scene.control("Suspend");
            suspend.requestFocus();
            boolean focusedBefore = scene.page.getScene().getFocusOwner() == suspend;
            suspend.fire();
            return List.of(
                    focusedBefore, suspend.isDisabled(), scene.page.getScene().getFocusOwner() == scene.table());
        });

        assertThat(seen).containsExactly(true, true, true);
    }

    /** A declined confirmation leaves the focus on the control that asked. */
    @Test
    void aDeclinedConfirmationGivesTheFocusBackToItsButton() {
        List<Object> seen = inPage(false, scene -> {
            scene.ready();
            ButtonBase kill = scene.control("Force kill…");
            kill.requestFocus();
            kill.fire();
            return List.of(
                    scene.confirmations.size(),
                    scene.sent.size(),
                    scene.page.getScene().getFocusOwner() == kill);
        });

        assertThat(seen).containsExactly(1, 0, true);
    }

    /**
     * The smallest window (what Windows 11 leaves of 720 x 480) leaves the line too little room beside
     * the buttons: it goes under them and the buttons stay where they were.
     *
     * <p>UI lab v2: in the smallest window the inspector is the sheet over the page, opened here as
     * Details opens it; the bar is stacked in its 360 px, so the line is under Force kill and shows
     * its first part, with the whole in its tooltip (before, under the table, it had the whole width).
     * At 1100 x 700 the inspector is under the table, its sections in columns, and the line spans them
     * all under the buttons, whole (changed on purpose: it stood beside the buttons before).
     *
     * <p>Fix round H (PR-03, LO-06): the sheet is 400 px wide, so in the smallest window the line under
     * Force kill now holds this result whole and needs no tooltip (changed on purpose: it was cut in 360).
     */
    @Test
    void inTheSmallestWindowTheLineGoesUnderTheButtonsWhole() {
        Function<Scene, List<Object>> afterAResult = scene -> {
            scene.ready();
            scene.page.openInspector();
            // The inspector goes into the sheet as it opens (round G, the resize measurement): its first
            // layout there takes two passes, as two pulses lay it out on screen while the sheet slides in.
            for (int pass = 0; pass < 2; pass++) {
                scene.page.applyCss();
                scene.page.layout();
            }
            // In the bar: under the table the inspector may scroll to show the line, which moves the whole bar.
            javafx.scene.Node bar = scene.page.lookup(".process-actions");
            double suspendBefore = scene.control("Suspend").localToScene(0, 0).getY()
                    - bar.localToScene(0, 0).getY();
            scene.control("End task…").fire();
            scene.page.showControlResult(new prism.srm.domain.ProcessControlResult(
                    scene.sent.get(0),
                    prism.srm.domain.ControlOutcome.success(),
                    "Close request sent to demo (PID 10).",
                    "The application may ignore it."));
            scene.page.applyCss();
            scene.page.layout();
            ActionResultLine line = scene.line();
            javafx.geometry.Bounds lineBounds = line.localToScene(line.getLayoutBounds());
            javafx.geometry.Bounds forceKill = scene.control("Force kill…")
                    .localToScene(scene.control("Force kill…").getLayoutBounds());
            javafx.geometry.Bounds page = scene.page.localToScene(scene.page.getLayoutBounds());
            return List.of(
                    lineBounds.getMinY() >= forceKill.getMaxY(),
                    line.tooltipShown(),
                    lineBounds.getMaxX() <= page.getMaxX(),
                    scene.control("Suspend").localToScene(0, 0).getY()
                                    - bar.localToScene(0, 0).getY()
                            == suspendBefore);
        };

        List<Object> smallest = inPage(704, 441, true, afterAResult);
        List<Object> wide = inPage(1100, 700, true, afterAResult);

        assertThat(smallest)
                .as("under the buttons, whole in the wider sheet, in the page; the buttons did not move")
                .containsExactly(true, false, true, true);
        assertThat(wide).as("under the columns, whole").containsExactly(true, false, true, true);
    }

    /**
     * Under the table the line about the last action is pinned with the actions at the top of the
     * inspector: what an action did is never only below the fold, and the facts that scroll under it do
     * not take it away. Fix round G (K2, PR-02): the line used to be scrolled into view; it is now never
     * out of it, so there is nothing to scroll.
     */
    @Test
    void underTheTableTheLineOfAResultIsPinnedInTheInspectorsView() {
        List<Object> seen = inPage(1100, 700, true, scene -> {
            scene.ready();
            scene.page.applyCss();
            scene.page.layout();
            scene.control("End task…").fire();
            scene.page.showControlResult(new prism.srm.domain.ProcessControlResult(
                    scene.sent.get(0),
                    prism.srm.domain.ControlOutcome.success(),
                    "Close request sent to demo (PID 10).",
                    "The application may ignore it."));
            scene.page.applyCss();
            scene.page.layout();
            ProcessDetailsPane pane = (ProcessDetailsPane) scene.page.lookup(".process-details");
            prism.srm.ui.control.InspectorFrame frame = pane.frame();
            javafx.scene.Node pinned = pane.lookup(".process-details-pinned");
            ActionResultLine line = scene.line();
            javafx.geometry.Bounds lineBounds = line.localToScene(line.getLayoutBounds());
            javafx.geometry.Bounds paneBounds = pane.localToScene(pane.getLayoutBounds());
            javafx.scene.Node viewport = frame.lookup(".inspector-scroll").lookup(".viewport");
            javafx.geometry.Bounds view = viewport.localToScene(viewport.getLayoutBounds());
            frame.scrollDetailsToEnd();
            scene.page.layout();
            javafx.geometry.Bounds scrolled = line.localToScene(line.getLayoutBounds());
            return List.of(
                    frame.isPinned(),
                    isInside(line, pinned),
                    lineBounds.getMinY() >= paneBounds.getMinY() - 0.5 && lineBounds.getMaxY() <= view.getMinY() + 0.5,
                    scrolled.getMinY() == lineBounds.getMinY());
        });

        assertThat(seen)
                .as("pinned, the line in the pinned part, above the part that scrolls, and it stays there")
                .containsExactly(true, true, true, true);
    }

    /** The line: the result in two parts, then, in a narrow place, only the first part, with the whole in a tooltip text. */
    @Test
    void theLineShowsTheResultInTwoPartsAndKeepsTheFirstWhereItIsNarrow() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            scene.control("End task…").fire();
            scene.page.showControlResult(new prism.srm.domain.ProcessControlResult(
                    scene.sent.get(0),
                    prism.srm.domain.ControlOutcome.success(),
                    "Close request sent to demo (PID 10).",
                    "The application may ignore it."));
            scene.page.applyCss();
            scene.page.layout();
            ActionResultLine line = scene.line();
            List<Label> parts = line.getChildrenUnmodifiable().stream()
                    .map(Label.class::cast)
                    .toList();
            List<Object> facts = new ArrayList<>();
            facts.add(parts.get(0).getText());
            facts.add(parts.get(1).getText());
            facts.add(parts.get(1).isVisible());
            facts.add(parts.get(0).getPseudoClassStates().stream()
                    .map(Object::toString)
                    .filter(state -> List.of("success", "warning", "failure").contains(state))
                    .toList());
            facts.add(line.tooltipShown());
            line.resize(parts.get(0).prefWidth(-1) + 10, line.getHeight());
            line.layout();
            facts.add(parts.get(1).isVisible());
            // Whole: laid out at least as wide as its text (the width is snapped up to the pixel).
            facts.add(parts.get(0).getWidth() >= parts.get(0).prefWidth(-1));
            facts.add(line.tooltipShown());
            line.resize(parts.get(0).prefWidth(-1) / 2, line.getHeight());
            line.layout();
            facts.add(parts.get(0).getWidth() < parts.get(0).prefWidth(-1));
            facts.add(line.tooltipShown());
            line.resize(1000, line.getHeight());
            line.layout();
            facts.add(line.tooltipShown());
            facts.add(scene.message());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        "Close request sent to demo (PID 10).",
                        "The application may ignore it.",
                        true,
                        List.of("success"),
                        false,
                        false,
                        true,
                        true,
                        true,
                        true,
                        false,
                        "Close request sent to demo (PID 10). The application may ignore it.");
    }

    /** A new result fades in, finitely; with reduced motion it is there at once. */
    @Test
    void aNewResultFadesInUnlessTheUserAsksForLessMotion() {
        List<Object> seen = inPage(true, scene -> {
            scene.ready();
            javafx.scene.Scene fxScene = scene.page.getScene();
            List<Object> facts = new ArrayList<>();
            fxScene.getPreferences().setReducedMotion(false);
            scene.control("Suspend").fire();
            scene.page.showControlResult(new prism.srm.domain.ProcessControlResult(
                    scene.sent.get(0), prism.srm.domain.ControlOutcome.success(), "demo was suspended."));
            facts.add(scene.line().arriving());
            facts.add(scene.line().getOpacity() < 1);
            fxScene.getPreferences().setReducedMotion(true);
            scene.control("Resume").fire();
            scene.page.showControlResult(new prism.srm.domain.ProcessControlResult(
                    scene.sent.get(1), prism.srm.domain.ControlOutcome.success(), "demo was resumed."));
            facts.add(scene.line().arriving());
            facts.add(scene.line().getOpacity());
            return facts;
        });

        assertThat(seen).containsExactly(true, true, false, 1.0);
    }

    /** A long name is cut where it is drawn, never in the data, and the PID beside it never is. */
    @Test
    void aLongNameIsCutInTheDialogButItsPidIsNot() {
        String longName = "a-process-with-a-name-far-too-long-for-any-dialog-to-show-whole-".repeat(3) + ".exe";
        List<Object> seen =
                SnapshotHarness.inspect(Theme.LIGHT, 400, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    Dialog<ButtonType> dialog = Confirmation.build(
                            ControlRequest.of(ControlAction.FORCE_KILL, ProcessListings.identity(4242), longName));
                    javafx.scene.control.DialogPane pane = dialog.getDialogPane();
                    pane.applyCss();
                    pane.resize(pane.prefWidth(-1), pane.prefHeight(-1));
                    pane.layout();
                    Label name = (Label) dialog.getDialogPane().lookup(".confirm-name");
                    Label pid = (Label) dialog.getDialogPane().lookup(".confirm-pid");
                    return List.<Object>of(
                            name.getText(),
                            name.getWidth() < name.prefWidth(-1),
                            pid.getWidth() >= pid.prefWidth(-1),
                            dialog.getDialogPane()
                                    .lookup(".confirm-question")
                                    .getAccessibleText()
                                    .contains(longName));
                });

        assertThat(seen).containsExactly(longName, true, true, true);
    }

    private static List<String> checked(PriorityChoices choices) {
        List<String> levels = new ArrayList<>();
        for (PriorityLevel level : PriorityLevel.values()) {
            if (choices.item(level).isSelected()) {
                levels.add(choices.item(level).getText());
            }
        }
        return levels;
    }

    private static List<String> checkedInContextMenu(Scene scene) {
        Menu priority = (Menu) scene.menuItem("Priority");
        return priority.getItems().stream()
                .filter(item -> item instanceof javafx.scene.control.RadioMenuItem radio && radio.isSelected())
                .map(MenuItem::getText)
                .toList();
    }

    /**
     * The confirmations are windows of their own and so not in the snapshots of the page. Their
     * panes are rendered here, both actions, a short and a long name, in both themes, for
     * inspection against docs/UI_GUIDE.md.
     */
    @Test
    void rendersTheConfirmations() {
        String longName = "a-process-with-a-name-far-too-long-for-any-dialog-to-show-whole-".repeat(2) + ".exe";
        for (Theme theme : Theme.values()) {
            for (ControlAction action : List.of(ControlAction.END_TASK, ControlAction.FORCE_KILL)) {
                // The short name twice: the second time with the keyboard focus on Cancel, the default button.
                // End task also as a termination signal asks it (Linux, macOS).
                List<String> names = action == ControlAction.END_TASK
                        ? List.of("java", longName, "java focused", "java signal")
                        : List.of("java", longName, "java focused");
                for (String name : names) {
                    java.nio.file.Path png =
                            SnapshotHarness.inspect(theme, 400, 200, () -> new javafx.scene.layout.Pane(), root -> {
                                boolean focused = name.endsWith(" focused");
                                boolean signal = name.endsWith(" signal");
                                Dialog<ButtonType> dialog = Confirmation.build(
                                        ControlRequest.of(
                                                action,
                                                ProcessListings.identity(4242),
                                                focused || signal ? "java" : name),
                                        signal ? Optional.of(ControlMechanism.TERMINATION_SIGNAL) : Optional.empty());
                                javafx.scene.control.DialogPane pane = dialog.getDialogPane();
                                if (focused) {
                                    // The harness has no window focus, so the ring is drawn by forcing its
                                    // pseudo-classes.
                                    javafx.scene.Node cancel = pane.getButtonTypes().stream()
                                            .map(pane::lookupButton)
                                            .filter(button -> ((javafx.scene.control.Button) button).isDefaultButton())
                                            .findFirst()
                                            .orElseThrow();
                                    cancel.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), true);
                                    cancel.pseudoClassStateChanged(PseudoClass.getPseudoClass("focus-visible"), true);
                                }
                                pane.getScene()
                                        .getPreferences()
                                        .setColorScheme(
                                                theme == Theme.DARK
                                                        ? javafx.application.ColorScheme.DARK
                                                        : javafx.application.ColorScheme.LIGHT);
                                pane.applyCss();
                                pane.resize(pane.prefWidth(-1), pane.prefHeight(-1));
                                pane.layout();
                                javafx.scene.image.WritableImage image = pane.snapshot(null, null);
                                return write(
                                        "confirm-"
                                                + action.name()
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .replace('_', '-')
                                                + (name.equals("java")
                                                        ? ""
                                                        : focused
                                                                ? "-cancel-focused"
                                                                : signal ? "-signal" : "-long-name"),
                                        theme,
                                        image);
                            });
                    assertThat(png).isRegularFile().isNotEmptyFile();
                }
            }
        }
    }

    private static java.nio.file.Path write(String name, Theme theme, javafx.scene.image.WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        image.getPixelReader()
                .getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        out.setRGB(0, 0, width, height, pixels, 0, width);
        java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("srm.snapshot.dir", "target/ui-snapshots"))
                .resolve("%s-%s-%dx%d.png"
                        .formatted(name, theme.name().toLowerCase(java.util.Locale.ROOT), width, height));
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(out, "png", file.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return file;
    }

    /** Whatever was checked by hand, a menu that opens shows the level of the listing: inspector and context menu. */
    @Test
    void aMenuThatOpensMarksTheLevelOfTheListing() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(
                    List.of(ProcessListings.process(10, "demo").priority(8).build()));
            scene.page.showControlProfile(FakeProcessController.windowsProfile());
            scene.table().getSelectionModel().select(0);
            List<Object> facts = new ArrayList<>();
            MenuButton priority = (MenuButton) scene.control("Priority");
            scene.bar().priorityChoices().item(PriorityLevel.HIGH).setSelected(true);
            priority.getOnShowing().handle(null);
            facts.add(checked(scene.bar().priorityChoices()));
            Menu context = (Menu) scene.menuItem("Priority");
            context.getItems().stream()
                    .filter(item -> "Low".equals(item.getText()))
                    .map(javafx.scene.control.RadioMenuItem.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .setSelected(true);
            context.getOnShowing().handle(null);
            facts.add(checkedInContextMenu(scene));
            return facts;
        });

        assertThat(seen).containsExactly(List.of("Normal"), List.of("Normal"));
    }

    /** As the menu skin does it: the item is checked first, then fired. */
    private static void choose(PriorityChoices choices, PriorityLevel level) {
        choices.item(level).setSelected(true);
        choices.item(level).fire();
    }

    /** The page asks for the confirmation with the mechanism of the system, which the dialog is worded after. */
    @Test
    void theConfirmationIsAskedWithTheMechanismOfTheSystem() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.show(List.of(ProcessListings.process(10, "demo").build()));
            scene.page.showControlProfile(FakeProcessController.linuxProfile());
            scene.table().getSelectionModel().select(0);
            scene.control("End task…").fire();
            return List.of(scene.mechanisms.get(0), scene.confirmations.get(0).action());
        });

        assertThat(seen).containsExactly(Optional.of(ControlMechanism.TERMINATION_SIGNAL), ControlAction.END_TASK);
    }

    @Test
    void aTerminationSignalIsConfirmedAsARequestToTerminate() {
        List<String> texts =
                SnapshotHarness.inspect(Theme.LIGHT, 400, 200, () -> new javafx.scene.layout.Pane(), root -> {
                    javafx.scene.control.DialogPane pane = Confirmation.build(
                                    ControlRequest.of(ControlAction.END_TASK, ProcessListings.identity(4242), "java"),
                                    Optional.of(ControlMechanism.TERMINATION_SIGNAL))
                            .getDialogPane();
                    return pane.lookupAll(".label").stream()
                            .map(Label.class::cast)
                            .flatMap(label -> java.util.stream.Stream.of(label.getText(), label.getAccessibleText()))
                            .filter(java.util.Objects::nonNull)
                            .toList();
                });

        assertThat(texts)
                .contains(
                        "Ask this process to terminate?",
                        "Ask java (PID 4242) to terminate?",
                        "The process decides whether to end. It may save its work first, or ignore the request.")
                .doesNotContain("Ask this process to close?");
    }

    /** CAP-2 in the page itself: on Prism's own row the inspector and the context menu offer nothing and say why. */
    @Test
    void prismsOwnRowHasEveryControlAndMenuItemDisabledWithTheReason() {
        List<Object> seen = inPage(true, scene -> {
            scene.page.showOwnProcess(10);
            scene.ready();
            List<Object> facts = new ArrayList<>();
            for (String text : List.of("End task…", "Force kill…", "Suspend", "Resume", "Priority")) {
                facts.add(text + " disabled=" + scene.control(text).isDisabled());
            }
            for (String text : List.of(
                    "Suspend",
                    "Resume",
                    "Priority",
                    "End task…",
                    "Force kill…",
                    "Manage",
                    "Set CPU limit…",
                    "Add to OOM management",
                    "Remove from OOM management",
                    "Unmanage")) {
                facts.add("menu " + text + " disabled=" + scene.menuItem(text).isDisable());
            }
            scene.menuItem("Force kill…").fire();
            facts.add(scene.message());
            facts.add("asked=" + scene.confirmations.size() + " sent=" + scene.sent.size());
            // The other process of the listing: the actions are back.
            scene.table().getSelectionModel().select(1);
            facts.add("other End task disabled=" + scene.control("End task…").isDisabled());
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        "End task… disabled=true",
                        "Force kill… disabled=true",
                        "Suspend disabled=true",
                        "Resume disabled=true",
                        "Priority disabled=true",
                        "menu Suspend disabled=true",
                        "menu Resume disabled=true",
                        "menu Priority disabled=true",
                        "menu End task… disabled=true",
                        "menu Force kill… disabled=true",
                        "menu Manage disabled=true",
                        "menu Set CPU limit… disabled=true",
                        "menu Add to OOM management disabled=true",
                        "menu Remove from OOM management disabled=true",
                        "menu Unmanage disabled=true",
                        ProcessActions.OWN_PROCESS,
                        "asked=0 sent=0",
                        "other End task disabled=false");
    }

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Node ancestor) {
        for (javafx.scene.Node at = node; at != null; at = at.getParent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }
}
