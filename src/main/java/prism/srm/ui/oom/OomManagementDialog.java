package prism.srm.ui.oom;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.CutTextTooltip;

/**
 * Asks for the OOM settings of one managed process (MGD-3): OOM management on or off, its OOM
 * priority, and its emergency action, each action with what it does on this system and whether it is
 * in effect now (CAP-5, OOM-8). Terminate is the per-process choice OOM-9 asks for: chosen, the
 * button says "Allow termination", the consequence is said in the text color, and Cancel is the
 * default button, so a stray Enter allows nothing.
 */
@FunctionalInterface
interface OomManagementDialog {

    /** What choosing Terminate means, said in the dialog before it is set. */
    String TERMINATE_MEANS =
            "In an Emergency OOM Guard force-kills this process without asking. Work it has not saved is lost.";

    /**
     * The settings the user chose, or empty if they cancelled.
     *
     * @param notes what each action does on this system and whether it is in effect now
     */
    Optional<OomManagement> ask(
            String name, long pid, OomManagement current, Map<EmergencyAction, String> notes, Node owner);

    static Optional<OomManagement> showDialog(
            String name, long pid, OomManagement current, Map<EmergencyAction, String> notes, Node owner) {
        Dialog<OomManagement> dialog = build(name, pid, current, notes);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        return dialog.showAndWait();
    }

    /**
     * The dialog; its result is the settings the controls hold when Set (or Allow termination) is
     * pressed, and none when it is cancelled. The controls carry ids for tests: {@code oom-managed},
     * {@code oom-priority-FIRST}, {@code oom-action-SUSPEND}, and so on.
     */
    static Dialog<OomManagement> build(
            String name, long pid, OomManagement current, Map<EmergencyAction, String> notes) {
        Dialog<OomManagement> dialog = new Dialog<>();
        dialog.setTitle("OOM management");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control(), Stylesheets.oom());
        pane.getStyleClass().addAll("cpu-limit-dialog", "oom-management-dialog");

        Label heading = new Label("OOM management for this process");
        heading.getStyleClass().addAll("text-section-title", "cpu-limit-heading");
        Label processName = new Label(name.isBlank() ? "Process" : name);
        processName.setMinWidth(0);
        processName.setTextOverrun(OverrunStyle.ELLIPSIS);
        CutTextTooltip.install(processName);
        Label pidLabel = new Label("PID " + pid);
        pidLabel.getStyleClass().addAll("text-small", "text-muted");
        pidLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox process = new HBox(processName, pidLabel);
        process.getStyleClass().add("cpu-limit-process");

        CheckBox managed = new CheckBox("OOM management on");
        managed.setId("oom-managed");
        managed.setSelected(current.managed());

        Label priorityCaption = new Label("Priority");
        priorityCaption.getStyleClass().addAll("text-small", "text-muted", "oom-dialog-caption");
        ToggleGroup priorityGroup = new ToggleGroup();
        Map<OomPriority, RadioButton> priorities = new EnumMap<>(OomPriority.class);
        HBox priorityRow = new HBox();
        priorityRow.getStyleClass().add("oom-dialog-priorities");
        for (OomPriority priority : OomPriority.values()) {
            RadioButton choice = new RadioButton(
                    switch (priority) {
                        case FIRST -> "First";
                        case NORMAL -> "Normal";
                        case LAST -> "Last";
                    });
            choice.setToggleGroup(priorityGroup);
            choice.setId("oom-priority-" + priority.name());
            choice.setSelected(priority == current.priority());
            choice.disableProperty().bind(managed.selectedProperty().not());
            priorities.put(priority, choice);
            priorityRow.getChildren().add(choice);
        }
        priorityCaption.setLabelFor(priorityRow.getChildren().get(0));

        Label actionCaption = new Label("Emergency action");
        actionCaption.getStyleClass().addAll("text-small", "text-muted", "oom-dialog-caption");
        ToggleGroup actionGroup = new ToggleGroup();
        Map<EmergencyAction, RadioButton> actions = new EnumMap<>(EmergencyAction.class);
        VBox actionList = new VBox();
        actionList.getStyleClass().add("oom-dialog-actions");
        for (EmergencyAction action : EmergencyAction.values()) {
            RadioButton choice = new RadioButton(
                    switch (action) {
                        case NOTIFY -> "Notify only (default)";
                        case LOWER_PRIORITY -> "Lower priority";
                        case SUSPEND -> "Suspend";
                        case TERMINATE -> "Terminate (force kill)";
                    });
            choice.setToggleGroup(actionGroup);
            choice.setId("oom-action-" + action.name());
            choice.setSelected(action == current.action());
            choice.disableProperty().bind(managed.selectedProperty().not());
            Label note = new Label(notes.getOrDefault(action, ""));
            note.getStyleClass().addAll("text-small", "text-muted", "oom-dialog-note");
            note.setWrapText(true);
            note.setMinHeight(Region.USE_PREF_SIZE);
            note.disableProperty().bind(managed.selectedProperty().not());
            choice.setAccessibleHelp(note.getText());
            actions.put(action, choice);
            actionList.getChildren().addAll(choice, note);
        }
        actionCaption.setLabelFor(actions.get(EmergencyAction.NOTIFY));

        Label consequence = new Label(TERMINATE_MEANS);
        consequence.getStyleClass().addAll("confirm-consequence", "oom-terminate-consequence");
        consequence.setWrapText(true);
        consequence.setMinHeight(Region.USE_PREF_SIZE);
        var terminating = Bindings.createBooleanBinding(
                () -> managed.isSelected()
                        && actions.get(EmergencyAction.TERMINATE).isSelected(),
                managed.selectedProperty(),
                actions.get(EmergencyAction.TERMINATE).selectedProperty());
        consequence.visibleProperty().bind(terminating);
        consequence.managedProperty().bind(consequence.visibleProperty());
        // The sentence comes and goes with the choice: the window follows, so that its buttons, the
        // default Cancel among them, never fall off its bottom (found by the Windows acceptance run).
        consequence.managedProperty().addListener((observable, before, after) -> {
            if (dialog.isShowing() && pane.getScene() != null && pane.getScene().getWindow() != null) {
                pane.getScene().getWindow().sizeToScene();
            }
        });

        VBox content = new VBox(
                heading, process, managed, priorityCaption, priorityRow, actionCaption, actionList, consequence);
        content.getStyleClass().addAll("cpu-limit-content", "oom-dialog-content");
        pane.setContent(content);

        ButtonType set = new ButtonType("Set", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(set, cancel);
        Button setButton = (Button) pane.lookupButton(set);
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setCancelButton(true);
        // OOM-9: choosing termination is its own explicit act; the default never allows it.
        setButton
                .textProperty()
                .bind(Bindings.when(terminating).then("Allow termination").otherwise("Set"));
        // Renamed while the dialog shows: sized by its own words, not by the bar's uniform width of "Set",
        // so that "Allow termination" is never cut (found by the Windows acceptance run).
        ButtonBar.setButtonUniformSize(setButton, false);
        setButton.setMinWidth(Region.USE_PREF_SIZE);
        setButton.defaultButtonProperty().bind(terminating.not());
        cancelButton.defaultButtonProperty().bind(terminating);
        // Styling only: while it allows termination the act is the destructive button of an act that
        // cannot be undone, beside the default Cancel; otherwise it is the default, filled with the
        // accent (app.css).
        terminating.addListener((observable, before, after) -> showAsTermination(setButton, after));
        showAsTermination(setButton, terminating.get());
        dialog.setOnShown(event -> managed.requestFocus());
        dialog.setResultConverter(answer -> answer == set ? chosen(managed, priorities, actions) : null);
        return dialog;
    }

    private static void showAsTermination(Button button, boolean termination) {
        if (termination) {
            if (!button.getStyleClass().contains("confirm-act")) {
                button.getStyleClass().addAll("confirm-act", "confirm-act-lasting");
            }
        } else {
            button.getStyleClass().removeAll("confirm-act", "confirm-act-lasting");
        }
    }

    /** What the controls say now. */
    private static OomManagement chosen(
            CheckBox managed, Map<OomPriority, RadioButton> priorities, Map<EmergencyAction, RadioButton> actions) {
        OomPriority priority = priorities.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(OomPriority.NORMAL);
        EmergencyAction action = actions.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(EmergencyAction.NOTIFY);
        return new OomManagement(managed.isSelected(), priority, action);
    }
}
