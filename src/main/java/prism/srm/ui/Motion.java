package prism.srm.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.util.Duration;

/**
 * The durations of the micro-motion of the user interface (docs/UI_GUIDE.md, Motion), and the
 * one place that asks whether the user wants less of it.
 *
 * <p>Every transition is short, finite, and started by the user or by a change of state; nothing
 * moves on its own and no value of the monitoring is animated. When the user prefers reduced
 * motion, every duration is zero and a state is shown at once. The preference is the one of the
 * scene of the node, which is the platform's unless a scene sets its own (the snapshot harness
 * does, so that a render is never caught in the middle of a transition). A node that is in no
 * scene cannot be seen moving, so for it every duration is zero as well.
 */
public final class Motion {

    /** A page coming into the page area. */
    public static final int PAGE_MS = 160;

    /** The surface of a segmented selector sliding to the entry chosen. */
    public static final int SLIDE_MS = 160;

    /** The inspector showing a process after it showed none. */
    public static final int REVEAL_MS = 140;

    /** A panel sliding in (UI lab v2: 160-240 ms). */
    public static final int PANEL_MS = 200;

    /** A panel or a sheet going away (UI lab v2: exits take 120 ms, easing in). */
    public static final int EXIT_MS = 120;

    /** One state giving way to another, as "Reading…" to a value. */
    public static final int CROSSFADE_MS = 120;

    private Motion() {}

    /**
     * The duration a transition on a node takes, or zero when the user prefers reduced motion or
     * the node is in no scene.
     *
     * @param node the node the transition is on; its scene is asked
     */
    public static Duration of(Node node, int millis) {
        return reduced(node) ? Duration.ZERO : Duration.millis(millis);
    }

    /** Whether a node is to move at all: not when its scene asks for less motion, and not without a scene. */
    public static boolean reduced(Node node) {
        Scene scene = node.getScene();
        return scene == null || scene.getPreferences().isReducedMotion();
    }
}
