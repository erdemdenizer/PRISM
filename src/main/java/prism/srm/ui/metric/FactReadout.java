package prism.srm.ui.metric;

import java.util.Optional;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * One fact about the system: its text, and whether that text is the fact or stands in for it.
 * Changed on the JavaFX Application Thread only, by a view model.
 */
public final class FactReadout {

    private final ReadOnlyObjectWrapper<MetricState> state = new ReadOnlyObjectWrapper<>(MetricState.WAITING);
    private final ReadOnlyStringWrapper text = new ReadOnlyStringWrapper(MetricState.WAITING.placeholder());

    public ReadOnlyObjectProperty<MetricState> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** The fact, or "Reading..." or "Unavailable" in its place. */
    public ReadOnlyStringProperty textProperty() {
        return text.getReadOnlyProperty();
    }

    /**
     * @param fact the fact as text, if the snapshot has it
     * @param cycles the number of monitoring cycles so far
     */
    public void show(Optional<String> fact, int cycles) {
        MetricState newState = MetricState.of(fact.isPresent(), cycles);
        state.set(newState);
        text.set(fact.orElse(newState.placeholder()));
    }

    public void showUnavailable() {
        state.set(MetricState.UNAVAILABLE);
        text.set(MetricState.UNAVAILABLE.placeholder());
    }
}
