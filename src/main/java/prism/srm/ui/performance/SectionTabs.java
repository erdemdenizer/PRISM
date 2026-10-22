package prism.srm.ui.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/**
 * The choice of the section the Performance page shows: its four resources as a row of entries.
 *
 * <p>Keyboard: the row is one stop in the Tab order, at the selected entry. The arrow keys move
 * the selection, which is the stock behavior of toggle buttons in a group; the navigation of the
 * shell works the same way.
 */
final class SectionTabs extends HBox {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Section, ToggleButton> entries = new EnumMap<>(Section.class);
    private Section selected;

    /**
     * @param selected the section selected at first
     * @param onSelected told the section each time another one is selected
     */
    SectionTabs(Section selected, Consumer<Section> onSelected) {
        getStyleClass().add("section-tabs");
        for (Section section : Section.values()) {
            ToggleButton entry = newEntry(section);
            entries.put(section, entry);
            getChildren().add(entry);
        }
        this.selected = selected;
        group.selectToggle(entries.get(selected));
        group.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                // A toggle button is switched off by a click on it; the row always has a selection.
                if (before != null) {
                    group.selectToggle(before);
                }
            } else if (after.getUserData() != this.selected) {
                // Not for the entry that was switched off and on again just above.
                this.selected = (Section) after.getUserData();
                onSelected.accept(this.selected);
            }
        });
    }

    /** Returns the entry of a section, for tests that drive it by keyboard. */
    ToggleButton entry(Section section) {
        return entries.get(section);
    }

    private ToggleButton newEntry(Section section) {
        ToggleButton entry = new ToggleButton(section.title());
        entry.getStyleClass().setAll("section-tab");
        entry.setUserData(section);
        entry.setToggleGroup(group);
        // One Tab stop for the whole row, at the selected entry; the arrow keys reach the rest.
        entry.focusTraversableProperty().bind(entry.selectedProperty());
        return entry;
    }
}
