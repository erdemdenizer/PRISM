package prism.srm.ui.oom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.OomState;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * CAP-2 (A-M4): the buttons of the OOM Guard page say why they are disabled on screen, in a line under
 * them, and to a screen reader as their help; not only in a tooltip, which a disabled control cannot
 * show. When they are enabled is the view model's choice, unchanged.
 */
@Tag("ui-snapshot")
class OomActionReasonsSceneTest {

    @Test
    void withoutASelectionTheReasonsOfTheDisabledButtonsAreOnScreen() {
        List<Object> seen = SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, OomActionReasonsSceneTest::page, root -> {
            Button management = button(root, "OOM management…");
            Button release = button(root, "Release OOM Guard hold");
            Labeled reasons = (Labeled) root.lookup(".oom-action-reasons");
            return List.of(
                    management.isDisabled(),
                    release.isDisabled(),
                    reasons.isVisible() && reasons.getHeight() > 0,
                    !management.getAccessibleHelp().isBlank()
                            && reasons.getText().contains(management.getAccessibleHelp()),
                    !release.getAccessibleHelp().isBlank() && reasons.getText().contains(release.getAccessibleHelp()),
                    management.getParent().getStyleClass().contains("action-holder"),
                    management.getParent().isVisible());
        });

        assertThat(seen).containsExactly(true, true, true, true, true, true, true);
    }

    private static Button button(Node root, String text) {
        return root.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static OomGuardPage page() {
        OomGuardPage page = new OomGuardPage(
                new ValueFormatter(Locale.US),
                ZoneOffset.UTC,
                OomFixtures.settings(true, true, false),
                settings -> {},
                request -> {},
                identity -> {});
        page.show(OomFixtures.view(
                OomFixtures.settings(true, true, false),
                OomState.NORMAL,
                Optional.of(OomFixtures.reading(46)),
                List.of(),
                List.of()));
        return page;
    }
}
