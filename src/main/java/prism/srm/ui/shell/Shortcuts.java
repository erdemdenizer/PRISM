package prism.srm.ui.shell;

import java.util.Optional;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * The keys of the window (K6): Ctrl+1 to Ctrl+7 choose a destination. On macOS the shortcut key is Command; the text says so ({@link #text}).
 *
 * <p>A key is one of these only with the shortcut key alone: not with Alt (AltGr is Ctrl+Alt on
 * Windows, and types characters such as "{" on many layouts), not with Shift. No text field of the
 * application binds the shortcut key with a digit, so they mean the same wherever the focus
 * is in the window. A modal dialog is a window of its own: while one is open these keys reach it, not
 * the shell, and do nothing.
 */
final class Shortcuts {

    /** The digit of each destination, in the order of {@link Destination}. */
    private static final KeyCode[] DIGITS = {
        KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5, KeyCode.DIGIT6, KeyCode.DIGIT7
    };

    private Shortcuts() {}

    /** The destination a key chooses, if it is one of Ctrl+1 to Ctrl+7 (the digits of the keypad too). */
    static Optional<Destination> destination(KeyEvent event) {
        if (!plain(event)) {
            return Optional.empty();
        }
        int index = switch (event.getCode()) {
            case DIGIT1, NUMPAD1 -> 0;
            case DIGIT2, NUMPAD2 -> 1;
            case DIGIT3, NUMPAD3 -> 2;
            case DIGIT4, NUMPAD4 -> 3;
            case DIGIT5, NUMPAD5 -> 4;
            case DIGIT6, NUMPAD6 -> 5;
            case DIGIT7, NUMPAD7 -> 6;
            default -> -1;
        };
        return index < 0 ? Optional.empty() : Optional.of(Destination.values()[index]);
    }

    /** The key of a destination as the system writes it, as "Ctrl+2" on Windows. */
    static String text(Destination destination) {
        return text(DIGITS[destination.ordinal()]);
    }

    private static String text(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN).getDisplayText();
    }

    private static boolean plain(KeyEvent event) {
        return event.isShortcutDown() && !event.isAltDown() && !event.isShiftDown();
    }
}
