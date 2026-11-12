package prism.srm.ui.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAttribute;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.LimitsFixtures;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The CPU and memory limit dialogs grow with the line that a typed value brings (the hint of an
 * invalid value, or the memory dialog's "Press Set limit…") and shrink when it goes. Their buttons,
 * Cancel among them, never fall off the bottom of the window. Found by the M11 Windows acceptance run
 * (2026-10-12) and confirmed by a probe; the OOM dialogs got the same treatment in milestone 11.
 */
@Tag("ui-snapshot")
class LimitDialogsSizingSceneTest {

    /** What one state of a shown dialog looks like. */
    private record State(boolean lineShown, boolean buttonsInside, boolean wordsWhole, double windowHeight) {}

    private static void settle(DialogPane pane) {
        pane.applyCss();
        pane.layout();
    }

    private static State state(Dialog<ButtonType> dialog, String lineClass) {
        DialogPane pane = dialog.getDialogPane();
        settle(pane);
        double sceneHeight = pane.getScene().getHeight();
        double sceneWidth = pane.getScene().getWidth();
        boolean inside = true;
        boolean whole = true;
        for (ButtonType type : pane.getButtonTypes()) {
            Button button = (Button) pane.lookupButton(type);
            Bounds bounds = button.localToScene(button.getBoundsInLocal());
            inside &= bounds.getMinY() >= 0 && bounds.getMaxY() <= sceneHeight && bounds.getMaxX() <= sceneWidth;
            // What the button draws: JavaFX shortens the words it has no room for ("Can…").
            whole &= ((Text) button.lookup(".text")).getText().equals(button.getText());
        }
        javafx.scene.Node line = pane.lookup(lineClass);
        return new State(
                line != null && line.isVisible(),
                inside,
                whole,
                pane.getScene().getWindow().getHeight());
    }

    private static TextField field(Dialog<ButtonType> dialog, String styleClass) {
        return (TextField) dialog.getDialogPane().lookup(styleClass);
    }

    /** Shows the dialog, applies each step in turn and records the state after each. */
    private static List<State> walk(
            Dialog<ButtonType> dialog, String fieldClass, String lineClass, List<String> typed) {
        List<State> states = new ArrayList<>();
        dialog.show();
        try {
            states.add(state(dialog, lineClass));
            TextField field = field(dialog, fieldClass);
            for (String text : typed) {
                if (text.equals("ENTER")) {
                    field.fireEvent(
                            new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                } else {
                    field.setText(text);
                }
                states.add(state(dialog, lineClass));
            }
            return states;
        } finally {
            dialog.setResult(ButtonType.CANCEL);
            dialog.close();
        }
    }

    private static Dialog<ButtonType> cpu() {
        return CpuLimitDialog.build("demo", 42, OptionalInt.of(30), CpuLimitDialog.means(LimitsFixtures.EMULATED));
    }

    private static Dialog<ButtonType> memory() {
        return MemoryLimitDialog.build("demo", 42, OptionalLong.empty(), "300 MiB");
    }

    private static void assertGrowsAndShrinks(List<State> states) {
        State before = states.get(0);
        State invalid = states.get(1);
        State valid = states.get(2);
        assertThat(before.lineShown()).as("no hint for the starting value").isFalse();
        assertThat(invalid.lineShown()).as("the hint of the invalid value").isTrue();
        assertThat(valid.lineShown()).as("the hint goes with a valid value").isFalse();
        assertThat(states)
                .as("every button inside the window, its words whole, in every state")
                .allSatisfy(state -> {
                    assertThat(state.buttonsInside()).isTrue();
                    assertThat(state.wordsWhole()).isTrue();
                });
        assertThat(invalid.windowHeight()).as("the window grows with the hint").isGreaterThan(before.windowHeight());
        assertThat(valid.windowHeight())
                .as("and is back to its size when the hint goes")
                .isEqualTo(before.windowHeight());
    }

    @Test
    void theCpuDialogGrowsWithTheHintOfAnInvalidValueAndShrinksBack() {
        List<State> states = SnapshotHarness.inspect(
                Theme.LIGHT,
                200,
                100,
                Pane::new,
                root -> walk(cpu(), ".cpu-limit-field", ".cpu-limit-hint", List.of("0", "50")));

        assertGrowsAndShrinks(states);
    }

    @Test
    void theMemoryDialogGrowsWithTheHintOfAnInvalidValueAndShrinksBack() {
        List<State> states = SnapshotHarness.inspect(
                Theme.LIGHT,
                200,
                100,
                Pane::new,
                root -> walk(memory(), ".memory-limit-field", ".cpu-limit-hint", List.of("abc", "512")));

        assertGrowsAndShrinks(states);
    }

    /** The memory dialog's other line: Enter after a valid value says how to set it, and the window follows. */
    @Test
    void theMemoryDialogGrowsWithThePressSetLineAndShrinksBack() {
        List<State> states = SnapshotHarness.inspect(
                Theme.LIGHT,
                200,
                100,
                Pane::new,
                root -> walk(
                        memory(), ".memory-limit-field", ".memory-limit-press-set", List.of("512", "ENTER", "1024")));

        State typed = states.get(1);
        State entered = states.get(2);
        State edited = states.get(3);
        assertThat(entered.lineShown()).isTrue();
        assertThat(edited.lineShown()).as("typing again takes the line away").isFalse();
        assertThat(states).allSatisfy(state -> {
            assertThat(state.buttonsInside()).isTrue();
            assertThat(state.wordsWhole()).isTrue();
        });
        assertThat(entered.windowHeight()).isGreaterThan(typed.windowHeight());
        assertThat(edited.windowHeight()).isEqualTo(typed.windowHeight());
    }

    /**
     * What a screen reader gets from the field of each dialog: the name from its label, and what was typed
     * as its value. Accessible text on a text field would be read as the value in place of the number.
     */
    @Test
    void eachFieldIsNamedByItsLabelAndReadsWhatWasTyped() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            List<Object> out = new ArrayList<>();
            for (Dialog<ButtonType> dialog : List.of(cpu(), memory())) {
                boolean isCpu = dialog.getTitle().startsWith("Set CPU");
                TextField typed = field(dialog, isCpu ? ".cpu-limit-field" : ".memory-limit-field");
                typed.setText(isCpu ? "50" : "512");
                Object label = typed.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY);
                out.add(label instanceof Label named ? named.getText() : "no label");
                out.add(typed.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            }
            return out;
        });

        assertThat(seen)
                .containsExactly(
                        "CPU limit in percent of the whole machine, 1 to 100",
                        "50",
                        "Memory limit in MiB, or in GiB when followed by GiB",
                        "512");
    }

    /**
     * A narrower dialog, where the explanation wraps to more lines and leaves less room: the hint still
     * keeps the buttons in view.
     */
    @Test
    void aNarrowerDialogKeepsItsButtonsInViewWhenTheHintAppears() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 200, 100, Pane::new, root -> {
            List<Object> out = new ArrayList<>();
            for (Dialog<ButtonType> dialog : List.of(cpu(), memory())) {
                dialog.getDialogPane().setPrefWidth(340);
                boolean isCpu = dialog.getTitle().startsWith("Set CPU");
                List<State> states = walk(
                        dialog,
                        isCpu ? ".cpu-limit-field" : ".memory-limit-field",
                        ".cpu-limit-hint",
                        List.of(isCpu ? "0" : "abc"));
                out.add(states.get(1).lineShown());
                out.add(states.get(1).buttonsInside());
                out.add(states.get(1).wordsWhole());
                out.add(states.get(1).windowHeight() > states.get(0).windowHeight());
            }
            return out;
        });

        assertThat(seen).containsExactly(true, true, true, true, true, true, true, true);
    }
}
