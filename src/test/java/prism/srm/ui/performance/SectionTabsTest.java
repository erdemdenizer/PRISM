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

    /** The surface under the selected entry slides to it, or is simply there with reduced motion. */
    @Test
    void theSelectedSurfaceSlidesToTheEntryChosenUnlessTheUserPrefersLessMotion() {
        List<Object> seen = inTabs(Section.CPU, (tabs, selections) -> {
            List<Object> facts = new ArrayList<>();
            javafx.scene.Node indicator = tabs.lookup(".segment-indicator");
            press(tabs.entry(Section.CPU), KeyCode.RIGHT);
            tabs.layout();
            facts.add(tabs.sliding());
            facts.add(indicator.getLayoutX()
                    == tabs.entry(Section.MEMORY).getLayoutX()
                            + tabs.getInsets().getLeft());
            tabs.getScene().getPreferences().setReducedMotion(false);
            press(tabs.entry(Section.MEMORY), KeyCode.RIGHT);
            facts.add(tabs.sliding());
            return facts;
        });

        assertThat(seen).containsExactly(false, true, true);
    }

    /** Halfway through a slide the surface is between the two entries, in place and in width. */
    @Test
    void theSurfaceIsBetweenTheTwoEntriesHalfwayThroughASlideAndOnTheTargetAtItsEnd() {
        List<Object> seen = inTabs(Section.CPU, (tabs, selections) -> {
            tabs.getScene().getPreferences().setReducedMotion(false);
            javafx.scene.Node indicator = tabs.lookup(".segment-indicator");
            ToggleButton from = tabs.entry(Section.CPU);
            ToggleButton to = tabs.entry(Section.NETWORK);
            double inset = tabs.getInsets().getLeft();
            tabs.entry(Section.NETWORK).fire();
            tabs.jumpSlideTo(javafx.util.Duration.millis(80));
            double midX = indicator.getLayoutX();
            double midWidth = indicator.getLayoutBounds().getWidth();
            tabs.jumpSlideTo(javafx.util.Duration.millis(160));
            List<Object> facts = new ArrayList<>();
            facts.add(midX > from.getLayoutX() + inset && midX < to.getLayoutX() + inset);
            facts.add(midWidth > Math.min(from.getWidth(), to.getWidth())
                    && midWidth < Math.max(from.getWidth(), to.getWidth()));
            facts.add(indicator.getLayoutX() == to.getLayoutX() + inset);
            facts.add(indicator.getLayoutBounds().getWidth() == to.getWidth());
            return facts;
        });

        assertThat(seen).containsExactly(true, true, true, true);
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
