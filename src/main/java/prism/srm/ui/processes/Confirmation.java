package prism.srm.ui.processes;

import java.util.Locale;
import java.util.Optional;
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
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlRequest;
import prism.srm.ui.Stylesheets;
import prism.srm.ui.control.CutTextTooltip;

/**
 * Asks the user to confirm End Task and Force Kill, naming the process and its PID (CTL-8). The two
 * are told apart in words: one asks the application, the other ends it at once. Cancel is the
 * default, so Enter and Escape both decline, and the destructive choice is a plain button that must
 * be chosen on purpose.
 *
 * <p>The process stands on a line of its own under the question: its name, cut with an ellipsis
 * where it is too long for the dialog and whole in a tooltip, and its PID beside it, which is never
 * cut. Only what is drawn is cut; a screen reader is given the whole question.
 *
 * <p>End task is worded after its mechanism (CAP-5): a close request asks the application to
 * close; a termination signal asks the process to terminate. Without a known mechanism the words
 * of a close request are used.
 */
@FunctionalInterface
interface Confirmation {

    /**
     * Whether the user confirmed the request; {@code mechanism} is what carries the action out on
     * this system, {@code owner} the node the dialog belongs to.
     */
    boolean confirm(ControlRequest request, Optional<ControlMechanism> mechanism, Node owner);

    /** Whether an action asks for confirmation. */
    static boolean needed(ControlAction action) {
        return action == ControlAction.END_TASK || action == ControlAction.FORCE_KILL;
    }

    /** The heading of the dialog; the process is named on the line below it. */
    static String heading(ControlAction action, Optional<ControlMechanism> mechanism) {
        return switch (action) {
            case END_TASK -> signal(mechanism) ? "Ask this process to terminate?" : "Ask this process to close?";
            case FORCE_KILL -> "Force this process to quit?";
            default -> throw new IllegalArgumentException("Not confirmed: " + action);
        };
    }

    /** The whole question, with the name of the process and its PID; for a screen reader. */
    static String question(ControlRequest request, Optional<ControlMechanism> mechanism) {
        String target = String.format(
                Locale.ROOT, "%s (PID %d)", request.name(), request.identity().pid());
        return switch (request.action()) {
            case END_TASK -> "Ask " + target + (signal(mechanism) ? " to terminate?" : " to close?");
            case FORCE_KILL -> "Force " + target + " to quit?";
            default -> throw new IllegalArgumentException("Not confirmed: " + request.action());
        };
    }

    /** What happens, in plain words. */
    static String consequence(ControlAction action, Optional<ControlMechanism> mechanism) {
        return switch (action) {
            case END_TASK ->
                signal(mechanism)
                        ? "The process decides whether to end. It may save its work first, or ignore the request."
                        : "The application decides whether to close. It may ask to save its work, or ignore the"
                                + " request.";
            case FORCE_KILL -> "The process is terminated at once. Work it has not saved is lost.";
            default -> throw new IllegalArgumentException("Not confirmed: " + action);
        };
    }

    /** The label of the button that carries the action out. */
    static String confirmLabel(ControlAction action) {
        return switch (action) {
            case END_TASK -> "End task";
            case FORCE_KILL -> "Force kill";
            default -> throw new IllegalArgumentException("Not confirmed: " + action);
        };
    }

    private static boolean signal(Optional<ControlMechanism> mechanism) {
        return mechanism.filter(ControlMechanism.TERMINATION_SIGNAL::equals).isPresent();
    }

    /** The dialog itself, shown modal to the window of the owner. Call on the JavaFX Application Thread. */
    static boolean showDialog(ControlRequest request, Optional<ControlMechanism> mechanism, Node owner) {
        // The typeface the window fell back to, if it did: the dialog reads like the window.
        return show(build(request, mechanism), owner);
    }

    /** Builds the dialog of a close request without showing it; for tests. */
    static Dialog<ButtonType> build(ControlRequest request) {
        return build(request, Optional.empty());
    }

    /** Builds the dialog without showing it; for {@link #showDialog} and for tests. */
    static Dialog<ButtonType> build(ControlRequest request, Optional<ControlMechanism> mechanism) {
        return build(
                confirmLabel(request.action()),
                heading(request.action(), mechanism),
                question(request, mechanism),
                request.name(),
                request.identity().pid(),
                consequence(request.action(), mechanism),
                request.action() == ControlAction.FORCE_KILL);
    }

    /** Shows a dialog built by {@link #build(String, String, String, String, long, String, boolean)}; whether the user confirmed. */
    static boolean show(Dialog<ButtonType> dialog, Node owner) {
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType act = dialog.getDialogPane().getButtonTypes().get(0);
        return dialog.showAndWait().filter(act::equals).isPresent();
    }

    /**
     * A confirmation of the page: the question, the process on a line of its own, what follows
     * from it, and the action and Cancel, Cancel the default (CTL-8). Shared by End task, Force kill
     * and the Resume of a process an earlier session may have left suspended.
     *
     * @param lasting whether the consequence cannot be undone, which reads it in the text color and
     *     gives the act the edge of a lasting act
     */
    static Dialog<ButtonType> build(
            String act,
            String heading,
            String wholeQuestion,
            String processName,
            long pidNumber,
            String consequenceText,
            boolean lasting) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(act);
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.processes());
        pane.getStyleClass().add("confirm-dialog");

        Label question = new Label(heading);
        question.getStyleClass().addAll("text-section-title", "confirm-question");
        question.setWrapText(true);
        question.setAccessibleText(wholeQuestion);
        Label name = new Label(processName.isBlank() ? "Process" : processName);
        name.getStyleClass().add("confirm-name");
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        CutTextTooltip.install(name);
        Label pid = new Label("PID " + pidNumber);
        pid.getStyleClass().addAll("text-small", "text-muted", "confirm-pid");
        pid.setMinWidth(Region.USE_PREF_SIZE);
        HBox process = new HBox(name, pid);
        process.getStyleClass().add("confirm-process");
        Label consequence = new Label(consequenceText);
        consequence.getStyleClass().add("confirm-consequence");
        if (lasting) {
            consequence.getStyleClass().add("confirm-consequence-lasting");
        }
        consequence.setWrapText(true);
        VBox content = new VBox(question, process, consequence);
        content.getStyleClass().add("confirm-content");
        pane.setContent(content);

        ButtonType carryOut = new ButtonType(act, ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(carryOut, cancel);
        Button actButton = (Button) pane.lookupButton(carryOut);
        actButton.setDefaultButton(false);
        actButton.getStyleClass().add("confirm-act");
        if (lasting) {
            // Styling only: an act that cannot be undone weighs more than one that asks (app.css).
            actButton.getStyleClass().add("confirm-act-lasting");
        }
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        // The keyboard starts on Cancel: a stray Enter or Space declines.
        dialog.setOnShown(event -> cancelButton.requestFocus());
        return dialog;
    }
}
