package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryCheck;
import prism.srm.ui.control.ActionResultLine;
import prism.srm.ui.control.CutTextTooltip;

/**
 * The processes an earlier session of Prism may have left suspended, at the top of the Processes
 * page (ADR-0011): a caption, then each with its name, its PID, why the earlier session held it,
 * what Prism could check, and Resume… where Prism offers it and Dismiss; then the line about the
 * last request. Takes room only while it has something to show. Layout and binding only.
 *
 * <p>The buttons are made again only when the list changes, so the one that was pressed keeps the
 * keyboard focus; when its entry goes, the focus moves to the button that takes its place.
 */
final class RecoveredSuspensionsPane extends VBox {

    private static final double NAME_WIDTH = 240;

    /** The explanation of an entry wraps at this width. */
    private static final double EXPLANATION_WIDTH = 340;

    /** A long name is cut, but not below this: the name is what the entry is about. */
    private static final double NAME_LEAST = 96;

    private final RecoveredSuspensions model;
    private final Consumer<RecoveredSuspension> resume;
    private final GridPane list = new GridPane();
    private final List<Button> buttons = new ArrayList<>();

    /** @param resume asks the user to confirm, then resumes; the page's, so it can show the dialog */
    RecoveredSuspensionsPane(RecoveredSuspensions model, Consumer<RecoveredSuspension> resume) {
        this.model = model;
        this.resume = resume;
        getStyleClass().add("recovered-suspensions");
        setAccessibleText("Processes an earlier session of Prism may have left suspended");

        Label caption = new Label(RecoveredSuspensions.CAPTION);
        caption.getStyleClass().addAll("text-small", "recovered-suspensions-caption");
        caption.setWrapText(true);
        caption.setMinHeight(Region.USE_PREF_SIZE);
        caption.visibleProperty().bind(Bindings.isNotEmpty(model.rows()));
        caption.managedProperty().bind(caption.visibleProperty());

        // While a journal of an earlier session is unread, the dialog says the list may be incomplete.
        Label incomplete = new Label();
        incomplete
                .textProperty()
                .bind(Bindings.createStringBinding(
                        () -> RecoveredSuspensions.incompleteness(
                                model.checkProperty().get()),
                        model.checkProperty()));
        incomplete.getStyleClass().addAll("text-small", "recovered-suspensions-incomplete");
        // In the warning color once Prism has stopped looking, as the summary beside the filter.
        javafx.css.PseudoClass warning = javafx.css.PseudoClass.getPseudoClass("warning");
        incomplete.pseudoClassStateChanged(warning, model.checkProperty().get() == RecoveryCheck.EXHAUSTED);
        model.checkProperty()
                .addListener((observable, before, now) ->
                        incomplete.pseudoClassStateChanged(warning, now == RecoveryCheck.EXHAUSTED));
        incomplete.setWrapText(true);
        incomplete.setMinHeight(Region.USE_PREF_SIZE);
        incomplete
                .visibleProperty()
                .bind(caption.visibleProperty().and(incomplete.textProperty().isNotEmpty()));
        incomplete.managedProperty().bind(incomplete.visibleProperty());

        list.getStyleClass().add("recovered-suspensions-list");
        list.visibleProperty().bind(caption.visibleProperty());
        list.managedProperty().bind(caption.visibleProperty());

        ActionResultLine message = new ActionResultLine(model.line());
        message.getStyleClass().add("recovered-suspensions-message");
        message.visibleProperty().bind(model.line().wholeProperty().isNotEmpty());
        message.managedProperty().bind(message.visibleProperty());

        getChildren().setAll(caption, incomplete, list, message);
        visibleProperty().bind(model.shownProperty());
        managedProperty().bind(visibleProperty());

        model.rows().addListener((ListChangeListener<RecoveredSuspension>) change -> rebuild());
        rebuild();
    }

    private void rebuild() {
        int focused = -1;
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).isFocused()) {
                focused = i;
            }
        }
        list.getChildren().clear();
        list.getColumnConstraints().clear();
        buttons.clear();
        ColumnConstraints fixed = new ColumnConstraints();
        ColumnConstraints explanationColumn = new ColumnConstraints();
        explanationColumn.setHgrow(Priority.ALWAYS);
        explanationColumn.setMinWidth(0);
        list.getColumnConstraints().addAll(fixed, fixed, fixed, explanationColumn);
        int line = 0;
        for (RecoveredSuspension entry : model.rows()) {
            String target = entry.name() + " (PID " + entry.identity().pid() + ")";
            Label name = new Label(entry.name());
            name.getStyleClass().add("recovered-suspension-name");
            name.setMinWidth(Math.min(name.prefWidth(-1), NAME_LEAST));
            name.setMaxWidth(NAME_WIDTH);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            CutTextTooltip.install(name);
            Label pid = new Label("PID " + entry.identity().pid());
            pid.getStyleClass().addAll("text-small", "text-muted");
            pid.setMinWidth(Region.USE_PREF_SIZE);
            Label why = new Label(RecoveredSuspensions.reasons(entry.reasons()));
            why.getStyleClass().addAll("text-small", "text-muted");
            why.setMinWidth(Region.USE_PREF_SIZE);
            Label explanation = new Label(entry.explanation());
            explanation.getStyleClass().addAll("text-small", "text-muted", "recovered-suspension-explanation");
            // On lines of its own width, wrapped: in the dialog the whole of what Prism could check is read.
            explanation.setWrapText(true);
            explanation.setMinWidth(0);
            explanation.setPrefWidth(EXPLANATION_WIDTH);
            explanation.setMaxWidth(EXPLANATION_WIDTH);
            explanation.setMinHeight(Region.USE_PREF_SIZE);
            // The whole entry as the journal had it, beside what Prism could check: the evidence (ADR-0011).
            Tooltip whole = new Tooltip(entry.explanation() + "\n\n" + entry.evidence());
            whole.setWrapText(true);
            whole.setMaxWidth(560);
            Tooltip.install(explanation, whole);
            List<javafx.scene.Node> cells = new ArrayList<>(List.of(name, pid, why, explanation));
            if (entry.resumable()) {
                Button resumeButton = new Button("Resume…");
                resumeButton.getStyleClass().add("recovered-suspension-resume");
                resumeButton.setMinWidth(Region.USE_PREF_SIZE);
                resumeButton.setAccessibleText("Resume " + target + " after a check");
                resumeButton.setDisable(model.pending(entry));
                resumeButton.setOnAction(event -> resume.accept(entry));
                buttons.add(resumeButton);
                cells.add(resumeButton);
            } else {
                Label none = new Label();
                cells.add(none);
            }
            Button dismiss = new Button("Dismiss");
            dismiss.getStyleClass().add("recovered-suspension-dismiss");
            dismiss.setMinWidth(Region.USE_PREF_SIZE);
            dismiss.setAccessibleText("Dismiss the entry for " + target + "; the process is left as it is");
            dismiss.setTooltip(new Tooltip("Closes the entry. Prism does not touch the process."));
            dismiss.setDisable(model.pending(entry));
            dismiss.setOnAction(event -> model.dismiss(entry));
            buttons.add(dismiss);
            cells.add(dismiss);
            for (javafx.scene.Node cell : cells) {
                GridPane.setValignment(cell, VPos.TOP);
            }
            list.addRow(line++, cells.toArray(javafx.scene.Node[]::new));
        }
        if (focused >= 0 && !buttons.isEmpty()) {
            buttons.get(Math.min(focused, buttons.size() - 1)).requestFocus();
        }
    }
}
