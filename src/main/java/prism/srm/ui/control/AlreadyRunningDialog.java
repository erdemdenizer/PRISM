package prism.srm.ui.control;

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
 * Says why this Prism does not start: another runs for this user, or it cannot make sure that none
 * does (milestone 10, one Prism per user profile). One button, Close, the default: nothing else can
 * be done here, and nothing of the other Prism is touched.
 */
public final class AlreadyRunningDialog {

    private AlreadyRunningDialog() {}

    /** Builds the dialog without showing it; for the application and for tests. */
    public static Dialog<ButtonType> build(String reason) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Prism");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control());
        pane.getStyleClass().addAll("cpu-limit-dialog", "already-running-dialog");

        Label heading = new Label("Prism does not start");
        heading.getStyleClass().addAll("text-section-title", "cpu-limit-heading");
        Label body = new Label(reason);
        body.getStyleClass().addAll("text-small", "cpu-limit-means", "already-running-reason");
        body.setWrapText(true);
        body.setMinHeight(Region.USE_PREF_SIZE);
        body.setPrefWidth(420);

        VBox content = new VBox(heading, body);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType close = new ButtonType("Close", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(close);
        Button closeButton = (Button) pane.lookupButton(close);
        closeButton.setDefaultButton(true);
        closeButton.setCancelButton(true);
        return dialog;
    }
}
