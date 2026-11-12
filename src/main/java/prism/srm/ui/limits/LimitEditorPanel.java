package prism.srm.ui.limits;

import java.util.List;
import java.util.Objects;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.ui.control.InspectorFrame;

/**
 * The panel about the selected managed process on the Limits page (UI lab v2): who it is, the actions on
 * it in their three groups, CPU limit, memory limit and the process (docs/UI_GUIDE.md, Limits page), the
 * line about the last action, and what its CPU limit does now, how it is applied, and its memory limit.
 * Every action is always there; one that cannot be used now is disabled, and why is said right under the
 * buttons of its group as well as in its tooltip (CAP-2, LO-08).
 *
 * <p>Fix round G (owner decision K2): the panel is an {@link InspectorFrame}. Its pinned part, which never
 * scrolls away, holds the name, the three groups with their reasons and the line about the last action
 * (LO-09: the result stands where the action was taken); the facts scroll under it, inside the panel.
 *
 * <p>One design in two arrangements, as the inspector of the Processes page: <b>narrow</b>, beside the
 * table or in the sheet over it, the groups under one another; <b>columns</b>, under the table, the three
 * groups side by side. A group is never narrower than its widest button, its buttons wrap inside it, and a
 * value of the facts has at least {@link #VALUE_MIN} px, so no button overlaps another and no sentence
 * stands one letter to a line (LO-01, LO-02). The close button of the sheet is the sheet's.
 *
 * <p>Fix round H (LO-06): where the frame is too low for the groups whole (beside the table in a low wide
 * window, in the sheet of the smallest one), the narrow panel is <b>compact</b>: each group on one line, its
 * title at the left of its buttons, its reasons under them, and the buttons named by their short words
 * ("Set…", "Remove"), whose whole names stay their accessible text and their tooltips say what they do.
 * Every node stays where it is: narrow, columns and compact are arrangements of the same children, so a
 * change of mode moves no node from one parent to another.
 *
 * <p>Layout and binding only: the actions are the page's buttons, which keep their bindings and
 * their dialogs; the panel only places them and shows the row the model calls current.
 */
final class LimitEditorPanel extends VBox {

    private static final PseudoClass WARNING = PseudoClass.getPseudoClass("warning");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass MUTED = PseudoClass.getPseudoClass("muted");
    private static final PseudoClass EMPTY = PseudoClass.getPseudoClass("empty");
    private static final PseudoClass COLUMNS = PseudoClass.getPseudoClass("columns");

    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");

    /** The key of a button's short words in its properties: shown while the panel is compact. */
    private static final String SHORT_TEXT = "prism.limits.short-text";

    /** The key of a button's whole words in its properties. */
    private static final String WHOLE_TEXT = "prism.limits.whole-text";

    /** Shown while no managed process is selected; the actions below stay, disabled, with their reasons. */
    static final String NONE = "Select a managed process to see its limits and the actions on it.";

    /** The least width of a value of the facts: "No CPU limit is applied." on one or two lines, never one letter a line. */
    static final double VALUE_MIN = 160;

    /** The measure of the sentence that stands in for the PID with no process selected, so it never ends on one word (LO-21). */
    private static final double NONE_MEASURE = 300;

    private final Label title = new Label();
    private final Label pid = new Label();
    private final HBox nameLine = new HBox();
    private final VBox names = new VBox();
    private final Label status = new Label();
    private final Label statusDetail = new Label();
    private final Label cpuValue = new Label();
    private final Label implementation = new Label();
    private final Label implementationDetail = new Label();
    private final Label memoryValue = new Label();
    private final Label memoryDetail = new Label();
    private final Label limitsState = new Label();
    private final VBox facts;
    private final List<ActionGroup> groups;
    private final GroupsPane actions;
    private final VBox pinned = new VBox();
    private final HBox header;
    private final Node line;
    private final InspectorFrame frame;
    private boolean narrow = true;
    private boolean compact;

    /** The buttons of one group, each in its holder, and the line with the reasons of the disabled ones. */
    record Group(String name, List<Node> buttons, Node reasons) {
        Group {
            Objects.requireNonNull(name, "name");
            buttons = List.copyOf(buttons);
            Objects.requireNonNull(reasons, "reasons");
        }
    }

    /**
     * @param groups the three groups of actions, CPU limit, memory limit and the process
     * @param line the line about the last action, under the groups
     */
    LimitEditorPanel(LimitsViewModel model, List<Group> groups, Node line) {
        Objects.requireNonNull(model, "model");
        this.line = Objects.requireNonNull(line, "line");
        getStyleClass().add("limit-editor");
        setAccessibleText("The limits of the selected managed process and the actions on it");

        // ---- header: the process ----
        title.getStyleClass().addAll("text-section-title", "limit-editor-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMinWidth(0);
        Tooltip whole = new Tooltip();
        whole.textProperty().bind(title.textProperty());
        title.setTooltip(whole);
        pid.getStyleClass().addAll("text-small", "text-muted", "limit-editor-pid");
        pid.setMinWidth(Region.USE_PREF_SIZE);
        // The name and the PID on one line, as in the inspector of the Processes page; with no process
        // selected, the sentence in place of the PID goes under the title (show).
        nameLine.getChildren().setAll(title, pid);
        nameLine.getStyleClass().add("limit-editor-names");
        nameLine.setMinWidth(0);
        names.getChildren().setAll(nameLine);
        names.getStyleClass().add("limit-editor-heading");
        names.setMinWidth(0);
        HBox.setHgrow(names, Priority.ALWAYS);
        header = new HBox(names);
        header.getStyleClass().add("limit-editor-header");

        // ---- what the CPU limit does now ----
        status.getStyleClass().addAll("text-medium", "limit-editor-status");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        statusDetail.getStyleClass().addAll("text-small", "text-muted");
        statusDetail.setWrapText(true);
        statusDetail.setMinHeight(Region.USE_PREF_SIZE);
        statusDetail.managedProperty().bind(statusDetail.visibleProperty());

        GridPane grid = new GridPane();
        grid.getStyleClass().add("limit-editor-facts");
        // The keys as wide as they are, the values the rest and never less than VALUE_MIN (LO-01).
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(Region.USE_PREF_SIZE);
        keys.setHgrow(Priority.NEVER);
        ColumnConstraints values = new ColumnConstraints();
        values.setMinWidth(VALUE_MIN);
        values.setHgrow(Priority.ALWAYS);
        values.setFillWidth(true);
        grid.getColumnConstraints().setAll(keys, values);
        grid.addRow(0, key("CPU limit"), value(cpuValue));
        VBox implementationBox = new VBox(implementation, implementationDetail);
        implementation.getStyleClass().add("chip");
        implementation.setMinWidth(Region.USE_PREF_SIZE);
        implementation.managedProperty().bind(implementation.visibleProperty());
        implementationDetail.getStyleClass().addAll("text-small", "text-muted");
        implementationDetail.setWrapText(true);
        implementationDetail.setMinHeight(Region.USE_PREF_SIZE);
        implementationDetail.managedProperty().bind(implementationDetail.visibleProperty());
        implementationBox.getStyleClass().add("limit-editor-value-box");
        grid.addRow(1, key("Applied as"), implementationBox);
        VBox memoryBox = new VBox(memoryValue, memoryDetail);
        memoryValue.getStyleClass().addAll("text-medium", "limit-editor-value");
        memoryDetail.getStyleClass().addAll("text-small", "text-muted");
        memoryDetail.setWrapText(true);
        memoryDetail.setMinHeight(Region.USE_PREF_SIZE);
        memoryDetail.managedProperty().bind(memoryDetail.visibleProperty());
        memoryBox.getStyleClass().add("limit-editor-value-box");
        grid.addRow(2, key("Memory limit"), memoryBox);
        grid.addRow(3, key("Limits"), value(limitsState));
        Tooltip memoryNote = new Tooltip(LimitsViewModel.MEMORY_NOTE);
        memoryNote.setWrapText(true);
        memoryNote.setMaxWidth(400);
        memoryValue.setTooltip(memoryNote);
        GridPane.setHgrow(implementationBox, Priority.ALWAYS);
        GridPane.setHgrow(memoryBox, Priority.ALWAYS);

        facts = new VBox(status, statusDetail, grid);
        facts.getStyleClass().add("limit-editor-facts-box");
        facts.managedProperty().bind(facts.visibleProperty());

        // ---- the actions, in their three groups, each with its reasons under its buttons ----
        this.groups = groups.stream().map(ActionGroup::new).toList();
        actions = new GroupsPane(this.groups);
        pinned.getStyleClass().add("limit-editor-pinned");
        pinned.getChildren().setAll(header, actions, line);

        VBox details = new VBox(facts);
        details.getStyleClass().add("limit-editor-body");
        frame = new InspectorFrame(pinned, details);
        frame.setCompactable(this::setCompact);
        VBox.setVgrow(frame, Priority.ALWAYS);
        getChildren().setAll(frame);
        arrange();
        show(model.currentProperty().get());
        model.currentProperty().addListener((observable, before, after) -> show(after));
    }

    /**
     * Narrow, beside the table or in the sheet: the groups under one another. Otherwise, under the table:
     * the three groups in columns.
     */
    void setNarrow(boolean value) {
        if (value != narrow) {
            narrow = value;
            arrange();
        }
    }

    boolean isNarrow() {
        return narrow;
    }

    /** The frame with the pinned part and the scrolling facts; for the page and for tests. */
    InspectorFrame frame() {
        return frame;
    }

    /**
     * Compact where the frame is too low for the groups whole (fix round H, LO-06); the narrow panel only,
     * the columns under the table have their room. The frame decides.
     */
    void setCompact(boolean value) {
        if (value != compact) {
            compact = value;
            arrange();
        }
    }

    /** Whether the panel shows its compact arrangement now: compact and narrow. */
    boolean isCompact() {
        return compact && narrow;
    }

    /** Narrow, columns or compact: what is shown and how it is laid out, never where a node is. */
    private void arrange() {
        pseudoClassStateChanged(COLUMNS, !narrow);
        boolean shortened = isCompact();
        pseudoClassStateChanged(COMPACT, shortened);
        actions.arrange(!narrow, shortened);
    }

    /**
     * Gives a button of the panel the short words it shows while the panel is compact ("Set…" under the
     * title "CPU limit"); its whole words stay its accessible text, so a screen reader says the same.
     */
    static Button shortened(Button button, String shortText) {
        button.getProperties().put(SHORT_TEXT, shortText);
        button.getProperties().put(WHOLE_TEXT, button.getText());
        button.setAccessibleText(button.getText());
        return button;
    }

    /** The short words of a button that has them while compact, else its whole words. */
    private static void shorten(Node holder, boolean value) {
        Node control = holder instanceof javafx.scene.layout.Pane pane
                        && !pane.getChildren().isEmpty()
                ? pane.getChildren().get(0)
                : holder;
        if (control instanceof Button button
                && !button.textProperty().isBound()
                && button.getProperties().get(SHORT_TEXT) instanceof String shortText
                && button.getProperties().get(WHOLE_TEXT) instanceof String wholeText) {
            button.setText(value ? shortText : wholeText);
        }
    }

    private static Label key(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("text-small", "text-muted", "limit-editor-key");
        label.setMinWidth(Region.USE_PREF_SIZE);
        // A key stands at the first line of its value, which may wrap to several.
        GridPane.setValignment(label, VPos.TOP);
        return label;
    }

    private static Label value(Label label) {
        label.getStyleClass().addAll("text-medium", "limit-editor-value");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /**
     * The three groups: under one another (narrow, and compact), or side by side under the table, sharing
     * the width as a row of boxes that grow would. The same children in every arrangement.
     */
    private static final class GroupsPane extends VBox {

        /** Between two groups side by side. */
        private static final double COLUMN_GAP = 24;

        private final List<ActionGroup> groups;
        private boolean columns;

        GroupsPane(List<ActionGroup> groups) {
            this.groups = groups;
            getStyleClass().add("limit-editor-actions");
            getChildren().setAll(groups);
        }

        void arrange(boolean inColumns, boolean compact) {
            columns = inColumns;
            double caption = 0;
            for (ActionGroup group : groups) {
                caption = Math.max(caption, group.headingWidth());
            }
            for (ActionGroup group : groups) {
                group.setCompact(compact, caption);
            }
            requestLayout();
        }

        @Override
        protected double computeMinWidth(double height) {
            if (!columns) {
                return super.computeMinWidth(height);
            }
            double sum = COLUMN_GAP * Math.max(0, groups.size() - 1);
            for (ActionGroup group : groups) {
                sum += group.minWidth(-1);
            }
            return snappedLeftInset() + sum + snappedRightInset();
        }

        @Override
        protected double computePrefWidth(double height) {
            if (!columns) {
                return super.computePrefWidth(height);
            }
            double sum = COLUMN_GAP * Math.max(0, groups.size() - 1);
            for (ActionGroup group : groups) {
                sum += group.prefWidth(-1);
            }
            return snappedLeftInset() + sum + snappedRightInset();
        }

        @Override
        protected double computePrefHeight(double width) {
            if (!columns) {
                return super.computePrefHeight(width);
            }
            double inner = width < 0 ? -1 : width - snappedLeftInset() - snappedRightInset();
            double[] widths = inner < 0 ? null : widths(inner);
            double tallest = 0;
            for (int index = 0; index < groups.size(); index++) {
                tallest = Math.max(tallest, groups.get(index).prefHeight(widths == null ? -1 : widths[index]));
            }
            return snappedTopInset() + tallest + snappedBottomInset();
        }

        @Override
        protected double computeMinHeight(double width) {
            return columns ? computePrefHeight(width) : super.computeMinHeight(width);
        }

        @Override
        protected void layoutChildren() {
            if (!columns) {
                super.layoutChildren();
                return;
            }
            double left = snappedLeftInset();
            double top = snappedTopInset();
            double inner = getWidth() - left - snappedRightInset();
            double[] widths = widths(inner);
            double x = left;
            for (int index = 0; index < groups.size(); index++) {
                ActionGroup group = groups.get(index);
                double width = snapSizeX(widths[index]);
                group.resizeRelocate(snapPositionX(x), top, width, group.prefHeight(width));
                x += width + COLUMN_GAP;
            }
        }

        /**
         * The widths of the columns, as a row of boxes that all grow: each its preferred width and an
         * equal share of what is left; in less room each gives up an equal share down to its least.
         */
        private double[] widths(double inner) {
            int count = groups.size();
            double[] widths = new double[count];
            double room = inner - COLUMN_GAP * Math.max(0, count - 1);
            double used = 0;
            for (int index = 0; index < count; index++) {
                widths[index] = groups.get(index).prefWidth(-1);
                used += widths[index];
            }
            double rest = room - used;
            if (rest >= 0) {
                for (int index = 0; index < count; index++) {
                    widths[index] += rest / count;
                }
                return widths;
            }
            // Shrink in equal shares, each no lower than its least, until the room is met or none can give.
            boolean[] floor = new boolean[count];
            for (int round = 0; round < count && rest < -0.01; round++) {
                int giving = 0;
                for (int index = 0; index < count; index++) {
                    if (!floor[index]) {
                        giving++;
                    }
                }
                if (giving == 0) {
                    break;
                }
                double share = rest / giving;
                for (int index = 0; index < count; index++) {
                    if (floor[index]) {
                        continue;
                    }
                    double least = groups.get(index).minWidth(-1);
                    double next = Math.max(least, widths[index] + share);
                    rest -= next - widths[index];
                    widths[index] = next;
                    floor[index] = next <= least + 0.01;
                }
            }
            return widths;
        }
    }

    /**
     * A group of actions: its title, its buttons, which wrap inside it, and the reasons of its disabled
     * buttons under them. Never narrower than its widest button or its title; it asks for its buttons on
     * one row, never for the width of its reasons, which wrap. Compact (fix round H): the title at the
     * left of the buttons, in a column as wide as the widest title of the panel, the reasons under both.
     */
    private static final class ActionGroup extends Region {

        /** Between the title, the buttons and the reasons, under one another. */
        private static final double SPACE = 8;

        /** Compact: between the title and the buttons, and between the buttons and the reasons. */
        private static final double CAPTION_GAP = 12;

        private static final double REASONS_GAP = 4;

        private final Label heading;
        private final FlowPane row = new FlowPane(8, 8);
        private final Node reasons;
        private boolean compact;
        private double captionColumn;

        ActionGroup(Group group) {
            heading = new Label(group.name());
            heading.getStyleClass().addAll("inspector-section-caption", "limit-editor-group-title");
            heading.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().setAll(group.buttons());
            row.getStyleClass().add("limit-editor-buttons");
            reasons = group.reasons();
            getChildren().setAll(heading, row, reasons);
            getStyleClass().add("limit-editor-group");
        }

        double headingWidth() {
            return heading.prefWidth(-1);
        }

        void setCompact(boolean value, double caption) {
            captionColumn = caption;
            if (value == compact) {
                requestLayout();
                return;
            }
            compact = value;
            for (Node button : row.getChildren()) {
                shorten(button, value);
            }
            heading.pseudoClassStateChanged(COMPACT, value);
            requestLayout();
        }

        @Override
        public Orientation getContentBias() {
            return Orientation.HORIZONTAL;
        }

        private double lead() {
            return compact ? captionColumn + CAPTION_GAP : 0;
        }

        private double widestButton() {
            double widest = 0;
            for (Node button : row.getChildren()) {
                if (button.isManaged()) {
                    widest = Math.max(widest, button.prefWidth(-1));
                }
            }
            return widest;
        }

        private double oneRow() {
            double width = 0;
            int shown = 0;
            for (Node button : row.getChildren()) {
                if (button.isManaged()) {
                    width += button.prefWidth(-1);
                    shown++;
                }
            }
            return width + row.getHgap() * Math.max(0, shown - 1);
        }

        @Override
        protected double computeMinWidth(double height) {
            double inner = compact ? lead() + widestButton() : Math.max(widestButton(), heading.prefWidth(-1));
            return snappedLeftInset() + inner + snappedRightInset();
        }

        @Override
        protected double computePrefWidth(double height) {
            double inner = compact ? lead() + oneRow() : Math.max(oneRow(), heading.prefWidth(-1));
            return snappedLeftInset() + inner + snappedRightInset();
        }

        @Override
        protected double computePrefHeight(double width) {
            double inner = width < 0 ? -1 : Math.max(0, width - snappedLeftInset() - snappedRightInset());
            double buttons = row.prefHeight(inner < 0 ? -1 : Math.max(0, inner - lead()));
            double height =
                    compact ? Math.max(heading.prefHeight(-1), buttons) : heading.prefHeight(-1) + SPACE + buttons;
            if (reasons.isManaged()) {
                height += (compact ? REASONS_GAP : SPACE) + reasons.prefHeight(inner);
            }
            return snappedTopInset() + height + snappedBottomInset();
        }

        @Override
        protected double computeMinHeight(double width) {
            return computePrefHeight(width);
        }

        @Override
        protected void layoutChildren() {
            double left = snappedLeftInset();
            double top = snappedTopInset();
            double inner = getWidth() - left - snappedRightInset();
            double headingHeight = heading.prefHeight(-1);
            double rowWidth = Math.max(0, inner - lead());
            double rowHeight = row.prefHeight(rowWidth);
            double bottom;
            if (compact) {
                // The title on the first line of buttons, its text centred on theirs.
                double line = Math.min(rowHeight, row.getChildren().isEmpty() ? headingHeight : firstButtonHeight());
                layoutInArea(
                        heading, left, top, captionColumn, Math.max(line, headingHeight), 0, HPos.LEFT, VPos.CENTER);
                layoutInArea(row, left + lead(), top, rowWidth, rowHeight, 0, HPos.LEFT, VPos.TOP);
                bottom = top + Math.max(headingHeight, rowHeight) + REASONS_GAP;
            } else {
                layoutInArea(heading, left, top, inner, headingHeight, 0, HPos.LEFT, VPos.CENTER);
                layoutInArea(row, left, top + headingHeight + SPACE, inner, rowHeight, 0, HPos.LEFT, VPos.TOP);
                bottom = top + headingHeight + SPACE + rowHeight + SPACE;
            }
            if (reasons.isManaged()) {
                layoutInArea(reasons, left, bottom, inner, reasons.prefHeight(inner), 0, HPos.LEFT, VPos.TOP);
            }
        }

        private double firstButtonHeight() {
            for (Node button : row.getChildren()) {
                if (button.isManaged()) {
                    return button.prefHeight(-1);
                }
            }
            return 0;
        }
    }

    private void show(LimitRow row) {
        pseudoClassStateChanged(EMPTY, row == null);
        facts.setVisible(row != null);
        if (row == null) {
            title.setText("No process selected");
            pid.setText(NONE);
            // The sentence wraps inside the panel at a measure that leaves no word alone; a PID is short and never cut.
            pid.setMinWidth(0);
            pid.setMaxWidth(NONE_MEASURE);
            pid.setWrapText(true);
            nameLine.getChildren().setAll(title);
            names.getChildren().setAll(nameLine, pid);
            return;
        }
        pid.setMinWidth(Region.USE_PREF_SIZE);
        pid.setMaxWidth(Region.USE_COMPUTED_SIZE);
        pid.setWrapText(false);
        names.getChildren().setAll(nameLine);
        nameLine.getChildren().setAll(title, pid);
        title.setText(row.name());
        pid.setText("PID " + row.pid());
        status.setText(row.status());
        status.pseudoClassStateChanged(WARNING, row.tone() == LimitRow.Tone.WARNING);
        status.pseudoClassStateChanged(ERROR, row.tone() == LimitRow.Tone.ERROR);
        status.pseudoClassStateChanged(MUTED, row.tone() == LimitRow.Tone.MUTED);
        text(statusDetail, row.detail());
        cpuValue.setText(row.cpuLimit());
        cpuValue.pseudoClassStateChanged(MUTED, LimitsViewModel.DASH.equals(row.cpuLimit()));
        implementation.setText(row.implementation());
        implementation.setVisible(!LimitsViewModel.DASH.equals(row.implementation()));
        text(implementationDetail, row.implementationDetail());
        if (LimitsViewModel.DASH.equals(row.implementation())) {
            text(implementationDetail, "No CPU limit is applied.");
        }
        memoryValue.setText(row.memoryLimit());
        memoryValue.pseudoClassStateChanged(MUTED, LimitsViewModel.DASH.equals(row.memoryLimit()));
        text(memoryDetail, row.memoryDetail());
        limitsState.setText(row.enabled() ? "Enabled" : "Disabled");
    }

    private static void text(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isEmpty());
    }

    /** For tests: the nodes that show the selected process. */
    List<Node> shownNodes() {
        return List.of(title, pid, status, cpuValue, implementation, memoryValue, limitsState);
    }
}
