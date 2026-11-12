package prism.srm.ui.limits;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.ActionResultLine;
import prism.srm.ui.control.CpuLimitDialog;
import prism.srm.ui.control.DisabledReasons;
import prism.srm.ui.control.FallbackOfferDialog;
import prism.srm.ui.control.MemoryLimitDialog;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.control.ResidentOfferDialog;
import prism.srm.ui.control.SelectionLine;
import prism.srm.ui.control.SheetOverlay;
import prism.srm.ui.format.ValueFormatter;

/**
 * The Limits page (UI-4): the managed processes with their CPU limit, their memory limit, the
 * status of the CPU limit and how it is applied (native, emulated, or unavailable); beside the table
 * (UI lab v2) a panel about the selected one with the actions on it in their three groups and, under
 * them in the panel, the line about the last action (fix round G, LO-09). Where the panel is a sheet,
 * the same line stands under the table too, with the name of the selected process, so a result is on
 * screen while the sheet is closed. Why limits are off or unavailable, and what an earlier session or
 * an exited process left, is said above the table.
 *
 * <p>Where the panel stands follows the rule of the Processes page ({@link Breakpoints#inspectorFor}):
 * beside the table, under it with its groups in columns ({@link InspectorLayout}), or in a sheet over
 * the page ({@link SheetOverlay}), opened by "Details and actions", Enter or a double click on a row,
 * and closed by Esc, its close button or a click beside it; the focus goes back to what opened it.
 *
 * <p>Layout and binding only: the snapshots are handed in on the JavaFX Application Thread, and the
 * requests go out through the function given at construction, which returns at once.
 */
public final class LimitsPage extends VBox {

    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");

    /**
     * The name column: at least this wide, and never wider than the ceiling (docs/UI_GUIDE.md); 12 px
     * under 160 so that Status stays whole beside the vertical scroll bar at the smallest window.
     */
    private static final double NAME_FLOOR = 148;

    private static final double NAME_CEILING = 520;

    /** The frame and room for a vertical scroll bar, so the last column never hides under it. */
    private static final double NAME_SLACK = 16;

    private final LimitsViewModel model;
    private final CpuLimitDialog dialog;
    private final MemoryLimitDialog memoryDialog;
    private final FallbackOfferDialog offerDialog;
    private final ResidentOfferDialog residentDialog;
    private final TableView<LimitRow> table = new TableView<>();
    private final InspectorLayout layout;
    private final LimitEditorPanel panel;
    private final SheetOverlay sheet;
    private final PageHeader header;
    private final VBox notice = new VBox();
    private final SelectionLine selectionLine;
    private final Button details = new Button("Details and actions");
    /** The page changed size: the focus the page last had is the one to keep, in a scene that is new too. */
    private final InvalidationListener pageSize = observable -> updateMode(this.lastFocus);
    /** The last control of the page that had the focus, so that it keeps it when the page changes scene. */
    private final ChangeListener<Node> focusFollower = (observable, before, after) -> followFocus(after);

    private Node lastFocus;
    private Breakpoints.Inspector mode;

    /** Whether the panel is a sheet now: the line under the table is shown only then. */
    private final javafx.beans.property.BooleanProperty inSheet = new javafx.beans.property.SimpleBooleanProperty();

    /** True while an update replaces the rows; the table's own selection changes do not count then. */
    private boolean rowsChanging;

    /**
     * @param requests manages a process or changes its limits and returns at once; the result is
     *     handed to {@link #showResult}
     */
    public LimitsPage(ValueFormatter formatter, Consumer<LimitRequest> requests) {
        this(
                formatter,
                requests,
                CpuLimitDialog::showDialog,
                MemoryLimitDialog::showDialog,
                FallbackOfferDialog::showDialog,
                ResidentOfferDialog::showDialog);
    }

    /** @param dialog asks for a CPU limit; a test answers in its place */
    LimitsPage(ValueFormatter formatter, Consumer<LimitRequest> requests, CpuLimitDialog dialog) {
        this(
                formatter,
                requests,
                dialog,
                MemoryLimitDialog::showDialog,
                FallbackOfferDialog::showDialog,
                ResidentOfferDialog::showDialog);
    }

    /** @param memoryDialog asks for a memory limit; @param offerDialog asks for consent to the emulated limit */
    LimitsPage(
            ValueFormatter formatter,
            Consumer<LimitRequest> requests,
            CpuLimitDialog dialog,
            MemoryLimitDialog memoryDialog,
            FallbackOfferDialog offerDialog,
            ResidentOfferDialog residentDialog) {
        this.model = new LimitsViewModel(formatter, requests);
        this.dialog = dialog;
        this.memoryDialog = memoryDialog;
        this.offerDialog = offerDialog;
        this.residentDialog = residentDialog;
        getStyleClass().add("limits");
        getStylesheets().addAll(Stylesheets.table(), Stylesheets.control(), Stylesheets.limits());

        header = new PageHeader("Limits");
        Label count = new Label();
        count.textProperty().bind(model.countProperty());
        count.getStyleClass().addAll("text-small", "text-muted");
        count.setMinWidth(Region.USE_PREF_SIZE);
        // Where the panel is a sheet, this opens it, so the actions are never reachable only by a click
        // on a row. Docked, the panel is always there and this is not shown.
        details.getStyleClass().add("limits-details");
        details.setMinWidth(Region.USE_PREF_SIZE);
        details.setTooltip(new Tooltip("Open the panel about the selected process (Enter in the table)"));
        details.managedProperty().bind(details.visibleProperty());
        details.setVisible(false);
        details.setOnAction(event -> openSheet(details));
        HBox trailing = new HBox(details, count);
        trailing.getStyleClass().add("limits-header-tools");
        header.setTrailing(trailing);

        // One label per notice, each in its own tone: a warning never turns a fact red, nor the reverse.
        notice.getStyleClass().add("limits-notices");
        notice.visibleProperty().bind(Bindings.isNotEmpty(model.notices()));
        notice.managedProperty().bind(notice.visibleProperty());
        showNotices(notice);
        model.notices().addListener((ListChangeListener<LimitsViewModel.Notice>) change -> showNotices(notice));

        buildTable();
        panel = editorPanel();
        layout = new InspectorLayout(table, panel, panel.frame());
        sheet = new SheetOverlay(
                "Details and actions", "The limits of the selected managed process and the actions on it", () -> table);
        VBox.setVgrow(layout, Priority.ALWAYS);

        javafx.beans.binding.StringBinding name = Bindings.createStringBinding(
                () -> model.currentProperty().get() == null
                        ? ""
                        : model.currentProperty().get().name(),
                model.currentProperty());
        javafx.beans.binding.StringBinding pid = Bindings.createStringBinding(
                () -> model.currentProperty().get() == null
                        ? ""
                        : "PID " + model.currentProperty().get().pid(),
                model.currentProperty());
        selectionLine = new SelectionLine(name, pid, model.line());
        selectionLine.getStyleClass().add("limits-selection-line");
        // Under the table where the panel is a sheet: the selected process and the last result (PR-02, LO-09).
        selectionLine.visibleProperty().bind(model.currentProperty().isNotNull().and(inSheet));
        selectionLine.managedProperty().bind(selectionLine.visibleProperty());

        // The children of the page, once (fix round H): a change of mode shows and hides, it moves nothing.
        getChildren().setAll(header, notice, layout, selectionLine, sheet);
        arrange(Breakpoints.Inspector.SIDE, null);
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

    /** Remembers the control of the page with the focus; one outside the page is forgotten. */
    private void followFocus(Node owner) {
        if (owner == null) {
            return;
        }
        lastFocus = isInside(owner, this) || isInside(owner, header) ? owner : null;
    }

    /** The panel where the room of the page has it; {@code movedFocus} as on the Processes page. */
    private void updateMode(Node movedFocus) {
        Scene scene = getScene();
        if (scene == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Node focused = movedFocus != null ? movedFocus : scene.getFocusOwner();
        Breakpoints.Inspector next = Breakpoints.inspectorFor(getWidth(), getHeight());
        arrange(next, focused);
        panel.setNarrow(next != Breakpoints.Inspector.BOTTOM || !Breakpoints.inspectorInColumns(getWidth()));
    }

    /**
     * Puts the panel beside the table, under it, or into the sheet; the same node moves. The focus, where
     * it was in the panel, follows the rule of the Processes page: into a sheet that is closed it cannot
     * go, so it goes to the table; docked it stays on the same control where that is still there and
     * enabled, else it goes to the table. The sheet, open or not, closes at once.
     */
    private void arrange(Breakpoints.Inspector next, Node focused) {
        if (next == mode) {
            return;
        }
        boolean inPanel = focused != null && (isInside(focused, panel) || sheet.contains(focused));
        sheet.dismiss();
        mode = next;
        if (next != Breakpoints.Inspector.SHEET && sheet.holds(panel)) {
            sheet.setContent(null);
        }
        layout.setMode(next);
        inSheet.set(next == Breakpoints.Inspector.SHEET);
        panel.setNarrow(next != Breakpoints.Inspector.BOTTOM || !Breakpoints.inspectorInColumns(getWidth()));
        // In a narrow page the panel goes into the sheet when the sheet opens (openSheet), not on every
        // crossing of the line (round G, the resize measurement).
        details.setVisible(next == Breakpoints.Inspector.SHEET);
        // As on the Processes page: the mode follows the size of the page, known in a layout pass, and the
        // title line may stand in the shell's toolbar, laid out before the page; its button is put in place now.
        if (details.isVisible() && header.getScene() != null) {
            details.applyCss();
            header.layout();
        }
        if (inPanel) {
            boolean stays = next != Breakpoints.Inspector.SHEET
                    && isInside(focused, panel)
                    && SheetOverlay.canTakeFocus(focused);
            (stays ? focused : table).requestFocus();
        }
    }

    /** Opens the sheet where the panel is one, a process selected or not; the focus goes back to {@code from}. */
    private void openSheet(Node from) {
        if (mode == Breakpoints.Inspector.SHEET) {
            if (!sheet.holds(panel)) {
                layout.releasePanel();
                sheet.setContent(panel);
            }
            sheet.open(from);
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // The sheet and its scrim cover the page; they take no room in it.
        sheet.resizeRelocate(0, 0, getWidth(), getHeight());
    }

    private static boolean isInside(Node node, Node container) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == container) {
                return true;
            }
        }
        return false;
    }

    /** Shows the managed processes and their limits. Call on the JavaFX Application Thread. */
    public void show(LimitsSnapshot snapshot) {
        boolean hadSelection = model.selected().isPresent();
        // New rows replace the old ones whenever a measured share changes, about once a second
        // while a limit throttles, and the table drops its selection with them: that is not the
        // user's choice, so the model keeps the process it had (milestone 9 review: the actions
        // went disabled within a second of selecting a throttled process).
        rowsChanging = true;
        try {
            model.show(snapshot);
        } finally {
            rowsChanging = false;
        }
        // The selection follows the process, not the position, through every update.
        if (hadSelection) {
            model.selected()
                    .ifPresentOrElse(
                            row -> table.getSelectionModel().select(row),
                            () -> table.getSelectionModel().clearSelection());
        }
    }

    /** Takes the result of a request about limits; shown if this page sent it. */
    public void showResult(LimitResult result) {
        model.accept(result);
    }

    private void buildTable() {
        table.getStyleClass().add("limits-table");
        table.setItems(model.rows());
        // Fixed columns, and a name column that takes the room left up to a ceiling: in a narrow
        // window the table scrolls sideways rather than cutting every value (docs/UI_GUIDE.md, Tables).
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label();
        placeholder.textProperty().bind(model.whenEmptyProperty());
        placeholder.getStyleClass().add("text-muted");
        placeholder.setWrapText(true);
        table.setPlaceholder(placeholder);
        table.setAccessibleText("Managed processes and their limits");

        TableColumn<LimitRow, String> name = column("Name", LimitRow::name, 200);
        name.setCellFactory(column -> new TextCell(row -> "", OverrunStyle.ELLIPSIS));
        TableColumn<LimitRow, String> pid = column("PID", LimitRow::pid, 64);
        pid.getStyleClass().add("numeric");
        pid.setCellFactory(column -> numericCell());
        TableColumn<LimitRow, String> cpu = column("CPU limit", LimitRow::cpuLimit, 80);
        cpu.getStyleClass().add("numeric");
        cpu.setCellFactory(column -> numericCell());
        TableColumn<LimitRow, String> memory = column("Memory limit", LimitRow::memoryLimit, 136);
        memory.getStyleClass().add("numeric");
        memory.setCellFactory(column -> {
            TextCell cell = new TextCell(LimitRow::memoryDetail, OverrunStyle.ELLIPSIS);
            cell.getStyleClass().add("numeric");
            return cell;
        });
        // Name, PID, CPU limit and Status fit the smallest window whole (504 px of table).
        TableColumn<LimitRow, String> status = column("Status", LimitRow::status, 196);
        status.setCellFactory(column -> new StatusCell());
        TableColumn<LimitRow, String> implementation = column("Implementation", LimitRow::implementation, 112);
        implementation.setCellFactory(column -> new TextCell(LimitRow::implementationDetail, OverrunStyle.ELLIPSIS));
        table.getColumns().setAll(List.of(name, pid, cpu, status, implementation, memory));
        Tooltip memoryNote = new Tooltip(LimitsViewModel.MEMORY_NOTE);
        Label memoryHeader = new Label("Memory limit");
        memoryHeader.setTooltip(memoryNote);
        memory.setText(null);
        memory.setGraphic(memoryHeader);
        table.getColumns().forEach(column -> column.setSortable(false));
        for (TableColumn<LimitRow, ?> fixed : List.of(pid, cpu, status, implementation, memory)) {
            fixed.setMinWidth(fixed.getPrefWidth());
            fixed.setMaxWidth(fixed.getPrefWidth());
            fixed.setResizable(false);
        }
        name.setMinWidth(NAME_FLOOR);
        name.setResizable(false);
        double others = pid.getPrefWidth()
                + cpu.getPrefWidth()
                + memory.getPrefWidth()
                + status.getPrefWidth()
                + implementation.getPrefWidth();
        // What the table leaves after the fixed columns, the frame and a vertical scroll bar.
        table.widthProperty()
                .addListener((observable, before, after) -> name.setPrefWidth(
                        Math.clamp(after.doubleValue() - others - NAME_SLACK, NAME_FLOOR, NAME_CEILING)));

        table.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            if (!rowsChanging) {
                model.select(after == null ? null : after.identity());
            }
        });
        // Where the panel is a sheet: a double click on a row, or Enter, opens it, as on the Processes page.
        table.setRowFactory(view -> {
            TableRow<LimitRow> row = new TableRow<>();
            row.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    openSheet(table);
                }
            });
            return row;
        });
        table.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER
                    && model.selected().isPresent()
                    && mode == Breakpoints.Inspector.SHEET) {
                openSheet(table);
                event.consume();
            }
        });
    }

    private static TableColumn<LimitRow, String> column(String title, Function<LimitRow, String> value, double width) {
        TableColumn<LimitRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setReorderable(false);
        return column;
    }

    private static TableCell<LimitRow, String> numericCell() {
        TextCell cell = new TextCell(row -> "", OverrunStyle.ELLIPSIS);
        cell.getStyleClass().add("numeric");
        return cell;
    }

    /** A cell of text, cut with an ellipsis, with a tooltip of the whole text or of a note about the row. */
    private static class TextCell extends TableCell<LimitRow, String> {
        private final Function<LimitRow, String> note;
        private final Tooltip tooltip = new Tooltip();

        TextCell(Function<LimitRow, String> note, OverrunStyle overrun) {
            this.note = note;
            setTextOverrun(overrun);
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(480);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            LimitRow row =
                    empty ? null : getTableRow() == null ? null : getTableRow().getItem();
            setText(empty || item == null ? null : item);
            pseudoClassStateChanged(MUTED, !empty && LimitsViewModel.DASH.equals(item));
            String extra = row == null ? "" : note.apply(row);
            String whole = empty || item == null ? "" : extra.isEmpty() ? item : item + "\n" + extra;
            tooltip.setText(whole);
            setTooltip(whole.isEmpty() ? null : tooltip);
        }
    }

    /** The status, in the tone of what it says, with the whole of it in the tooltip. */
    private static final class StatusCell extends TextCell {
        StatusCell() {
            super(LimitRow::detail, OverrunStyle.ELLIPSIS);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            LimitRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
            LimitRow.Tone tone = row == null ? LimitRow.Tone.NORMAL : row.tone();
            pseudoClassStateChanged(WARNING, tone == LimitRow.Tone.WARNING);
            pseudoClassStateChanged(ERROR, tone == LimitRow.Tone.ERROR);
            pseudoClassStateChanged(MUTED, tone == LimitRow.Tone.MUTED);
        }
    }

    /**
     * The panel about the selected process, with the actions on it in three groups: the CPU limit,
     * the memory limit, the process (docs/UI_GUIDE.md, Limits page). Every action is always there; a
     * disabled one says why in its tooltip and in the line under the buttons of its group (CAP-2, LO-08).
     */
    private LimitEditorPanel editorPanel() {
        // Fix round H (LO-06): each with the short words it shows in the compact panel of a low window.
        Button edit = LimitEditorPanel.shortened(action("Set CPU limit…", LimitsViewModel.Action.EDIT), "Set…");
        edit.setOnAction(event -> edit(edit));
        Button remove = LimitEditorPanel.shortened(action("Remove CPU limit", LimitsViewModel.Action.REMOVE), "Remove");
        remove.setOnAction(event -> model.request(LimitsViewModel.Action.REMOVE));
        Button emulated = LimitEditorPanel.shortened(
                action("Use emulated limit…", LimitsViewModel.Action.USE_EMULATED), "Emulated…");
        emulated.setOnAction(event -> useEmulated(emulated));
        Button memory =
                LimitEditorPanel.shortened(action("Set memory limit…", LimitsViewModel.Action.EDIT_MEMORY), "Set…");
        memory.setOnAction(event -> editMemory(memory));
        Button removeMemory = LimitEditorPanel.shortened(
                action("Remove memory limit", LimitsViewModel.Action.REMOVE_MEMORY), "Remove");
        removeMemory.setOnAction(event -> model.request(LimitsViewModel.Action.REMOVE_MEMORY));
        Button watch = LimitEditorPanel.shortened(
                action("Watch resident memory…", LimitsViewModel.Action.USE_WATCHED), "Watch…");
        watch.setOnAction(event -> useWatched(watch));
        // CAP-2: on a system with a native memory cap, where it may fail, the button is always there,
        // disabled with its reason until a process is offered the watched limit; elsewhere never shown.
        watch.visibleProperty().bind(model.watchOfferedProperty());
        watch.managedProperty().bind(watch.visibleProperty());
        Button toggle = action("Disable", LimitsViewModel.Action.TOGGLE);
        toggle.textProperty().bind(model.toggleTextProperty());
        toggle.setOnAction(event -> model.request(LimitsViewModel.Action.TOGGLE));
        Button unmanage = action("Unmanage", LimitsViewModel.Action.UNMANAGE);
        unmanage.setOnAction(event -> model.request(LimitsViewModel.Action.UNMANAGE));
        ActionResultLine line = new ActionResultLine(model.line());
        line.getStyleClass().add("limits-action-message");
        return new LimitEditorPanel(
                model,
                List.of(
                        group("CPU limit", edit, remove, emulated),
                        group("Memory limit", memory, removeMemory, watch),
                        group("Process", toggle, unmanage)),
                line);
    }

    /** A group of actions with the line of its own reasons under its buttons (LO-08). */
    private LimitEditorPanel.Group group(String name, Button... buttons) {
        DisabledReasons reasons = new DisabledReasons();
        reasons.getStyleClass().add("limit-editor-reasons");
        return new LimitEditorPanel.Group(name, held(reasons, buttons), reasons);
    }

    /** Each button in a holder that carries its tooltip, enabled or not; its reason in the line (CAP-2). */
    private List<Node> held(DisabledReasons reasons, Button... buttons) {
        List<Node> holders = new ArrayList<>();
        for (Button button : buttons) {
            LimitsViewModel.Action action = (LimitsViewModel.Action) button.getUserData();
            holders.add(reasons.hold(button, model.explanationProperty(action)));
        }
        return holders;
    }

    private Button action(String text, LimitsViewModel.Action action) {
        Button button = new Button(text);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setUserData(action);
        button.disableProperty().bind(model.disabledProperty(action));
        return button;
    }

    /**
     * After a dialog the focus goes back to the button that opened it; where that is disabled now, to the
     * close button of the open sheet, or to the table.
     */
    private void refocus(Node opener) {
        if (SheetOverlay.canTakeFocus(opener)) {
            opener.requestFocus();
        } else if (sheet.isOpen()) {
            sheet.focusClose();
        } else {
            table.requestFocus();
        }
    }

    private void edit(Node opener) {
        LimitRow row = model.selected().orElse(null);
        if (row == null || model.disabledProperty(LimitsViewModel.Action.EDIT).get()) {
            return;
        }
        OptionalInt chosen = dialog.ask(
                row.name(), row.identity().pid(), model.currentLimit(), CpuLimitDialog.means(model.capability()), this);
        if (chosen.isPresent()) {
            model.setCpuLimit(row.identity(), chosen.getAsInt());
        }
        refocus(opener);
    }

    private void showNotices(VBox box) {
        List<Label> labels = new ArrayList<>();
        for (LimitsViewModel.Notice each : model.notices()) {
            Label label = new Label(each.text());
            label.getStyleClass().add("limits-notice");
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.pseudoClassStateChanged(ERROR, each.tone() == LimitRow.Tone.ERROR);
            label.pseudoClassStateChanged(WARNING, each.tone() == LimitRow.Tone.WARNING);
            labels.add(label);
        }
        box.getChildren().setAll(labels);
    }

    private void editMemory(Node opener) {
        LimitRow row = model.selected().orElse(null);
        if (row == null
                || model.disabledProperty(LimitsViewModel.Action.EDIT_MEMORY).get()) {
            return;
        }
        OptionalLong chosen = memoryDialog.ask(
                row.name(), row.identity().pid(), model.currentMemoryLimit(), "", model.memoryWatched(), this);
        if (chosen.isPresent()) {
            model.setMemoryLimit(row.identity(), chosen.getAsLong());
        }
        refocus(opener);
    }

    /** The explicit consent to the emulated CPU limit (MILESTONES section 10): asked, never assumed. */
    private void useEmulated(Node opener) {
        LimitRow row = model.selected().orElse(null);
        if (row == null
                || model.disabledProperty(LimitsViewModel.Action.USE_EMULATED).get()) {
            return;
        }
        model.offer().ifPresent(offer -> {
            if (offerDialog.accept(row.name(), row.identity().pid(), offer, this)) {
                model.acceptEmulated();
            }
        });
        refocus(opener);
    }

    /** The explicit consent to the watched resident memory (plan O9): asked, never assumed. */
    private void useWatched(Node opener) {
        LimitRow row = model.selected().orElse(null);
        if (row == null
                || model.disabledProperty(LimitsViewModel.Action.USE_WATCHED).get()) {
            return;
        }
        model.residentOffer().ifPresent(reason -> {
            if (residentDialog.accept(row.name(), row.identity().pid(), reason, this)) {
                model.acceptWatched();
            }
        });
        refocus(opener);
    }

    /** For tests: the table of the page. */
    TableView<LimitRow> table() {
        return table;
    }

    /** For tests: where the panel is now. */
    Breakpoints.Inspector mode() {
        return mode;
    }

    /** For tests: the sheet the panel is shown in where it is not docked. */
    SheetOverlay sheet() {
        return sheet;
    }

    /** For tests: the model of the page. */
    LimitsViewModel model() {
        return model;
    }

    /** For tests: selects the row of a process, as the user would. */
    void select(ProcessIdentity identity) {
        model.rows().stream()
                .filter(row -> row.identity().equals(identity))
                .findFirst()
                .ifPresent(row -> table.getSelectionModel().select(row));
    }
}
