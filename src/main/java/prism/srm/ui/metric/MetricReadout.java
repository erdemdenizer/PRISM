package prism.srm.ui.metric;

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

    public void showGraph(GraphData newGraph, String newScale) {
        if (!newGraph.equals(graph.get())) {
            graph.set(newGraph);
        }
        scale.set(newScale);
    }

    private void show(MetricState newState, Quantity first, String newDetail, Quantity second) {
        state.set(newState);
        status.set(newState.placeholder());
        value.set(first.number());
        unit.set(first.unit());
        detail.set(newDetail);
        secondValue.set(second.number());
        secondUnit.set(second.unit());
    }
}
