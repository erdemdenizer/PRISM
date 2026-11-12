package prism.srm.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import prism.srm.platform.ChromeColors;
import prism.srm.platform.WindowChrome;

/**
 * {@link WindowChrome} on Windows 11: the caption, caption text and border colors of a window through
 * the Desktop Window Manager (UI lab v2).
 *
 * <p>The window is found among the windows of the calling thread ({@code EnumThreadWindows} with
 * {@code GetCurrentThreadId}): those whose class name begins with {@value #GLASS_STAGE_CLASS_PREFIX},
 * the class JavaFX (Glass) gives the native window of a stage, and whose text is exactly the title.
 * Exactly one such window is changed; none gives {@link Result#NOT_FOUND}, more than one {@link
 * Result#AMBIGUOUS}, and nothing is changed then. The class name is an implementation detail of JavaFX
 * 25 measured on Windows 11 build 26200 (lab-spikes/titlebar), not an API: should it change, the lookup
 * finds nothing and the frame keeps the system's colors.
 *
 * <p>The attributes ({@code DWMWINDOWATTRIBUTE}), each a 4-byte value:
 *
 * <ul>
 *   <li>{@value #DWMWA_USE_IMMERSIVE_DARK_MODE} {@code DWMWA_USE_IMMERSIVE_DARK_MODE}, a BOOL;
 *   <li>{@value #DWMWA_CAPTION_COLOR} {@code DWMWA_CAPTION_COLOR}, a COLORREF;
 *   <li>{@value #DWMWA_TEXT_COLOR} {@code DWMWA_TEXT_COLOR}, a COLORREF;
 *   <li>{@value #DWMWA_BORDER_COLOR} {@code DWMWA_BORDER_COLOR}, a COLORREF.
 * </ul>
 *
 * Microsoft documents each as "supported starting with Windows 11 Build 22000", so nothing is set
 * before that build ({@link Result#UNAVAILABLE}). The build is checked once with {@code
 * VerifyVersionInfoW}, which a process without a manifest that declares Windows 10 sees as Windows 8:
 * there the check fails and the frame stays as it is, which is the safe side. Every call's HRESULT is
 * checked; the first that is not {@code S_OK} ends the attempt with {@link Result#FAILED}.
 *
 * <p>Sources:
 *
 * <ul>
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmsetwindowattribute
 *   <li>https://learn.microsoft.com/en-us/windows/win32/gdi/colorref
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumthreadwindows
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getclassnamew
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindowtextw
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-verifyversioninfow
 *   <li>https://learn.microsoft.com/en-us/windows/win32/api/winnt/nf-winnt-versetconditionmask
 * </ul>
 */
public final class WindowsWindowChrome implements WindowChrome {

    /** The first build of Windows 11, from which the four attributes are supported. */
    static final int FIRST_BUILD = 22_000;

    static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    static final int DWMWA_BORDER_COLOR = 34;
    static final int DWMWA_CAPTION_COLOR = 35;
    static final int DWMWA_TEXT_COLOR = 36;

    /**
     * {@code DWMWA_COLOR_DEFAULT}, 0xFFFFFFFF: documented for the border, caption and text attributes as the
     * value that gives the system's own color back.
     */
    static final int DWMWA_COLOR_DEFAULT = 0xFFFFFFFF;

    /** The class of the native window of a JavaFX stage, followed by a number (measured, JavaFX 25). */
    static final String GLASS_STAGE_CLASS_PREFIX = "GlassWndClass-GlassWindowClass-";

    private static final int S_OK = 0;

    /** The native calls, behind a seam so that the decisions are tested without them. */
    interface Dwm {

        /** Whether the running system is at least this build. */
        boolean buildAtLeast(int build);

        /** The windows of the calling thread, with their class names and texts. */
        List<ThreadWindow> threadWindows();

        /** {@code DwmSetWindowAttribute} with a 4-byte value; returns the HRESULT. */
        int setAttribute(long hwnd, int attribute, int value);
    }

    /** A window of the calling thread. */
    record ThreadWindow(long hwnd, String className, String text) {}

    private final Dwm dwm;
    private Boolean supported;
    private String detail = "";

    public WindowsWindowChrome() {
        this(new JnaDwm());
    }

    WindowsWindowChrome(Dwm dwm) {
        this.dwm = Objects.requireNonNull(dwm, "dwm");
    }

    @Override
    public boolean available() {
        if (supported == null) {
            supported = dwm.buildAtLeast(FIRST_BUILD);
        }
        if (!supported) {
            detail = "Windows before build " + FIRST_BUILD + ", or the build could not be read";
        }
        return supported;
    }

    @Override
    public Result apply(String title, ChromeColors colors) {
        Objects.requireNonNull(colors, "colors");
        return set(title, new int[][] {
            {DWMWA_USE_IMMERSIVE_DARK_MODE, colors.dark() ? 1 : 0},
            {DWMWA_CAPTION_COLOR, colorref(colors.caption())},
            {DWMWA_TEXT_COLOR, colorref(colors.text())},
            {DWMWA_BORDER_COLOR, colorref(colors.border())}
        });
    }

    /** The documented default for caption, text and border, and the dark mode off. */
    @Override
    public Result reset(String title) {
        return set(title, new int[][] {
            {DWMWA_USE_IMMERSIVE_DARK_MODE, 0},
            {DWMWA_CAPTION_COLOR, DWMWA_COLOR_DEFAULT},
            {DWMWA_TEXT_COLOR, DWMWA_COLOR_DEFAULT},
            {DWMWA_BORDER_COLOR, DWMWA_COLOR_DEFAULT}
        });
    }

    private Result set(String title, int[][] steps) {
        Objects.requireNonNull(title, "title");
        if (title.isEmpty()) {
            throw new IllegalArgumentException("A window without a title cannot be found by it.");
        }
        if (!available()) {
            return Result.UNAVAILABLE;
        }
        try {
            return setOn(title, steps);
        } catch (RuntimeException failure) {
            // A failure of the native binding (JNA) is a result like any other, not an exception for the
            // JavaFX listener that called; an Error is not caught.
            detail = "the native call failed: " + failure;
            return Result.FAILED;
        }
    }

    private Result setOn(String title, int[][] steps) {
        List<ThreadWindow> matches = select(dwm.threadWindows(), title);
        if (matches.isEmpty()) {
            detail = "no window of this thread is titled \"" + title + "\"";
            return Result.NOT_FOUND;
        }
        if (matches.size() > 1) {
            detail = matches.size() + " windows of this thread are titled \"" + title + "\"";
            return Result.AMBIGUOUS;
        }
        long hwnd = matches.get(0).hwnd();
        for (int[] step : steps) {
            int hresult = dwm.setAttribute(hwnd, step[0], step[1]);
            if (hresult != S_OK) {
                detail = "DwmSetWindowAttribute(" + step[0] + ") returned HRESULT 0x" + Integer.toHexString(hresult);
                return Result.FAILED;
            }
        }
        detail = "";
        return Result.APPLIED;
    }

    @Override
    public String detail() {
        return detail;
    }

    /** The windows that are the native window of a stage with exactly this title. */
    static List<ThreadWindow> select(List<ThreadWindow> windows, String title) {
        List<ThreadWindow> matches = new ArrayList<>();
        for (ThreadWindow window : windows) {
            if (window.className().startsWith(GLASS_STAGE_CLASS_PREFIX)
                    && window.text().equals(title)) {
                matches.add(window);
            }
        }
        return matches;
    }

    /**
     * A color {@code 0xRRGGBB} as a COLORREF, {@code 0x00BBGGRR}: "the low-order byte contains a value
     * for the relative intensity of red; the second byte contains a value for green; and the third byte
     * contains a value for blue. The high-order byte must be zero."
     */
    static int colorref(int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("Not 0xRRGGBB: " + Integer.toHexString(rgb));
        }
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return (blue << 16) | (green << 8) | red;
    }

    /** {@code dwmapi.dll}, which {@code jna-platform} 5.19.1 does not map. */
    interface DwmApi extends StdCallLibrary {

        /**
         * {@code HRESULT DwmSetWindowAttribute(HWND hwnd, DWORD dwAttribute, LPCVOID pvAttribute, DWORD
         * cbAttribute)}.
         * https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmsetwindowattribute
         */
        int DwmSetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }

    /** The calls on the running system. The library is loaded on the first call that needs it. */
    private static final class JnaDwm implements Dwm {

        /** The longest class name a window class may have, and its terminating null. */
        private static final int CLASS_CHARACTERS = 257;

        private DwmApi api;

        @Override
        public boolean buildAtLeast(int build) {
            WinNT.OSVERSIONINFOEX wanted = new WinNT.OSVERSIONINFOEX();
            wanted.dwBuildNumber = new DWORD(build);
            long condition =
                    Kernel32.INSTANCE.VerSetConditionMask(0, WinNT.VER_BUILDNUMBER, (byte) WinNT.VER_GREATER_EQUAL);
            if (Kernel32.INSTANCE.VerifyVersionInfoW(wanted, WinNT.VER_BUILDNUMBER, condition)) {
                return true;
            }
            // ERROR_OLD_WIN_VERSION when the system is older; any other error leaves the build unknown, and
            // an unknown build is treated as too old.
            return false;
        }

        @Override
        public List<ThreadWindow> threadWindows() {
            List<ThreadWindow> windows = new ArrayList<>();
            WinUser.WNDENUMPROC collect = (hwnd, data) -> {
                char[] className = new char[CLASS_CHARACTERS];
                int classLength = User32.INSTANCE.GetClassName(hwnd, className, className.length);
                String name = new String(className, 0, Math.max(classLength, 0));
                String text = "";
                // The text is read only from a stage window: GetWindowText sends WM_GETTEXT to a window of
                // this process, and the IME, text-service and toolkit windows need not be asked.
                if (name.startsWith(GLASS_STAGE_CLASS_PREFIX)) {
                    int textLength = User32.INSTANCE.GetWindowTextLength(hwnd);
                    char[] chars = new char[Math.max(textLength, 0) + 1];
                    int read = User32.INSTANCE.GetWindowText(hwnd, chars, chars.length);
                    text = new String(chars, 0, Math.max(read, 0));
                }
                windows.add(new ThreadWindow(Pointer.nativeValue(hwnd.getPointer()), name, text));
                return true;
            };
            // FALSE also when the thread has no window at all, so the result says nothing more than the list.
            User32.INSTANCE.EnumThreadWindows(Kernel32.INSTANCE.GetCurrentThreadId(), collect, null);
            // The callback must stay reachable for the whole native call (JNA Callback documentation).
            Reference.reachabilityFence(collect);
            return windows;
        }

        @Override
        public int setAttribute(long hwnd, int attribute, int value) {
            if (api == null) {
                api = Native.load("dwmapi", DwmApi.class, W32APIOptions.DEFAULT_OPTIONS);
            }
            return api.DwmSetWindowAttribute(new HWND(new Pointer(hwnd)), attribute, new IntByReference(value), 4);
        }
    }
}
