package prism.srm.ui.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import prism.srm.ui.Motion;

/**
 * The choice of the section the Performance page shows: its four resources as one segmented
 * selector. A surface under the entries marks the selected one and slides to it when another is
 * chosen (docs/UI_GUIDE.md, Motion); with reduced motion it is simply there.
 *
 * <p>Keyboard: the row is one stop in the Tab order, at the selected entry. The arrow keys move
 * the selection, which is the stock behavior of toggle buttons in a group; the navigation of the
 * shell works the same way.
 */
final class SectionTabs extends StackPane {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Section, ToggleButton> entries = new EnumMap<>(Section.class);
    private final Region indicator = new Region();
    private final Timeline slide = new Timeline();

    /** Where the surface is and how wide, while it slides; the timeline moves these two. */
    private final DoubleProperty slideX = new SimpleDoubleProperty();

    private final DoubleProperty slideWidth = new SimpleDoubleProperty();

    private Section selected;

    /**
     * @param selected the section selected at first
     * @param onSelected told the section each time another one is selected
     */
    SectionTabs(Section selected, Consumer<Section> onSelected) {
        getStyleClass().add("segmented");
        setMaxWidth(USE_PREF_SIZE);
        HBox row = new HBox();
        row.getStyleClass().add("segments");
        for (Section section : Section.values()) {
            if (!row.getChildren().isEmpty()) {
                Region divider = new Region();
                divider.getStyleClass().add("segment-divider");
                row.getChildren().add(divider);
            }
            ToggleButton entry = newEntry(section);
            entries.put(section, entry);
            row.getChildren().add(entry);
        }
        indicator.getStyleClass().add("segment-indicator");
        indicator.setManaged(false);
        indicator.setMouseTransparent(true);
        // The surface is unmanaged, so a size it is given through a property changes nothing by
        // itself: each step of the slide places and sizes it by hand.
        slideX.addListener(observable -> placeIndicator(slideX.get(), slideWidth.get()));
        slideWidth.addListener(observable -> placeIndicator(slideX.get(), slideWidth.get()));
        getChildren().setAll(indicator, row);

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
                moveIndicator();
                onSelected.accept(this.selected);
            }
        });
    }

    /** Selects a section as a click on its entry does: the page is told, and the surface moves to it. */
    void select(Section section) {
        group.selectToggle(entries.get(section));
    }

    /** Returns the entry of a section, for tests that drive it by keyboard. */
    ToggleButton entry(Section section) {
        return entries.get(section);
    }

    /** Whether the surface is sliding to the selected entry right now; for tests of the motion. */
    boolean sliding() {
        return slide.getStatus() == Timeline.Status.RUNNING;
    }

    /** Moves the slide to a moment of its course, for tests that cannot wait for pulses. */
    void jumpSlideTo(Duration moment) {
        slide.jumpTo(moment);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // A new size puts the surface where it belongs at once; a slide in progress is left to
        // run, it ends on the same place.
        if (!sliding()) {
            placeIndicator(entries.get(selected));
        }
    }

    private void moveIndicator() {
        ToggleButton target = entries.get(selected);
        Duration duration = Motion.of(this, Motion.SLIDE_MS);
        slide.stop();
        if (duration.equals(Duration.ZERO) || indicator.getWidth() == 0) {
            placeIndicator(target);
            return;
        }
        // Both read before either is set: setting one places the surface with the other.
        double startX = indicator.getLayoutX();
        double startWidth = indicator.getWidth();
        slideX.set(startX);
        slideWidth.set(startWidth);
        slide.getKeyFrames()
                .setAll(new KeyFrame(
                        duration,
                        new KeyValue(slideX, target.getLayoutX() + snappedLeftInset()),
                        new KeyValue(slideWidth, target.getWidth())));
        slide.setOnFinished(event -> placeIndicator(target));
        slide.playFromStart();
    }

    private void placeIndicator(ToggleButton target) {
        placeIndicator(target.getLayoutX() + snappedLeftInset(), target.getWidth());
    }

    private void placeIndicator(double x, double width) {
        ToggleButton any = entries.get(selected);
        double y = any.getLayoutY() + snappedTopInset();
        indicator.resizeRelocate(x, y, width, any.getHeight());
    }

    private ToggleButton newEntry(Section section) {
        ToggleButton entry = new ToggleButton(section.title());
        entry.getStyleClass().setAll("segment");
        entry.setUserData(section);
        entry.setToggleGroup(group);
        // One Tab stop for the whole row, at the selected entry; the arrow keys reach the rest.
        entry.focusTraversableProperty().bind(entry.selectedProperty());
        return entry;
    }
}
