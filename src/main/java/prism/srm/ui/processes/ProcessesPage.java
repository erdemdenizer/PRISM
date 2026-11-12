package prism.srm.ui.processes;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableRow;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessDetailsResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.domain.SuspendedProcess;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.CpuLimitDialog;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.control.SelectionLine;
import prism.srm.ui.control.SheetOverlay;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;
import prism.srm.ui.metric.PageNotice;

/**
 * The Processes page: its title with the number of processes, the filter, the table of all
 * processes, and the inspector of the selected one with the actions on it (End task, Force kill,
 * Suspend, Resume, Priority). The actions are also in the context menu of the table, which is how
 * the keyboard reaches them from there. End task and Force kill ask for confirmation first (CTL-8).
 *
 * <p>UI lab v2: the title line holds the number of processes and the filter too (Ctrl+F goes to
 * it). The inspector is placed by the size of the window, by the rule the Limits page follows as
 * well ({@link Breakpoints#inspectorFor}): beside the table, under it with its sections in columns,
 * or in a sheet over the page ({@link SheetOverlay}), where Enter, a double click or Details opens it
 * and Escape, × or a click beside it closes it. Its sections, its view models and its keyboard routes
 * are the same in every place.
 *
 * <p>Its structure is on screen from the start. Until the first listing arrives the table says
 * that the processes are being read; a filter that leaves nothing says so in the table; a failed
 * start, and process monitoring that ended while the application ran, are explained at the top of
 * the page, and then no process is shown.
 *
 * <p>Every method is called on the JavaFX Application Thread and reads nothing from the system:
 * the listings are handed in, and the details of a process and the actions on it are asked for
 * through the functions given at construction, which return at once.
 */
public final class ProcessesPage extends VBox {

    /** The size of the icons in the filter field. */
    private static final double SEARCH_ICON_SIZE = 16;

    private static final double CLEAR_ICON_SIZE = 12;

    /** The inspector beside the table: as wide at first, and between these widths when moved. */
    static final double INSPECTOR_WIDTH = 360;

    static final double INSPECTOR_MIN = 320;

    /**
     * Under the table: the table keeps at least this many rows (or as many as it has) before the inspector
     * gets more than its pinned part and the least of its details (owner decision K2, PR-04).
     */
    static final int TABLE_ROWS_KEPT = 5;

    /** Under the table: the table never shows fewer rows than this, whatever the inspector needs. */
    static final int TABLE_ROWS_LEAST = 2;

    static final double INSPECTOR_MAX = 440;

    /** The least the filter field gives way to; below it, it folds into the search button. */
    private static final double FILTER_MIN = 160;

    private static final double TOOLBAR_GAP = 8;

    private static final PseudoClass IN_SHEET = PseudoClass.getPseudoClass("sheet");

    private static final PseudoClass SIDE = PseudoClass.getPseudoClass("side");

    /** Whether the inspector is a sheet now: the line under the table is shown only then. */
    private final javafx.beans.property.BooleanProperty inSheet = new javafx.beans.property.SimpleBooleanProperty();

    private final ProcessesViewModel model;
    private final ProcessTable table;
    private final ProcessActionBar actionBar;
    private final ProcessDetailsPane details;
    private final PageHeader header;
    private final Node notice;
    private final Node held;
    /** Where the inspector is a sheet: the selected process and the line about its last action, under the table. */
    private final SelectionLine selectionLine;

    /** The table and the docked inspector, in every mode (fix round H: nothing moves between parents). */
    private final ProcessDock dock;

    private final SheetOverlay sheet;
    private final Button openDetails = new Button("Details");
    private final Button searchButton = new Button();
    private final Label count = new Label();
    private final TextField filter = new TextField();
    private final StackPane filterField;
    private final RecoveredSuspensionsSummary recoveredSummary;
    /** The page changed size: the focus the page last had is the one to keep, in a scene that is new too. */
    private final InvalidationListener pageSize = observable -> updateMode(this.lastFocus);
    /** The last control of the page that had the focus, so that it keeps it when the page changes scene. */
    private final ChangeListener<Node> focusFollower = (observable, before, after) -> followFocus(after);

    private Node lastFocus;
    private Breakpoints.Inspector mode;
    /** In a title line too narrow for the filter, it is folded into the search button until asked for. */
    private boolean filterFolded;

    private boolean filterAskedFor;
    private final Confirmation confirmation;
    private final CpuLimitDialog limitDialog;
    private final ResumeConfirmation resumeConfirmation;
    /** The list of what an earlier session may have left suspended; one, shown in its dialog on request. */
    private final RecoveredSuspensionsPane recoveredPane;

    /** Asks the user before the Resume of a process an earlier session may have left suspended. */
    @FunctionalInterface
    interface ResumeConfirmation {
        boolean confirm(RecoveredSuspension entry, Optional<prism.srm.domain.ControlMechanism> resume, Node owner);

        static boolean showDialog(
                RecoveredSuspension entry, Optional<prism.srm.domain.ControlMechanism> resume, Node owner) {
            String target = entry.name() + " (PID " + entry.identity().pid() + ")";
            return Confirmation.show(
                    Confirmation.build(
                            "Resume",
                            RecoveredSuspensions.confirmHeading(),
                            "Resume " + target + "?",
                            entry.name(),
                            entry.identity().pid(),
                            RecoveredSuspensions.confirmConsequence(resume),
                            false),
                    owner);
        }
    }

    /**
     * @param formatter formats the values in the notation of the user's locale
     * @param zone the time zone start times are shown in
     * @param logFolder where the log file is, as text for the user; named when something fails
     * @param detailsRequest asks for the path and the command line of a process and returns at
     *     once; the answer is handed to {@link #showDetails}
     */
    public ProcessesPage(
            ValueFormatter formatter, ZoneId zone, String logFolder, Consumer<ProcessIdentity> detailsRequest) {
        this(formatter, zone, logFolder, detailsRequest, request -> {}, Confirmation::showDialog);
    }

    /**
     * @param controlRequest carries out an action on a process and returns at once; the result is
     *     handed to {@link #showControlResult}
     */
    public ProcessesPage(
            ValueFormatter formatter,
            ZoneId zone,
            String logFolder,
            Consumer<ProcessIdentity> detailsRequest,
            Consumer<ControlRequest> controlRequest) {
        this(formatter, zone, logFolder, detailsRequest, controlRequest, Confirmation::showDialog);
    }

    /**
     * @param limitRequest manages a process or sets its CPU limit and returns at once; the result is
     *     handed to {@link #showLimitResult}
     * @param recoveryRequest resumes or dismisses a process an earlier session may have left
     *     suspended and returns at once; the result is handed to {@link #showRecoveryResult}
     */
    public ProcessesPage(
            ValueFormatter formatter,
            ZoneId zone,
            String logFolder,
            Consumer<ProcessIdentity> detailsRequest,
            Consumer<ControlRequest> controlRequest,
            Consumer<LimitRequest> limitRequest,
            Consumer<RecoveryRequest> recoveryRequest) {
        this(
                formatter,
                zone,
                logFolder,
                detailsRequest,
                controlRequest,
                limitRequest,
                recoveryRequest,
                Confirmation::showDialog,
                CpuLimitDialog::showDialog,
                ResumeConfirmation::showDialog);
    }

    /** @param confirmation asks the user before End task and Force kill; a test answers in its place */
    ProcessesPage(
            ValueFormatter formatter,
            ZoneId zone,
            String logFolder,
            Consumer<ProcessIdentity> detailsRequest,
            Consumer<ControlRequest> controlRequest,
            Confirmation confirmation) {
        this(
                formatter,
                zone,
                logFolder,
                detailsRequest,
                controlRequest,
                request -> {},
                request -> {},
                confirmation,
                CpuLimitDialog::showDialog,
                ResumeConfirmation::showDialog);
    }

    /**
     * @param limitDialog asks for a CPU limit; a test answers in its place
     * @param resumeConfirmation asks before resuming what an earlier session left; a test answers
     */
    ProcessesPage(
            ValueFormatter formatter,
            ZoneId zone,
            String logFolder,
            Consumer<ProcessIdentity> detailsRequest,
            Consumer<ControlRequest> controlRequest,
            Consumer<LimitRequest> limitRequest,
            Consumer<RecoveryRequest> recoveryRequest,
            Confirmation confirmation,
            CpuLimitDialog limitDialog,
            ResumeConfirmation resumeConfirmation) {
        this.confirmation = confirmation;
        this.limitDialog = limitDialog;
        this.resumeConfirmation = resumeConfirmation;
        model = new ProcessesViewModel(
                formatter, zone, logFolder, detailsRequest, controlRequest, limitRequest, recoveryRequest);
        getStyleClass().add("processes");
        getStylesheets()
                .addAll(Stylesheets.metric(), Stylesheets.table(), Stylesheets.control(), Stylesheets.processes());

        recoveredPane = new RecoveredSuspensionsPane(model.recovered(), this::resumeRecovered);
        table = new ProcessTable(model, model.text());
        actionBar = new ProcessActionBar(model.actions(), this::act);
        VBox.setVgrow(table, Priority.ALWAYS);
        table.menu().putActions(actionItems());

        details = new ProcessDetailsPane(model.details(), actionBar);
        actionBar.setMiddle(new LimitsSection(model.managed(), this::chooseManaged));
        selectionLine = new SelectionLine(
                model.details().titleProperty(),
                model.details().pidProperty(),
                model.actions().line());
        selectionLine.getStyleClass().add("process-selection-line");
        // Under the table while the inspector is a sheet and a process is selected (PR-02, PR-07).
        selectionLine
                .visibleProperty()
                .bind(model.details().processShownProperty().and(inSheet));
        selectionLine.managedProperty().bind(selectionLine.visibleProperty());
        filterField = buildFilterField();
        recoveredSummary = new RecoveredSuspensionsSummary(model.recovered(), this::reviewRecovered);
        header = header();
        notice = new PageNotice(model.status());
        held = new HeldSuspensionsPane(model.held());
        sheet = new SheetOverlay("Details", "Details of the selected process", () -> table);
        sheet.addStyleClasses("process-sheet", "process-sheet-scrim", "process-sheet-close");
        details.getStyleClass().add("process-inspector");
        dock = new ProcessDock(table, details);
        VBox.setVgrow(dock, Priority.ALWAYS);
        // The children of the page, once (fix round H): a change of mode shows and hides, it moves nothing.
        getChildren().setAll(header, notice, held, dock, selectionLine, sheet);
        openers();

        arrange(Breakpoints.Inspector.BOTTOM);
        // The room the page has decides, not the window: the navigation beside it may be wide or narrow.
        widthProperty().addListener(pageSize);
        heightProperty().addListener(pageSize);
        sceneProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.focusOwnerProperty().removeListener(focusFollower);
            }
            Node focused = lastFocus;
            if (after != null) {
                after.focusOwnerProperty().addListener(focusFollower);
            }
            updateMode(focused);
        });
    }

    /** For tests: where the inspector is now. */
    Breakpoints.Inspector mode() {
        return mode;
    }

    /** For tests: whether the sheet over the page is open. */
    boolean sheetOpen() {
        return sheet.isOpen();
    }

    /** For tests: the sheet over the page. */
    SheetOverlay sheet() {
        return sheet;
    }

    /** Remembers the control of the page with the focus; one outside the page is forgotten. */
    private void followFocus(Node owner) {
        if (owner == null) {
            return;
        }
        lastFocus = isInside(owner, this) || isInside(owner, header) ? owner : null;
    }

    /**
     * The inspector where the room of the page has it.
     *
     * @param movedFocus the control that had the focus in the scene the page came from; {@code null}
     *     when the page did not change scene
     */
    private void updateMode(Node movedFocus) {
        Scene scene = getScene();
        if (scene == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Node focused = movedFocus != null ? movedFocus : scene.getFocusOwner();
        Breakpoints.Inspector next = Breakpoints.inspectorFor(getWidth(), getHeight());
        arrange(next, focused);
        // Under the table, in columns where the page is wide enough for them, else stacked (a tall narrow window).
        details.setNarrow(next != Breakpoints.Inspector.BOTTOM || !Breakpoints.inspectorInColumns(getWidth()));
    }

    private void arrange(Breakpoints.Inspector next) {
        arrange(next, null);
    }

    /**
     * Puts the table and the inspector where the mode has them. Fix round H (the resize cost): no node
     * changes its parent; the dock lays the table and the inspector out for the mode, the line under the
     * table and Details are shown or hidden. Only a sheet that was opened gives the inspector back.
     *
     * <p>The focus, where it was in the inspector: into a sheet that is closed it cannot go, so it goes
     * to the table; into a docked inspector it stays on the same control where that is still there and
     * enabled, else it goes to the table. The sheet, open or not, closes at once.
     */
    private void arrange(Breakpoints.Inspector next, Node focused) {
        if (next == mode) {
            return;
        }
        boolean inInspector = focused != null && (isInside(focused, details) || sheet.contains(focused));
        sheet.dismiss();
        mode = next;
        pseudoClassStateChanged(SIDE, next == Breakpoints.Inspector.SIDE);
        details.setNarrow(next != Breakpoints.Inspector.BOTTOM || !Breakpoints.inspectorInColumns(getWidth()));
        details.pseudoClassStateChanged(IN_SHEET, next == Breakpoints.Inspector.SHEET);
        if (next != Breakpoints.Inspector.SHEET && sheet.holds(details)) {
            sheet.setContent(null);
        }
        // The inspector goes into the sheet when the sheet opens, not before (round G); docked, it is in the dock.
        dock.setMode(next);
        inSheet.set(next == Breakpoints.Inspector.SHEET);
        openDetails.setVisible(next == Breakpoints.Inspector.SHEET);
        openDetails.setManaged(next == Breakpoints.Inspector.SHEET);
        fitToolbar();
        layOutNow(header, openDetails);
        if (inInspector) {
            // The split and the scroll pane hold their content in their skins: those are made now, so
            // that the control the focus was on is in the scene again before it is asked to take it.
            applyCss();
            boolean stays = next != Breakpoints.Inspector.SHEET
                    && isInside(focused, details)
                    && SheetOverlay.canTakeFocus(focused);
            (stays ? focused : table).requestFocus();
        }
    }

    /** For tests: the table and the docked inspector. */
    ProcessDock dock() {
        return dock;
    }

    /**
     * Enter and a double click on a row open the sheet over the page; Ctrl+F goes to the filter.
     * Beside or under the table the inspector is always there, and these do nothing new.
     */
    private void openers() {
        table.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER
                    && mode == Breakpoints.Inspector.SHEET
                    && table.getSelectionModel().getSelectedItem() != null) {
                openInspector(table);
                event.consume();
            }
        });
        table.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (mode == Breakpoints.Inspector.SHEET
                    && event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 2
                    && onARow(event.getPickResult().getIntersectedNode())) {
                openInspector(table);
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
                focusFilter();
                event.consume();
            }
        });
        // A plain secondary button, as on the Limits page: a quiet one read as a label (UI audit SH-14).
        openDetails.getStyleClass().add("process-details-open");
        // Never cut to "..." (PR-06): the summary and the number give way before it.
        openDetails.setMinWidth(Region.USE_PREF_SIZE);
        // The words on screen are short; a screen reader says what the button opens.
        openDetails.setAccessibleText("Details of the selected process");
        openDetails.setTooltip(new Tooltip("Details of the selected process    Enter"));
        openDetails
                .disableProperty()
                .bind(model.details().processShownProperty().not());
        openDetails.setOnAction(event -> openInspector(openDetails));
    }

    private static boolean onARow(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at instanceof TableRow<?> row) {
                return !row.isEmpty();
            }
        }
        return false;
    }

    /** Opens the sheet over the page, in the window where the inspector is one; focus goes into it. */
    void openInspector() {
        openInspector(table);
    }

    /** @param from where the focus goes back to when the sheet closes */
    private void openInspector(Node from) {
        if (mode == Breakpoints.Inspector.SHEET) {
            if (!sheet.holds(details)) {
                dock.releaseInspector();
                sheet.setContent(details);
            }
            sheet.open(from);
        }
    }

    /** Closes the sheet over the page; the focus goes back to what opened it, else to the table. */
    void closeInspector() {
        sheet.close();
    }

    /** Ctrl+F: the filter, unfolded where it was folded into its button, with its text selected. */
    private void focusFilter() {
        sheet.dismiss();
        filterAskedFor = true;
        fitToolbar();
        filter.requestFocus();
        filter.selectAll();
    }

    /** Where the focus goes after an action that disabled the control it was on: the table, or × in the sheet. */
    private void focusAfterAction() {
        if (sheet.isOpen()) {
            sheet.focusClose();
        } else {
            table.requestFocus();
        }
    }

    /** A choice of "Limits & protection", as the item of the same name in the context menu. */
    private void chooseManaged(ManagedActions.Choice choice) {
        Node origin = getScene() == null ? null : getScene().getFocusOwner();
        if (choice == ManagedActions.Choice.SET_CPU_LIMIT) {
            setCpuLimit();
        } else {
            model.managed().choose(choice);
        }
        if (origin != null && origin.isDisabled()) {
            focusAfterAction();
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // The sheet and its scrim cover the page; they take no room in it.
        sheet.resizeRelocate(0, 0, getWidth(), getHeight());
    }

    /**
     * What process control is on this system: the capabilities, and the level each listed priority
     * stands for. Until it is known, every action is disabled.
     */
    public void showControlProfile(ControlProfile profile) {
        model.showControlProfile(profile);
    }

    /** The PID of Prism itself, whose row offers no action. Call on the JavaFX Application Thread. */
    public void showOwnProcess(long pid) {
        model.showOwnProcess(pid);
    }

    /**
     * Takes the result of an action. It is shown if it is about the selected process; the Logs page
     * has every result. Call on the JavaFX Application Thread.
     */
    public void showControlResult(ProcessControlResult result) {
        model.showControlResult(result);
    }

    /**
     * Carries out an action on the selected process, after the user confirmed it where that is
     * needed. The keyboard focus stays where the user can go on: after a declined confirmation, on
     * the control that asked; once the request is on its way, which disables the actions of the
     * process, on the table, where it would otherwise fall to wherever the scene sends it.
     */
    void act(ControlAction action, Optional<PriorityLevel> level) {
        Optional<ControlRequest> request = model.actions().requestFor(action, level);
        if (request.isEmpty()) {
            return;
        }
        Node origin = getScene() == null ? null : getScene().getFocusOwner();
        if (Confirmation.needed(action)
                && !confirmation.confirm(request.get(), model.actions().mechanism(action), this)) {
            if (origin != null && !origin.isDisabled()) {
                origin.requestFocus();
            }
            return;
        }
        // The dialog lets listings arrive; a process that exited meanwhile is not acted on.
        model.actions().request(request.get());
        if (origin != null && (origin == table || origin.isDisabled() || isInside(origin, actionBar))) {
            if (origin == table) {
                table.requestFocus();
            } else {
                focusAfterAction();
            }
        }
    }

    private static boolean isInside(Node node, Node container) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == container) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes the managed processes and their limits: the mark of the selected process, and whether a
     * CPU limit can be set. Call on the JavaFX Application Thread.
     */
    public void showLimits(LimitsSnapshot snapshot) {
        model.showLimits(snapshot);
    }

    /** Takes the result of a request about limits; shown if it is about the selected process. */
    public void showLimitResult(LimitResult result) {
        model.showLimitResult(result);
    }

    /** Takes the processes an earlier session may have left suspended. */
    public void showRecovered(List<RecoveredSuspension> entries) {
        model.showRecovered(entries);
    }

    /** Takes whether the list of what an earlier session may have left suspended is whole. */
    public void showRecoveryCheck(prism.srm.domain.RecoveryCheck check) {
        model.showRecoveryCheck(check);
    }

    /** Takes the result of a Resume or a Dismiss of such a process. */
    public void showRecoveryResult(RecoveryResult result) {
        model.showRecoveryResult(result);
    }

    /**
     * Opens the list of what an earlier session may have left suspended, with Resume… and Dismiss
     * for each and the line about the last request; modal to the window, closed by the user.
     */
    void reviewRecovered() {
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Possibly left suspended");
        javafx.scene.control.DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control(), Stylesheets.processes());
        pane.getStyleClass().add("recovered-dialog");
        pane.setContent(recoveredPane);
        pane.getButtonTypes()
                .setAll(new javafx.scene.control.ButtonType(
                        "Close", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
            if (getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                pane.getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        dialog.showAndWait();
        // The list belongs to no dialog between two reviews.
        pane.setContent(null);
    }

    /** For tests: the list the dialog shows. */
    RecoveredSuspensionsPane recoveredPane() {
        return recoveredPane;
    }

    /** Resume of a process an earlier session may have left suspended: only after the user confirmed it. */
    private void resumeRecovered(RecoveredSuspension entry) {
        if (resumeConfirmation.confirm(entry, model.actions().mechanism(ControlAction.RESUME), this)) {
            model.recovered().resume(entry);
        }
    }

    /** Set CPU limit…: asks for the percentage, then manages the process if needed and sets it. */
    void setCpuLimit() {
        ManagedActions managed = model.managed();
        if (managed.disabledProperty(ManagedActions.Choice.SET_CPU_LIMIT).get()) {
            return;
        }
        ProcessRow row = managed.selected().orElse(null);
        if (row == null) {
            return;
        }
        OptionalInt current = managed.currentLimit().map(OptionalInt::of).orElse(OptionalInt.empty());
        OptionalInt chosen = limitDialog.ask(
                row.snapshot().name(), row.identity().pid(), current, CpuLimitDialog.means(managed.capability()), this);
        if (chosen.isPresent()) {
            // The dialog lets listings arrive; a process that left meanwhile is not acted on.
            managed.setCpuLimit(row.identity(), chosen.getAsInt());
        }
    }

    /** The actions as menu items, for the context menu of the table: the order and the groups of the inspector. */
    private List<MenuItem> actionItems() {
        List<MenuItem> items = new ArrayList<>();
        items.add(actionItem("Suspend", ControlAction.SUSPEND));
        items.add(actionItem("Resume", ControlAction.RESUME));
        Menu priority = new Menu("Priority");
        priority.disableProperty().bind(model.actions().disabledProperty(ControlAction.CHANGE_PRIORITY));
        PriorityChoices choices = PriorityChoices.fill(
                priority.getItems(), model.actions(), level -> act(ControlAction.CHANGE_PRIORITY, Optional.of(level)));
        priority.setOnShowing(event -> choices.markObserved());
        items.add(priority);
        items.add(new SeparatorMenuItem());
        items.add(actionItem("End task…", ControlAction.END_TASK));
        items.add(actionItem("Force kill…", ControlAction.FORCE_KILL));
        // Managing and limiting, a group of its own: they act on the process over time, not once.
        items.add(new SeparatorMenuItem());
        items.add(managedItem(
                "Manage", ManagedActions.Choice.MANAGE, () -> model.managed().choose(ManagedActions.Choice.MANAGE)));
        items.add(managedItem("Set CPU limit…", ManagedActions.Choice.SET_CPU_LIMIT, this::setCpuLimit));
        // UI-2: OOM management, disabled with its reason, never hidden (CAP-2).
        items.add(managedItem(
                "Add to OOM management",
                ManagedActions.Choice.OOM_MANAGE,
                () -> model.managed().choose(ManagedActions.Choice.OOM_MANAGE)));
        items.add(managedItem(
                "Remove from OOM management",
                ManagedActions.Choice.OOM_UNMANAGE,
                () -> model.managed().choose(ManagedActions.Choice.OOM_UNMANAGE)));
        items.add(managedItem(
                "Unmanage",
                ManagedActions.Choice.UNMANAGE,
                () -> model.managed().choose(ManagedActions.Choice.UNMANAGE)));
        return items;
    }

    private MenuItem managedItem(String text, ManagedActions.Choice choice, Runnable act) {
        MenuItem item = new MenuItem(text);
        item.disableProperty().bind(model.managed().disabledProperty(choice));
        item.setOnAction(event -> act.run());
        return item;
    }

    private MenuItem actionItem(String text, ControlAction action) {
        MenuItem item = new MenuItem(text);
        item.disableProperty().bind(model.actions().disabledProperty(action));
        item.setOnAction(event -> act(action, Optional.empty()));
        return item;
    }

    /**
     * Takes the processes Prism holds suspended, from its own record. Once process monitoring has
     * stopped, they are offered for Resume here. Call on the JavaFX Application Thread.
     */
    public void showHeldSuspensions(List<SuspendedProcess> held) {
        model.showHeld(held);
    }

    /** Shows a listing of all processes. Call on the JavaFX Application Thread. */
    public void show(List<ProcessSnapshot> processes) {
        table.changeRows(() -> model.update(processes));
    }

    /**
     * Takes the result of a read of process details. It is shown only if it is for the process
     * that is selected now. Call on the JavaFX Application Thread.
     */
    public void showDetails(ProcessDetailsResult result) {
        model.showDetails(result);
    }

    /** Shows that monitoring could not start. Call on the JavaFX Application Thread. */
    public void showStartupFailure() {
        table.changeRows(model::startupFailed);
    }

    /**
     * Shows that process monitoring has ended and takes the processes off the page: they would
     * otherwise stand there as if they were current. Call on the JavaFX Application Thread.
     */
    public void showMonitoringStopped() {
        table.changeRows(model::monitoringStopped);
    }

    /**
     * Shows that system monitoring ended before any process was listed. Process monitoring starts
     * with a system snapshot, so no listing may ever come; one that does is shown as usual. Call
     * on the JavaFX Application Thread.
     */
    public void showProcessesNotListed() {
        table.changeRows(model::processesNotListed);
    }

    /**
     * The title line: the title at the left; at the right what there is to read and to do on the
     * page as a whole: what an earlier session may have left suspended, the number of processes,
     * the filter, and Details where the inspector is a sheet. One line, never two: in a line too
     * narrow, the filter gives way to 160 px and then folds into its search button.
     */
    private PageHeader header() {
        PageHeader line = new PageHeader("Processes");
        count.textProperty().bind(model.countProperty());
        count.getStyleClass().addAll("text-small", "text-muted", "process-count");
        count.setMinWidth(Region.USE_PREF_SIZE);
        // The filter has no label on screen: its prompt says what it is. A screen reader takes the
        // name of a text field from its label, so it has one that takes no room and is not drawn.
        Label name = new Label("Filter processes by name, PID or user");
        name.setLabelFor(filter);
        name.setManaged(false);
        name.setVisible(false);
        searchButton.setGraphic(new IconView(Icon.SEARCH, SEARCH_ICON_SIZE));
        searchButton.getStyleClass().addAll("quiet", "process-filter-unfold");
        searchButton.setAccessibleText("Filter processes");
        searchButton.setTooltip(new Tooltip("Filter    Ctrl+F"));
        searchButton.setOnAction(event -> focusFilter());
        searchButton.setVisible(false);
        searchButton.setManaged(false);
        recoveredSummary.setMinWidth(0);
        HBox toolbar = new HBox(name, recoveredSummary, count, filterField, searchButton, openDetails);
        toolbar.getStyleClass().add("process-toolbar");
        line.setTrailing(toolbar);
        line.widthProperty().addListener((observable, before, after) -> fitToolbar());
        // The line keeps the least width of its tools (Details is never cut, PR-06): the room it is given
        // is its parent's, which still narrows when the line no longer does.
        InvalidationListener room = observable -> fitToolbar();
        line.parentProperty().addListener((observable, before, after) -> {
            if (before instanceof Region parent) {
                parent.widthProperty().removeListener(room);
            }
            if (after instanceof Region parent) {
                parent.widthProperty().addListener(room);
            }
        });
        recoveredSummary.visibleProperty().addListener((observable, before, after) -> fitToolbar());
        // Folded again once the filter is left empty.
        filter.focusedProperty().addListener((observable, before, after) -> {
            if (!after && filter.getText().isEmpty()) {
                filterAskedFor = false;
                fitToolbar();
            }
        });
        return line;
    }

    /** Folds the filter into its button where the title line has no 160 px for it, and unfolds it when asked for. */
    private void fitToolbar() {
        if (header == null || header.getWidth() <= 0) {
            return;
        }
        Node title = header.lookup(".text-page-title");
        double used = (title == null ? 0 : title.prefWidth(-1)) + header.getSpacing();
        for (Node item : new Node[] {recoveredSummary, count, openDetails}) {
            if (item.isManaged() && item.isVisible()) {
                used += item.prefWidth(-1) + TOOLBAR_GAP;
            }
        }
        boolean folded = lineRoom() < used + FILTER_MIN;
        boolean showField = !folded || filterAskedFor || !filter.getText().isEmpty();
        filterFolded = folded;
        filterField.setVisible(showField);
        filterField.setManaged(showField);
        searchButton.setVisible(!showField);
        searchButton.setManaged(!showField);
        // Unfolded in a line too narrow for it, the filter takes the place of the number.
        boolean countShown = !folded || !showField;
        count.setVisible(countShown);
        count.setManaged(countShown);
    }

    /**
     * The mode follows the size of the page, which is known only in a layout pass, and the title line
     * may stand out of the page, in the shell's toolbar, which is laid out before the page: a button it
     * shows from now on is styled and put in its place at once, not left unstyled at the top of the line
     * for the shell to measure in the next pass.
     */
    private static void layOutNow(Parent line, Node shown) {
        if (shown.isVisible() && line.getScene() != null) {
            shown.applyCss();
            line.layout();
        }
    }

    /** The width the title line is given: its parent's, less the parent's padding, or its own. */
    private double lineRoom() {
        if (header.getParent() instanceof Region parent && parent.getWidth() > 0) {
            return Math.min(
                    header.getWidth(), parent.getWidth() - parent.snappedLeftInset() - parent.snappedRightInset());
        }
        return header.getWidth();
    }

    /** For tests: whether the filter is folded into its button. */
    boolean filterFolded() {
        return filterFolded && !filterField.isVisible();
    }

    /** The filter: a text field with the search icon at its left and a button that clears it. */
    private StackPane buildFilterField() {
        filter.getStyleClass().add("process-filter");
        filter.setPromptText("Filter by name, PID or user");
        // Typing filters the rows the table holds; nothing is read from the system for it.
        filter.textProperty().addListener((observable, before, after) -> table.changeRows(() -> model.filter(after)));
        filter.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && !filter.getText().isEmpty()) {
                filter.clear();
                event.consume();
            }
        });

        IconView search = new IconView(Icon.SEARCH, SEARCH_ICON_SIZE);
        StackPane.setAlignment(search, Pos.CENTER_LEFT);
        StackPane.setMargin(search, new Insets(0, 0, 0, 8));

        // Clears the filter with the pointer; Escape does it from the keyboard, so the button is
        // not a stop in the Tab order. It is there only while there is something to clear.
        Button clear = new Button();
        clear.setGraphic(new IconView(Icon.X, CLEAR_ICON_SIZE));
        clear.getStyleClass().setAll("filter-clear");
        clear.setTooltip(new Tooltip("Clear filter"));
        clear.setAccessibleText("Clear filter");
        clear.setFocusTraversable(false);
        clear.visibleProperty().bind(filter.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        clear.setOnAction(event -> {
            filter.clear();
            filter.requestFocus();
        });
        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        StackPane.setMargin(clear, new Insets(0, 5, 0, 0));

        StackPane field = new StackPane(filter, search, clear);
        field.getStyleClass().add("filter-field");
        field.setMinWidth(FILTER_MIN);
        return field;
    }
}
