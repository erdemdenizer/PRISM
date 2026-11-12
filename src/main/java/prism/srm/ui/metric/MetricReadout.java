package prism.srm.ui.metric;

import java.util.Objects;
import java.util.function.DoubleFunction;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import prism.srm.ui.format.Quantity;
import prism.srm.ui.graph.GraphData;

/**
 * What one metric panel shows: its state, its current values as text, and the data of its graph.
 * Changed on the JavaFX Application Thread only, by a view model.
 *
 * <p>A text is set only when it differs from the one shown, so a value that did not change causes
 * no work in the scene.
 */
public final class MetricReadout {

    private static final Quantity NONE = new Quantity("", "");

    private final ReadOnlyObjectWrapper<MetricState> state = new ReadOnlyObjectWrapper<>(MetricState.WAITING);
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper(MetricState.WAITING.placeholder());
    private final ReadOnlyStringWrapper value = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper unit = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper detail = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper secondValue = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper secondUnit = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper scale = new ReadOnlyStringWrapper("");
    private final ReadOnlyObjectWrapper<GraphData> graph = new ReadOnlyObjectWrapper<>(GraphData.EMPTY);
    private final ReadOnlyBooleanWrapper notRunning = new ReadOnlyBooleanWrapper(false);

    /** How a sample of the graph reads as text, in the unit of the values; set with the graph. */
    private DoubleFunction<Quantity> sampleFormat = sample -> Quantity.MISSING;

    public ReadOnlyObjectProperty<MetricState> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /** What to show in place of the values while the state is not {@link MetricState#LIVE}. */
    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    /** The number of the current value; for a panel with two series, of the first. */
    public ReadOnlyStringProperty valueProperty() {
        return value.getReadOnlyProperty();
    }

    /** The unit of {@link #valueProperty()}. */
    public ReadOnlyStringProperty unitProperty() {
        return unit.getReadOnlyProperty();
    }

    /** A second, less important figure next to the value; empty when there is none. */
    public ReadOnlyStringProperty detailProperty() {
        return detail.getReadOnlyProperty();
    }

    /** The number of the current value of the second series; empty for a panel with one. */
    public ReadOnlyStringProperty secondValueProperty() {
        return secondValue.getReadOnlyProperty();
    }

    /** The unit of {@link #secondValueProperty()}. */
    public ReadOnlyStringProperty secondUnitProperty() {
        return secondUnit.getReadOnlyProperty();
    }

    /** The value at the top of the graph's scale; empty while the graph has nothing to scale. */
    public ReadOnlyStringProperty scaleProperty() {
        return scale.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<GraphData> graphProperty() {
        return graph.getReadOnlyProperty();
    }

    /**
     * Whether the values are unavailable because monitoring is not running (it could not start,
     * or it ended), rather than because the system did not give them.
     */
    public ReadOnlyBooleanProperty notRunningProperty() {
        return notRunning.getReadOnlyProperty();
    }

    /**
     * Returns a sample of the graph as text, in the unit and the notation of the current values;
     * a skipped sample (NaN) is {@link Quantity#MISSING}.
     */
    public Quantity formatSample(double sample) {
        return Double.isNaN(sample) ? Quantity.MISSING : sampleFormat.apply(sample);
    }

    /** Shows one current value, with an optional figure beside it. */
    public void showValue(Quantity current, String newDetail) {
        show(MetricState.LIVE, current, newDetail, NONE);
    }

    /** Shows the current values of two series. */
    public void showValues(Quantity first, Quantity second) {
        show(MetricState.LIVE, first, "", second);
    }

    public void showNoValues(MetricState newState) {
        show(newState, NONE, "", NONE);
    }

    /** Takes the values off because monitoring is not running; the page says why. */
    public void showNotRunning() {
        show(MetricState.UNAVAILABLE, NONE, "", NONE);
        notRunning.set(true);
    }

    public void showGraph(GraphData newGraph, String newScale) {
        if (!newGraph.equals(graph.get())) {
            graph.set(newGraph);
        }
        scale.set(newScale);
    }

    /**
     * Shows the graph, with how one of its samples reads as text for a cursor on it.
     *
     * @param newSampleFormat formats a sample that was not skipped
     */
    public void showGraph(GraphData newGraph, String newScale, DoubleFunction<Quantity> newSampleFormat) {
        sampleFormat = Objects.requireNonNull(newSampleFormat, "newSampleFormat");
        showGraph(newGraph, newScale);
    }

    private void show(MetricState newState, Quantity first, String newDetail, Quantity second) {
        notRunning.set(false);
        state.set(newState);
        status.set(newState.placeholder());
        value.set(first.number());
        unit.set(first.unit());
        detail.set(newDetail);
        secondValue.set(second.number());
        secondUnit.set(second.unit());
    }
}
