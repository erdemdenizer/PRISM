package prism.srm.ui;

/**
 * The window sizes at which the layout of the user interface takes a turn (lab-docs/LAB_DESIGN_SPEC.md,
 * section 7), in one place: the shell, the Processes page and the Limits page decide by these numbers
 * and by nothing else, so that every page takes the same turn at the same size.
 *
 * <p>The sizes of the shell are of the window's content (its scene). The sizes of the inspectors are of
 * the page (fix round G): a page knows its own room, which is what the window leaves it after the
 * navigation, whatever that takes.
 */
public final class Breakpoints {

    /** Below this width the page margins and the page header are those of a narrow window. */
    public static final double NARROW_BELOW = 820;

    /**
     * The width of the rail, its hairline included (shell.css): each icon with its name under it, below
     * {@link #LABELLED_NAV_FROM} and in every narrow window (owner decision after fix round H: the names
     * show at every width). Measured: the widest name, "Performance" in Plex SemiBold 11 px, is 66.0 px
     * (its advance widths); an entry is 70 px with no side padding, the list 4 px at its left and 5 px
     * at its right, and the hairline: 80. The 1366 x 768 window (1352 px of scene) keeps 1272 px of page,
     * its inspector beside the table ({@link #SIDE_FROM}); 84 px left the Processes columns 0.4 px short
     * at fractional scales (ProcessTableScaleTest).
     */
    public static final double RAIL_WIDTH = 80;

    /**
     * The width of the sidebar, names beside the icons, its hairline included
     * (shell.css). Measured: the widest name, "Performance" in Plex SemiBold 13 px, is 78.1 px, and
     * "Ctrl+7" in Plex 12 px 33.7 px; with the 20 px icon, 12 px after it, 12 px before the shortcut
     * and 10 px of padding at each end a row needs 176 px, and the list has 8 px at each side of it.
     */
    public static final double SIDEBAR_WIDTH = 200;

    /**
     * From this width the navigation shows its names: a sidebar of {@link #SIDEBAR_WIDTH} in place of
     * the {@link #RAIL_WIDTH} rail. Measured from the inspector, not chosen: {@link #SIDE_FROM} is a page
     * width, so from here the page beside the sidebar still has the room to keep its inspector beside
     * the table, and no window takes a worse inspector because of the names. Windows of 1366 x 768 and
     * 1440 x 900 (1352 and 1426 px of scene) keep the rail and the inspector beside the table.
     */
    public static final double LABELLED_NAV_FROM = Breakpoints.SIDE_FROM + SIDEBAR_WIDTH;

    /** From this width the page margins are 24 px and the Overview shows its four metrics in a row. */
    public static final double WIDE_FROM = 1100;

    /** From this width text blocks keep a readable measure and an inspector is at its widest at first. */
    public static final double ULTRAWIDE_FROM = 2200;

    /** Below this height the rhythm of the window is compact. */
    public static final double LOW_BELOW = 560;

    /**
     * Fix round G: the rules of the inspectors are for the room of the page, not for the window. The page
     * has the window less the navigation beside it, whose width differs from one window width to another
     * (rail or wide sidebar), and less the toolbar above it and the status bar under it. A page reads
     * its own width and height (its margins included) and asks {@link #inspectorFor}.
     *
     * <p>From this page width an inspector stands beside its table, at any height. Measured: beside the
     * inspector at its least (320 px and the 12 px divider) the Processes table needs 866 px for its
     * columns, and the page its 2 x 24 px margins: 1246 px; 1260 leaves the rounding some room (it was
     * 1320 px of window with the 60 px rail). Below it the table would scroll sideways
     * (ProcessTableWidthTest, BreakpointAgreementSceneTest).
     */
    public static final double SIDE_FROM = 1260;

    /**
     * From this page width an inspector under its table stands in columns: its three groups of actions
     * side by side over "Now" and "Executable" side by side (measured: about 760 px of actions, with the
     * margins of the page and the panel about 850). Fix round G: lowered from the 1040 px of page that a
     * 1100 px window has, so that the default window, whose scene is 1086 px wide in a real Windows frame,
     * shows the inspector instead of a sheet (LO-05, PR-05).
     */
    public static final double BOTTOM_FROM = 960;

    /**
     * The least page height for an inspector under its table: the table with five rows, the pinned part
     * of the inspector (its name and its actions) and the least of its details (owner decision K2). A
     * real 1100 x 700 window leaves the page 579 px.
     */
    public static final double BOTTOM_LEAST_HEIGHT = 500;

    /**
     * A tall narrow page (fix round G, PR-07, LO-23; LAB_DESIGN_SPEC section 7, "tall: bottom"): from this
     * width and {@link #TALL_FROM} high, the inspector stands under its table with its groups under one
     * another, as beside it, instead of in a sheet over an empty table.
     */
    public static final double TALL_BOTTOM_FROM = 640;

    /** See {@link #TALL_BOTTOM_FROM}. */
    public static final double TALL_FROM = 800;

    /** Where an inspector stands for a page of a size. */
    public enum Inspector {
        /** Beside the table, at the right: from {@link #SIDE_FROM} wide, at any height (it scrolls inside). */
        SIDE,
        /** Under the table: in columns from {@link #BOTTOM_FROM} wide, stacked in a tall narrow page. */
        BOTTOM,
        /** In a sheet over the page from its right, opened on request: everywhere else. */
        SHEET
    }

    private Breakpoints() {}

    /**
     * Where an inspector stands in a page of this size, its margins included; the one rule of the
     * Processes and the Limits page.
     */
    public static Inspector inspectorFor(double pageWidth, double pageHeight) {
        if (pageWidth >= SIDE_FROM) {
            return Inspector.SIDE;
        }
        if (pageWidth >= BOTTOM_FROM && pageHeight >= BOTTOM_LEAST_HEIGHT) {
            return Inspector.BOTTOM;
        }
        if (pageWidth >= TALL_BOTTOM_FROM && pageHeight >= TALL_FROM) {
            return Inspector.BOTTOM;
        }
        return Inspector.SHEET;
    }

    /** Whether an inspector under its table, in a page this wide, stands in columns (else stacked). */
    public static boolean inspectorInColumns(double pageWidth) {
        return pageWidth >= BOTTOM_FROM;
    }
}
