package prism.srm.ui.shell;

import java.util.List;

/**
 * The scene sizes of real Windows windows at 100 % scale (owner decision K8): the scene of a window
 * is about 14 px narrower and 45 px lower than its outer size, so a 1100 x 700 window has a
 * 1086 x 655 scene and takes the layout of that size, not of a 1100 x 700 snapshot. Snapshots at
 * these sizes carry the marker {@code -real} in their names; the numbers in the file names are
 * scene sizes.
 */
final class RealSceneSizes {

    /** A scene size, with the outer size of the window it belongs to. */
    record Size(int width, int height, String window) {}

    static final List<Size> ALL = List.of(
            new Size(706, 435, "720x480"),
            new Size(746, 515, "760x560"),
            new Size(1086, 655, "1100x700"),
            new Size(1163, 739, "1177x784"),
            new Size(1352, 723, "1366x768"),
            new Size(1426, 855, "1440x900"),
            new Size(1586, 455, "1600x500"),
            new Size(1906, 987, "1920x1032"));

    private RealSceneSizes() {}
}
