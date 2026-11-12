package prism.srm.ui.control;

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.domain.LimitResult;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.RecoveryResult;

/**
 * What a line about actions says ({@link ActionResultLine} draws it): its first part, its second
 * part, its tone, and the whole as one text. A result comes in as {@link ProcessControlResult}
 * gives it, so the whole is the text the activity log has; text that stands in for a result (a
 * reason, an action on its way) is one muted part. Counts the results that came to be shown, so
 * the line can mark each arrival. Shared by the actions on the selected process, the Resume of
 * what Prism holds after monitoring stopped, the recovery list and the Limits page. Every method is called on the JavaFX Application
 * Thread; needs no running toolkit.
 */
public final class ResultLineState {

    private final ReadOnlyStringWrapper headline = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper explanation = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<ResultTone> tone = new ReadOnlyObjectWrapper<>(ResultTone.NONE);
    private final ReadOnlyStringWrapper whole = new ReadOnlyStringWrapper("");
    private final ReadOnlyIntegerWrapper results = new ReadOnlyIntegerWrapper(0);

    /** Text that stands in for a result: one muted part; empty for nothing. */
    public void showText(String text) {
        headline.set(text);
        explanation.set("");
        tone.set(ResultTone.NONE);
        whole.set(text);
    }

    /** A result, in its two parts and its tone; does not count it as arrived. */
    public void showResult(ProcessControlResult result) {
        showResult(result.result(), result.message(), result.explanation(), result.text());
    }

    /** The result of a request about managed processes and limits. */
    public void showResult(LimitResult result) {
        showResult(result.outcome().result(), result.message(), result.explanation(), result.text());
    }

    /** The result of a request about a process an earlier session may have left suspended. */
    public void showResult(RecoveryResult result) {
        showResult(result.outcome().result(), result.message(), result.explanation(), result.text());
    }

    private void showResult(OperationResult result, String message, String because, String text) {
        headline.set(message);
        explanation.set(because);
        tone.set(ResultTone.of(result));
        whole.set(text);
    }

    /**
     * Keeps the headline and its tone, and drops the explanation, which no longer holds: for an
     * explanation that described settings since replaced.
     */
    public void dropExplanation() {
        if (explanation.get().isEmpty()) {
            return;
        }
        explanation.set("");
        whole.set(headline.get());
    }

    /** The result just shown came now: the line marks it. */
    public void arrived() {
        results.set(results.get() + 1);
    }

    public ReadOnlyStringProperty headlineProperty() {
        return headline.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty explanationProperty() {
        return explanation.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<ResultTone> toneProperty() {
        return tone.getReadOnlyProperty();
    }

    /** The whole line as one text, for a screen reader and for the tooltip. */
    public ReadOnlyStringProperty wholeProperty() {
        return whole.getReadOnlyProperty();
    }

    /** Counts the results that came to be shown. */
    public ReadOnlyIntegerProperty resultsProperty() {
        return results.getReadOnlyProperty();
    }
}
