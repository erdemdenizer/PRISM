package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomState;
import prism.srm.domain.OomView;
import prism.srm.ui.OomFixtures;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * UI lab v2: the badge of OOM Guard in the navigation (lab-docs/LAB_DESIGN_SPEC.md, section 2). It marks
 * a real state only: memory pressure from Warning up. The views reach the shell as the application hands
 * them over ({@link Shell#showOom}); the dot, the pseudo-class and the accessible name of the entry are
 * read from the scene.
 */
@Tag("ui-snapshot")
class ShellOomBadgeSceneTest {

    private static final OomSettings MONITORING = OomFixtures.settings(true, true, false);

    @Test
    void theBadgeAndTheNameFollowThePressureAndGoAwayAtNormal() {
        List<Mark> seen = SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, KeyboardRoutesSceneTest::populated, root -> {
            Shell shell = (Shell) root;
            List<Mark> marks = new ArrayList<>();
            shell.showOom(view(MONITORING, OomState.NORMAL, 46));
            marks.add(mark(shell));
            shell.showOom(view(MONITORING, OomState.WARNING, 13));
            marks.add(mark(shell));
            shell.showOom(view(MONITORING, OomState.EMERGENCY, 3));
            marks.add(mark(shell));
            shell.showOom(view(MONITORING, OomState.NORMAL, 40));
            marks.add(mark(shell));
            return marks;
        });

        Mark normal = seen.get(0);
        assertThat(normal).isEqualTo(new Mark(Optional.empty(), false, false, "OOM Guard"));

        Mark warning = seen.get(1);
        assertThat(warning.badge())
                .hasValueSatisfying(text ->
                        assertThat(text).startsWith("Memory pressure warning").contains("13"));
        assertThat(warning.dotVisible()).isTrue();
        assertThat(warning.badged()).isTrue();
        assertThat(warning.name()).isEqualTo("OOM Guard, " + warning.badge().orElseThrow());

        Mark emergency = seen.get(2);
        assertThat(emergency.badge())
                .hasValueSatisfying(text -> assertThat(text).startsWith("Memory pressure emergency"));
        assertThat(emergency.name()).isEqualTo("OOM Guard, " + emergency.badge().orElseThrow());

        assertThat(seen.get(3)).as("back to Normal").isEqualTo(normal);
    }

    /** With monitoring switched off there is no pressure to mark, whatever the state last was. */
    @Test
    void noBadgeWhileOomGuardDoesNotMonitor() {
        Mark seen = SnapshotHarness.inspect(Theme.LIGHT, 1100, 700, KeyboardRoutesSceneTest::populated, root -> {
            Shell shell = (Shell) root;
            shell.showOom(view(MONITORING, OomState.CRITICAL, 6));
            OomSettings off = MONITORING.withSwitches(MONITORING.switches().withMonitoring(false));
            shell.showOom(view(off, OomState.CRITICAL, 6));
            return mark(shell);
        });

        assertThat(seen).isEqualTo(new Mark(Optional.empty(), false, false, "OOM Guard"));
    }

    /** What the navigation shows for OOM Guard. */
    record Mark(Optional<String> badge, boolean dotVisible, boolean badged, String name) {}

    private static Mark mark(Shell shell) {
        shell.applyCss();
        shell.layout();
        ToggleButton entry = shell.navigation().entry(Destination.OOM_GUARD);
        Node dot = entry.lookup(".nav-badge");
        return new Mark(
                shell.navigation().badge(Destination.OOM_GUARD),
                dot != null && dot.isVisible(),
                entry.getPseudoClassStates().contains(PseudoClass.getPseudoClass("badged")),
                entry.getAccessibleText());
    }

    private static OomView view(OomSettings settings, OomState state, double availablePercent) {
        return OomFixtures.view(
                settings, state, Optional.of(OomFixtures.reading(availablePercent)), List.of(), List.of());
    }
}
