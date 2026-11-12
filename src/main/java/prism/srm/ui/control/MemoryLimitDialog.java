package prism.srm.ui.control;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.UnaryOperator;
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
import prism.srm.domain.ManagedSettings;
import prism.srm.ui.Stylesheets;

/**
 * Asks for the memory limit of one process (LIM-2, LIM-5, milestone 10): the memory it may commit,
 * in MiB or GiB, with what reaching it means said before it is set. Cancel is the default button: a
 * process that reaches the limit may fail (docs/UI_GUIDE.md, destructive confirmations). Enter in
 * the field neither cancels nor sets: it says how to set the limit, or what is wrong with the value;
 * Escape cancels.
 */
@FunctionalInterface
public interface MemoryLimitDialog {

    /**
     * What a watched memory limit is (LIM-9, CAP-3): said under the field, every time, where no native
     * limit is available or the user chose the watched one.
     */
    String WATCHED_MEANS = "Not a cap: Prism watches the resident memory of the process, shared pages included,"
            + " and when it goes above the limit OOM Guard applies the emergency action of the process, as far as its"
            + " automatic intervention switches allow and only while OOM management is on for it. A process it"
            + " suspends keeps its memory, so such a hold is usually ended by you. A limit below what the process"
            + " uses now acts at the next listing.";

    /** What a memory limit is: said under the field, every time. */
    String MEANS = "Caps the memory the process may commit, not what it has in RAM. A process that reaches the cap"
            + " cannot allocate memory; most programs then fail or crash, and Prism cannot undo that. The child"
            + " processes it starts afterwards get the same cap each.";

    /**
     * The limit the user chose, in bytes, or empty if they cancelled.
     *
     * @param current the limit the process has now, to start from
     * @param inUse what the process has now, in words, to compare with; empty when not known
     * @param watched whether the limit is watched resident memory rather than a native cap
     */
    OptionalLong ask(String name, long pid, OptionalLong current, String inUse, boolean watched, Node owner);

    /**
     * The bytes typed: a whole number of MiB ("256", "256 MiB") or GiB ("2 GiB"), at least 1 MiB.
     * Case is ignored without a locale.
     */
    static OptionalLong parse(String text) {
        if (text == null) {
            return OptionalLong.empty();
        }
        String trimmed = text.strip().toLowerCase(Locale.ROOT);
        long unit = 1L << 20;
        if (trimmed.endsWith("gib") || trimmed.endsWith("gb") || trimmed.endsWith("g")) {
            unit = 1L << 30;
            trimmed = trimmed.replaceAll("(gib|gb|g)$", "").strip();
        } else if (trimmed.endsWith("mib") || trimmed.endsWith("mb") || trimmed.endsWith("m")) {
            trimmed = trimmed.replaceAll("(mib|mb|m)$", "").strip();
        }
        if (trimmed.isEmpty() || trimmed.length() > 7 || !trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        long bytes = Long.parseLong(trimmed) * unit;
        return ManagedSettings.validMemoryBytes(bytes) ? OptionalLong.of(bytes) : OptionalLong.empty();
    }

    /** The dialog itself, modal to the window of the owner. Call on the JavaFX Application Thread. */
    static OptionalLong showDialog(
            String name, long pid, OptionalLong current, String inUse, boolean watched, Node owner) {
        Dialog<ButtonType> dialog = build(name, pid, current, inUse, watched);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        ButtonType set = dialog.getDialogPane().getButtonTypes().get(0);
        TextField field = (TextField) dialog.getDialogPane().lookup(".memory-limit-field");
        return dialog.showAndWait().filter(set::equals).isPresent() ? parse(field.getText()) : OptionalLong.empty();
    }

    /** Builds the dialog of a native limit without showing it; for tests. */
    static Dialog<ButtonType> build(String name, long pid, OptionalLong current, String inUse) {
        return build(name, pid, current, inUse, false);
    }

    /** Builds the dialog without showing it; for {@link #showDialog} and for tests. */
    static Dialog<ButtonType> build(String name, long pid, OptionalLong current, String inUse, boolean watched) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Set memory limit");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control());
        pane.getStyleClass().addAll("cpu-limit-dialog", "memory-limit-dialog");

        Label heading = new Label(
                watched ? "Watch the resident memory of this process" : "Limit the memory this process may commit");
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

        String start = current.isPresent()
                ? (current.getAsLong() % (1L << 30) == 0
                        ? current.getAsLong() / (1L << 30) + " GiB"
                        : current.getAsLong() / (1L << 20) + "")
                : "";
        TextField field = new TextField(start);
        field.getStyleClass().addAll("cpu-limit-field", "memory-limit-field");
        field.setPrefColumnCount(8);
        UnaryOperator<TextFormatter.Change> limited =
                change -> change.getControlNewText().length() <= 12 ? change : null;
        field.setTextFormatter(new TextFormatter<>(limited));
        Label unit = new Label("MiB, or GiB with \"GiB\"; at least 1 MiB");
        unit.getStyleClass().addAll("text-small", "text-muted");
        // A screen reader takes the name of a text field from its label and reads its accessible text as
        // its value, so the name is a label that takes no room and is not drawn, as the filter's is.
        Label spoken = new Label("Memory limit in MiB, or in GiB when followed by GiB");
        spoken.setLabelFor(field);
        spoken.setManaged(false);
        spoken.setVisible(false);
        HBox entry = new HBox(field, unit, spoken);
        entry.getStyleClass().add("cpu-limit-entry");

        Label now = new Label(inUse.isEmpty() ? "" : "In use now: " + inUse + ".");
        now.getStyleClass().addAll("text-small", "text-muted");
        now.setVisible(!inUse.isEmpty());
        now.setManaged(!inUse.isEmpty());

        Label meaning = new Label(watched ? WATCHED_MEANS : MEANS);
        meaning.getStyleClass().addAll("text-small", "cpu-limit-means", "memory-limit-warning");
        meaning.setWrapText(true);
        meaning.setMinHeight(Region.USE_PREF_SIZE);

        Label hint = new Label("Enter a whole number of MiB, or of GiB followed by GiB.");
        hint.getStyleClass().addAll("text-small", "text-muted", "cpu-limit-hint");
        hint.visibleProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> !field.getText().isBlank()
                                && parse(field.getText()).isEmpty(),
                        field.textProperty()));
        hint.managedProperty().bind(hint.visibleProperty());

        // Enter in the field sets nothing and loses nothing: it says how to set the limit (Cancel
        // stays the default button, so that Enter never sets a limit that may make the process fail).
        Label pressSet = new Label("Press Set limit to apply it, or Escape to cancel.");
        pressSet.getStyleClass().addAll("text-small", "text-muted", "memory-limit-press-set");
        pressSet.setVisible(false);
        pressSet.managedProperty().bind(pressSet.visibleProperty());
        field.textProperty().addListener((observable, before, after) -> pressSet.setVisible(false));
        // Both lines come and go with what is typed: the window follows, so that its buttons, the
        // default Cancel among them, never fall off its bottom (as the OOM dialogs do since milestone 11).
        javafx.beans.value.ChangeListener<Boolean> followLines = (observable, before, after) -> {
            if (dialog.isShowing() && pane.getScene() != null && pane.getScene().getWindow() != null) {
                pane.getScene().getWindow().sizeToScene();
            }
        };
        hint.managedProperty().addListener(followLines);
        pressSet.managedProperty().addListener(followLines);

        VBox content = new VBox(heading, process, entry, hint, pressSet, now, meaning);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType set = new ButtonType("Set limit", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(set, cancel);
        Button setButton = (Button) pane.lookupButton(set);
        setButton.setDefaultButton(false);
        // Styling only: the act is the one filled button; Cancel stays the default (app.css).
        setButton.getStyleClass().add("primary");
        setButton
                .disableProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> parse(field.getText()).isEmpty(), field.textProperty()));
        Button cancelButton = (Button) pane.lookupButton(cancel);
        // Cancel is the default: Enter does not set a limit that may make the process fail.
        cancelButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                cancelButton.fire();
            } else if (event.getCode() == KeyCode.ENTER) {
                // Not the default button: the typed value would be lost (final M10 audit).
                event.consume();
                pressSet.setVisible(parse(field.getText()).isPresent());
            }
        });
        dialog.setOnShown(event -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog;
    }
}
