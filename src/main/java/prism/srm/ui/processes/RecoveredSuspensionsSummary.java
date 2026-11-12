package prism.srm.ui.processes;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import prism.srm.domain.RecoveryCheck;

/**
 * At the right of the filter line, while an earlier session may have left processes suspended:
 * how many, in one short sentence, and a plain Review… button that opens the list (ADR-0011).
 * While a journal of an earlier session is unread it also says the list may be incomplete, even
 * when nothing is listed: muted while Prism is still checking and nothing is listed, in the
 * warning color once Prism has stopped looking. It takes no height of its own, so the table keeps
 * its rows in the smallest window, and it gives way before the filter does. Layout and binding only.
 */
final class RecoveredSuspensionsSummary extends HBox {

    private static final javafx.css.PseudoClass WARNING = javafx.css.PseudoClass.getPseudoClass("warning");
    private static final javafx.css.PseudoClass PENDING = javafx.css.PseudoClass.getPseudoClass("pending");

    RecoveredSuspensionsSummary(RecoveredSuspensions model, Runnable review) {
        getStyleClass().add("recovered-summary");
        Label count = new Label();
        count.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> RecoveredSuspensions.summary(
                                model.rows().size(), model.checkProperty().get()),
                        model.rows(),
                        model.checkProperty()));
        count.getStyleClass().addAll("text-small", "recovered-summary-text");
        count.setMinWidth(0);
        javafx.scene.control.Tooltip whole = new javafx.scene.control.Tooltip();
        whole.setWrapText(true);
        // As wide as the tooltip of a result line.
        whole.setMaxWidth(640);
        whole.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> RecoveredSuspensions.summaryInFull(
                                model.rows().size(), model.checkProperty().get()),
                        model.rows(),
                        model.checkProperty()));
        count.setTooltip(whole);
        count.accessibleTextProperty().bind(whole.textProperty());
        Runnable tone = () -> {
            RecoveryCheck now = model.checkProperty().get();
            count.pseudoClassStateChanged(WARNING, now == RecoveryCheck.EXHAUSTED);
            count.pseudoClassStateChanged(
                    PENDING, now == RecoveryCheck.PENDING && model.rows().isEmpty());
        };
        tone.run();
        model.checkProperty().addListener((observable, before, now) -> tone.run());
        model.rows().addListener((javafx.collections.ListChangeListener<Object>) change -> tone.run());
        Button open = new Button("Review…");
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setAccessibleText("Review the processes an earlier session of Prism may have left suspended");
        open.setOnAction(event -> review.run());
        open.visibleProperty().bind(Bindings.isNotEmpty(model.rows()));
        open.managedProperty().bind(open.visibleProperty());
        getChildren().setAll(count, open);
        visibleProperty().bind(model.summaryShownProperty());
        managedProperty().bind(visibleProperty());
    }
}
