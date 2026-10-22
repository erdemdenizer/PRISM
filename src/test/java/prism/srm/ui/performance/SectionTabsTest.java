package prism.srm.ui.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import javafx.event.Event;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Drives the choice of the section in a real scene. The key events are made here and delivered to
 * the entries; no window has focus, so this proves what the entries do with a key, not that a key
 * pressed by a person reaches them.
 */
@Tag("ui-snapshot")
class SectionTabsTest {

    @Test
    void listsTheFourSectionsInTheOrderOfTheSpecificationWithTheGivenOneSelected() {
        List<Object> seen = inTabs(Section.DISK, (tabs, selections) -> {
            List<Object> facts = new ArrayList<>();
            for (Section section : Section.values()) {
                facts.add(tabs.entry(section).getText());
                facts.add(tabs.entry(section).isSelected());
            }
            facts.add(selections.size());
            return facts;
        });

        assertThat(seen).containsExactly("CPU", false, "Memory", false, "Disk", true, "Network", false, 0);
    }

    @Test
    void theArrowKeysMoveTheSelectionAndTellTheListener() {
        List<Section> selections = inTabs(Section.CPU, (tabs, seen) -> {
            press(tabs.entry(Section.CPU), KeyCode.RIGHT);
            press(tabs.entry(Section.MEMORY), KeyCode.RIGHT);
            press(tabs.entry(Section.DISK), KeyCode.LEFT);
            return List.copyOf(seen);
        });

        assertThat(selections).containsExactly(Section.MEMORY, Section.DISK, Section.MEMORY);
    }

    @Test
    void theRowIsOneTabStopAtTheSelectedEntry() {
        List<Boolean> traversable = inTabs(Section.CPU, (tabs, selections) -> {
            List<Boolean> seen = new ArrayList<>();
            seen.add(tabs.entry(Section.CPU).isFocusTraversable());
            seen.add(tabs.entry(Section.MEMORY).isFocusTraversable());
            press(tabs.entry(Section.CPU), KeyCode.RIGHT);
            seen.add(tabs.entry(Section.CPU).isFocusTraversable());
            seen.add(tabs.entry(Section.MEMORY).isFocusTraversable());
            return seen;
        });

        assertThat(traversable).containsExactly(true, false, false, true);
    }

    @Test
    void activatingAnEntrySelectsItsSection() {
        List<Section> selections = inTabs(Section.CPU, (tabs, seen) -> {
            tabs.entry(Section.NETWORK).fire();
            return List.copyOf(seen);
        });

        assertThat(selections).containsExactly(Section.NETWORK);
    }

    /** A toggle button is switched off by a second click; the row must keep its selection. */
    @Test
    void activatingTheSelectedEntryAgainKeepsItSelectedAndTellsNobody() {
        List<Object> seen = inTabs(Section.MEMORY, (tabs, selections) -> {
            ToggleButton memory = tabs.entry(Section.MEMORY);
            memory.fire();
            return List.of(memory.isSelected(), selections.size());
        });

        assertThat(seen).containsExactly(true, 0);
    }

    private static <T> T inTabs(Section selected, BiFunction<SectionTabs, List<Section>, T> inspection) {
        List<Section> selections = new ArrayList<>();
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                520,
                40,
                () -> new SectionTabs(selected, selections::add),
                root -> inspection.apply((SectionTabs) root, selections));
    }

    private static void press(ToggleButton entry, KeyCode key) {
        Event.fireEvent(entry, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
        Event.fireEvent(entry, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
    }
}
