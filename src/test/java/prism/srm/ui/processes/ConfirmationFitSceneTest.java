package prism.srm.ui.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlRequest;
import prism.srm.ui.snapshot.DialogFit;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The confirmations of End task and Force kill (UI audit LO-15, LO-17): shown in their windows with a
 * long name, their buttons are inside the window with their words whole; Cancel is the default; the
 * act is the destructive button, and only the act that cannot be undone carries the lasting edge.
 */
@Tag("ui-snapshot")
class ConfirmationFitSceneTest {

    private static final String LONG = "a-build-tool-with-a-very-long-name-that-the-dialog-has-to-cut-somewhere.exe";

    @ParameterizedTest(name = "render scale {0}")
    @ValueSource(doubles = {1.0, 1.25})
    void theConfirmationsFitAndShowCancelAsTheDefault(double scale) {
        Map<String, List<Object>> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Map<String, List<Object>> out = new LinkedHashMap<>();
            for (ControlAction action : List.of(ControlAction.END_TASK, ControlAction.FORCE_KILL)) {
                for (Optional<ControlMechanism> mechanism :
                        List.of(Optional.<ControlMechanism>empty(), Optional.of(ControlMechanism.TERMINATION_SIGNAL))) {
                    Dialog<ButtonType> dialog = Confirmation.build(
                            ControlRequest.of(action, ProcessListings.identity(4242), LONG), mechanism);
                    DialogPane pane = dialog.getDialogPane();
                    Button act =
                            (Button) pane.lookupButton(pane.getButtonTypes().get(0));
                    Button cancel =
                            (Button) pane.lookupButton(pane.getButtonTypes().get(1));
                    List<Object> facts = List.of(
                            cancel.isDefaultButton(),
                            act.isDefaultButton(),
                            act.getStyleClass().contains("confirm-act"),
                            act.getStyleClass().contains("confirm-act-lasting"));
                    DialogFit.Fit fit = DialogFit.measure(dialog, shown -> {}, scale);
                    out.put(
                            action + " " + mechanism.map(Object::toString).orElse("default"),
                            List.of(facts, fit.fits()));
                }
            }
            return out;
        });

        assertThat(seen)
                .containsEntry("END_TASK default", List.of(List.of(true, false, true, false), true))
                .containsEntry("FORCE_KILL default", List.of(List.of(true, false, true, true), true))
                .containsEntry("END_TASK TERMINATION_SIGNAL", List.of(List.of(true, false, true, false), true))
                .containsEntry("FORCE_KILL TERMINATION_SIGNAL", List.of(List.of(true, false, true, true), true));
    }
}
