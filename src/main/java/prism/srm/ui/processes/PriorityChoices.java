package prism.srm.ui.processes;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.collections.ObservableList;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import prism.srm.application.ControlMessages;
import prism.srm.domain.PriorityLevel;

/**
 * The five levels of priority as menu items, the same in the inspector and in the context menu of
 * the table (CTL-6; Realtime is never offered). The level the last listing shows is checked;
 * none is when the listing shows a number that stands for no level, and a line above them says
 * which number it is. The mark follows the listing only: choosing a level sends the request and
 * puts the mark back where the listing has it, so a level asked for is never shown as one the
 * process has. Choosing the level that is checked sends nothing.
 */
final class PriorityChoices {

    private final ProcessActions actions;
    private final ToggleGroup group = new ToggleGroup();
    private final Map<PriorityLevel, RadioMenuItem> levels = new EnumMap<>(PriorityLevel.class);

    private PriorityChoices(ProcessActions actions) {
        this.actions = actions;
    }

    /**
     * Puts the items into a menu and keeps their mark on the level the listing shows.
     *
     * @param choose sends a change to a level other than the current one
     */
    static PriorityChoices fill(
            ObservableList<MenuItem> items, ProcessActions actions, Consumer<PriorityLevel> choose) {
        PriorityChoices choices = new PriorityChoices(actions);
        MenuItem unmapped = new MenuItem();
        unmapped.textProperty().bind(actions.unmappedPriorityProperty());
        // Disabled, so the keyboard passes it; muted, not dimmed, because it says what the process has.
        unmapped.setDisable(true);
        unmapped.getStyleClass().add("priority-unmapped");
        unmapped.visibleProperty().bind(actions.unmappedPriorityProperty().isNotEmpty());
        SeparatorMenuItem separator = new SeparatorMenuItem();
        separator.visibleProperty().bind(unmapped.visibleProperty());
        items.addAll(unmapped, separator);
        for (PriorityLevel level : PriorityLevel.values()) {
            RadioMenuItem item = new RadioMenuItem(ControlMessages.levelName(level));
            item.setToggleGroup(choices.group);
            item.getStyleClass().add("priority-level");
            item.setOnAction(event -> {
                if (level != actions.observedLevelProperty().get()) {
                    choose.accept(level);
                }
                choices.markObserved();
            });
            choices.levels.put(level, item);
            items.add(item);
        }
        actions.observedLevelProperty().addListener((observable, before, after) -> choices.markObserved());
        choices.markObserved();
        return choices;
    }

    /** Puts the mark on the level the listing shows, or on none. */
    void markObserved() {
        PriorityLevel observed = actions.observedLevelProperty().get();
        group.selectToggle(observed == null ? null : levels.get(observed));
    }

    /** The item of a level; for tests. */
    RadioMenuItem item(PriorityLevel level) {
        return levels.get(level);
    }
}
