package prism.srm.platform.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * End Task on Windows (CTL-4, ADR-0002): a close request ({@code WM_CLOSE}) posted to the
 * top-level windows of the process. Windows has no equivalent of SIGTERM, so this is a request the
 * application may ignore, and success means "close requested", never "closed". Nothing waits for
 * the process to exit; the next listing shows whether it did.
 *
 * <p>Which windows: the visible, unowned top-level windows of the process. Owned windows are left
 * out, because dialogs and message boxes are owned by default and are destroyed with their owner;
 * hidden windows are not the application's face. A window with the tool-window style is asked
 * only if the process has no other, since its close request often closes a palette and not the
 * application. A packaged (UWP or WinUI) application draws its frame in another process
 * (ApplicationFrameHost), so it has no window of its own here, and End Task is {@code
 * UNSUPPORTED} for it, as for any process without a window.
 * https://learn.microsoft.com/en-us/windows/win32/winmsg/window-features (Owned Windows)
 * https://learn.microsoft.com/en-us/windows/win32/winmsg/extended-window-styles (WS_EX_TOOLWINDOW)
 *
 * <p>The request is posted, not sent: PostMessage returns without waiting for the window to
 * process it, so a hung application only gets the message in its queue. A window that disappeared
 * since it was listed ({@code ERROR_INVALID_WINDOW_HANDLE}) is passed over. A post that UIPI blocks,
 * to a process at a higher integrity level, fails with {@code ERROR_ACCESS_DENIED}.
 * https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-postmessagew
 */
final class WindowsEndTask {

    static final int ERROR_INVALID_WINDOW_HANDLE = com.sun.jna.platform.win32.WinError.ERROR_INVALID_WINDOW_HANDLE;

    private final Win32 win32;
    private final WindowsProcessAccess access;

    WindowsEndTask(Win32 win32, WindowsProcessAccess access) {
        this.win32 = win32;
        this.access = access;
    }

    ControlOutcome requestClose(ProcessIdentity target) {
        WindowsProcessAccess.Opening opening = access.open(target, 0);
        if (opening.failure().isPresent()) {
            return opening.failure().get();
        }
        // Held open while the windows are asked, so the PID is the identity's throughout.
        try (PinnedProcess process = opening.process().get()) {
            Optional<List<Win32.Window>> windows = win32.topLevelWindows();
            if (windows.isEmpty()) {
                int error = win32.lastError();
                return ControlOutcome.of(
                        OperationResult.NATIVE_ERROR, error, "EnumWindows failed: " + win32.describe(error));
            }
            List<Win32.Window> candidates = candidates(windows.get(), process.pid());
            if (candidates.isEmpty()) {
                return ControlOutcome.of(
                        OperationResult.UNSUPPORTED, "the process has no visible unowned top-level window");
            }
            int posted = 0;
            int vanished = 0;
            int denied = 0;
            OptionalInt otherError = OptionalInt.empty();
            for (Win32.Window window : candidates) {
                int error = win32.postClose(window.hwnd());
                if (error == 0) {
                    posted++;
                } else if (error == ERROR_INVALID_WINDOW_HANDLE) {
                    vanished++;
                } else if (error == WindowsProcessAccess.ERROR_ACCESS_DENIED) {
                    denied++;
                } else if (otherError.isEmpty()) {
                    otherError = OptionalInt.of(error);
                }
            }
            String tally = posted + " of " + candidates.size() + " window(s) asked"
                    + (vanished > 0 ? ", " + vanished + " gone meanwhile" : "")
                    + (denied > 0 ? ", " + denied + " refused by the system" : "")
                    + (otherError.isPresent() ? ", failed: " + win32.describe(otherError.getAsInt()) : "");
            if (posted > 0) {
                return ControlOutcome.success("close requested: " + tally);
            }
            if (otherError.isPresent()) {
                return ControlOutcome.of(OperationResult.NATIVE_ERROR, otherError.getAsInt(), tally);
            }
            if (denied > 0) {
                return ControlOutcome.of(
                        OperationResult.ACCESS_DENIED, WindowsProcessAccess.ERROR_ACCESS_DENIED, tally);
            }
            // Every window went away before it could be asked.
            return process.exited()
                    ? ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process exited: " + tally)
                    : ControlOutcome.of(OperationResult.UNSUPPORTED, "no window was left to ask: " + tally);
        }
    }

    /** The windows to ask: visible and unowned, the ones without the tool-window style first. */
    static List<Win32.Window> candidates(List<Win32.Window> windows, int pid) {
        List<Win32.Window> main = new ArrayList<>();
        List<Win32.Window> tools = new ArrayList<>();
        for (Win32.Window window : windows) {
            if (window.pid() != pid || !window.visible() || window.owned()) {
                continue;
            }
            (window.toolWindow() ? tools : main).add(window);
        }
        return main.isEmpty() ? tools : main;
    }
}
