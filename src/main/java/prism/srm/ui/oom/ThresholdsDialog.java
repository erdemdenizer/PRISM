package prism.srm.ui.oom;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.OomThresholds;
import prism.srm.ui.Stylesheets;

/**
 * Asks for the three thresholds of OOM Guard and its absolute floor (OOM-3, UI-5): whole percentages
 * of physical memory still available, and a number of MiB. Set thresholds is offered only for valid
 * numbers; what is wrong is said under the fields. Lowering a threshold starts no action by itself:
 * only switches the user turned on act.
 */
@FunctionalInterface
interface ThresholdsDialog {

    String ORDER = "Emergency must be below Critical, Critical below Warning, all between 1 and "
            + OomThresholds.MOST_PERCENT + ".";

    /** A field whose number is not taken, marked in the error color next to the rule it breaks (LO-16). */
    PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    /** The four fields of the dialog. */
    enum Field {
        WARNING,
        CRITICAL,
        EMERGENCY,
        FLOOR
    }

    /**
     * The fields that make the numbers invalid: one that is not a whole number or is out of its range,
     * and both fields of a pair out of order. Empty exactly when {@link #parse} takes the numbers.
     */
    static Set<Field> invalidFields(String warning, String critical, String emergency, String floorMiB) {
        Set<Field> invalid = EnumSet.noneOf(Field.class);
        Optional<Integer> w = number(warning, 3);
        Optional<Integer> c = number(critical, 3);
        Optional<Integer> e = number(emergency, 3);
        if (w.isEmpty() || w.get() > OomThresholds.MOST_PERCENT) {
            invalid.add(Field.WARNING);
        }
        if (c.isEmpty()) {
            invalid.add(Field.CRITICAL);
        }
        if (e.isEmpty() || e.get() < 1) {
            invalid.add(Field.EMERGENCY);
        }
        if (number(floorMiB, 7).isEmpty()) {
            invalid.add(Field.FLOOR);
        }
        if (c.isPresent() && w.isPresent() && c.get() >= w.get()) {
            invalid.add(Field.CRITICAL);
            invalid.add(Field.WARNING);
        }
        if (e.isPresent() && c.isPresent() && e.get() >= c.get()) {
            invalid.add(Field.EMERGENCY);
            invalid.add(Field.CRITICAL);
        }
        return invalid;
    }

    /**
     * The thresholds chosen, or empty if the user cancelled.
     *
     * @param totalBytes the physical memory, to say beforehand that a floor at or above half of it is
     *     ignored; empty when not known
     */
    Optional<OomThresholds> ask(OomThresholds current, Optional<Long> totalBytes, Node owner);

    static Optional<OomThresholds> showDialog(OomThresholds current, Optional<Long> totalBytes, Node owner) {
        Dialog<OomThresholds> dialog = build(current, totalBytes);
        if (owner.getScene() != null && owner.getScene().getWindow() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            if (owner.getScene().getStylesheets().contains(Stylesheets.fontsFallback())) {
                dialog.getDialogPane().getStylesheets().add(Stylesheets.fontsFallback());
            }
        }
        return dialog.showAndWait();
    }

    /** Whole numbers only, with no locale: digits, at most three for a percentage and seven for MiB. */
    static Optional<OomThresholds> parse(String warning, String critical, String emergency, String floorMiB) {
        Optional<Integer> w = number(warning, 3);
        Optional<Integer> c = number(critical, 3);
        Optional<Integer> e = number(emergency, 3);
        Optional<Integer> f = number(floorMiB, 7);
        if (w.isEmpty() || c.isEmpty() || e.isEmpty() || f.isEmpty()) {
            return Optional.empty();
        }
        long floor = (long) f.get() << 20;
        return OomThresholds.valid(w.get(), c.get(), e.get(), floor)
                ? Optional.of(new OomThresholds(w.get(), c.get(), e.get(), floor))
                : Optional.empty();
    }

    private static Optional<Integer> number(String text, int digits) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() || trimmed.length() > digits || !trimmed.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(trimmed));
    }

    /**
     * The dialog; its result is the thresholds the fields hold when Set thresholds is pressed, which
     * is offered only while they are valid, and none when it is cancelled. The fields carry ids for
     * tests: {@code thresholds-warning}, {@code thresholds-critical}, {@code thresholds-emergency},
     * {@code thresholds-floor}.
     */
    static Dialog<OomThresholds> build(OomThresholds current, Optional<Long> totalBytes) {
        Dialog<OomThresholds> dialog = new Dialog<>();
        dialog.setTitle("Memory pressure thresholds");
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Stylesheets.app(), Stylesheets.control(), Stylesheets.oom());
        pane.getStyleClass().addAll("cpu-limit-dialog", "thresholds-dialog");

        Label heading = new Label("When does OOM Guard warn and act?");
        heading.getStyleClass().addAll("text-section-title", "cpu-limit-heading");
        Label means = new Label("Each state begins when the memory still available is at or below its share of physical"
                + " memory; Emergency also below the floor.");
        means.getStyleClass().addAll("text-small", "text-muted");
        means.setWrapText(true);
        means.setMinHeight(Region.USE_PREF_SIZE);

        TextField warning = field(current.warningPercent());
        TextField critical = field(current.criticalPercent());
        TextField emergency = field(current.emergencyPercent());
        TextField floor = field((int) (current.emergencyFloorBytes() >> 20));
        warning.setId("thresholds-warning");
        critical.setId("thresholds-critical");
        emergency.setId("thresholds-emergency");
        floor.setId("thresholds-floor");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("thresholds-grid");
        row(grid, 0, "Warning", warning, "%", "Warning threshold in percent");
        row(grid, 1, "Critical", critical, "%", "Critical threshold in percent");
        row(grid, 2, "Emergency", emergency, "%", "Emergency threshold in percent");
        row(grid, 3, "Or under", floor, "MiB", "Emergency floor in MiB");

        Label order = new Label(ORDER);
        order.getStyleClass().addAll("text-small", "text-muted", "cpu-limit-hint", "thresholds-rule");
        order.setWrapText(true);
        order.setMinHeight(Region.USE_PREF_SIZE);
        var invalid = Bindings.createBooleanBinding(
                () -> parse(warning.getText(), critical.getText(), emergency.getText(), floor.getText())
                        .isEmpty(),
                warning.textProperty(),
                critical.textProperty(),
                emergency.textProperty(),
                floor.textProperty());
        order.visibleProperty().bind(invalid);
        // The fields that break the rule carry :invalid, an edge in the error color (oom.css).
        Map<Field, TextField> fields = Map.of(
                Field.WARNING, warning, Field.CRITICAL, critical, Field.EMERGENCY, emergency, Field.FLOOR, floor);
        ChangeListener<String> mark = (observable, before, after) -> {
            Set<Field> wrong =
                    invalidFields(warning.getText(), critical.getText(), emergency.getText(), floor.getText());
            fields.forEach((which, field) -> field.pseudoClassStateChanged(INVALID, wrong.contains(which)));
        };
        fields.values().forEach(field -> field.textProperty().addListener(mark));
        mark.changed(null, null, null);
        order.managedProperty().bind(order.visibleProperty());
        // The hint comes and goes with the numbers: the window follows, so that its buttons never fall
        // off its bottom (found by the Windows acceptance run).
        order.managedProperty().addListener((observable, before, after) -> {
            if (dialog.isShowing() && pane.getScene() != null && pane.getScene().getWindow() != null) {
                pane.getScene().getWindow().sizeToScene();
            }
        });

        Label ignored = new Label("A floor at or above half of the physical memory is ignored; 0 turns it off.");
        ignored.getStyleClass().addAll("text-small", "text-muted");
        ignored.setWrapText(true);
        ignored.setMinHeight(Region.USE_PREF_SIZE);
        totalBytes.ifPresent(total -> ignored.setText("A floor at or above half of the physical memory ("
                + ((total / 2) >> 20) + " MiB) is ignored; 0 turns it off."));

        VBox content = new VBox(heading, means, grid, order, ignored);
        content.getStyleClass().add("cpu-limit-content");
        pane.setContent(content);

        ButtonType set = new ButtonType("Set thresholds", ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(set, cancel);
        Button setButton = (Button) pane.lookupButton(set);
        setButton.setDefaultButton(true);
        setButton.disableProperty().bind(invalid);
        Button cancelButton = (Button) pane.lookupButton(cancel);
        cancelButton.setCancelButton(true);
        // A field with a formatter takes Escape and Enter for itself (milestone 9 and 10 reviews): Escape
        // cancels, Enter sets what the fields hold when it is valid and does nothing otherwise.
        for (TextField field : List.of(warning, critical, emergency, floor)) {
            field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    event.consume();
                    cancelButton.fire();
                } else if (event.getCode() == KeyCode.ENTER) {
                    event.consume();
                    if (!setButton.isDisabled()) {
                        setButton.fire();
                    }
                }
            });
        }
        dialog.setOnShown(event -> {
            warning.requestFocus();
            warning.selectAll();
        });
        dialog.setResultConverter(answer -> answer == set
                ? parse(warning.getText(), critical.getText(), emergency.getText(), floor.getText())
                        .orElse(null)
                : null);
        return dialog;
    }

    private static TextField field(int value) {
        TextField field = new TextField(Integer.toString(value));
        field.getStyleClass().addAll("cpu-limit-field", "thresholds-field");
        field.setPrefColumnCount(4);
        UnaryOperator<TextFormatter.Change> limited =
                change -> change.getControlNewText().length() <= 7 ? change : null;
        field.setTextFormatter(new TextFormatter<>(limited));
        return field;
    }

    /**
     * A screen reader takes the name of a text field from its label and reads its accessible text as its
     * value, so the name that says what the number is is a label that takes no room and is not drawn.
     */
    private static void row(GridPane grid, int index, String caption, TextField field, String unit, String spoken) {
        Label name = new Label(caption);
        Label units = new Label(unit);
        units.getStyleClass().addAll("text-small", "text-muted");
        Label accessibleName = new Label(spoken);
        accessibleName.setLabelFor(field);
        accessibleName.setManaged(false);
        accessibleName.setVisible(false);
        grid.addRow(index, name, field, units);
        grid.getChildren().add(accessibleName);
    }
}
