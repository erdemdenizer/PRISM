package prism.srm.platform.windows;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LIFE-5 on Windows when Prism runs in a console and the console is closed.
 *
 * <p>Closing a console sends {@code CTRL_CLOSE_EVENT} to the processes attached to it. The handlers
 * of a process are called "on a last-registered, first-called basis until one of the handlers returns
 * TRUE", and when one returns TRUE "no other handler functions are called and the system terminates
 * the process" (HandlerRoutine). The JVM's own handler raises the signal that starts the shutdown
 * hooks and returns TRUE at once, so the system can end the process before the hooks have released
 * anything; the image verification saw exactly that: the CPU limiter's hold stayed after the console
 * was closed.
 *
 * <p>The handler registered here comes after the JVM's and is therefore called first. On {@code
 * CTRL_CLOSE_EVENT} it runs the release on the thread the system created for it, then returns FALSE
 * so that the JVM's handler runs next and the JVM shuts down as before. The system waits for a close
 * handler as long as {@code SPI_GETHUNGAPPTIMEOUT}, 5000 ms by default (HandlerRoutine, Timeouts), so
 * the release must be bounded below that. Every other signal is left to the handlers after this one:
 * Ctrl+C and Ctrl+Break do not end the process by themselves, and the logoff and shutdown signals are
 * "received only by services" (HandlerRoutine).
 *
 * <p>Sources:
 *
 * <ul>
 *   <li>https://learn.microsoft.com/en-us/windows/console/setconsolectrlhandler
 *   <li>https://learn.microsoft.com/en-us/windows/console/handlerroutine
 * </ul>
 */
public final class WindowsConsoleClose {

    /** {@code CTRL_CLOSE_EVENT}, from the parameter table of HandlerRoutine. */
    static final int CTRL_CLOSE_EVENT = 2;

    private static final Logger LOG = LoggerFactory.getLogger(WindowsConsoleClose.class);

    /** Guards {@link #registered}; a lock of its own, so that no other code can hold it. */
    private static final Object LOCK = new Object();

    /** The registered callback; kept so that it is never collected while the system may call it. */
    private static CloseHandler registered;

    private WindowsConsoleClose() {}

    /**
     * Registers {@code release} to run when the console of this process is closed, once per process.
     *
     * @return whether the handler is in place; false after an earlier registration or when the system
     *     refused it, which is logged
     */
    public static boolean register(Runnable release) {
        Objects.requireNonNull(release, "release");
        synchronized (LOCK) {
            return registerLocked(release);
        }
    }

    private static boolean registerLocked(Runnable release) {
        if (registered != null) {
            return false;
        }
        CloseHandler handler = type -> handle(type, release);
        // The system calls the handler on a thread it creates; JNA attaches it to the JVM under this name, as a
        // daemon that never holds up the exit, and detaches it after the call (CallbackThreadInitializer).
        Native.setCallbackThreadInitializer(handler, new CallbackThreadInitializer(true, true, "srm-console-close"));
        if (!ConsoleControl.INSTANCE.SetConsoleCtrlHandler(handler, true)) {
            LOG.warn(
                    "The console close handler was not registered: SetConsoleCtrlHandler failed with {}",
                    Native.getLastError());
            return false;
        }
        registered = handler;
        return true;
    }

    /**
     * What the handler does with a signal: on a close, the release, whose failure is logged; never
     * TRUE, so that the handlers registered before this one, the JVM's among them, are called next.
     */
    static boolean handle(int type, Runnable release) {
        if (type == CTRL_CLOSE_EVENT) {
            try {
                release.run();
            } catch (RuntimeException failed) {
                LOG.error("The release when the console was closed failed", failed);
            }
        }
        return false;
    }

    /** {@code BOOL WINAPI HandlerRoutine(_In_ DWORD dwCtrlType)}. */
    interface CloseHandler extends StdCallLibrary.StdCallCallback {
        boolean callback(int type);
    }

    /** The function of kernel32.dll; {@code jna-platform} 5.19.1 does not map it. */
    interface ConsoleControl extends StdCallLibrary {

        ConsoleControl INSTANCE = Native.load("kernel32", ConsoleControl.class, W32APIOptions.DEFAULT_OPTIONS);

        /**
         * {@code BOOL WINAPI SetConsoleCtrlHandler(_In_opt_ PHANDLER_ROUTINE HandlerRoutine, _In_ BOOL
         * Add)}: nonzero on success.
         * https://learn.microsoft.com/en-us/windows/console/setconsolectrlhandler
         */
        boolean SetConsoleCtrlHandler(CloseHandler handler, boolean add);
    }
}
