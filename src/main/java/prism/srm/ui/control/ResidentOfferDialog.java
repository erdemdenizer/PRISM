package prism.srm.ui.control;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.ui.Stylesheets;

/**
 * Asks whether Prism may watch the resident memory of a process whose native memory limit could not
 * be applied (LIM-9, plan O9: offered, never assumed). It says why the native limit was not applied
 * and that the watched limit is not a cap. Cancel is the default; nothing is watched unless the user
 * chooses it.
 */
@FunctionalInterface
public interface ResidentOfferDialog {

    /** Whether the user accepts the watched resident memory. */
    boolean accept(String name, long pid, String reason, Node owner);

    /** What the offer says, in words. */
    static String text(String reason) {
        return "The native memory limit was not applied: " + reason + ". Prism can watch the resident memory of the"
                + " process instead. That is not a cap: the process may use more. When it goes above the limit, OOM"
                + " Guard applies the emergency action of the process, as far as its automatic intervention switches"
                + " allow and only while OOM management is on for it.";
    }

    /** Said apart from the offer: what a hold of the policy means for the user. */
    static final String HOLD = "A suspended process keeps its memory: you usually end such a hold.";

    static boolean showDialog(String name, long pid, String reason, Node owner) {
        Dialog<ButtonType> dialog = build(name, pid, reason);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType watch = dialog.getDialogPane().getButtonTypes().get(0);
        return dialog.showAndWait().filter(watch::equals).isPresent();
    }

    static Dialog<ButtonType> build(String name, long pid, String reason) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Watch the resident memory?");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control());
        pane.getStyleClass().addAll("cpu-limit-dialog", "fallback-dialog");

        Label heading = new Label("Watch the resident memory instead?");
        heading.getStyleClass().addAll("text-section-title", "cpu-limit-heading");
        heading.setWrapText(true);
        Label processName = new Label(name.isBlank() ? "Process" : name);
        processName.setMinWidth(0);
        processName.setTextOverrun(OverrunStyle.ELLIPSIS);
        CutTextTooltip.install(processName);
        Label pidLabel = new Label("PID " + pid);
        pidLabel.getStyleClass().addAll("text-small", "text-muted");
        pidLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox process = new HBox(processName, pidLabel);
        process.getStyleClass().add("cpu-limit-process");

        Label body = new Label(text(reason));
        body.getStyleClass().addAll("text-small", "cpu-limit-means");
        body.setWrapText(true);
        body.setMinHeight(Region.USE_PREF_SIZE);

        Label hold = new Label(HOLD);
        hold.getStyleClass().addAll("text-small", "cpu-limit-means");
        hold.setWrapText(true);
        hold.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(heading, process, body, hold);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType watch = new ButtonType("Watch resident memory", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(watch, cancel);
        Button watchButton = (Button) pane.lookupButton(watch);
        watchButton.setDefaultButton(false);
        // Styling only: the act is the one filled button; Cancel stays the default (app.css).
        watchButton.getStyleClass().add("primary");
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        dialog.setOnShown(event -> cancelButton.requestFocus());
        return dialog;
    }
}
