package prism.srm.ui.logs;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.ui.Breakpoints;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.TableFocus;
import prism.srm.ui.Typefaces;
import prism.srm.ui.control.CutTextTooltip;
import prism.srm.ui.control.PageHeader;
import prism.srm.ui.control.TextWidths;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;

/**
 * The Logs page (UI-6): what Prism did and noticed during this session, newest first, among it
 * every action on a process and its result with the detail the message left out. A filter, and a
 * button that opens the folder of the log file, which keeps the entries of earlier sessions. Its
 * states are those of {@link LogsViewModel}.
 *
 * <p>Master and detail, as an event viewer is: the table is one line per entry, for scanning, and
 * the pane under it shows the selected entry whole, its date, its severity and its message wrapped,
 * selectable and copyable. The pane has a fixed height, so a selection moves nothing.
 *
 * <p>Layout and binding only. Every method is called on the JavaFX Application Thread; the entries
 * are handed in.
 */
public final class LogsPage extends VBox {

    private static final PseudoClass INFO = PseudoClass.getPseudoClass("info");
    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    /**
     * The widest the message column gets in an ultrawide window (LAB_DESIGN_SPEC section 7): a line
     * of text is read, not scanned across 2000 px. The whole message is in the detail pane.
     */
    static final double READABLE_MESSAGE_WIDTH = 960;

    private final LogsViewModel model;
    /** The default upper bound of a TableColumn's width (TableColumnBase#maxWidthProperty). */
    private static final double COLUMN_MAX_WIDTH = 5000;

    private final TableColumn<ActivityEntry, ActivityEntry> message;
    private final TableView<ActivityEntry> table;
    private final Label detailSeverity = new Label();
    private final Label detailTime = new Label();
    private final TextArea detailMessage = new TextArea();
    private final Label detailHint = new Label(LogsViewModel.NO_SELECTION);
    private final Button copy = new Button("Copy");
    private final InvalidationListener sceneWidth = observable -> fitMessageColumn();

    /** The entry the detail pane shows; null while none is selected. */
    private ActivityEntry detailed;

    /** True while the rows are replaced: the selection passes through nothing on its way back. */
    private boolean refreshing;

    /** Where an entry is copied to: the system clipboard, unless a test says otherwise. */
    private Consumer<String> clipboard = text -> {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    };

    /**
     * @param capacity the number of entries the activity log keeps, as the application built it
     * @param logFolder where the log file is, as text for the user
     * @param openLogFolder opens that folder in the system's file manager; returns at once
     */
    public LogsPage(ZoneId zone, int capacity, String logFolder, Runnable openLogFolder) {
        model = new LogsViewModel(zone, capacity);
        getStyleClass().add("logs");
        getStylesheets().addAll(Stylesheets.table(), Stylesheets.logs());

        message = column("Message", "logs-message");
        table = table();

        PageHeader header = new PageHeader("Logs");
        Label count = new Label();
        count.textProperty().bind(model.countProperty());
        count.getStyleClass().addAll("text-small", "text-muted", "logs-count");
        // As wide as the widest count this log can have, its text at the right: the filter beside it
        // stays where it is while the log is read and while the count changes (LS-11).
        double widest = Math.ceil(TextWidths.width(Font.font(Typefaces.FAMILY, 12), model.widestCount()));
        count.setMinWidth(widest);
        count.setPrefWidth(widest);
        count.setAlignment(Pos.CENTER_RIGHT);
        Button open = new Button("Open log folder");
        open.getStyleClass().add("logs-open");
        open.setTooltip(new Tooltip(logFolder));
        open.setOnAction(event -> openLogFolder.run());
        // The page toolbar (UI lab v2): the filter, the count and the folder button on the title's row.
        HBox trailing = new HBox(toolbar(), count, open);
        trailing.getStyleClass().add("logs-trailing");
        header.setTrailing(trailing);

        Label folderProblem = line(model.folderProblemProperty(), "logs-problem");
        Label limitNote = line(model.limitNoteProperty(), "text-muted");
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().setAll(header, folderProblem, limitNote, table, detail());

        table.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            if (!refreshing) {
                showDetail(after);
            }
        });
        showDetail(null);
        sceneProperty().addListener((observable, before, after) -> {
            if (before != null) {
                before.widthProperty().removeListener(sceneWidth);
            }
            if (after != null) {
                after.widthProperty().addListener(sceneWidth);
            }
            fitMessageColumn();
        });
    }

    /** The end of a request to open the log folder: empty when the system was asked to, otherwise why not. */
    public void showFolderOutcome(Optional<String> problem) {
        model.folderOutcome(problem);
    }

    /** A line of small text, there only while it has text. */
    private static Label line(ReadOnlyStringProperty text, String styleClass) {
        Label line = new Label();
        line.textProperty().bind(text);
        line.getStyleClass().addAll(styleClass, "text-small");
        line.setWrapText(true);
        line.setMinHeight(Region.USE_PREF_SIZE);
        line.visibleProperty().bind(text.isNotEmpty());
        line.managedProperty().bind(line.visibleProperty());
        return line;
    }

    /** Sends what is copied elsewhere than the system clipboard; for tests. */
    void copyTo(Consumer<String> target) {
        clipboard = Objects.requireNonNull(target, "target");
    }

    /** Shows the activity log as it is now, oldest entry first. Call on the JavaFX Application Thread. */
    public void show(List<ActivityEntry> entries) {
        keepingPlace(() -> model.show(entries));
    }

    /**
     * Replaces the rows and keeps the reader's place (LS-09): the selected entry stays selected while
     * it is still shown, and a table scrolled away from the top keeps its first visible entry at the
     * top, so a new entry at the head of the log does not push the one being read down. A table at
     * the top stays at the top, where the new entry appears. The detail pane is redone only when the
     * selected entry changed.
     */
    private void keepingPlace(Runnable change) {
        ActivityEntry selected = table.getSelectionModel().getSelectedItem();
        ActivityEntry firstShown = firstVisibleEntry();
        refreshing = true;
        try {
            change.run();
            int index = selected == null ? -1 : table.getItems().indexOf(selected);
            if (index >= 0) {
                if (table.getSelectionModel().getSelectedIndex() != index) {
                    table.getSelectionModel().clearAndSelect(index);
                }
            } else {
                table.getSelectionModel().clearSelection();
            }
            int top = firstShown == null ? -1 : table.getItems().indexOf(firstShown);
            if (top > 0) {
                table.scrollTo(top);
            }
        } finally {
            refreshing = false;
        }
        showDetail(table.getSelectionModel().getSelectedItem());
    }

    /** The entry in the first row drawn, when the table is scrolled away from its top; otherwise null. */
    private ActivityEntry firstVisibleEntry() {
        if (!(table.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) {
            return null;
        }
        IndexedCell<?> first = flow.getFirstVisibleCell();
        if (first == null
                || first.getIndex() <= 0
                || first.getIndex() >= table.getItems().size()) {
            return null;
        }
        return table.getItems().get(first.getIndex());
    }

    private HBox toolbar() {
        TextField filter = new TextField();
        filter.getStyleClass().add("logs-filter");
        filter.setPromptText("Filter the log");
        Label name = new Label("Filter the log");
        name.setLabelFor(filter);
        name.setManaged(false);
        name.setVisible(false);
        filter.textProperty().addListener((observable, before, after) -> keepingPlace(() -> model.filter(after)));
        filter.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && !filter.getText().isEmpty()) {
                filter.clear();
                event.consume();
            }
        });
        IconView search = new IconView(Icon.SEARCH, 16);
        StackPane.setAlignment(search, Pos.CENTER_LEFT);
        StackPane.setMargin(search, new Insets(0, 0, 0, 8));

        // As on the Processes page: clears the filter with the pointer, Escape does it from the
        // keyboard, so it is not a stop in the Tab order; there only while there is text.
        Button clear = new Button();
        clear.setGraphic(new IconView(Icon.X, 12));
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
        HBox toolbar = new HBox(name, field);
        toolbar.getStyleClass().add("logs-toolbar");
        return toolbar;
    }

    private TableView<ActivityEntry> table() {
        TableView<ActivityEntry> view = new TableView<>(model.rows());
        view.getStyleClass().add("logs-table");
        // The name a screen reader says for the table, as the tables of the other pages have one.
        view.setAccessibleText("Activity log");
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Measured from the font the table draws with: the bundled typeface, or the system font
        // where that did not load (an unknown family resolves to the system font).
        Font body = TextWidths.tableBody();
        Font header = TextWidths.tableHeader();
        TableColumn<ActivityEntry, ActivityEntry> time = column("Time", "logs-time");
        time.setCellFactory(unused -> new TextCell(model::time));
        fixedWidth(time, TextWidths.column(body, header, "Time", List.of("00:00:00", "88:88:88")));
        TableColumn<ActivityEntry, ActivityEntry> severity = column("Severity", "logs-severity");
        severity.setCellFactory(unused -> new SeverityCell());
        fixedWidth(
                severity,
                TextWidths.column(
                        body,
                        header,
                        "Severity",
                        Arrays.stream(ActivitySeverity.values())
                                .map(LogsViewModel::severity)
                                .toList()));
        message.setCellFactory(unused -> new MessageCell());
        view.getColumns().setAll(List.of(time, severity, message));

        // Ctrl+C copies the selected entry whole, as the Copy button and the context menu do.
        view.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C && copySelected(view)) {
                event.consume();
            }
        });

        // The way to Copy from a row with the pointer, and with Shift+F10 or the Menu key. No
        // accelerator on the item: Ctrl+C is the table's own key, and a scene accelerator would take
        // it from the filter field and the detail pane too.
        MenuItem copyEntry = new MenuItem("Copy entry");
        copyEntry.setOnAction(event -> copySelected(view));
        ContextMenu menu = new ContextMenu(copyEntry);
        menu.setOnShowing(event -> copyEntry.setDisable(view.getSelectionModel().getSelectedItem() == null));
        view.setContextMenu(menu);

        Label whenEmpty = new Label();
        whenEmpty.textProperty().bind(model.whenEmptyProperty());
        whenEmpty.getStyleClass().add("text-muted");
        view.setPlaceholder(whenEmpty);
        // Tabbed into with no row focused, or empty, the table draws its own ring (KB-2).
        TableFocus.track(view);
        return view;
    }

    /** Copies the selected entry whole; false when none is selected. */
    private boolean copySelected(TableView<ActivityEntry> view) {
        ActivityEntry entry = view.getSelectionModel().getSelectedItem();
        if (entry == null) {
            return false;
        }
        clipboard.accept(model.copied(entry));
        return true;
    }

    /**
     * The detail pane under the table (LS-01, K3): the selected entry's severity in its word and
     * color, its date and time, and the whole message, wrapped in a read-only text area that can be
     * selected and copied, with its own scroll bar when the message is longer than the pane. A fixed
     * height, lower in a low window (logs.css); with no entry selected it says how to fill it.
     */
    private VBox detail() {
        detailSeverity.getStyleClass().add("logs-detail-severity");
        detailSeverity.setMinWidth(Region.USE_PREF_SIZE);
        detailTime.getStyleClass().addAll("logs-detail-time", "text-small", "text-muted");
        detailTime.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        copy.getStyleClass().add("logs-copy");
        copy.setTooltip(new Tooltip("Copy the whole entry, as Ctrl+C does in the table"));
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setOnAction(event -> {
            if (detailed != null) {
                clipboard.accept(model.copied(detailed));
            }
        });
        HBox head = new HBox(detailSeverity, detailTime, gap, copy);
        head.getStyleClass().add("logs-detail-head");

        detailMessage.getStyleClass().add("logs-detail-message");
        detailMessage.setEditable(false);
        detailMessage.setWrapText(true);
        detailMessage.setPrefRowCount(3);
        detailMessage.setMinHeight(0);
        // A screen reader takes the name of a text area from its label: the name takes no room.
        Label name = new Label("Entry details");
        name.setLabelFor(detailMessage);
        name.setManaged(false);
        name.setVisible(false);
        detailHint.getStyleClass().addAll("logs-detail-hint", "text-muted");
        detailHint.setWrapText(true);
        detailHint.setAlignment(Pos.TOP_LEFT);
        detailHint.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane body = new StackPane(detailMessage, detailHint);
        body.getStyleClass().add("logs-detail-body");
        body.setMinHeight(0);
        StackPane.setAlignment(detailMessage, Pos.TOP_LEFT);
        StackPane.setAlignment(detailHint, Pos.TOP_LEFT);
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox pane = new VBox(head, body, name);
        pane.getStyleClass().addAll("panel", "logs-detail");
        return pane;
    }

    /** Fills the detail pane with an entry, or empties it; nothing is redone for the entry it shows. */
    private void showDetail(ActivityEntry entry) {
        if (entry != null && entry.equals(detailed)) {
            return;
        }
        detailed = entry;
        boolean shown = entry != null;
        detailMessage.setVisible(shown);
        detailHint.setVisible(!shown);
        copy.setDisable(!shown);
        ActivitySeverity severity = shown ? entry.severity() : null;
        detailSeverity.setText(shown ? LogsViewModel.severity(severity) : "");
        detailSeverity.pseudoClassStateChanged(INFO, severity == ActivitySeverity.INFO);
        detailSeverity.pseudoClassStateChanged(WARNING, severity == ActivitySeverity.WARNING);
        detailSeverity.pseudoClassStateChanged(ERROR, severity == ActivitySeverity.ERROR);
        detailTime.setText(shown ? model.dateTime(entry) : "");
        detailMessage.setText(shown ? entry.message() : "");
        detailMessage.setScrollTop(0);
        detailMessage.setAccessibleHelp(shown ? LogsViewModel.severity(severity) + ", " + model.dateTime(entry) : null);
    }

    /**
     * In an ultrawide window the message column keeps a readable width; elsewhere it takes the rest, up
     * to the column's own default bound. Not Double.MAX_VALUE: the constrained resize policy adds the
     * bounds up, and with rows arriving after the first layout it gave the column 9.2E18 px, so the
     * real window showed no Message column at all.
     */
    private void fitMessageColumn() {
        Scene scene = getScene();
        boolean ultrawide = scene != null && scene.getWidth() >= Breakpoints.ULTRAWIDE_FROM;
        message.setMaxWidth(ultrawide ? READABLE_MESSAGE_WIDTH : COLUMN_MAX_WIDTH);
    }

    private static void fixedWidth(TableColumn<?, ?> column, double width) {
        column.setMinWidth(width);
        column.setPrefWidth(width);
        column.setMaxWidth(width);
        column.setResizable(false);
    }

    private static TableColumn<ActivityEntry, ActivityEntry> column(String title, String styleClass) {
        TableColumn<ActivityEntry, ActivityEntry> column = new TableColumn<>(title);
        column.getStyleClass().add(styleClass);
        column.setSortable(false);
        column.setReorderable(false);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
        return column;
    }

    /** A cell that shows one text of the entry, with the whole of it in a tooltip while it is cut. */
    private static class TextCell extends TableCell<ActivityEntry, ActivityEntry> {
        private final java.util.function.Function<ActivityEntry, String> text;

        TextCell(java.util.function.Function<ActivityEntry, String> text) {
            this.text = text;
            CutTextTooltip.install(this);
        }

        @Override
        protected void updateItem(ActivityEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            setText(empty || entry == null ? null : text.apply(entry));
        }
    }

    /**
     * The severity, in the warning or error color where it is one, Info set back to the muted text:
     * the color is on the value it describes, and the word says it too.
     */
    private static final class SeverityCell extends TextCell {
        SeverityCell() {
            super(entry -> LogsViewModel.severity(entry.severity()));
        }

        @Override
        protected void updateItem(ActivityEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            ActivitySeverity severity = empty || entry == null ? null : entry.severity();
            pseudoClassStateChanged(INFO, severity == ActivitySeverity.INFO);
            pseudoClassStateChanged(WARNING, severity == ActivitySeverity.WARNING);
            pseudoClassStateChanged(ERROR, severity == ActivitySeverity.ERROR);
        }
    }

    /**
     * The message, cut at the end of the column, with the whole of it in a tooltip while it is cut,
     * in the detail pane once selected, and for a screen reader always.
     */
    private static final class MessageCell extends TextCell {
        MessageCell() {
            super(ActivityEntry::message);
        }

        @Override
        protected void updateItem(ActivityEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            setAccessibleText(empty || entry == null ? null : entry.message());
        }
    }
}
