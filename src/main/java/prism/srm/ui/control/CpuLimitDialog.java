package prism.srm.ui.control;

import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.ManagedSettings;
import prism.srm.ui.Stylesheets;

/**
 * Asks for the CPU limit of one process (LIM-2): a whole percentage of the whole machine, 1 to 100,
 * with what the limit is on this system said before it is set (CAP-5). The Processes page and the
 * Limits page ask through it; a test answers in its place.
 */
@FunctionalInterface
public interface CpuLimitDialog {

    /**
     * The limit the user chose, or empty if they cancelled.
     *
     * @param current the limit the process has now, to start from
     * @param means what the limit is on this system, said under the field
     */
    OptionalInt ask(String name, long pid, OptionalInt current, String means, Node owner);

    /** What a CPU limit is on this system, said under the field (CAP-5): never more than it is. */
    static String means(LimitCapability capability) {
        if (!capability.available()) {
            return "Not available here: " + capability.reason().orElse("") + ".";
        }
        if (capability.mechanism().filter(ControlMechanism.JOB_OBJECT::equals).isPresent()) {
            return "Native hard: the system caps the process and the child processes it starts afterwards at this"
                    + " share of the whole machine. The process stays in its job until it exits; removing the limit"
                    + " clears it. If Prism is ended without closing, the limit stays until the process exits.";
        }
        return "Emulated: Prism stops and continues the process in short cycles, so the limit is approximate."
                + " It cannot hold a process below about 5 % of what it would use.";
    }

    /** The percentage typed, if it is a whole number from 1 to 100. */
    static OptionalInt parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 3) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return OptionalInt.empty();
            }
        }
        int value = Integer.parseInt(text);
        return ManagedSettings.validCpuPercent(value) ? OptionalInt.of(value) : OptionalInt.empty();
    }

    /** The dialog itself, modal to the window of the owner. Call on the JavaFX Application Thread. */
    static OptionalInt showDialog(String name, long pid, OptionalInt current, String means, Node owner) {
        Dialog<ButtonType> dialog = build(name, pid, current, means);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            // The typeface the window fell back to, if it did: the dialog reads like the window.
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType set = dialog.getDialogPane().getButtonTypes().get(0);
        TextField field = (TextField) dialog.getDialogPane().lookup(".cpu-limit-field");
        return dialog.showAndWait().filter(set::equals).isPresent() ? parse(field.getText()) : OptionalInt.empty();
    }

    /** Builds the dialog without showing it; for {@link #showDialog} and for tests. */
    static Dialog<ButtonType> build(String name, long pid, OptionalInt current, String means) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Set CPU limit");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control());
        pane.getStyleClass().add("cpu-limit-dialog");

        Label heading = new Label("Limit the CPU this process may use");
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

        TextField field = new TextField(current.isPresent() ? Integer.toString(current.getAsInt()) : "");
        field.getStyleClass().add("cpu-limit-field");
        field.setPrefColumnCount(4);
        // Digits only, three at most: anything else is not a percentage from 1 to 100.
        UnaryOperator<TextFormatter.Change> digits = change -> {
            String next = change.getControlNewText();
            return next.length() <= 3 && next.chars().allMatch(c -> c >= '0' && c <= '9') ? change : null;
        };
        field.setTextFormatter(new TextFormatter<>(digits));
        Label unit = new Label("% of the whole machine, 1 to 100");
        unit.getStyleClass().addAll("text-small", "text-muted");
        // A screen reader takes the name of a text field from its label and reads its accessible text as
        // its value, so the name is a label that takes no room and is not drawn, as the filter's is.
        Label spoken = new Label("CPU limit in percent of the whole machine, 1 to 100");
        spoken.setLabelFor(field);
        spoken.setManaged(false);
        spoken.setVisible(false);
        HBox entry = new HBox(field, unit, spoken);
        entry.getStyleClass().add("cpu-limit-entry");

        Label meaning = new Label(means);
        meaning.getStyleClass().addAll("text-small", "text-muted", "cpu-limit-means");
        meaning.setWrapText(true);
        meaning.setMinHeight(Region.USE_PREF_SIZE);

        // Why Set limit is disabled, while what is typed is no limit: said, not left to be guessed.
        Label hint = new Label("Enter a whole number from 1 to 100.");
        hint.getStyleClass().addAll("text-small", "text-muted", "cpu-limit-hint");
        hint.visibleProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> !field.getText().isEmpty()
                                && parse(field.getText()).isEmpty(),
                        field.textProperty()));
        hint.managedProperty().bind(hint.visibleProperty());
        // The hint comes and goes with what is typed: the window follows, so that its buttons, Cancel
        // among them, never fall off its bottom (as the OOM dialogs do since milestone 11).
        hint.managedProperty().addListener((observable, before, after) -> {
            if (dialog.isShowing() && pane.getScene() != null && pane.getScene().getWindow() != null) {
                pane.getScene().getWindow().sizeToScene();
            }
        });

        VBox content = new VBox(heading, process, entry, hint, meaning);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType set = new ButtonType("Set limit", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(set, cancel);
        Button setButton = (Button) pane.lookupButton(set);
        setButton.setDefaultButton(true);
        setButton
                .disableProperty()
                .bind(Bindings.createBooleanBinding(() -> parse(field.getText()).isEmpty(), field.textProperty()));
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setCancelButton(true);
        // A field with a formatter takes Escape for itself (it would only undo the typing), so the
        // dialog never heard it and stayed open: Escape cancels the dialog from the field too.
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                cancelButton.fire();
            }
        });
        dialog.setOnShown(event -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog;
    }
}
