package prism.srm.ui.oom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javafx.scene.AccessibleAttribute;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomThresholds;
import prism.srm.ui.snapshot.DialogSnapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The dialogs of OOM Guard with a toolkit: which button Enter presses, Escape, and how they look (OOM-9). */
@Tag("ui-snapshot")
class OomDialogsSceneTest {

    private static final Map<EmergencyAction, String> NOTES = Map.of(
            EmergencyAction.NOTIFY,
            "A warning and a log entry; nothing is done to the process.",
            EmergencyAction.LOWER_PRIORITY,
            "Lowers its priority to Low at Critical and in an Emergency; restored at Normal.",
            EmergencyAction.SUSPEND,
            "Suspends it, thread by thread, in an Emergency; resumed at Normal. Not in effect: automatic"
                    + " suspend is off.",
            EmergencyAction.TERMINATE,
            "Force-kills it in an Emergency, at most once in this session. Not in effect: emergency"
                    + " termination is off.");

    private static final String LONG_NAME = "a-build-tool-with-a-very-long-name-that-the-dialog-has-to-cut.exe";

    static Stream<Theme> themes() {
        return Stream.of(Theme.values());
    }

    /** Each dialog at the size its window takes (UI audit LO-17): the preferred size of its pane. */
    @ParameterizedTest
    @MethodSource("themes")
    void rendersEveryDialog(Theme theme) {
        DialogSnapshots.render(
                "dialog-oom-management",
                theme,
                () -> OomManagementDialog.build(
                        LONG_NAME, 9120, new OomManagement(true, OomPriority.FIRST, EmergencyAction.SUSPEND), NOTES));
        DialogSnapshots.render(
                "dialog-oom-management-terminate",
                theme,
                () -> OomManagementDialog.build(
                        "node.exe",
                        9120,
                        new OomManagement(true, OomPriority.NORMAL, EmergencyAction.TERMINATE),
                        NOTES));
        DialogSnapshots.render(
                "dialog-oom-management-off",
                theme,
                () -> OomManagementDialog.build("notepad.exe", 7004, OomManagement.OFF, NOTES));
        DialogSnapshots.render(
                "dialog-thresholds",
                theme,
                () -> ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30)));
        DialogSnapshots.render(
                "dialog-thresholds-invalid",
                theme,
                () -> ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30)),
                dialog -> field(dialog, "critical").setText("20"));
        DialogSnapshots.render("confirm-termination-switch", theme, TerminationConsent::build);
    }

    /** OOM-9: Enter never allows termination; it does set any other choice. */
    @Test
    void enterNeverAllowsTermination() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomManagement> suspend = OomManagementDialog.build(
                    "demo", 42, new OomManagement(true, OomPriority.NORMAL, EmergencyAction.SUSPEND), NOTES);
            Dialog<OomManagement> terminate = OomManagementDialog.build(
                    "demo", 42, new OomManagement(true, OomPriority.NORMAL, EmergencyAction.TERMINATE), NOTES);
            String terminateText = setButton(terminate).getText();
            ButtonData whileTerminating = defaultButton(terminate);
            // Switching back from Terminate gives the button its usual word and Enter back.
            ((RadioButton) terminate.getDialogPane().lookup("#oom-action-SUSPEND")).setSelected(true);
            ButtonData afterSwitchingBack = defaultButton(terminate);
            String afterText = setButton(terminate).getText();
            return List.of(
                    defaultButton(suspend),
                    whileTerminating,
                    terminateText,
                    afterSwitchingBack,
                    afterText,
                    defaultButton(TerminationConsent.build()));
        });

        assertThat(seen)
                .containsExactly(
                        ButtonData.OK_DONE,
                        ButtonData.CANCEL_CLOSE,
                        "Allow termination",
                        ButtonData.OK_DONE,
                        "Set",
                        ButtonData.CANCEL_CLOSE);
    }

    /** The result is what the controls hold when Set is pressed, and nothing when cancelled. */
    @Test
    void theResultIsWhatTheControlsHold() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomManagement> dialog = OomManagementDialog.build("demo", 42, OomManagement.OFF, NOTES);
            ((CheckBox) dialog.getDialogPane().lookup("#oom-managed")).setSelected(true);
            ((RadioButton) dialog.getDialogPane().lookup("#oom-priority-LAST")).setSelected(true);
            ((RadioButton) dialog.getDialogPane().lookup("#oom-action-LOWER_PRIORITY")).setSelected(true);
            ButtonType set = dialog.getDialogPane().getButtonTypes().get(0);
            ButtonType cancel = dialog.getDialogPane().getButtonTypes().get(1);
            return List.of(
                    dialog.getResultConverter().call(set),
                    String.valueOf(dialog.getResultConverter().call(cancel)));
        });

        assertThat(seen)
                .containsExactly(new OomManagement(true, OomPriority.LAST, EmergencyAction.LOWER_PRIORITY), "null");
    }

    /**
     * Found by the Windows acceptance run (2026-10-12): choosing Terminate in an open dialog adds the
     * consequence sentence, and the window must grow with it, or the buttons fall off its bottom and
     * the default Cancel cannot be seen.
     */
    @Test
    void choosingTerminateInAnOpenDialogKeepsItsButtonsInsideTheWindow() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomManagement> dialog = OomManagementDialog.build(
                    "demo", 42, new OomManagement(true, OomPriority.NORMAL, EmergencyAction.NOTIFY), NOTES);
            dialog.show();
            try {
                ((RadioButton) dialog.getDialogPane().lookup("#oom-action-TERMINATE")).setSelected(true);
                dialog.getDialogPane().applyCss();
                dialog.getDialogPane().layout();
                javafx.stage.Window window = dialog.getDialogPane().getScene().getWindow();
                Button cancel = (Button) dialog.getDialogPane()
                        .lookupButton(dialog.getDialogPane().getButtonTypes().get(1));
                javafx.geometry.Bounds inScene = cancel.localToScene(cancel.getBoundsInLocal());
                double sceneHeight = dialog.getDialogPane().getScene().getHeight();
                Button allow = setButton(dialog);
                return List.of(
                        inScene.getMaxY() <= sceneHeight,
                        window.getHeight() > 0,
                        allow.getText(),
                        // What the button draws: JavaFX cuts the words it has no room for ("Allow …").
                        ((javafx.scene.text.Text) allow.lookup(".text")).getText());
            } finally {
                dialog.close();
            }
        });

        assertThat(seen)
                .as("buttons inside the window; the renamed button wide enough for its words")
                .containsExactly(true, true, "Allow termination", "Allow termination");
    }

    /** The same for the thresholds dialog: the hint that comes with invalid numbers keeps the buttons in view. */
    @Test
    void anInvalidThresholdKeepsTheButtonsInsideTheWindow() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomThresholds> dialog = ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30));
            dialog.show();
            try {
                field(dialog, "critical").setText("20");
                dialog.getDialogPane().applyCss();
                dialog.getDialogPane().layout();
                Button cancel = (Button) dialog.getDialogPane()
                        .lookupButton(dialog.getDialogPane().getButtonTypes().get(1));
                javafx.geometry.Bounds inScene = cancel.localToScene(cancel.getBoundsInLocal());
                return List.of(
                        inScene.getMaxY() <= dialog.getDialogPane().getScene().getHeight());
            } finally {
                dialog.close();
            }
        });

        assertThat(seen).containsExactly(true);
    }

    /** CAP-2: with OOM management off the choices stay in view, disabled, and so does the Terminate sentence. */
    @Test
    void withManagementOffTheChoicesAreDisabledNotHidden() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomManagement> dialog = OomManagementDialog.build(
                    "demo", 42, new OomManagement(false, OomPriority.NORMAL, EmergencyAction.TERMINATE), NOTES);
            javafx.scene.Node terminate = dialog.getDialogPane().lookup("#oom-action-TERMINATE");
            return List.of(
                    terminate.isDisabled(),
                    terminate.isVisible(),
                    dialog.getDialogPane().lookup("#oom-priority-FIRST").isDisabled(),
                    defaultButton(dialog),
                    ((CheckBox) dialog.getDialogPane().lookup("#oom-managed")).isSelected());
        });

        assertThat(seen).containsExactly(true, true, true, ButtonData.OK_DONE, false);
    }

    @Test
    void invalidThresholdsCannotBeSetAndEscapeInAFieldCancels() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomThresholds> dialog = ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30));
            dialog.show();
            try {
                Button set = setButton(dialog);
                boolean validEnabled = !set.isDisabled();
                field(dialog, "critical").setText("20");
                boolean invalidDisabled = set.isDisabled();
                field(dialog, "warning")
                        .fireEvent(
                                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                return List.of(validEnabled, invalidDisabled, dialog.isShowing(), String.valueOf(dialog.getResult()));
            } finally {
                dialog.close();
            }
        });

        assertThat(seen).containsExactly(true, true, false, "null");
    }

    /** Enter in a field sets what the fields hold, typed values included; nothing while they are not valid. */
    @Test
    void enterInAFieldSetsTheTypedThresholdsOnlyWhenValid() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomThresholds> dialog = ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30));
            dialog.show();
            try {
                TextField critical = field(dialog, "critical");
                critical.requestFocus();
                critical.setText("20");
                KeyEvent enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
                critical.fireEvent(enter);
                boolean stillShowing = dialog.isShowing();
                critical.setText("9");
                critical.fireEvent(enter);
                OomThresholds result = dialog.getResult();
                return List.of(stillShowing, dialog.isShowing(), result == null ? -1 : result.criticalPercent());
            } finally {
                dialog.close();
            }
        });

        assertThat(seen).containsExactly(true, false, 9);
    }

    /**
     * What a screen reader gets from each threshold field: the name from its label, and the number as its
     * value. Accessible text on a text field would be read as the value in place of the number.
     */
    @Test
    void eachThresholdFieldIsNamedByItsLabelAndReadsItsNumber() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<OomThresholds> dialog = ThresholdsDialog.build(OomThresholds.DEFAULTS, Optional.of(32L << 30));
            List<Object> out = new java.util.ArrayList<>();
            for (String name : List.of("warning", "critical", "emergency", "floor")) {
                TextField typed = field(dialog, name);
                Object label = typed.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY);
                out.add(label instanceof Label named ? named.getText() : "no label");
                out.add(typed.getText().equals(typed.queryAccessibleAttribute(AccessibleAttribute.TEXT)));
            }
            return out;
        });

        assertThat(seen)
                .containsExactly(
                        "Warning threshold in percent",
                        true,
                        "Critical threshold in percent",
                        true,
                        "Emergency threshold in percent",
                        true,
                        "Emergency floor in MiB",
                        true);
    }

    private static TextField field(Dialog<OomThresholds> dialog, String name) {
        return (TextField) dialog.getDialogPane().lookup("#thresholds-" + name);
    }

    private static Button setButton(Dialog<?> dialog) {
        return (Button) dialog.getDialogPane()
                .lookupButton(dialog.getDialogPane().getButtonTypes().get(0));
    }

    private static ButtonData defaultButton(Dialog<?> dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> ((Button) dialog.getDialogPane().lookupButton(type)).isDefaultButton())
                .map(ButtonType::getButtonData)
                .findFirst()
                .orElse(ButtonData.OTHER);
    }
}
