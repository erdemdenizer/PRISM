package prism.srm.ui.processes;

import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The details of the selected process, below the table: its name and PID, its executable path and
 * command line, and the values of the process that have no column.
 *
 * <p>Layout and binding only. Every node is created once, here, and the pane has the same lines
 * whether a process is selected or not, so selecting one moves nothing in the window. A text
 * that stands in for a value is set back. Path and command line never wrap: they are cut with an
 * ellipsis and then have a tooltip.
 */
final class ProcessDetailsPane extends VBox {

    private static final PseudoClass PLACEHOLDER = PseudoClass.getPseudoClass("placeholder");

    ProcessDetailsPane(ProcessDetailsModel model) {
        getStyleClass().add("process-details");
        setAccessibleText("Details of the selected process");

        Label title = new Label();
        title.textProperty().bind(model.titleProperty());
        title.getStyleClass().add("process-details-title");
        showPlaceholder(title, !model.processShownProperty().get());
        model.processShownProperty().addListener((observable, before, after) -> showPlaceholder(title, !after));
        // A long name gives way; the PID beside it does not.
        title.setMinWidth(0);
        CutTextTooltip.install(title);
        Label pid = new Label();
        pid.textProperty().bind(model.pidProperty());
        pid.getStyleClass().add("text-muted");
        pid.setMinWidth(Region.USE_PREF_SIZE);
        HBox heading = new HBox(title, pid);
        heading.getStyleClass().add("process-details-heading");

        GridPane lines = new GridPane();
        lines.getStyleClass().add("process-details-lines");
        ColumnConstraints captions = new ColumnConstraints();
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        // Lets a value be narrower than its text, so that it is cut instead of widening the pane.
        values.setMinWidth(0);
        lines.getColumnConstraints().addAll(captions, values);
        Label path = value(model.path());
        // Cut in its middle, so that the file name stays readable; processes.css says so.
        path.getStyleClass().add("detail-path");
        lines.addRow(0, caption("Path"), cut(path));
        lines.addRow(1, caption("Command line"), cut(value(model.commandLine())));

        FlowPane facts = new FlowPane();
        facts.getStyleClass().add("process-details-facts");
        facts.getChildren()
                .addAll(
                        fact("Started", model.started()),
                        fact("Parent PID", model.parentPid()),
                        fact("Private memory", model.privateMemory()),
                        fact("Virtual size", model.virtualSize()),
                        fact("I/O read", model.ioRead()),
                        fact("I/O write", model.ioWrite()));

        getChildren().setAll(heading, lines, facts);
    }

    private static Label caption(String text) {
        Label caption = new Label(text);
        caption.getStyleClass().addAll("detail-caption", "text-muted");
        return caption;
    }

    private static Label value(DetailValue value) {
        Label label = new Label();
        label.textProperty().bind(value.textProperty());
        label.getStyleClass().add("detail-value");
        showPlaceholder(label, value.placeholderProperty().get());
        value.placeholderProperty().addListener((observable, before, after) -> showPlaceholder(label, after));
        return label;
    }

    /** A value that may be narrower than its text: it is cut, and then its tooltip has it. */
    private static Label cut(Label value) {
        value.setMinWidth(0);
        value.setMaxWidth(Double.MAX_VALUE);
        CutTextTooltip.install(value);
        return value;
    }

    /**
     * A caption with its value on one line. Every fact is as wide as every other, so the facts
     * stand in columns however many fit on a line, and stay where they are while the values
     * change. Their captions are as wide as those of the lines above, so the values of the first
     * column begin where the path begins.
     */
    private static HBox fact(String name, DetailValue value) {
        HBox fact = new HBox(caption(name), value(value));
        fact.getStyleClass().add("detail-fact");
        return fact;
    }

    private static void showPlaceholder(Label label, boolean placeholder) {
        label.pseudoClassStateChanged(PLACEHOLDER, placeholder);
    }
}
