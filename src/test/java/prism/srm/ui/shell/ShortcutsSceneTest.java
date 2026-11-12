package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * K6: the keys of the window behave the same wherever the focus is and are written the same way
 * wherever they are shown. The events are made here: Control and Meta both down is the shortcut key on
 * every system.
 */
@Tag("ui-snapshot")
class ShortcutsSceneTest {

    /** The shortcut key alone with a digit is a destination; with Shift or Alt (AltGr) it is not. */
    @Test
    void onlyTheShortcutKeyAloneWithADigitChoosesADestination() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 100, 100, Region::new, root -> {
            List<Object> facts = new ArrayList<>();
            facts.add(Shortcuts.destination(key(KeyCode.DIGIT2, false, false)));
            facts.add(Shortcuts.destination(key(KeyCode.NUMPAD7, false, false)));
            facts.add(Shortcuts.destination(key(KeyCode.DIGIT8, false, false)));
            facts.add(Shortcuts.destination(key(KeyCode.DIGIT2, true, false)));
            facts.add(Shortcuts.destination(key(KeyCode.DIGIT2, false, true)));
            facts.add(Shortcuts.destination(
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DIGIT2, false, false, false, false)));
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        Optional.of(Destination.PROCESSES),
                        Optional.of(Destination.SETTINGS),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
    }

    /**
     * The keys are written as the system writes them: "Ctrl+1" to "Ctrl+7" on Windows, and the same text
     * in the accessible help of an entry, beside the name of the rail and in the row with names.
     */
    @Test
    void theKeysAreWrittenTheSameWayEverywhere() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 706, 435, KeyboardRoutesSceneTest::populated, root -> {
            Shell shell = (Shell) root;
            List<Object> facts = new ArrayList<>();
            Sidebar list = shell.navigation();
            List<String> texts = new ArrayList<>();
            for (Destination destination : Destination.values()) {
                Labeled hint =
                        (Labeled) ((javafx.scene.Parent) list.entry(destination).getGraphic()).lookup(".nav-shortcut");
                texts.add(Shortcuts.text(destination) + "|"
                        + list.entry(destination).getAccessibleHelp() + "|" + hint.getText());
            }
            facts.add(texts);
            return facts;
        });

        List<String> texts = new ArrayList<>();
        for (Destination destination : Destination.values()) {
            String key = expected(Integer.toString(destination.ordinal() + 1));
            texts.add(key + "|" + key + "|" + key);
        }
        assertThat(seen).containsExactly(texts);
    }

    /**
     * No text field binds the shortcut key with a digit: in the filter of a page it chooses a destination
     * as anywhere else, and the text typed stays as it was.
     */
    @Test
    void inATextFieldTheKeysDoWhatTheyDoAnywhereAndLeaveTheTextAlone() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 706, 435, KeyboardRoutesSceneTest::populated, root -> {
            Shell shell = (Shell) root;
            List<Object> facts = new ArrayList<>();
            shell.navigation().select(Destination.LOGS);
            shell.applyCss();
            shell.layout();
            TextInputControl filter = (TextInputControl) shell.getCenter().lookup(".logs-filter");
            filter.requestFocus();
            filter.setText("prism");
            shortcut(filter, KeyCode.DIGIT3);
            facts.add(shell.navigation().selected());
            facts.add(filter.getText());
            return facts;
        });

        assertThat(seen).containsExactly(Destination.PERFORMANCE, "prism");
    }

    /** "Ctrl+" and the key on Windows; elsewhere the system's own way, which ends with the key. */
    private static String expected(String key) {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return windows ? "Ctrl+" + key : Shortcuts.text(Destination.OVERVIEW).replace("1", key);
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, true, alt, true);
    }

    private static void shortcut(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, true));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, true, false, true));
    }
}
