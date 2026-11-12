package prism.srm.platform;

/**
 * The frame the operating system draws around a window of the application: its caption bar, the
 * caption text and the one-pixel border (UI lab v2). PRISM colors them only on Windows 11; everywhere
 * else {@link #UNAVAILABLE} answers and the frame stays as the system draws it.
 *
 * <p>An implementation finds the native window by its exact title among the windows the calling thread
 * owns, so it must be called on the thread that created the window: the JavaFX Application Thread. The
 * calls it makes there are short and do not wait on anything.
 */
public interface WindowChrome {

    /** What one attempt did. */
    enum Result {
        /** The colors are set on the window. */
        APPLIED,
        /**
         * No window of the calling thread has the title yet. JavaFX gives the native window its title a
         * moment after the window is shown, so a caller may try again on a later turn.
         */
        NOT_FOUND,
        /** More than one window of the calling thread has the title; none of them is changed. */
        AMBIGUOUS,
        /** The system cannot color the frame (another system, or Windows before build 22000). */
        UNAVAILABLE,
        /** The system refused a value; what was set before stays, the rest is not tried. */
        FAILED
    }

    /**
     * Colors the frame of the window with this exact title.
     *
     * @param title the title of the window, not empty
     * @param colors the colors, from the tokens of the current theme
     */
    Result apply(String title, ChromeColors colors);

    /**
     * Gives the frame of the window with this exact title the system's own colors again, for example when
     * a high-contrast theme is turned on. A system that cannot color frames has nothing to give back.
     *
     * @param title the title of the window, not empty
     */
    default Result reset(String title) {
        return Result.UNAVAILABLE;
    }

    /** Whether {@link #apply} can ever succeed here; a caller that gets false need not call it. */
    boolean available();

    /**
     * Why the last attempt did not apply, for the log; empty after one that did. Logging is left to the
     * caller, which can do it off the thread the attempt ran on.
     */
    String detail();

    /** A system whose frame cannot be colored. */
    WindowChrome UNAVAILABLE = new WindowChrome() {
        @Override
        public Result apply(String title, ChromeColors colors) {
            return Result.UNAVAILABLE;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String detail() {
            return "PRISM colors the window frame only on Windows 11";
        }
    };
}
