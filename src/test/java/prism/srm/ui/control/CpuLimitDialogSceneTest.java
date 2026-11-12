package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalInt;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The CPU limit dialog with a toolkit: the keyboard. */
@Tag("ui-snapshot")
class CpuLimitDialogSceneTest {

    /**
     * Escape in the field, where the focus is when the dialog opens, cancels the dialog. The field
     * has a formatter, and such a field takes Escape for itself: in the real application the dialog
     * stayed open (milestone 9 review, found by driving the window).
     */
    @Test
    void escapeInTheFieldCancelsTheDialog() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog =
                    CpuLimitDialog.build("demo", 42, OptionalInt.of(30), CpuLimitDialog.means(LimitsFixtures.EMULATED));
            TextField field = (TextField) dialog.getDialogPane().lookup(".cpu-limit-field");
            // The field's skin, which brings the behavior that takes Escape, as when the dialog shows.
            dialog.getDialogPane().applyCss();
            field.setText("55");
            KeyEvent escape = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
            field.fireEvent(escape);
            ButtonType result = dialog.getResult();
            return List.of(field.getSkin() != null, result == null ? "none" : result.getButtonData(), field.getText());
        });

        assertThat(seen).containsExactly(true, ButtonData.CANCEL_CLOSE, "55");
    }
}
