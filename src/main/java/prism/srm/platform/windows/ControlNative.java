package prism.srm.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinBase.FILETIME;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * The Win32 functions process control needs that {@code jna-platform} 5.19.1 does not map, or maps
 * in a way that hides a failure. Each signature is the one Microsoft documents, with the page it
 * comes from. The errors are read with {@link Native#getLastError()}, which JNA saves right after
 * each call.
 */
final class ControlNative {

    private ControlNative() {}

    /** Functions of kernel32.dll. */
    interface Kernel32Control extends StdCallLibrary {

        Kernel32Control INSTANCE = Native.load("kernel32", Kernel32Control.class, W32APIOptions.DEFAULT_OPTIONS);

        /**
         * {@code DWORD SuspendThread(HANDLE hThread)}: the previous suspend count, or {@code
         * (DWORD) -1} on failure. Needs {@code THREAD_SUSPEND_RESUME}.
         * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-suspendthread
         */
        int SuspendThread(HANDLE thread);

        /**
         * {@code DWORD ResumeThread(HANDLE hThread)}: the previous suspend count, or {@code (DWORD)
         * -1} on failure. Needs {@code THREAD_SUSPEND_RESUME}.
         * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-resumethread
         */
        int ResumeThread(HANDLE thread);

        /**
         * {@code DWORD GetProcessIdOfThread(HANDLE Thread)}: the PID of the thread's process, or 0
         * on failure. Needs {@code THREAD_QUERY_LIMITED_INFORMATION}. Windows Vista and later.
         * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocessidofthread
         */
        int GetProcessIdOfThread(HANDLE thread);

        /**
         * {@code BOOL IsProcessCritical(HANDLE hProcess, PBOOL Critical)}. Needs {@code
         * PROCESS_QUERY_LIMITED_INFORMATION}. Windows 8.1 and later.
         * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-isprocesscritical
         */
        boolean IsProcessCritical(HANDLE process, IntByReference critical);

        /**
         * {@code BOOL GetThreadTimes(HANDLE hThread, LPFILETIME lpCreationTime, LPFILETIME
         * lpExitTime, LPFILETIME lpKernelTime, LPFILETIME lpUserTime)}: nonzero on success. Needs
         * {@code THREAD_QUERY_INFORMATION} or {@code THREAD_QUERY_LIMITED_INFORMATION}. The creation
         * time is a FILETIME, 100-nanosecond units since 1601.
         * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getthreadtimes
         */
        boolean GetThreadTimes(
                HANDLE thread, FILETIME creationTime, FILETIME exitTime, FILETIME kernelTime, FILETIME userTime);

        /**
         * {@code BOOL CheckRemoteDebuggerPresent(HANDLE hProcess, PBOOL pbDebuggerPresent)}: nonzero
         * on success; the flag is set to TRUE if the process is being debugged. The page names no
         * access right; Prism opens the process with {@code PROCESS_QUERY_INFORMATION} for it and
         * takes a failure as "cannot tell".
         * https://learn.microsoft.com/en-us/windows/win32/api/debugapi/nf-debugapi-checkremotedebuggerpresent
         */
        boolean CheckRemoteDebuggerPresent(HANDLE process, IntByReference debuggerPresent);
    }

    /** Functions of user32.dll. */
    interface User32Control extends StdCallLibrary {

        User32Control INSTANCE = Native.load("user32", User32Control.class, W32APIOptions.DEFAULT_OPTIONS);

        /**
         * {@code BOOL PostMessageW(HWND, UINT, WPARAM, LPARAM)}: nonzero when the message was
         * posted. {@code jna-platform} maps PostMessage as {@code void}, which hides a failure.
         * Posting is subject to UIPI: a message to a window of a process at a higher integrity
         * level fails with {@code ERROR_ACCESS_DENIED}.
         * https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-postmessagew
         */
        boolean PostMessageW(HWND window, int message, WPARAM wParam, LPARAM lParam);
    }
}
