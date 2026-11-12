package prism.srm.ui.processes;

import java.util.Optional;
import java.util.function.BiConsumer;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import prism.srm.domain.ControlAction;
import prism.srm.domain.PriorityLevel;
import prism.srm.ui.control.ActionResultLine;
import prism.srm.ui.icon.Icon;
import prism.srm.ui.icon.IconView;

/**
 * The actions on the selected process, in the pinned part of the inspector, which never scrolls away
 * (fix round G, owner decision K2): Suspend, Resume and Priority under "Control", then "Limits &amp;
 * protection" (the middle, set by the page), then, set apart as the danger zone, the two that end a
 * process, End task and Force kill, under "End process"; and the line about them: the action on its
 * way, the result of the last action on this process, or why none is possible. The order puts what is
 * undone easily first and what cannot be undone last; Force kill is told apart by its place, its words
 * and its confirmation. Every button is always there; one that cannot be used is disabled and says why
 * (CAP-2). Layout and binding only.
 *
 * <p>One design in two arrangements. <b>Stacked</b>, in the inspector beside the table, in the sheet, or
 * under the table in a tall narrow window: the groups under one another, each under its caption, and
 * the line under them, whole. <b>Columns</b>, in the inspector under the table: the three groups side by
 * side, each under its caption, their captions on one line, and the line under all three. The Tab order
 * is the order of the groups either way.
 *
 * <p>Fix round H (PR-03): stacked, the bar has a <b>compact</b> arrangement for a frame too low for it
 * whole, as beside the table in a low wide window or in the sheet of the smallest one. The same groups in
 * the same order, the danger zone still last under its caption: the control group without its caption
 * (the buttons say what they do), and "Limits &amp; protection" as one labelled menu with its state and
 * its reasons ({@link LimitsSection#setCompact}). The frame chooses it (InspectorFrame#setCompactable).
 *
 * <p>A disabled control receives no pointer events, so it could not show a tooltip of its own. Each
 * control therefore stands in a holder that carries the tooltip: what the action does while it is
 * enabled, why it is not while it is disabled. A screen reader is given the same text as help.
 */
final class ProcessActionBar extends Pane {

    /** The space between two buttons. */
    private static final double GAP = 8;

    /** The space between the columns of the arrangement under the table. */
    static final double COLUMN_GAP = 24;

    private static final PseudoClass BELOW = PseudoClass.getPseudoClass("below");

    /** The space between two groups under one another. */
    private static final double GROUP_SPACE = 12;

    /** The space between a caption and its buttons, and between the last buttons and the line. */
    private static final double CAPTION_GAP = 8;

    /** The number of controls in the first group: Suspend, Resume, Priority. */
    private static final int CONTROL_GROUP = 3;

    private final PriorityChoices priorityChoices;
    private final ActionResultLine line;

    /** The caption of the group that ends a process: the danger zone, under a hairline. */
    private final Label endCaption = new Label("End process");

    /** The caption of the control group. */
    private final Label controlCaption = new Label("Control");

    /** Whether the groups stand under one another (beside the table, in the sheet) or in columns (under it). */
    private boolean stacked;

    /** What stands between the control group and the group that ends a process; none until set. */
    private Node middle;

    /** Whether the stacked arrangement is compact (fix round H); no effect in columns. */
    private boolean compact;

    /**
     * @param act carries out an action on the selected process, asking for confirmation where one
     *     is needed; the level is present for a change of priority
     */
    ProcessActionBar(ProcessActions actions, BiConsumer<ControlAction, Optional<PriorityLevel>> act) {
        getStyleClass().add("process-actions");
        setAccessibleText("Actions on the selected process");

        MenuButton priority = new MenuButton("Priority");
        priority.setGraphic(new IconView(Icon.CHEVRON_DOWN, 12));
        priority.setContentDisplay(ContentDisplay.RIGHT);
        priority.setGraphicTextGap(4);
        priorityChoices = PriorityChoices.fill(
                priority.getItems(), actions, level -> act.accept(ControlAction.CHANGE_PRIORITY, Optional.of(level)));
        priority.setOnShowing(event -> priorityChoices.markObserved());

        line = new ActionResultLine(actions.line());
        line.getStyleClass().add("process-action-message");
        line.pseudoClassStateChanged(BELOW, true);

        // The level is in what a screen reader and the tooltip say, so it is known without opening the menu.
        priority.accessibleTextProperty()
                .bind(Bindings.concat("Priority, current: ", actions.currentPriorityProperty()));
        StackPane priorityHolder = held(
                actions,
                ControlAction.CHANGE_PRIORITY,
                priority,
                Bindings.concat(
                        "Current: ",
                        actions.currentPriorityProperty(),
                        ". ",
                        actions.explanationProperty(ControlAction.CHANGE_PRIORITY)));
        StackPane endTask = held(actions, ControlAction.END_TASK, button("End task…", ControlAction.END_TASK, act));
        getChildren()
                .setAll(
                        held(actions, ControlAction.SUSPEND, button("Suspend", ControlAction.SUSPEND, act)),
                        held(actions, ControlAction.RESUME, button("Resume", ControlAction.RESUME, act)),
                        priorityHolder,
                        endTask,
                        held(actions, ControlAction.FORCE_KILL, button("Force kill…", ControlAction.FORCE_KILL, act)),
                        line,
                        endCaption,
                        controlCaption);
        for (Label caption : new Label[] {endCaption, controlCaption}) {
            caption.getStyleClass().addAll("inspector-section-caption", "process-actions-end-caption");
            // As wide as its group, so that its hairline separates the group from what is above.
            caption.setMaxWidth(Double.MAX_VALUE);
        }
    }

    /**
     * Stacked, the bar is as narrow as the inspector beside or over the table, its groups under one
     * another; otherwise its groups stand in columns, under the table.
     */
    void setStacked(boolean value) {
        if (value == stacked) {
            return;
        }
        stacked = value;
        showCompact();
    }

    /**
     * What the bar has between the control group and the group that ends a process ("Limits &amp;
     * protection"), so that the danger zone stays last. It is a child after the control group, so the
     * Tab order is the order on screen.
     */
    void setMiddle(Node node) {
        if (middle != null) {
            getChildren().remove(middle);
        }
        middle = node;
        if (node != null) {
            getChildren().add(CONTROL_GROUP, node);
        }
        requestLayout();
    }

    /** Whether a child is a button of the bar, not the line, a caption or the middle. */
    private boolean isButton(Node child) {
        return child != line && child != endCaption && child != controlCaption && child != middle;
    }

    /**
     * Compact where the frame is too low for the bar whole (fix round H, PR-03); stacked only, the columns
     * under the table have their own room. The nodes stay where they are; only what is shown changes.
     */
    void setCompact(boolean value) {
        if (value == compact) {
            return;
        }
        compact = value;
        showCompact();
    }

    /** Whether the bar shows its compact arrangement now: compact and stacked. */
    boolean isCompact() {
        return compact && stacked;
    }

    private void showCompact() {
        if (middle instanceof LimitsSection section) {
            section.setCompact(isCompact());
        }
        controlCaption.setVisible(!isCompact());
        requestLayout();
    }

    boolean isStacked() {
        return stacked;
    }

    /** The priority items; for tests. */
    PriorityChoices priorityChoices() {
        return priorityChoices;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    /** The widths of the control group and of the group that ends a process, each with its gaps. */
    private double[] groupWidths() {
        double first = 0;
        double second = 0;
        int index = 0;
        for (Node child : getChildren()) {
            if (!isButton(child)) {
                continue;
            }
            if (index < CONTROL_GROUP) {
                first += (index == 0 ? 0 : GAP) + child.prefWidth(-1);
            } else {
                second += (index == CONTROL_GROUP ? 0 : GAP) + child.prefWidth(-1);
            }
            index++;
        }
        return new double[] {first, second};
    }

    private double buttonsHeight() {
        double height = 0;
        for (Node child : getChildren()) {
            if (isButton(child)) {
                height = Math.max(height, child.prefHeight(-1));
            }
        }
        return height;
    }

    /** In columns: the width of the first column, the caption over the control group. */
    private double controlColumnWidth() {
        return Math.max(groupWidths()[0], controlCaption.prefWidth(-1));
    }

    /** In columns: the width of the middle column; its own, which does not follow the values it shows (PR-10). */
    private double middleColumnWidth() {
        return middle == null ? 0 : middle.prefWidth(-1);
    }

    /** In columns: the width of the last column, the caption over the group that ends a process. */
    private double endColumnWidth() {
        return Math.max(groupWidths()[1], endCaption.prefWidth(-1));
    }

    private double middleHeight(double width) {
        return middle == null ? 0 : middle.prefHeight(width);
    }

    @Override
    protected double computeMinWidth(double height) {
        double inner;
        if (stacked) {
            double[] groups = groupWidths();
            double between = middle == null ? 0 : middle.minWidth(-1);
            inner = Math.max(Math.max(groups[0], groups[1]), between);
        } else {
            inner = controlColumnWidth()
                    + (middle == null ? 0 : COLUMN_GAP + middleColumnWidth())
                    + COLUMN_GAP
                    + endColumnWidth();
        }
        return snappedLeftInset() + inner + snappedRightInset();
    }

    @Override
    protected double computePrefWidth(double height) {
        return computeMinWidth(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        double buttons = buttonsHeight();
        double caption = endCaption.prefHeight(-1);
        double group = caption + CAPTION_GAP + buttons;
        double content;
        if (stacked) {
            double inner = width < 0 ? -1 : width - snappedLeftInset() - snappedRightInset();
            double between = middle == null ? 0 : GROUP_SPACE + middleHeight(inner);
            double first = isCompact() ? buttons : group;
            content = first + between + GROUP_SPACE + group + CAPTION_GAP + line.prefHeight(-1);
        } else {
            content = Math.max(group, middleHeight(middleColumnWidth())) + CAPTION_GAP + line.prefHeight(-1);
        }
        return snappedTopInset() + content + snappedBottomInset();
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        if (stacked) {
            layoutStacked();
        } else {
            layoutColumns();
        }
    }

    /** "Control" with its group, the middle, "End process" with its group, the line under them. */
    private void layoutStacked() {
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double width = getWidth() - left - snappedRightInset();
        double rowHeight = buttonsHeight();
        double captionHeight = endCaption.prefHeight(-1);
        double firstTop = top;
        if (!isCompact()) {
            layoutInArea(controlCaption, left, top, width, captionHeight, 0, HPos.LEFT, VPos.CENTER);
            firstTop = top + captionHeight + CAPTION_GAP;
        }
        double after = firstTop + rowHeight;
        if (middle != null) {
            double middleTop = after + GROUP_SPACE;
            double middleHeight = middleHeight(width);
            layoutInArea(middle, left, middleTop, width, middleHeight, 0, HPos.LEFT, VPos.TOP);
            after = middleTop + middleHeight;
        }
        double captionTop = after + GROUP_SPACE;
        double secondTop = captionTop + captionHeight + CAPTION_GAP;
        layoutInArea(endCaption, left, captionTop, width, captionHeight, 0, HPos.LEFT, VPos.CENTER);
        placeButtons(left, firstTop, left, secondTop, rowHeight);
        double lineTop = secondTop + rowHeight + CAPTION_GAP;
        layoutInArea(line, left, lineTop, width, line.prefHeight(-1), 0, HPos.LEFT, VPos.CENTER);
    }

    /**
     * "Control", "Limits &amp; protection" and "End process" side by side, their captions on one line, each
     * group under its caption; the line under all three, as wide as the bar.
     */
    private void layoutColumns() {
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double width = getWidth() - left - snappedRightInset();
        double rowHeight = buttonsHeight();
        double captionHeight = endCaption.prefHeight(-1);
        double controlWidth = controlColumnWidth();
        double x = left + controlWidth + COLUMN_GAP;
        double bottom = top + captionHeight + CAPTION_GAP + rowHeight;
        if (middle != null) {
            double middleWidth = middleColumnWidth();
            double middleHeight = middleHeight(middleWidth);
            layoutInArea(middle, x, top, middleWidth, middleHeight, 0, HPos.LEFT, VPos.TOP);
            bottom = Math.max(bottom, top + middleHeight);
            x += middleWidth + COLUMN_GAP;
        }
        double endWidth = Math.max(0, Math.min(endColumnWidth(), left + width - x));
        double rowTop = top + captionHeight + CAPTION_GAP;
        layoutInArea(controlCaption, left, top, controlWidth, captionHeight, 0, HPos.LEFT, VPos.CENTER);
        layoutInArea(endCaption, x, top, endWidth, captionHeight, 0, HPos.LEFT, VPos.CENTER);
        placeButtons(left, rowTop, x, rowTop, rowHeight);
        double lineTop = bottom + CAPTION_GAP;
        layoutInArea(line, left, lineTop, width, line.prefHeight(-1), 0, HPos.LEFT, VPos.CENTER);
    }

    /** The control group on its row from one point, the group that ends a process on its row from another. */
    private void placeButtons(double firstX, double firstY, double secondX, double secondY, double rowHeight) {
        double x = firstX;
        int index = 0;
        for (Node child : getChildren()) {
            if (!isButton(child)) {
                continue;
            }
            if (index == CONTROL_GROUP) {
                x = secondX;
            }
            x += index == 0 || index == CONTROL_GROUP ? 0 : GAP;
            double childWidth = child.prefWidth(-1);
            layoutInArea(
                    child,
                    x,
                    index < CONTROL_GROUP ? firstY : secondY,
                    childWidth,
                    rowHeight,
                    0,
                    HPos.LEFT,
                    VPos.CENTER);
            x += childWidth;
            index++;
        }
    }

    private static Button button(
            String text, ControlAction action, BiConsumer<ControlAction, Optional<PriorityLevel>> act) {
        Button button = new Button(text);
        button.setOnAction(event -> act.accept(action, Optional.empty()));
        if (action == ControlAction.FORCE_KILL) {
            // UI lab v2: only Force kill has the danger words; End task is set apart by its place in
            // the danger zone and its wording. Both still ask for confirmation.
            button.getStyleClass().add("destructive");
        }
        return button;
    }

    /** The control in a holder that shows its tooltip whether the control is enabled or not. */
    private static StackPane held(ProcessActions actions, ControlAction action, ButtonBase control) {
        return held(actions, action, control, actions.explanationProperty(action));
    }

    private static StackPane held(
            ProcessActions actions, ControlAction action, ButtonBase control, ObservableValue<String> tip) {
        control.getStyleClass().add("process-action");
        // A label is never cut: in a narrow window the message beside the buttons gives way.
        control.setMinWidth(Region.USE_PREF_SIZE);
        control.disableProperty().bind(actions.disabledProperty(action));
        control.accessibleHelpProperty().bind(actions.explanationProperty(action));
        Tooltip tooltip = new Tooltip();
        tooltip.textProperty().bind(tip);
        tooltip.setShowDuration(Duration.seconds(10));
        StackPane holder = new StackPane(control);
        holder.getStyleClass().add("process-action-holder");
        holder.setMinWidth(Region.USE_PREF_SIZE);
        Tooltip.install(holder, tooltip);
        return holder;
    }
}
