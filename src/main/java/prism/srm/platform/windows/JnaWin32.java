package prism.srm.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prism.srm.platform.windows.ControlNative.Kernel32Control;
import prism.srm.platform.windows.ControlNative.User32Control;

/**
 * {@link Win32} on the running system, through JNA. Each method makes the call its name says and
 * returns what came back; the error of a failed call is read at once with {@link
 * Native#getLastError()}. Nothing here decides what a result means.
 *
 * <p>Calls of {@code jna-platform} (with their Microsoft Learn pages):
 *
 * <ul>
 *   <li>OpenProcess, OpenThread, CloseHandle:
 *       https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openprocess
 *       https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openthread
 *       https://learn.microsoft.com/en-us/windows/win32/api/handleapi/nf-handleapi-closehandle
 *   <li>GetExitCodeProcess, GetExitCodeThread (STILL_ACTIVE while running), GetProcessTimes,
 *       GetPriorityClass, SetPriorityClass: the pages under
 *       https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/
 *   <li>QueryFullProcessImageNameW:
 *       https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-queryfullprocessimagenamew
 *   <li>CreateToolhelp32Snapshot with TH32CS_SNAPTHREAD or TH32CS_SNAPPROCESS (always system-wide
 *       for threads: the process argument is ignored and every thread is included), Thread32First,
 *       Thread32Next, Process32FirstW, Process32NextW; the end of the list is ERROR_NO_MORE_FILES:
 *       https://learn.microsoft.com/en-us/windows/win32/api/tlhelp32/nf-tlhelp32-createtoolhelp32snapshot
 *   <li>EnumWindows (on Windows 8 and later, top-level windows of desktop apps only),
 *       GetWindowThreadProcessId (0 for an invalid window), IsWindowVisible, GetWindow with
 *       GW_OWNER, GetWindowLongW with GWL_EXSTYLE:
 *       https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumwindows
 * </ul>
 *
 * <p>Every handle a method opens is either returned to the caller or closed before the method
 * returns; snapshot handles are closed in {@code finally}.
 */
final class JnaWin32 implements Win32 {

    private static final Logger LOG = LoggerFactory.getLogger(JnaWin32.class);

    /** {@code WS_EX_TOOLWINDOW}. https://learn.microsoft.com/en-us/windows/win32/winmsg/extended-window-styles */
    static final int WS_EX_TOOLWINDOW = 0x00000080;

    /** Room for the longest path the system has: 32,767 characters and the terminating null. */
    private static final int PATH_CHARACTERS = 32_768;

    private final Kernel32 kernel32 = Kernel32.INSTANCE;
    private final Kernel32Control control = Kernel32Control.INSTANCE;
    private final User32 user32 = User32.INSTANCE;
    private final User32Control userControl = User32Control.INSTANCE;

    @Override
    public Opened openProcess(int rights, int pid) {
        HANDLE handle = kernel32.OpenProcess(rights, false, pid);
        return handle == null ? Opened.failed(Native.getLastError()) : Opened.of(value(handle));
    }

    @Override
    public Opened openThread(int rights, int threadId) {
        HANDLE handle = kernel32.OpenThread(rights, false, threadId);
        return handle == null ? Opened.failed(Native.getLastError()) : Opened.of(value(handle));
    }

    @Override
    public void close(long handle) {
        if (!kernel32.CloseHandle(handle(handle))) {
            LOG.warn("CloseHandle failed: {}", describe(Native.getLastError()));
        }
    }

    @Override
    public OptionalInt processExitCode(long process) {
        IntByReference code = new IntByReference();
        return kernel32.GetExitCodeProcess(handle(process), code)
                ? OptionalInt.of(code.getValue())
                : OptionalInt.empty();
    }

    @Override
    public OptionalInt threadExitCode(long thread) {
        IntByReference code = new IntByReference();
        return kernel32.GetExitCodeThread(handle(thread), code) ? OptionalInt.of(code.getValue()) : OptionalInt.empty();
    }

    @Override
    public OptionalLong creationTime(long process) {
        WinBase.FILETIME creation = new WinBase.FILETIME();
        boolean read = kernel32.GetProcessTimes(
                handle(process), creation, new WinBase.FILETIME(), new WinBase.FILETIME(), new WinBase.FILETIME());
        if (!read) {
            return OptionalLong.empty();
        }
        return OptionalLong.of((Integer.toUnsignedLong(creation.dwHighDateTime) << 32)
                | Integer.toUnsignedLong(creation.dwLowDateTime));
    }

    @Override
    public OptionalLong processorTime(long process) {
        WinBase.FILETIME kernel = new WinBase.FILETIME();
        WinBase.FILETIME user = new WinBase.FILETIME();
        boolean read =
                kernel32.GetProcessTimes(handle(process), new WinBase.FILETIME(), new WinBase.FILETIME(), kernel, user);
        if (!read) {
            return OptionalLong.empty();
        }
        // Both are amounts of time in 100-nanosecond units, kernel and user mode.
        return OptionalLong.of(filetime(kernel) + filetime(user));
    }

    @Override
    public OptionalLong threadCreationTime(long thread) {
        WinBase.FILETIME creation = new WinBase.FILETIME();
        boolean read = control.GetThreadTimes(
                handle(thread), creation, new WinBase.FILETIME(), new WinBase.FILETIME(), new WinBase.FILETIME());
        return read ? OptionalLong.of(filetime(creation)) : OptionalLong.empty();
    }

    @Override
    public Optional<Boolean> debuggerPresent(long process) {
        IntByReference present = new IntByReference();
        return control.CheckRemoteDebuggerPresent(handle(process), present)
                ? Optional.of(present.getValue() != 0)
                : Optional.empty();
    }

    private static long filetime(WinBase.FILETIME time) {
        return (Integer.toUnsignedLong(time.dwHighDateTime) << 32) | Integer.toUnsignedLong(time.dwLowDateTime);
    }

    @Override
    public Optional<Boolean> critical(long process) {
        IntByReference critical = new IntByReference();
        return control.IsProcessCritical(handle(process), critical)
                ? Optional.of(critical.getValue() != 0)
                : Optional.empty();
    }

    @Override
    public Optional<String> imagePath(long process) {
        char[] path = new char[PATH_CHARACTERS];
        IntByReference length = new IntByReference(path.length);
        if (!kernel32.QueryFullProcessImageName(handle(process), 0, path, length)) {
            return Optional.empty();
        }
        return Optional.of(new String(path, 0, length.getValue()));
    }

    @Override
    public int setPriorityClass(long process, int priorityClass) {
        return kernel32.SetPriorityClass(handle(process), new DWORD(priorityClass)) ? 0 : Native.getLastError();
    }

    @Override
    public OptionalInt priorityClass(long process) {
        DWORD priorityClass = kernel32.GetPriorityClass(handle(process));
        // GetPriorityClass returns 0 on failure; no class has the value 0.
        return priorityClass == null || priorityClass.intValue() == 0
                ? OptionalInt.empty()
                : OptionalInt.of(priorityClass.intValue());
    }

    @Override
    public ThreadListing threadsOf(int pid) {
        HANDLE snapshot = kernel32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPTHREAD, new DWORD(0));
        if (WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
            return ThreadListing.failed(Native.getLastError());
        }
        try {
            List<Integer> ids = new ArrayList<>();
            Tlhelp32.THREADENTRY32 entry = new Tlhelp32.THREADENTRY32();
            boolean more = kernel32.Thread32First(snapshot, entry);
            while (more) {
                // Every thread of the system is in the snapshot; only the ones of this process count.
                if (entry.th32OwnerProcessID == pid) {
                    ids.add(entry.th32ThreadID);
                }
                more = kernel32.Thread32Next(snapshot, entry);
            }
            int end = Native.getLastError();
            if (end != Kernel32.ERROR_NO_MORE_FILES) {
                return ThreadListing.failed(end);
            }
            return ThreadListing.of(ids);
        } finally {
            kernel32.CloseHandle(snapshot);
        }
    }

    @Override
    public Optional<Boolean> processListed(int pid) {
        HANDLE snapshot = kernel32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new DWORD(0));
        if (WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
            return Optional.empty();
        }
        try {
            Tlhelp32.PROCESSENTRY32 entry = new Tlhelp32.PROCESSENTRY32();
            entry.dwSize = new DWORD(entry.size());
            boolean more = kernel32.Process32First(snapshot, entry);
            while (more) {
                if (entry.th32ProcessID.intValue() == pid) {
                    return Optional.of(true);
                }
                more = kernel32.Process32Next(snapshot, entry);
            }
            return Native.getLastError() == Kernel32.ERROR_NO_MORE_FILES ? Optional.of(false) : Optional.empty();
        } finally {
            kernel32.CloseHandle(snapshot);
        }
    }

    @Override
    public int processIdOfThread(long thread) {
        return control.GetProcessIdOfThread(handle(thread));
    }

    @Override
    public Counted suspendThread(long thread) {
        int previous = control.SuspendThread(handle(thread));
        return new Counted(previous, previous == -1 ? Native.getLastError() : 0);
    }

    @Override
    public Counted resumeThread(long thread) {
        int previous = control.ResumeThread(handle(thread));
        return new Counted(previous, previous == -1 ? Native.getLastError() : 0);
    }

    @Override
    public Optional<List<Window>> topLevelWindows() {
        List<HWND> found = new ArrayList<>();
        // The callback only collects: a window that is asked about later may be gone by then,
        // which the questions below answer harmlessly (a PID of 0). It always goes on, so a
        // FALSE from EnumWindows is a failure.
        // https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumwindows
        boolean enumerated = user32.EnumWindows(
                (window, unused) -> {
                    found.add(window);
                    return true;
                },
                null);
        if (!enumerated) {
            return Optional.empty();
        }
        List<Window> windows = new ArrayList<>(found.size());
        for (HWND window : found) {
            IntByReference pid = new IntByReference();
            user32.GetWindowThreadProcessId(window, pid);
            boolean visible = user32.IsWindowVisible(window);
            boolean owned = user32.GetWindow(window, new DWORD(WinUser.GW_OWNER)) != null;
            boolean tool = (user32.GetWindowLong(window, WinUser.GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0;
            windows.add(new Window(Pointer.nativeValue(window.getPointer()), pid.getValue(), visible, owned, tool));
        }
        return Optional.of(windows);
    }

    @Override
    public int lastError() {
        return Native.getLastError();
    }

    @Override
    public int postClose(long window) {
        // WM_CLOSE: https://learn.microsoft.com/en-us/windows/win32/winmsg/wm-close
        boolean posted =
                userControl.PostMessageW(new HWND(new Pointer(window)), WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
        return posted ? 0 : Native.getLastError();
    }

    @Override
    public String describe(int error) {
        try {
            return error + " (" + Kernel32Util.formatMessage(error).strip() + ")";
        } catch (RuntimeException unknownCode) {
            return Integer.toString(error);
        }
    }

    // ----- Job Objects -----

    private final JobNative.Kernel32Jobs jobs = JobNative.Kernel32Jobs.INSTANCE;

    @Override
    public Created createJob(String name) {
        // ERROR_ALREADY_EXISTS is told after a call that succeeded; the page does not say a new job resets it.
        kernel32.SetLastError(0);
        HANDLE handle = jobs.CreateJobObjectW(null, name == null ? null : new WString(name));
        int error = Native.getLastError();
        if (handle == null) {
            return new Created(0, false, error);
        }
        return new Created(value(handle), error == WinError.ERROR_ALREADY_EXISTS, 0);
    }

    @Override
    public Opened openJob(int rights, String name) {
        HANDLE handle = jobs.OpenJobObjectW(rights, false, new WString(name));
        return handle == null ? Opened.failed(Native.getLastError()) : Opened.of(value(handle));
    }

    @Override
    public int assignToJob(long job, long process) {
        return jobs.AssignProcessToJobObject(handle(job), handle(process)) ? 0 : Native.getLastError();
    }

    @Override
    public Optional<Boolean> inJob(long process, OptionalLong job) {
        IntByReference result = new IntByReference();
        boolean ok = jobs.IsProcessInJob(handle(process), job.isPresent() ? handle(job.getAsLong()) : null, result);
        return ok ? Optional.of(result.getValue() != 0) : Optional.empty();
    }

    @Override
    public Optional<Boolean> selfInJob() {
        IntByReference result = new IntByReference();
        return jobs.IsProcessInJob(kernel32.GetCurrentProcess(), null, result)
                ? Optional.of(result.getValue() != 0)
                : Optional.empty();
    }

    @Override
    public int setCpuRate(long job, int flags, int rate) {
        JobObjectStructures.CpuRateControlInformation info = new JobObjectStructures.CpuRateControlInformation();
        info.ControlFlags = flags;
        info.CpuRate = rate;
        return jobs.SetInformationJobObject(
                        handle(job), JobNative.JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION, info, info.size())
                ? 0
                : Native.getLastError();
    }

    @Override
    public Optional<CpuRate> cpuRate(long job) {
        JobObjectStructures.CpuRateControlInformation info = new JobObjectStructures.CpuRateControlInformation();
        IntByReference length = new IntByReference();
        boolean ok = jobs.QueryInformationJobObject(
                handle(job), JobNative.JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION, info, info.size(), length);
        return ok && length.getValue() == info.size()
                ? Optional.of(new CpuRate(info.ControlFlags, info.CpuRate))
                : Optional.empty();
    }

    @Override
    public Optional<ExtendedLimits> extendedLimits(long job) {
        return readExtended(job)
                .map(info -> new ExtendedLimits(info.BasicLimitInformation.LimitFlags, info.ProcessMemoryLimit));
    }

    @Override
    public int setExtendedLimits(long job, int limitFlags, long processMemoryLimit) {
        Optional<JobObjectStructures.ExtendedLimitInformation> read = readExtended(job);
        if (read.isEmpty()) {
            int error = Native.getLastError();
            return error == 0 ? WinError.ERROR_INVALID_DATA : error;
        }
        JobObjectStructures.ExtendedLimitInformation info = read.get();
        info.BasicLimitInformation.LimitFlags = limitFlags;
        info.ProcessMemoryLimit = processMemoryLimit;
        return jobs.SetInformationJobObject(
                        handle(job), JobNative.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, info.size())
                ? 0
                : Native.getLastError();
    }

    @Override
    public OptionalInt activeProcesses(long job) {
        JobObjectStructures.BasicAccountingInformation info = new JobObjectStructures.BasicAccountingInformation();
        IntByReference length = new IntByReference();
        boolean ok = jobs.QueryInformationJobObject(
                handle(job), JobNative.JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION, info, info.size(), length);
        return ok && length.getValue() == info.size() ? OptionalInt.of(info.ActiveProcesses) : OptionalInt.empty();
    }

    private Optional<JobObjectStructures.ExtendedLimitInformation> readExtended(long job) {
        JobObjectStructures.ExtendedLimitInformation info = new JobObjectStructures.ExtendedLimitInformation();
        IntByReference length = new IntByReference();
        boolean ok = jobs.QueryInformationJobObject(
                handle(job), JobNative.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, info.size(), length);
        return ok && length.getValue() == info.size() ? Optional.of(info) : Optional.empty();
    }

    private static long value(HANDLE handle) {
        return Pointer.nativeValue(handle.getPointer());
    }

    private static HANDLE handle(long value) {
        return new HANDLE(new Pointer(value));
    }
}
