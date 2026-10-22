package prism.srm.ui.shell;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The primary navigation: the seven destinations as a vertical list.
 *
 * <p>All seven are always listed, so the shape of the product is visible from the first version.
 * A destination whose page does not exist yet is disabled, and one line below the list says what
 * the dimmed entries mean; a disabled control can show no tooltip of its own.
 *
 * <p>Keyboard: the list is one stop in the Tab order, at the selected entry. The arrow keys move
 * the selection to the next entry that is available, which is the stock behavior of toggle
 * buttons in a group.
 */
final class Sidebar extends VBox {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Destination, ToggleButton> entries = new EnumMap<>(Destination.class);
    private final ReadOnlyObjectWrapper<Destination> selected = new ReadOnlyObjectWrapper<>();

    /** @param available the destinations that have a page; the first of them is selected */
    Sidebar(Set<Destination> available) {
        getStyleClass().add("sidebar");
        for (Destination destination : Destination.values()) {
            ToggleButton entry = newEntry(destination, available.contains(destination));
            entries.put(destination, entry);
            getChildren().add(entry);
            if (group.getSelectedToggle() == null && !entry.isDisable()) {
                group.selectToggle(entry);
                selected.set(destination);
            }
        }
        group.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                // A toggle button is switched off by a click on it; a page list always has a
                // selection.
                if (before != null) {
                    group.selectToggle(before);
                }
            } else {
                selected.set((Destination) after.getUserData());
            }
        });

        if (available.size() < Destination.values().length) {
            // Directly below the entries it explains.
            Label legend = new Label("Dimmed pages are not available in this version.");
            legend.getStyleClass().addAll("sidebar-legend", "text-caption", "text-muted");
            legend.setWrapText(true);
            legend.setMinHeight(Region.USE_PREF_SIZE);
            getChildren().add(legend);
        }
    }

    /** The selected destination; it changes when another entry is selected. */
    ReadOnlyObjectProperty<Destination> selectedProperty() {
        return selected.getReadOnlyProperty();
    }

    /** Returns the selected destination. */
    Destination selected() {
        return selected.get();
    }

    /** Returns the entry of a destination, for tests that drive it by keyboard. */
    ToggleButton entry(Destination destination) {
        return entries.get(destination);
    }

    private ToggleButton newEntry(Destination destination, boolean available) {
        ToggleButton entry = new ToggleButton(destination.title());
        entry.getStyleClass().setAll("nav-item");
        entry.setUserData(destination);
        entry.setToggleGroup(group);
        entry.setMaxWidth(Double.MAX_VALUE);
        entry.setDisable(!available);
        if (!available) {
            entry.setAccessibleText(destination.title() + ", not available in this version");
        }
        // One Tab stop for the whole list, at the selected entry; the arrow keys reach the rest.
        entry.focusTraversableProperty().bind(entry.selectedProperty());
        return entry;
    }
}
