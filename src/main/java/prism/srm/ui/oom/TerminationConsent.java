package prism.srm.ui.oom;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.ui.Stylesheets;

/**
 * Asks before the global switch of emergency termination is turned on (OOM-9): what it lets OOM Guard
 * do, in the text color; "Allow termination" or Cancel, which is the default and has the focus.
 */
@FunctionalInterface
interface TerminationConsent {

    String QUESTION = "Allow OOM Guard to force-kill processes marked for termination?";
    String CONSEQUENCE = "In an Emergency OOM Guard force-kills, without asking, a managed process whose emergency"
            + " action is Terminate, at most once per process in a session. Work it has not saved is lost.";

    boolean confirm(Node owner);

    static boolean showDialog(Node owner) {
        Dialog<ButtonType> dialog = build();
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType allow = dialog.getDialogPane().getButtonTypes().get(0);
        return dialog.showAndWait().filter(allow::equals).isPresent();
    }

    static Dialog<ButtonType> build() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Emergency termination");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control(), Stylesheets.oom());
        pane.getStyleClass().addAll("confirm-dialog", "termination-consent");
        Label question = new Label(QUESTION);
        question.getStyleClass().addAll("text-section-title", "confirm-question");
        question.setWrapText(true);
        question.setMinHeight(Region.USE_PREF_SIZE);
        Label consequence = new Label(CONSEQUENCE);
        consequence.getStyleClass().addAll("confirm-consequence", "oom-terminate-consequence");
        consequence.setWrapText(true);
        consequence.setMinHeight(Region.USE_PREF_SIZE);
        VBox content = new VBox(question, consequence);
        content.getStyleClass().addAll("confirm-content", "oom-consent-content");
        pane.setContent(content);
        ButtonType allow = new ButtonType("Allow termination", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(allow, cancel);
        Button allowButton = (Button) pane.lookupButton(allow);
        allowButton.setDefaultButton(false);
        // Styling only: allowing termination is a destructive act that cannot be undone; Cancel stays the default.
        allowButton.getStyleClass().addAll("confirm-act", "confirm-act-lasting");
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        dialog.setOnShown(event -> cancelButton.requestFocus());
        return dialog;
    }
}
