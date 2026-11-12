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
import prism.srm.domain.FallbackOffer;
import prism.srm.ui.Stylesheets;

/**
 * The explicit consent to the emulated CPU limit after the native one was not applied (MILESTONES
 * §10): why the native limit was not applied, what the emulated one is and what it costs, and how
 * many may run here. Cancel is the default; nothing runs unless the user chooses the emulated limit.
 */
@FunctionalInterface
public interface FallbackOfferDialog {

    /** Whether the user accepts the emulated limit. */
    boolean accept(String name, long pid, FallbackOffer offer, Node owner);

    /** What the offer says, in words: the reason, the mechanism, the cost and the bound. */
    static String text(FallbackOffer offer) {
        String cost = offer.perThreadSuspension()
                ? " It can cost Prism about a tenth of one logical processor while it holds the process back,"
                        + " because every pause lists the threads of the whole system."
                : "";
        return "The native limit was not applied: " + offer.reason() + ". The emulated limit stops and continues the"
                + " process in short cycles; it is approximate and works only while Prism runs." + cost
                + " At most " + offer.mostLimits()
                + (offer.mostLimits() == 1 ? " emulated limit runs" : " emulated limits run")
                + " at once on this computer.";
    }

    static boolean showDialog(String name, long pid, FallbackOffer offer, Node owner) {
        Dialog<ButtonType> dialog = build(name, pid, offer);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType use = dialog.getDialogPane().getButtonTypes().get(0);
        return dialog.showAndWait().filter(use::equals).isPresent();
    }

    static Dialog<ButtonType> build(String name, long pid, FallbackOffer offer) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Use the emulated CPU limit?");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control());
        pane.getStyleClass().addAll("cpu-limit-dialog", "fallback-dialog");

        Label heading = new Label("Use the emulated CPU limit instead?");
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

        Label body = new Label(text(offer));
        body.getStyleClass().addAll("text-small", "cpu-limit-means");
        body.setWrapText(true);
        body.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(heading, process, body);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType use = new ButtonType("Use emulated limit", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(use, cancel);
        Button useButton = (Button) pane.lookupButton(use);
        useButton.setDefaultButton(false);
        // Styling only: the act is the one filled button; Cancel stays the default (app.css).
        useButton.getStyleClass().add("primary");
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        dialog.setOnShown(event -> cancelButton.requestFocus());
        return dialog;
    }
}
