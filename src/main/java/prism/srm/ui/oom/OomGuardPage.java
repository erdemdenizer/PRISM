package prism.srm.ui.oom;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomThresholds;
import prism.srm.domain.OomView;
import prism.srm.domain.ProcessIdentity;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.TableFocus;
import prism.srm.ui.control.ActionResultLine;
import prism.srm.ui.control.CutTextTooltip;
import prism.srm.ui.control.DisabledReasons;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.control.TextWidths;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.metric.FactView;
import prism.srm.ui.metric.FactsPanel;
import prism.srm.ui.oom.OomGuardViewModel.ActionRow;
import prism.srm.ui.oom.OomGuardViewModel.CandidateRow;
import prism.srm.ui.oom.OomGuardViewModel.Switch;
import prism.srm.ui.oom.OomGuardViewModel.Tone;

/**
 * The OOM Guard page (UI-5): the state with the memory available and since when; monitoring and its
 * thresholds; apart from them, under a heading of their own, the three switches of automatic
 * intervention with the mechanisms of this system; the managed processes with their emergency action
 * and what OOM Guard holds on them, and the actions on the selected one; the latest automatic
 * actions.
 *
 * <p>Layout and binding only: the views of OOM Guard come in on the JavaFX Application Thread; the
 * settings and the requests go out through the functions given at construction, which return at once.
 */
public final class OomGuardPage extends VBox {

    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    /** A value of the state band that is no figure: a dash, or a word that stands in for one. */
    private static final PseudoClass NO_FIGURE = PseudoClass.getPseudoClass("no-figure");

    /** The spacing and the padding of the page when it is not compact, as oom.css sets them. */
    private static final double USUAL_SPACE = 12;

    /** The managed table never shows fewer rows than this below its header (plan A11, the fold). */
    private static final int MANAGED_ROWS = 2;

    /** The height of a table header and of a row, as table.css sets them. */
    private static final double HEADER_HEIGHT = 32;

    private static final double ROW_HEIGHT = 30;

    /** The frame of a table, 1 px above and below (table.css). */
    private static final double FRAME = 2;

    /** The width of a scroll bar, as app.css sets it. */
    private static final double SCROLL_BAR = 12;

    /** As on the Limits page: a floor for the name, a ceiling beyond which the room stays empty. */
    private static final double NAME_FLOOR = 148;

    private static final double NAME_CEILING = 520;
    private static final double NAME_SLACK = 16;

    /** The widest the Process column of the recent actions grows in a wide window. */
    private static final double PROCESS_CEILING = 360;

    /**
     * What snapping to device pixels can add to the padding of a cell or a header, at either side, at
     * any display scale from 100 %: at most one device pixel a side, which is never more than 1 px.
     */
    private static final double SNAPPED_PADDING = 2;

    /** The table of recent actions shows at most this many rows before it scrolls. */
    private static final int ACTION_ROWS = 6;

    private final OomGuardViewModel model;
    private final OomManagementDialog managementDialog;
    private final ThresholdsDialog thresholdsDialog;
    private final TerminationConsent consent;
    private final TableView<CandidateRow> table = new TableView<>();
    private final TableView<ActionRow> recent = new TableView<>();
    private final Map<Switch, CheckBox> switches = new EnumMap<>(Switch.class);
    private final CheckBox monitoring = new CheckBox("Monitor memory pressure");
    private final FactView stateFact;
    private boolean rowsChanging;
    private TableColumn<CandidateRow, String> nameColumn;
    private TableColumn<CandidateRow, String> statusColumn;
    private double statusCeiling;
    private double fixedWidths;
    private List<String> statusesMeasured;

    /**
     * @param initial the settings read at start-up, shown until the first view of OOM Guard comes
     * @param settings publishes changed settings and returns at once
     * @param requests sets the OOM settings of a process and returns at once; the result is handed to
     *     {@link #showResult}
     * @param release ends what OOM Guard holds on a process and returns at once
     */
    public OomGuardPage(
            ValueFormatter formatter,
            ZoneId zone,
            OomSettings initial,
            Consumer<OomSettings> settings,
            Consumer<LimitRequest> requests,
            Consumer<ProcessIdentity> release) {
        this(
                formatter,
                zone,
                initial,
                settings,
                requests,
                release,
                OomManagementDialog::showDialog,
                ThresholdsDialog::showDialog,
                TerminationConsent::showDialog);
    }

    OomGuardPage(
            ValueFormatter formatter,
            ZoneId zone,
            OomSettings initial,
            Consumer<OomSettings> settings,
            Consumer<LimitRequest> requests,
            Consumer<ProcessIdentity> release,
            OomManagementDialog managementDialog,
            ThresholdsDialog thresholdsDialog,
            TerminationConsent consent) {
        this.model = new OomGuardViewModel(formatter, zone, initial, settings, requests, release);
        this.managementDialog = managementDialog;
        this.thresholdsDialog = thresholdsDialog;
        this.consent = consent;
        getStyleClass().add("oom");
        getStylesheets().addAll(Stylesheets.metric(), Stylesheets.table(), Stylesheets.control(), Stylesheets.oom());

        PageHeader header = new PageHeader("OOM Guard");
        Label count = new Label();
        count.textProperty().bind(model.countProperty());
        count.getStyleClass().addAll("text-small", "text-muted");
        count.setMinWidth(Region.USE_PREF_SIZE);
        header.setTrailing(count);

        stateFact = new FactView("State", model.state(), null);
        stateFact.setMinWidth(Region.USE_PREF_SIZE);
        model.stateToneProperty().addListener((observable, before, after) -> tone(stateFact, after));
        FactView available = new FactView("Available", model.available(), null);
        available.setMinWidth(Region.USE_PREF_SIZE);
        FactView since = new FactView("Since", model.since(), null);
        since.setMinWidth(Region.USE_PREF_SIZE);
        FactsPanel facts = new FactsPanel(stateFact, available, since);
        // UI lab v2: the state band. The state word as calm as its state, the memory available as the
        // figure the page is about, and next to the facts whether OOM Guard may act on its own at all:
        // beside them, not at the far end of a wide window (UI audit LO-24). The values stand on one
        // baseline; a value that is no figure ("–", "Reading…") is set in the size of words (LO-13).
        facts.getStyleClass().add("oom-state");
        stateFact.getStyleClass().add("oom-state-word");
        available.getStyleClass().add("oom-available");
        for (FactView fact : List.of(available, since)) {
            if (fact.lookup(".fact-value") instanceof Label figure) {
                figure.textProperty()
                        .addListener((observable, before, after) ->
                                figure.pseudoClassStateChanged(NO_FIGURE, noFigure(after)));
                figure.pseudoClassStateChanged(NO_FIGURE, noFigure(figure.getText()));
            }
        }
        facts.getChildren().add(safeDefault());

        getChildren().setAll(header, facts, monitoringRow(), interventions(), managed(), recent());
        heightProperty().addListener((observable, before, after) -> updateCompact());
        widthProperty().addListener((observable, before, after) -> updateCompact());
    }

    /** Shows a view of OOM Guard. Call on the JavaFX Application Thread. */
    public void show(OomView view) {
        boolean hadSelection = model.selected().isPresent();
        rowsChanging = true;
        try {
            model.show(view);
        } finally {
            rowsChanging = false;
        }
        if (hadSelection) {
            model.selected()
                    .ifPresentOrElse(
                            row -> table.getSelectionModel().select(row),
                            () -> table.getSelectionModel().clearSelection());
        }
    }

    /** How each action is provided on this system, to name its mechanism. Call on the JavaFX Application Thread. */
    public void showControlProfile(ControlProfile profile) {
        model.showControlProfile(profile);
    }

    /** Takes the result of a request about limits; shown if this page sent it. */
    public void showResult(LimitResult result) {
        model.accept(result);
    }

    /**
     * "Automatic intervention is off" while none of the three switches is on: the safe default, said
     * in words on a neutral chip. Gone as soon as one of them is on; the switches below say which.
     */
    private VBox safeDefault() {
        Label chip = new Label("Automatic intervention is off");
        chip.getStyleClass().addAll("chip", "oom-safe-default");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        chip.setTooltip(new Tooltip("The three switches of automatic intervention below are off."));
        VBox box = new VBox(chip);
        box.getStyleClass().add("oom-safe-default-box");
        box.visibleProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> !model.switchProperty(Switch.PRIORITY).get()
                                && !model.switchProperty(Switch.SUSPEND).get()
                                && !model.switchProperty(Switch.TERMINATE).get(),
                        model.switchProperty(Switch.PRIORITY),
                        model.switchProperty(Switch.SUSPEND),
                        model.switchProperty(Switch.TERMINATE)));
        box.managedProperty().bind(box.visibleProperty());
        return box;
    }

    // ----- Monitoring -----

    private HBox monitoringRow() {
        Label title = new Label("Monitoring");
        title.getStyleClass().addAll("text-section-title", "oom-group-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        monitoring.setSelected(model.monitoringProperty().get());
        model.monitoringProperty().addListener((observable, before, after) -> monitoring.setSelected(after));
        monitoring.setOnAction(event -> model.setMonitoring(monitoring.isSelected()));
        monitoring.setMinWidth(Region.USE_PREF_SIZE);
        Label thresholds = new Label();
        thresholds.textProperty().bind(model.thresholdsProperty());
        thresholds.getStyleClass().addAll("text-muted", "oom-thresholds");
        thresholds.setMinWidth(0);
        // A narrow window wraps the thresholds onto a second line (oom.css caps it at two) rather
        // than cutting what they are; only beyond two lines is the rest cut, and the tooltip has it.
        thresholds.setWrapText(true);
        thresholds.setMinHeight(Region.USE_PREF_SIZE);
        thresholds.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip whole = new Tooltip();
        whole.textProperty().bind(model.thresholdsProperty());
        thresholds.setTooltip(whole);
        Button change = new Button("Change…");
        change.setMinWidth(Region.USE_PREF_SIZE);
        change.setAccessibleText("Change the thresholds");
        change.setOnAction(event -> changeThresholds());
        HBox row = new HBox(title, monitoring, thresholds, change);
        row.getStyleClass().add("oom-monitoring");
        HBox.setHgrow(thresholds, Priority.SOMETIMES);
        return row;
    }

    private void changeThresholds() {
        OomThresholds current = model.settings().thresholds();
        thresholdsDialog.ask(current, model.totalBytes(), this).ifPresent(model::setThresholds);
    }

    // ----- Automatic intervention -----

    private VBox interventions() {
        Label title = new Label("Automatic intervention");
        title.getStyleClass().addAll("text-section-title", "oom-group-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        Label note = new Label("Off by default. Acts only on managed processes with OOM management on.");
        note.getStyleClass().addAll("text-small", "text-muted");
        note.setMinWidth(0);
        note.setTextOverrun(OverrunStyle.ELLIPSIS);
        CutTextTooltip.install(note);
        HBox heading = new HBox(title, note);
        heading.getStyleClass().add("oom-group-heading");

        FlowPane boxes = new FlowPane(
                checkBox(Switch.PRIORITY, "Lower priority at Critical"),
                checkBox(Switch.SUSPEND, "Suspend at Emergency"),
                checkBox(Switch.TERMINATE, "Terminate at Emergency (force kill)"));
        boxes.getStyleClass().add("oom-switches");

        Label mechanism = new Label();
        mechanism.textProperty().bind(model.mechanismProperty());
        mechanism.getStyleClass().addAll("text-small", "text-muted", "oom-mechanism");
        mechanism.setWrapText(true);
        mechanism.setMinHeight(Region.USE_PREF_SIZE);

        VBox group = new VBox(heading, boxes, mechanism);
        group.getStyleClass().add("oom-interventions");
        return group;
    }

    private CheckBox checkBox(Switch which, String text) {
        CheckBox box = new CheckBox(text);
        box.setMinWidth(Region.USE_PREF_SIZE);
        box.setSelected(model.switchProperty(which).get());
        model.switchProperty(which).addListener((observable, before, after) -> box.setSelected(after));
        box.disableProperty().bind(model.switchDisabledProperty(which));
        Tooltip help = new Tooltip();
        help.textProperty().bind(model.switchHelpProperty(which));
        help.setWrapText(true);
        help.setMaxWidth(400);
        box.setTooltip(help);
        box.accessibleHelpProperty().bind(model.switchHelpProperty(which));
        box.setOnAction(event -> {
            boolean on = box.isSelected();
            // OOM-9: the global switch of termination is turned on only after its own confirmation.
            if (which == Switch.TERMINATE && on && !consent.confirm(this)) {
                box.setSelected(false);
                return;
            }
            model.setSwitch(which, on);
        });
        switches.put(which, box);
        return box;
    }

    /**
     * Compact while the page stands at no more than the height it needs with the usual spacing: the
     * area gives it no more room than that, and may give it less (the Processes page does the same).
     */
    private void updateCompact() {
        pseudoClassStateChanged(COMPACT, getHeight() <= heightWithUsualSpacing());
    }

    /** The least height of the page with the usual spacing and padding, whichever it has now. */
    private double heightWithUsualSpacing() {
        int gaps = Math.max(0, getManagedChildren().size() - 1);
        return minHeight(getWidth())
                + gaps * (USUAL_SPACE - getSpacing())
                + (USUAL_SPACE - getPadding().getTop())
                + (USUAL_SPACE - getPadding().getBottom());
    }

    // ----- Managed processes -----

    private VBox managed() {
        VBox notices = new VBox();
        notices.getStyleClass().add("oom-notices");
        notices.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(model.notices()));
        notices.managedProperty().bind(notices.visibleProperty());
        showNotices(notices);
        model.notices().addListener((ListChangeListener<OomGuardViewModel.Notice>) change -> showNotices(notices));

        buildTable();
        RowsHolder holder = new RowsHolder(table, this::columnsWidth);
        model.rows().addListener((ListChangeListener<CandidateRow>) change -> holder.requestLayout());
        VBox.setVgrow(holder, Priority.ALWAYS);

        // CAP-2: why a button is disabled is said on screen, not only in a tooltip it cannot show.
        DisabledReasons reasons = new DisabledReasons();
        reasons.getStyleClass().add("oom-action-reasons");
        Button management = new Button("OOM management…");
        management.setMinWidth(Region.USE_PREF_SIZE);
        Node managementHolder = bind(reasons, management, OomGuardViewModel.Action.MANAGEMENT);
        management.setOnAction(event -> editManagement());
        Button release = new Button("Release OOM Guard hold");
        release.setMinWidth(Region.USE_PREF_SIZE);
        Node releaseHolder = bind(reasons, release, OomGuardViewModel.Action.RELEASE);
        release.setOnAction(event -> {
            model.releaseHold();
            table.requestFocus();
        });
        ActionResultLine line = new ActionResultLine(model.line());
        line.visibleProperty().bind(model.line().wholeProperty().isNotEmpty());
        HBox.setHgrow(line, Priority.ALWAYS);
        FlowPane buttons = new FlowPane(16, 8, managementHolder, releaseHolder);
        buttons.getStyleClass().add("oom-actions");
        VBox actions = new VBox(buttons, reasons, line);
        actions.getStyleClass().add("oom-action-area");
        // Its line is kept while empty (a result moves no button); the room a tall window gives goes to the table.
        actions.setMaxHeight(Region.USE_PREF_SIZE);

        VBox section = new VBox(notices, holder, actions);
        section.getStyleClass().add("oom-managed");
        VBox.setVgrow(section, Priority.ALWAYS);
        // Never higher than its rows (UI audit LO-12): the room a tall window has to spare stays under
        // the page, not between the table and its actions.
        section.setMaxHeight(Region.USE_PREF_SIZE);
        return section;
    }

    /** The button disabled as the model says, in a holder that carries its tooltip; its reason in the line. */
    private Node bind(DisabledReasons reasons, Button button, OomGuardViewModel.Action action) {
        button.disableProperty().bind(model.disabledProperty(action));
        return reasons.hold(button, model.explanationProperty(action));
    }

    private void showNotices(VBox box) {
        List<Label> labels = new ArrayList<>();
        for (OomGuardViewModel.Notice each : model.notices()) {
            Label label = new Label(each.text());
            label.getStyleClass().add("oom-notice");
            label.setMinWidth(0);
            // A notice (a fall back to Notify only, a journal that cannot be written) wraps onto a
            // second line in a narrow window (oom.css caps it at two) rather than losing its end.
            label.setWrapText(true);
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setTextOverrun(OverrunStyle.ELLIPSIS);
            CutTextTooltip.install(label);
            tone(label, each.tone());
            labels.add(label);
        }
        box.getChildren().setAll(labels);
    }

    private void buildTable() {
        table.getStyleClass().add("oom-table");
        // Its height is given by its holder, in whole rows (RowsHolder); in a narrow window Priority
        // and Memory scroll sideways. A low window sets denser rows for this table (shell.css).
        table.setItems(model.rows());
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        Label placeholder = new Label();
        placeholder.textProperty().bind(model.whenEmptyProperty());
        placeholder.getStyleClass().addAll("text-muted", "oom-placeholder");
        placeholder.setWrapText(true);
        table.setPlaceholder(placeholder);
        // An empty table is a Tab stop too: it draws its ring while no row has the focus (KB-2).
        TableFocus.track(table);
        table.setAccessibleText("Managed processes and their emergency actions");

        TableColumn<CandidateRow, String> name = column("Name", CandidateRow::name, 200);
        name.setCellFactory(column -> new ToneCell<>(row -> Tone.NORMAL));
        TableColumn<CandidateRow, String> pid = column("PID", CandidateRow::pid, 64);
        pid.getStyleClass().add("numeric");
        pid.setCellFactory(column -> numeric());
        TableColumn<CandidateRow, String> action = column(
                "Emergency action",
                CandidateRow::action,
                measured("Emergency action", OomGuardViewModel.actionTexts()));
        action.setCellFactory(column -> new ToneCell<>(row -> row.actionMuted() ? Tone.MUTED : Tone.NORMAL));
        TableColumn<CandidateRow, String> status =
                column("Status", CandidateRow::status, measured("Status", OomGuardViewModel.statusTexts()));
        status.setCellFactory(column -> new ToneCell<>(CandidateRow::statusTone));
        statusColumn = status;
        TableColumn<CandidateRow, String> priority =
                column("Priority", CandidateRow::priority, measured("Priority", OomGuardViewModel.priorityTexts()));
        priority.setCellFactory(column -> new ToneCell<>(row -> Tone.NORMAL));
        TableColumn<CandidateRow, String> memory = column("Memory", CandidateRow::memory, 112);
        memory.getStyleClass().add("numeric");
        memory.setCellFactory(column -> numeric());
        Label memoryHeader = new Label("Memory");
        memoryHeader.setTooltip(new Tooltip("Resident memory, as listed"));
        memory.setText(null);
        memory.setGraphic(memoryHeader);
        table.getColumns().setAll(List.of(name, pid, action, status, priority, memory));
        table.getColumns().forEach(column -> column.setSortable(false));
        for (TableColumn<CandidateRow, ?> fixed : List.of(pid, action, status, priority, memory)) {
            fixed.setMinWidth(fixed.getPrefWidth());
            fixed.setMaxWidth(fixed.getPrefWidth());
            fixed.setResizable(false);
        }
        name.setMinWidth(NAME_FLOOR);
        name.setResizable(false);
        nameColumn = name;
        statusCeiling = status.getPrefWidth();
        fixedWidths = pid.getPrefWidth() + action.getPrefWidth() + priority.getPrefWidth() + memory.getPrefWidth();
        table.widthProperty().addListener((observable, before, after) -> fitColumns());
        model.rows().addListener((ListChangeListener<CandidateRow>) change -> fitColumns());
        fitColumns();
        table.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            if (!rowsChanging) {
                model.select(after == null ? null : after.identity());
            }
        });
    }

    /**
     * The Status column as wide as the statuses the rows hold now, up to the widest fixed status (a
     * failure beyond it is cut and has its tooltip); the name takes the rest, between its floor and
     * its ceiling. While every status is "–" the name is not cut for an empty column (UI audit LO-26).
     */
    private void fitColumns() {
        // The rows are replaced as their memory changes, about once a second; the statuses seldom do.
        List<String> statuses = model.rows().stream().map(CandidateRow::status).toList();
        if (!statuses.equals(statusesMeasured)) {
            statusesMeasured = statuses;
            double status = Math.min(statusCeiling, measured("Status", statuses));
            statusColumn.setMinWidth(status);
            statusColumn.setMaxWidth(status);
            statusColumn.setPrefWidth(status);
        }
        nameColumn.setPrefWidth(nameWidth(table.getWidth()));
    }

    private double nameWidth(double tableWidth) {
        return Math.clamp(
                tableWidth - fixedWidths - statusColumn.getPrefWidth() - NAME_SLACK, NAME_FLOOR, NAME_CEILING);
    }

    /** The width the columns of the managed table take in a table of this width. */
    private double columnsWidth(double tableWidth) {
        return fixedWidths + statusColumn.getPrefWidth() + nameWidth(tableWidth);
    }

    private static boolean noFigure(String text) {
        return text == null || text.chars().noneMatch(Character::isDigit);
    }

    private void editManagement() {
        CandidateRow row = model.selected().orElse(null);
        if (row == null
                || model.disabledProperty(OomGuardViewModel.Action.MANAGEMENT).get()) {
            return;
        }
        OomManagement current = model.currentOom().orElse(OomManagement.OFF);
        managementDialog
                .ask(row.name(), row.identity().pid(), current, model.actionNotes(), this)
                .filter(Predicate.not(current::equals))
                .ifPresent(chosen -> model.setOom(row.identity(), chosen));
        table.requestFocus();
    }

    // ----- Recent automatic actions -----

    private VBox recent() {
        Label title = new Label("Recent automatic actions");
        title.getStyleClass().add("text-section-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        Label bound = new Label("last " + OomView.MOST_ACTIONS);
        bound.getStyleClass().addAll("text-small", "text-muted");
        HBox heading = new HBox(title, bound);
        heading.getStyleClass().add("oom-group-heading");

        recent.getStyleClass().add("oom-recent");
        recent.setItems(model.actions());
        recent.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        Label empty = new Label(OomGuardViewModel.NO_ACTIONS);
        empty.getStyleClass().add("text-muted");
        recent.setPlaceholder(empty);
        TableFocus.track(recent);
        recent.setAccessibleText("Recent automatic actions of OOM Guard");
        // Measured from the texts they hold, so that "14:02:11" is never cut (UI audit LO-10).
        TableColumn<ActionRow, String> time =
                recentColumn("Time", ActionRow::time, measured("Time", List.of(OomGuardViewModel.TIME_SAMPLE)));
        TableColumn<ActionRow, String> state =
                recentColumn("State", ActionRow::state, measured("State", OomGuardViewModel.recordStateTexts()));
        TableColumn<ActionRow, String> process = recentColumn("Process", ActionRow::process, 180);
        TableColumn<ActionRow, String> action =
                recentColumn("Action", ActionRow::action, measured("Action", OomGuardViewModel.recordActionTexts()));
        TableColumn<ActionRow, String> result = recentColumn("Result", ActionRow::result, 240);
        result.setCellFactory(column -> new ToneCell<>(row -> row.failed() ? Tone.ERROR : Tone.NORMAL));
        recent.getColumns().setAll(List.of(time, state, process, action, result));
        // Only the process and the result take the room a wide window gives; the process up to a
        // ceiling (as the name of the managed table has one), so that in a wide window the result
        // is not pushed far from it (UI audit LO-24). A process cut there has its tooltip.
        for (TableColumn<ActionRow, String> fixed : List.of(time, state, action)) {
            fixed.setMinWidth(fixed.getPrefWidth());
            fixed.setMaxWidth(fixed.getPrefWidth());
        }
        process.setMaxWidth(PROCESS_CEILING);
        recent.getColumns().forEach(column -> column.setSortable(false));
        // Never higher than its rows and never lower (UI audit LO-11): the header, up to six rows and
        // the frame of 1 px above and below; the room left goes to the table of managed processes.
        recent.prefHeightProperty()
                .bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> HEADER_HEIGHT
                                + rowHeight(recent)
                                        * Math.max(
                                                1,
                                                Math.min(
                                                        ACTION_ROWS,
                                                        model.actions().size()))
                                + FRAME,
                        model.actions(),
                        recent.fixedCellSizeProperty()));
        recent.setMinHeight(Region.USE_PREF_SIZE);
        recent.setMaxHeight(Region.USE_PREF_SIZE);
        VBox section = new VBox(heading, recent);
        section.getStyleClass().add("oom-recent-section");
        return section;
    }

    /** The height of a row of a table: its fixed cell size, as table.css and shell.css set it. */
    private static double rowHeight(TableView<?> table) {
        return table.getFixedCellSize() > 0 ? table.getFixedCellSize() : ROW_HEIGHT;
    }

    /**
     * Holds the managed table at a height of whole rows (UI audit LO-12): the header, as many whole rows
     * as the room allows (at least {@link #MANAGED_ROWS}, at most all of them), the frame, and the room
     * of a sideways scroll bar only while the columns need one. What is left of the room, less than a
     * row, stays empty under the table, so no row is ever shown in part.
     */
    private static final class RowsHolder extends Region {
        private final TableView<CandidateRow> table;
        private final DoubleUnaryOperator columnsWidth;

        RowsHolder(TableView<CandidateRow> table, DoubleUnaryOperator columnsWidth) {
            this.table = table;
            this.columnsWidth = columnsWidth;
            getChildren().add(table);
        }

        @Override
        protected double computeMinHeight(double width) {
            return height(width, MANAGED_ROWS);
        }

        @Override
        protected double computePrefHeight(double width) {
            return height(width, Math.max(MANAGED_ROWS, table.getItems().size()));
        }

        @Override
        protected double computeMaxHeight(double width) {
            return computePrefHeight(width);
        }

        @Override
        protected double computeMinWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefWidth(double height) {
            return table.prefWidth(-1);
        }

        @Override
        protected void layoutChildren() {
            double width = getWidth();
            int rows = table.getItems().size();
            int shown = Math.max(MANAGED_ROWS, Math.min(rows, fit(getHeight(), false)));
            boolean sideways = sideways(width, rows > shown);
            if (sideways) {
                shown = Math.max(MANAGED_ROWS, Math.min(rows, fit(getHeight(), true)));
                sideways = sideways(width, rows > shown);
            }
            table.resizeRelocate(0, 0, width, height(shown, sideways));
        }

        /** How many whole rows a height holds below the header, with or without a sideways bar. */
        private int fit(double height, boolean sideways) {
            return (int) Math.floor((height - HEADER_HEIGHT - FRAME - (sideways ? SCROLL_BAR : 0)) / rowHeight(table));
        }

        private double height(int rows, boolean sideways) {
            return HEADER_HEIGHT + rowHeight(table) * rows + FRAME + (sideways ? SCROLL_BAR : 0);
        }

        /** The height with this many rows in view at a width, with a sideways bar if one is needed. */
        private double height(double width, int rows) {
            double known = width > 0 ? width : getWidth();
            return height(rows, sideways(known, table.getItems().size() > rows));
        }

        /** Whether the columns are wider than the table shows them, beside a vertical bar or not. */
        private boolean sideways(double width, boolean vertical) {
            if (width <= 0) {
                return false;
            }
            double room = width - FRAME - (vertical ? SCROLL_BAR : 0);
            return columnsWidth.applyAsDouble(width) > room + 0.5;
        }
    }

    /**
     * The width of a column of fixed texts: the widest of its texts and its header, as the header and
     * the cells draw them, with the padding of a cell on each side and the room that padding takes at
     * a display scale. At 125 % JavaFX snaps the 10 px padding of a header to device pixels, 10.4 px a
     * side, and "Status", measured to the hundredth of a pixel, was cut to "Stat…" in a column of its
     * own measure (UI audit LO-26, fix round H).
     */
    private static double measured(String title, List<String> texts) {
        double widest = TextWidths.width(TextWidths.tableHeader(), title);
        for (String text : texts) {
            widest = Math.max(widest, TextWidths.width(TextWidths.tableBody(), text));
        }
        return TextWidths.cell(widest + SNAPPED_PADDING);
    }

    private static <S> TableColumn<S, String> column(String title, Function<S, String> value, double width) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setReorderable(false);
        return column;
    }

    private static TableColumn<ActionRow, String> recentColumn(
            String title, Function<ActionRow, String> value, double width) {
        TableColumn<ActionRow, String> column = column(title, value, width);
        column.setCellFactory(cell -> new ToneCell<>(row -> Tone.NORMAL));
        return column;
    }

    private static ToneCell<CandidateRow> numeric() {
        ToneCell<CandidateRow> cell = new ToneCell<>(row -> Tone.NORMAL);
        cell.getStyleClass().add("numeric");
        return cell;
    }

    /** A cell of text, cut with an ellipsis and whole in its tooltip, in the tone of its row. */
    private static final class ToneCell<S> extends TableCell<S, String> {
        private final Function<S, Tone> tone;
        private final Tooltip tooltip = new Tooltip();

        ToneCell(Function<S, Tone> tone) {
            this.tone = tone;
            setTextOverrun(OverrunStyle.ELLIPSIS);
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(480);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            S row = empty || getTableRow() == null ? null : getTableRow().getItem();
            setText(empty || item == null ? null : item);
            Tone shown = row == null ? Tone.NORMAL : tone.apply(row);
            if (!empty && OomGuardViewModel.DASH.equals(item)) {
                shown = Tone.MUTED;
            }
            OomGuardPage.tone(this, shown);
            tooltip.setText(empty || item == null ? "" : item);
            setTooltip(empty || item == null ? null : tooltip);
        }
    }

    private static void tone(javafx.scene.Node node, Tone tone) {
        node.pseudoClassStateChanged(WARNING, tone == Tone.WARNING);
        node.pseudoClassStateChanged(ERROR, tone == Tone.ERROR);
        node.pseudoClassStateChanged(MUTED, tone == Tone.MUTED);
    }

    // ----- For tests -----

    OomGuardViewModel model() {
        return model;
    }

    TableView<CandidateRow> table() {
        return table;
    }

    CheckBox monitoringBox() {
        return monitoring;
    }

    CheckBox switchBox(Switch which) {
        return switches.get(which);
    }

    void select(ProcessIdentity identity) {
        model.rows().stream()
                .filter(row -> row.identity().equals(identity))
                .findFirst()
                .ifPresent(row -> table.getSelectionModel().select(row));
    }

    Optional<CandidateRow> selectedRow() {
        return model.selected();
    }
}
