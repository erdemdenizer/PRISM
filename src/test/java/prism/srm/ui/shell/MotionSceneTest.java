package prism.srm.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import prism.srm.ui.format.ValueFormatter;
import prism.srm.ui.snapshot.SnapshotHarness;
import prism.srm.ui.snapshot.SnapshotHarness.Theme;

/**
 * The motion of a page coming into the page area, read from a real scene: it runs when the user
 * chooses a page, it is finite and stops when another page is chosen, and with reduced motion
 * there is none and the page is simply there. The harness asks for reduced motion; the test
 * switches it on the scene.
 */
@Tag("ui-snapshot")
class MotionSceneTest {

    @Test
    void withReducedMotionAPageIsSimplyThere() {
        List<Object> seen = inShell(shell -> {
            select(shell, Destination.PERFORMANCE);
            Node page = page(shell);
            return List.of(shell.pageComingIn(), page.getOpacity(), page.getTranslateY());
        });

        assertThat(seen).containsExactly(false, 1.0, 0.0);
    }

    @Test
    void withoutReducedMotionAPageComesInWithAShortFiniteMotionThatTheNextPageSettles() {
        List<Object> seen = inShell(shell -> {
            shell.getScene().getPreferences().setReducedMotion(false);
            List<Object> facts = new ArrayList<>();
            select(shell, Destination.PERFORMANCE);
            Node performance = page(shell);
            facts.add(shell.pageComingIn());
            facts.add(performance.getOpacity() < 1);
            // The next page settles the one before it: nothing is left half transparent.
            select(shell, Destination.PROCESSES);
            facts.add(performance.getOpacity());
            facts.add(performance.getTranslateY());
            facts.add(shell.pageComingIn());
            // Reduced motion again: the motion is cut short and the page is there.
            shell.getScene().getPreferences().setReducedMotion(true);
            select(shell, Destination.OVERVIEW);
            Node overview = page(shell);
            facts.add(shell.pageComingIn());
            facts.add(overview.getOpacity());
            return facts;
        });

        assertThat(seen).containsExactly(true, true, 1.0, 0.0, true, false, 1.0);
    }

    private static void select(Shell shell, Destination destination) {
        shell.navigation().entry(destination).fire();
    }

    private static Node page(Shell shell) {
        return shell.pageArea().getContent();
    }

    private static <T> T inShell(java.util.function.Function<Shell, T> scenario) {
        return SnapshotHarness.inspect(
                Theme.LIGHT,
                1100,
                700,
                () -> new Shell(new ValueFormatter(Locale.US), ZoneOffset.UTC, 60, "C:\\logs", identity -> {}),
                root -> scenario.apply((Shell) root));
    }
}
