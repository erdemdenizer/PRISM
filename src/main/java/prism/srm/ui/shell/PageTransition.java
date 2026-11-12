package prism.srm.ui.shell;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;
import prism.srm.ui.Motion;

/**
 * The short motion of a page coming into the page area: it fades in while it moves up a few
 * units. One finite transition, started when the user chooses a page; with reduced motion the
 * page is simply there.
 *
 * <p>One instance per shell. A page chosen while the previous one is still coming in stops that
 * motion and leaves the previous page as it rests, so no page is ever left half transparent.
 */
final class PageTransition {

    /** How far up a page moves while it comes in. */
    private static final double RISE = 4;

    private final FadeTransition fade = new FadeTransition();
    private final TranslateTransition rise = new TranslateTransition();
    private final ParallelTransition motion = new ParallelTransition(fade, rise);
    private Node current;

    PageTransition() {
        fade.setFromValue(0);
        fade.setToValue(1);
        rise.setFromY(RISE);
        rise.setToY(0);
        motion.setInterpolator(Interpolator.SPLINE(0.2, 0.8, 0.2, 1));
    }

    /**
     * Brings a page in. Call on the JavaFX Application Thread. A page that is not in a scene yet,
     * as when the shell is built, cannot be seen coming in and is left as it is ({@link Motion}).
     */
    void playFor(Node page) {
        settle();
        Duration duration = Motion.of(page, Motion.PAGE_MS);
        if (duration.equals(Duration.ZERO)) {
            return;
        }
        current = page;
        fade.setNode(page);
        rise.setNode(page);
        fade.setDuration(duration);
        rise.setDuration(duration);
        motion.playFromStart();
    }

    /** Stops the motion and leaves the page it was on as it rests. */
    void settle() {
        if (current == null) {
            return;
        }
        motion.stop();
        current.setOpacity(1);
        current.setTranslateY(0);
        current = null;
    }

    /** Whether a page is coming in right now. */
    boolean running() {
        return motion.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }
}
