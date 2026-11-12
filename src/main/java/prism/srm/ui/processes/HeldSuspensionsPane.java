package prism.srm.ui.processes;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.ListChangeListener;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspendedProcess;
import prism.srm.ui.control.ActionResultLine;
import prism.srm.ui.control.CutTextTooltip;

/**
 * The processes Prism still holds suspended after process monitoring stopped: each with its name,
 * its PID beside it as in the inspector, and a plain Resume button; and the message of the last
 * Resume. Below the notice that monitoring stopped; it takes room only while it has something to
 * offer, and the caption only while something is held. Layout and binding only.
 *
 * <p>The buttons are made again only when the processes change, not while a Resume is on its way,
 * so the button that was pressed keeps the keyboard focus; when its process goes, the focus moves
 * to the button that takes its place, or to the one before it.
 */
final class HeldSuspensionsPane extends VBox {

    /** The widest a name gets before it is cut; the PID and the button stay in view. */
    private static final double NAME_WIDTH = 320;

    private final HeldSuspensions model;
    private final GridPane list = new GridPane();
    private final List<Button> buttons = new ArrayList<>();

    HeldSuspensionsPane(HeldSuspensions model) {
        this.model = model;
        getStyleClass().add("held-suspensions");
        setAccessibleText("Processes Prism still holds suspended");

        // Closing or restarting is what the notice above leads to; either leaves these suspended.
        Label caption = new Label("Prism still holds these processes suspended. Resume them before you close or"
                + " restart Prism; otherwise they stay suspended.");
        caption.getStyleClass().addAll("text-small", "text-muted");
        caption.setWrapText(true);
        caption.setMinHeight(Region.USE_PREF_SIZE);
        BooleanBinding anyHeld = Bindings.isNotEmpty(model.rows());
        caption.visibleProperty().bind(anyHeld);
        caption.managedProperty().bind(anyHeld);

        list.getStyleClass().add("held-suspensions-list");
        list.visibleProperty().bind(anyHeld);
        list.managedProperty().bind(anyHeld);

        ActionResultLine message = new ActionResultLine(model.line());
        message.getStyleClass().add("held-suspensions-message");
        message.visibleProperty().bind(model.messageProperty().isNotEmpty());
        message.managedProperty().bind(message.visibleProperty());

        getChildren().setAll(caption, list, message);
        visibleProperty().bind(model.shownProperty());
        managedProperty().bind(visibleProperty());

        model.rows().addListener((ListChangeListener<SuspendedProcess>) change -> rebuild());
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
        buttons.clear();
        int line = 0;
        for (SuspendedProcess process : model.rows()) {
            ProcessIdentity identity = process.identity();
            Label name = new Label(process.name());
            name.getStyleClass().add("held-suspension-name");
            name.setMinWidth(0);
            name.setMaxWidth(NAME_WIDTH);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            CutTextTooltip.install(name);
            Label pid = new Label("PID " + identity.pid());
            pid.getStyleClass().addAll("text-small", "text-muted");
            pid.setMinWidth(Region.USE_PREF_SIZE);
            Button resume = new Button("Resume");
            resume.getStyleClass().add("held-suspension-resume");
            resume.setMinWidth(Region.USE_PREF_SIZE);
            resume.setAccessibleText("Resume " + process.name() + " (PID " + identity.pid() + ")");
            resume.setOnAction(event -> model.resume(identity));
            GridPane.setValignment(name, VPos.CENTER);
            GridPane.setValignment(pid, VPos.CENTER);
            list.addRow(line++, name, pid, resume);
            buttons.add(resume);
        }
        if (focused >= 0 && !buttons.isEmpty()) {
            buttons.get(Math.min(focused, buttons.size() - 1)).requestFocus();
        }
    }
}
