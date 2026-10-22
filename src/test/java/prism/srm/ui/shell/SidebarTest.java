package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javafx.event.Event;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * Drives the navigation in a real scene. The key events are made here and delivered to the
 * entries; no window has focus, so this proves what the entries do with a key, not that a key
 * pressed by a person reaches them.
 */
@Tag("ui-snapshot")
class SidebarTest {

    private static final Set<Destination> THREE_PAGES =
            EnumSet.of(Destination.OVERVIEW, Destination.PERFORMANCE, Destination.LOGS);

    @Test
    void listsAllSevenDestinationsInTheOrderOfTheSpecification() {
        List<String> titles = inSidebar(EnumSet.of(Destination.OVERVIEW), sidebar -> {
            List<String> texts = new ArrayList<>();
            for (Destination destination : Destination.values()) {
                texts.add(sidebar.entry(destination).getText());
            }
            return texts;
        });

        assertThat(titles)
                .containsExactly("Overview", "Processes", "Performance", "Limits", "OOM Guard", "Logs", "Settings");
    }

    @Test
    void aDestinationWithoutAPageIsDisabledAndTheListSaysWhatThatMeans() {
        List<Object> seen = inSidebar(EnumSet.of(Destination.OVERVIEW), sidebar -> {
            List<Object> facts = new ArrayList<>();
            facts.add(sidebar.selected());
            facts.add(sidebar.entry(Destination.OVERVIEW).isDisable());
            for (Destination destination : EnumSet.complementOf(EnumSet.of(Destination.OVERVIEW))) {
                facts.add(sidebar.entry(destination).isDisable());
            }
            facts.add(sidebar.entry(Destination.PROCESSES).getAccessibleText());
            facts.add(sidebar.getChildren().getLast() instanceof Label legend ? legend.getText() : "no legend");
            return facts;
        });

        assertThat(seen)
                .containsExactly(
                        Destination.OVERVIEW,
                        false,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        "Processes, not available in this version",
                        "Dimmed pages are not available in this version.");
    }

    @Test
    void theArrowKeysMoveTheSelectionAndSkipWhatIsDisabled() {
        List<Destination> selections = inSidebar(THREE_PAGES, sidebar -> {
            List<Destination> seen = new ArrayList<>();
            // Processes, between Overview and Performance, is disabled.
            press(sidebar.entry(Destination.OVERVIEW), KeyCode.DOWN);
            seen.add(sidebar.selected());
            // Limits and OOM Guard, between Performance and Logs, are disabled.
            press(sidebar.entry(Destination.PERFORMANCE), KeyCode.DOWN);
            seen.add(sidebar.selected());
            press(sidebar.entry(Destination.LOGS), KeyCode.UP);
            seen.add(sidebar.selected());
            return seen;
        });

        assertThat(selections).containsExactly(Destination.PERFORMANCE, Destination.LOGS, Destination.PERFORMANCE);
    }

    @Test
    void theListIsOneTabStopAtTheSelectedEntry() {
        List<Boolean> traversable = inSidebar(THREE_PAGES, sidebar -> {
            List<Boolean> seen = new ArrayList<>();
            seen.add(sidebar.entry(Destination.OVERVIEW).isFocusTraversable());
            seen.add(sidebar.entry(Destination.PERFORMANCE).isFocusTraversable());
            press(sidebar.entry(Destination.OVERVIEW), KeyCode.DOWN);
            seen.add(sidebar.entry(Destination.OVERVIEW).isFocusTraversable());
            seen.add(sidebar.entry(Destination.PERFORMANCE).isFocusTraversable());
            return seen;
        });

        assertThat(traversable).containsExactly(true, false, false, true);
    }

    /** A toggle button is switched off by a second click; the page list must keep its selection. */
    @Test
    void activatingTheSelectedEntryAgainKeepsItSelected() {
        List<Object> seen = inSidebar(THREE_PAGES, sidebar -> {
            ToggleButton overview = sidebar.entry(Destination.OVERVIEW);
            overview.fire();
            return List.of(sidebar.selected(), overview.isSelected());
        });

        assertThat(seen).containsExactly(Destination.OVERVIEW, true);
    }

    @Test
    void whenEveryDestinationHasAPageThereIsNothingToExplain() {
        boolean legend = inSidebar(
                EnumSet.allOf(Destination.class),
                sidebar -> sidebar.getChildren().getLast() instanceof Label);

        assertThat(legend).isFalse();
    }

    private static <T> T inSidebar(Set<Destination> available, Function<Sidebar, T> inspection) {
        return SnapshotHarness.inspect(
                Theme.LIGHT, 160, 480, () -> new Sidebar(available), root -> inspection.apply((Sidebar) root));
    }

    private static void press(ToggleButton entry, KeyCode key) {
        Event.fireEvent(entry, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
        Event.fireEvent(entry, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", key, false, false, false, false));
    }
}
