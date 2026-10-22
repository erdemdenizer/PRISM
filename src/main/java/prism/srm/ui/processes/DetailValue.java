package prism.srm.ui.processes;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * One line of the details of a process: its text, and whether that text is the value or stands in
 * for it ("Reading…", a dash, the reason why there is none). Changed on the JavaFX Application
 * Thread only, by the model of the details.
 */
final class DetailValue {

    private final ReadOnlyStringWrapper text = new ReadOnlyStringWrapper(ProcessText.MISSING);
    private final ReadOnlyBooleanWrapper placeholder = new ReadOnlyBooleanWrapper(true);

    ReadOnlyStringProperty textProperty() {
        return text.getReadOnlyProperty();
    }

    /** Whether the text stands in for a value that is not there. */
    ReadOnlyBooleanProperty placeholderProperty() {
        return placeholder.getReadOnlyProperty();
    }

    void showValue(String value) {
        text.set(value);
        placeholder.set(false);
    }

    void showInstead(String words) {
        text.set(words);
        placeholder.set(true);
    }

    /** A value read from a snapshot, where the dash is what stands in for a missing one. */
    void show(String valueOrDash) {
        text.set(valueOrDash);
        placeholder.set(ProcessText.MISSING.equals(valueOrDash));
    }
}
