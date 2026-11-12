package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI audit LO-14: the badge of OOM Guard in the navigation takes the tone of the state the page and the
 * status bar show it in: the warning color at Warning and Critical, the error color in an Emergency.
 */
@Tag("ui-snapshot")
class OomBadgeToneSceneTest {

    private static final OomSettings MONITORING = OomFixtures.settings(true, true, false);

    @Test
    void theBadgeHasTheToneOfTheState() {
        List<Paint> seen = SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, KeyboardRoutesSceneTest::populated, root -> {
            Shell shell = (Shell) root;
            List<Paint> dots = new ArrayList<>();
            for (OomState state : List.of(OomState.WARNING, OomState.CRITICAL, OomState.EMERGENCY, OomState.WARNING)) {
                shell.showOom(
                        OomFixtures.view(MONITORING, state, Optional.of(OomFixtures.reading(4)), List.of(), List.of()));
                shell.applyCss();
                shell.layout();
                Region dot =
                        (Region) shell.navigation().entry(Destination.OOM_GUARD).lookup(".nav-badge");
                dots.add(dot.getBackground().getFills().get(1).getFill());
            }
            return dots;
        });

        Color warning = Color.web("#8a5100");
        Color error = Color.web("#b0222a");
        assertThat(seen).containsExactly(warning, warning, error, warning);
    }
}
