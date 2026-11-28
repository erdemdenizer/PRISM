package prism.srm.ui.control;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.NativeConsentOffer;
import prism.srm.domain.OperationResult;
import prism.srm.ui.Stylesheets;

/**
 * The explicit consent a native limit needs before PRISM asks anything of the system (LIM-6, ADR-0016 decisions 4 and
 * 6): on Linux, moving the process into a systemd scope PRISM creates, which cannot be undone while it runs, and,
 * separately, a hard memory limit under which the kernel may terminate it. Every statement of the offer is listed as
 * the limiter fixed it. The confirming button names the act; Cancel is the default, and nothing changes unless the user
 * confirms. The consent is for this one request about this one process.
 */
@FunctionalInterface
public interface NativeConsentDialog {

    /** What a declined consent changed: nothing, said on the line the answer would have used. */
    static LimitResult declined(LimitRequest request) {
        String target = request.name() + " (PID " + request.identity().pid() + ")";
        return new LimitResult(
                request,
                ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "consent declined"),
                "Nothing was changed for " + target + ".",
                "The consent was not given, so PRISM moved nothing and set no native limit.");
    }

    /**
     * The gap between two statements, set here rather than in the style sheet so that the window is sized for it
     * before the style sheet is applied (a 4/8 px grid step; docs/UI_GUIDE.md, Limits page).
     */
    double STATEMENT_GAP = 8;

    /** Whether the user gives the consents the offer names. */
    boolean accept(String name, long pid, NativeConsentOffer offer, Node owner);

    /** The confirming button: the act it starts, never a bare "OK". */
    static String confirmLabel(NativeConsentOffer offer) {
        if (offer.adoption()) {
            return offer.hardMemory() ? "Move and set limits" : "Move and limit";
        }
        return "Set hard limit";
    }

    /** The heading of the dialog, as a question about the act. */
    static String heading(NativeConsentOffer offer) {
        if (offer.adoption()) {
            return offer.hardMemory()
                    ? "Move this process into a systemd scope and set a hard memory limit?"
                    : "Move this process into a systemd scope to limit it?";
        }
        return "Set a hard memory limit on this process?";
    }

    /** What the user confirms, before the statements; empty where the one statement stands alone. */
    static String lead(NativeConsentOffer offer) {
        if (alone(offer)) {
            return "";
        }
        return offer.adoption()
                ? "PRISM limits it through the Linux kernel. Confirm that you accept this:"
                : "Confirm that you accept the following:";
    }

    /** One statement without a move: said as the body, not as a list of one (UI review). */
    private static boolean alone(NativeConsentOffer offer) {
        return !offer.adoption() && offer.statements().size() == 1;
    }

    static boolean showDialog(String name, long pid, NativeConsentOffer offer, Node owner) {
        Dialog<ButtonType> dialog = build(name, pid, offer);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType confirm = dialog.getDialogPane().getButtonTypes().get(0);
        return dialog.showAndWait().filter(confirm::equals).isPresent();
    }

    static Dialog<ButtonType> build(String name, long pid, NativeConsentOffer offer) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(offer.adoption() ? "Move into a systemd scope?" : "Set a hard memory limit?");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control());
        pane.getStyleClass().addAll("cpu-limit-dialog", "confirm-dialog", "native-consent-dialog");

        Label title = new Label(heading(offer));
        title.getStyleClass().addAll("text-section-title", "cpu-limit-heading");
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        Label processName = new Label(name.isBlank() ? "Process" : name);
        processName.setMinWidth(0);
        processName.setTextOverrun(OverrunStyle.ELLIPSIS);
        CutTextTooltip.install(processName);
        Label pidLabel = new Label("PID " + pid);
        pidLabel.getStyleClass().addAll("text-small", "text-muted");
        pidLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox process = new HBox(processName, pidLabel);
        process.getStyleClass().add("cpu-limit-process");

        List<Node> body = new ArrayList<>();
        if (alone(offer)) {
            Label statement = new Label(offer.statements().get(0));
            statement.getStyleClass().addAll("text-small", "cpu-limit-means", "native-consent-statement");
            statement.setWrapText(true);
            statement.setMinHeight(Region.USE_PREF_SIZE);
            body.add(statement);
        } else {
            Label lead = new Label(lead(offer));
            lead.getStyleClass().addAll("text-small", "cpu-limit-means");
            lead.setWrapText(true);
            lead.setMinHeight(Region.USE_PREF_SIZE);
            List<Node> lines = new ArrayList<>();
            for (String statement : offer.statements()) {
                lines.add(statementLine(statement));
            }
            VBox statements = new VBox(STATEMENT_GAP);
            statements.getChildren().setAll(lines);
            statements.getStyleClass().add("native-consent-statements");
            body.add(lead);
            body.add(statements);
        }

        Label scope = new Label("This consent is for this process and this limit only.");
        scope.getStyleClass().addAll("text-small", "text-muted");
        scope.setWrapText(true);
        scope.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(title, process);
        content.getChildren().addAll(body);
        content.getChildren().add(scope);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType confirm = new ButtonType(confirmLabel(offer), ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(confirm, cancel);
        Button confirmButton = (Button) pane.lookupButton(confirm);
        confirmButton.setDefaultButton(false);
        // The act cannot be undone (the move is one-way; under a hard memory limit the kernel may terminate the
        // process): a plain button with its words in the danger color, never a fill that outweighs the default Cancel,
        // as in the consent to termination (app.css). Where the kernel may terminate, its edge is in that color too.
        confirmButton.getStyleClass().add("confirm-act");
        if (offer.hardMemory()) {
            confirmButton.getStyleClass().add("confirm-act-lasting");
        }
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        dialog.setOnShown(event -> cancelButton.requestFocus());
        return dialog;
    }

    /** One statement, with a dash before it and its lines wrapped beside the dash, not under it. */
    private static Node statementLine(String statement) {
        Label dash = new Label("–");
        dash.getStyleClass().addAll("text-small", "text-muted", "native-consent-dash");
        dash.setMinWidth(Region.USE_PREF_SIZE);
        Label text = new Label(statement);
        text.getStyleClass().addAll("text-small", "native-consent-statement");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox line = new HBox(dash, text);
        line.getStyleClass().add("native-consent-line");
        // A screen reader reads the statement, not the dash before it.
        line.setAccessibleText(statement);
        return line;
    }
}
