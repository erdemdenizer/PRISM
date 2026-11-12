package prism.srm.platform;

/**
 * The colors of a window frame, each as {@code 0xRRGGBB}.
 *
 * @param dark whether the frame is dark: the system then draws its caption buttons for a dark caption
 * @param caption the caption bar
 * @param text the caption text
 * @param border the one-pixel border around the window
 */
public record ChromeColors(boolean dark, int caption, int text, int border) {

    public ChromeColors {
        check(caption, "caption");
        check(text, "text");
        check(border, "border");
    }

    private static void check(int rgb, String name) {
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException(name + " is not 0xRRGGBB: " + Integer.toHexString(rgb));
        }
    }
}
