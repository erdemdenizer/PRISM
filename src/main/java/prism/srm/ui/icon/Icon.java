package prism.srm.ui.icon;

import java.util.List;

/*
 * The path data below is from Lucide (https://lucide.dev), ISC License, Copyright (c) 2026 Lucide
 * Icons and Contributors. Icons that Lucide adapted from Feather are also under the MIT License,
 * Copyright (c) 2013-present Cole Bemis. Both notices are in THIRD-PARTY-NOTICES.md at the root
 * of the repository. Shapes that Lucide draws as rect, circle or line are written here as path
 * data; nothing else was changed.
 */

/**
 * The icons the application draws: a small subset of Lucide, each as the path data of its strokes
 * on a 24 by 24 grid. {@link IconView} draws one at the size a place needs.
 *
 * <p>Named by what the glyph shows, not by where it is used: which destination of the navigation
 * takes which icon is decided where the destinations are.
 */
public enum Icon {
    /** Four panels: the layout of the Overview. */
    LAYOUT_DASHBOARD(
            "M4 3h5a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 3h5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 12h5a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z",
            "M4 16h5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z"),
    /** A list (Lucide "list"): the processes, a list of rows. */
    LIST("M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13"),
    /** A pulse line: live load. */
    ACTIVITY(
            "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"),
    /** Sliders: limits that are set. */
    SLIDERS_HORIZONTAL(
            "M10 5H3", "M12 19H3", "M14 3v4", "M16 17v4", "M21 12h-9", "M21 19h-5", "M21 5h-7", "M8 10v4", "M8 12H3"),
    /** A shield with a mark: the guard against running out of memory. */
    SHIELD_ALERT(
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
            "M12 8v4",
            "M12 16h.01"),
    /** A scroll with lines: the log. */
    SCROLL_TEXT(
            "M15 12h-5",
            "M15 8h-5",
            "M19 17V5a2 2 0 0 0-2-2H4",
            "M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3"),
    /** A gear. */
    SETTINGS(
            "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
            "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"),
    /** A magnifying glass: the filter. */
    SEARCH("m21 21-4.34-4.34", "M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0z"),
    /** A cross: clear, close. */
    X("M18 6 6 18", "m6 6 12 12"),
    /** Sorted smallest first. */
    CHEVRON_UP("m18 15-6-6-6 6"),
    /** Sorted largest first. */
    CHEVRON_DOWN("m6 9 6 6 6-6"),
    /** A check mark. */
    CHECK("M20 6 9 17l-5-5"),
    /** A circle with a mark: something to look at. */
    CIRCLE_ALERT("M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0z", "M12 8v4", "M12 16h.01"),
    /** Three lines (Lucide "menu"): shows the navigation in a narrow window. */
    MENU("M4 12h16", "M4 6h16", "M4 18h16"),
    /** Three dots (Lucide "ellipsis"): more commands. */
    ELLIPSIS(
            "M13 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0z",
            "M20 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0z",
            "M6 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0z");

    /** The size of the grid the path data is drawn on. */
    public static final double GRID = 24;

    private final List<String> paths;

    Icon(String... paths) {
        this.paths = List.of(paths);
    }

    /** The strokes of the icon, as SVG path data on the 24 by 24 grid. */
    public List<String> paths() {
        return paths;
    }
}
