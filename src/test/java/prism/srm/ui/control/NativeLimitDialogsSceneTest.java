package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.domain.FallbackOffer;
import prism.srm.ui.snapshot.DialogSnapshots;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/** The memory limit and fallback dialogs of milestone 10 with a toolkit: default button, keyboard, rendering. */
@Tag("ui-snapshot")
class NativeLimitDialogsSceneTest {

    private static final FallbackOffer OFFER =
            new FallbackOffer("it is in a Job Object Prism does not hold", 4, 16, true);

    /** Enter must not set a limit that may make the process fail, nor start the costlier limiter. */
    @Test
    void cancelIsTheDefaultButtonOfBothDialogs() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> memory = MemoryLimitDialog.build("demo", 42, OptionalLong.empty(), "300 MiB");
            Dialog<ButtonType> offer = FallbackOfferDialog.build("demo", 42, OFFER);
            Dialog<ButtonType> resident =
                    ResidentOfferDialog.build("demo", 42, "it is in a Job Object Prism does not hold");
            return List.of(defaultButton(memory), defaultButton(offer), defaultButton(resident));
        });

        assertThat(seen).containsExactly(ButtonData.CANCEL_CLOSE, ButtonData.CANCEL_CLOSE, ButtonData.CANCEL_CLOSE);
    }

    /** As in the CPU dialog: the field with a formatter must not swallow Escape (milestone 9 review). */
    @Test
    void escapeInTheMemoryFieldCancelsTheDialog() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog = MemoryLimitDialog.build("demo", 42, OptionalLong.of(512L << 20), "");
            TextField field = (TextField) dialog.getDialogPane().lookup(".memory-limit-field");
            dialog.getDialogPane().applyCss();
            String start = field.getText();
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            ButtonType result = dialog.getResult();
            return List.of(start, result == null ? "none" : result.getButtonData());
        });

        assertThat(seen).containsExactly("512", ButtonData.CANCEL_CLOSE);
    }

    /**
     * Enter in the field, with the dialog shown as the user sees it, after typing a value: what happens.
     * Returns whether the dialog is still showing, its result, whether "Set limit" is enabled, which
     * hint lines are visible, and what the field holds.
     */
    private static List<Object> enterInTheField(String typed) {
        return SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog = MemoryLimitDialog.build("demo", 42, OptionalLong.empty(), "");
            dialog.show();
            try {
                TextField field = (TextField) dialog.getDialogPane().lookup(".memory-limit-field");
                field.requestFocus();
                field.setText(typed);
                KeyEvent enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
                field.fireEvent(enter);
                ButtonType result = dialog.getResult();
                javafx.scene.Node set = dialog.getDialogPane()
                        .lookupButton(dialog.getDialogPane().getButtonTypes().get(0));
                javafx.scene.Node press = dialog.getDialogPane().lookup(".memory-limit-press-set");
                javafx.scene.Node invalid = dialog.getDialogPane().lookup(".cpu-limit-hint");
                return List.of(
                        dialog.isShowing(),
                        result == null ? "none" : result.getButtonData().name(),
                        !set.isDisabled(),
                        press != null && press.isVisible(),
                        invalid != null && invalid.isVisible(),
                        field.getText());
            } finally {
                dialog.setResult(ButtonType.CANCEL);
                dialog.close();
            }
        });
    }

    /**
     * Enter after typing a valid value neither cancels (the value is not lost) nor sets the limit
     * (Cancel is the default: a limit that may make the process fail is set only by its button); the
     * dialog stays and says how to set it (final M10 audit, the defect of the earlier report).
     */
    @Test
    void enterAfterAValidValueNeitherCancelsNorSetsAndSaysHowToSetIt() {
        assertThat(enterInTheField("512")).containsExactly(true, "none", true, true, false, "512");
    }

    /** Enter after an invalid value keeps the dialog and its value, and shows what is wrong. */
    @Test
    void enterAfterAnInvalidValueKeepsTheDialogAndShowsTheProblem() {
        assertThat(enterInTheField("0.5 GiB")).containsExactly(true, "none", false, false, true, "0.5 GiB");
    }

    /** The focus starts in the field, with the value selected, so typing replaces it. */
    @Test
    void theFocusStartsInTheFieldWithItsValueSelected() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog = MemoryLimitDialog.build("demo", 42, OptionalLong.of(512L << 20), "");
            dialog.show();
            try {
                TextField field = (TextField) dialog.getDialogPane().lookup(".memory-limit-field");
                return List.of(
                        field.isFocused() || dialog.getDialogPane().getScene().getFocusOwner() == field,
                        field.getSelectedText());
            } finally {
                dialog.setResult(ButtonType.CANCEL);
                dialog.close();
            }
        });
        assertThat(seen).containsExactly(true, "512");
    }

    /** The refusal of a second launch: one button, Close, the default and the cancel button. */
    @Test
    void theRefusalOfASecondLaunchHasOnlyCloseAsDefault() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            Dialog<ButtonType> dialog = AlreadyRunningDialog.build("Prism is already running for this user.");
            List<ButtonType> buttons = dialog.getDialogPane().getButtonTypes();
            javafx.scene.control.Button close =
                    (javafx.scene.control.Button) dialog.getDialogPane().lookupButton(buttons.get(0));
            return List.of(buttons.size(), close.isDefaultButton(), close.isCancelButton(), close.getText());
        });
        assertThat(seen).containsExactly(1, true, true, "Close");
    }

    static Stream<Arguments> themes() {
        return Stream.of(Arguments.of(Theme.LIGHT), Arguments.of(Theme.DARK));
    }

    /**
     * Renders the dialogs for inspection (target/ui-snapshots/dialog-memory-*, dialog-fallback-*,
     * dialog-already-running-*, dialog-resident-offer-*), each at the size its window takes (LO-17).
     */
    @ParameterizedTest
    @MethodSource("themes")
    void rendersBothDialogs(Theme theme) {
        DialogSnapshots.render(
                "dialog-memory",
                theme,
                () -> MemoryLimitDialog.build(
                        "a-build-tool-with-a-very-long-name-that-the-dialog-has-to-cut.exe",
                        9120,
                        OptionalLong.of(2L << 30),
                        "1.4 GiB"));
        DialogSnapshots.render(
                "dialog-cpu-limit",
                theme,
                () -> CpuLimitDialog.build(
                        "a-build-tool-with-a-very-long-name-that-the-dialog-has-to-cut.exe",
                        9120,
                        java.util.OptionalInt.of(30),
                        CpuLimitDialog.means(prism.srm.ui.LimitsFixtures.EMULATED)));
        DialogSnapshots.render(
                "dialog-cpu-limit-invalid",
                theme,
                () -> CpuLimitDialog.build(
                        "chrome.exe",
                        18812,
                        java.util.OptionalInt.of(30),
                        CpuLimitDialog.means(prism.srm.ui.LimitsFixtures.EMULATED)),
                dialog -> ((javafx.scene.control.TextField)
                                dialog.getDialogPane().lookup(".cpu-limit-field"))
                        .setText("0"));
        DialogSnapshots.render(
                "dialog-already-running",
                theme,
                () -> AlreadyRunningDialog.build(
                        "Prism is already running for this user. Only one Prism may"
                                + " run at a time, so that two never limit the same process; switch to the window that is open."));
        DialogSnapshots.render("dialog-fallback", theme, () -> FallbackOfferDialog.build("chrome.exe", 18812, OFFER));
        DialogSnapshots.render(
                "dialog-resident-offer",
                theme,
                () -> ResidentOfferDialog.build("chrome.exe", 18812, "it is in a Job Object Prism does not hold"));
    }

    private static ButtonData defaultButton(Dialog<ButtonType> dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> ((Button) dialog.getDialogPane().lookupButton(type)).isDefaultButton())
                .map(ButtonType::getButtonData)
                .findFirst()
                .orElse(ButtonData.OTHER);
    }
}
