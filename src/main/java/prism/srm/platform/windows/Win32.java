package prism.srm.platform.windows;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The Win32 calls process control is made of, one method per call or per small group of calls
 * that always go together. Handles are passed as their numeric value, so that a test can stand in
 * for the system without real handles and can see each one opened and closed. {@link JnaWin32} is
 * the implementation on the system; it holds the documentation of every call.
 *
 * <p>Every method returns what the call returned and the error it set, and decides nothing.
 */
interface Win32 {

    /**
     * The exit code of a process or thread that has not ended.
     * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getexitcodeprocess
     */
    int STILL_ACTIVE = com.sun.jna.platform.win32.WinBase.STILL_ACTIVE;

    /** A handle that was opened, or the error that kept it from opening. */
    record Opened(long handle, int error) {
        static Opened of(long handle) {
            return new Opened(handle, 0);
        }

        static Opened failed(int error) {
            return new Opened(0, error);
        }

        boolean ok() {
            return handle != 0;
        }
    }

    /**
     * What SuspendThread or ResumeThread returned: the previous suspend count, or {@code -1}
     * (the DWORD {@code 0xFFFFFFFF}) with the error.
     */
    record Counted(int value, int error) {
        boolean ok() {
            return value != -1;
        }
    }

    /** The thread IDs of one process from a system-wide snapshot, or the error that kept it from being taken. */
    record ThreadListing(List<Integer> threadIds, int error, boolean ok) {
        public ThreadListing {
            threadIds = List.copyOf(threadIds);
        }

        static ThreadListing of(List<Integer> ids) {
            return new ThreadListing(ids, 0, true);
        }

        static ThreadListing failed(int error) {
            return new ThreadListing(List.of(), error, false);
        }
    }

    /**
     * A top-level window as it was when the windows were enumerated.
     *
     * @param pid the process that created it; 0 when the window no longer existed when asked
     * @param owned it has an owner window, as dialogs and message boxes do
     * @param toolWindow it has the tool-window style
     */
    record Window(long hwnd, int pid, boolean visible, boolean owned, boolean toolWindow) {}

    Opened openProcess(int rights, int pid);

    Opened openThread(int rights, int threadId);

    /** Closes a handle opened through this interface. */
    void close(long handle);

    OptionalInt processExitCode(long process);

    OptionalInt threadExitCode(long thread);

    /** The creation time of the process in 100-nanosecond units since 1601 (a FILETIME). */
    OptionalLong creationTime(long process);

    /**
     * The processor time the process has used, kernel and user together, summed over its threads,
     * in 100-nanosecond units (GetProcessTimes); empty when it could not be read.
     */
    OptionalLong processorTime(long process);

    /** The creation time of the thread in 100-nanosecond units since 1601 (GetThreadTimes); empty when it could not be read. */
    OptionalLong threadCreationTime(long thread);

    /** Whether a debugger is attached to the process (CheckRemoteDebuggerPresent); empty when it could not be read. */
    Optional<Boolean> debuggerPresent(long process);

    /** Whether the system marks the process as critical; empty when it could not be read. */
    Optional<Boolean> critical(long process);

    /** The full path of the executable; empty when it could not be read. */
    Optional<String> imagePath(long process);

    /** Returns 0 when the class was set, otherwise the error. */
    int setPriorityClass(long process, int priorityClass);

    OptionalInt priorityClass(long process);

    /** The threads the system lists for one process at this moment. */
    ThreadListing threadsOf(int pid);

    /** Whether the system lists a process with this PID at this moment; empty when the list could not be read. */
    Optional<Boolean> processListed(int pid);

    /** The PID of the process the thread belongs to; 0 when it could not be read. */
    int processIdOfThread(long thread);

    Counted suspendThread(long thread);

    Counted resumeThread(long thread);

    /** The top-level windows; empty when EnumWindows failed, with the code in {@link #lastError()}. */
    Optional<List<Window>> topLevelWindows();

    /**
     * GetLastError of the call that just failed on this thread, for the calls that report a failure
     * without its code (a zero, a false, an empty answer). Ask right after the failure, before any
     * other call. https://learn.microsoft.com/en-us/windows/win32/api/errhandlingapi/nf-errhandlingapi-getlasterror
     */
    int lastError();

    /** Posts a close request to a window. Returns 0 when it was posted, otherwise the error. */
    int postClose(long window);

    /** The system's text for an error code, for the log. */
    String describe(int error);

    // ----- Job Objects (milestone 10, JobNative) -----

    /**
     * A job created by name.
     *
     * @param existed the name belonged to a job already, and the handle is that job's
     *     (ERROR_ALREADY_EXISTS)
     */
    record Created(long handle, boolean existed, int error) {
        boolean ok() {
            return handle != 0;
        }
    }

    /** The CPU rate control of a job as read back: its ControlFlags and the union read as CpuRate. */
    record CpuRate(int flags, int rate) {}

    /** What Prism reads of a job's extended limits: the limit flags and the per-process memory limit. */
    record ExtendedLimits(int limitFlags, long processMemoryLimit) {}

    /** CreateJobObjectW with the default security descriptor and this name; a null name makes a job without one. */
    Created createJob(String name);

    /** OpenJobObjectW with exactly these rights. */
    Opened openJob(int rights, String name);

    /** AssignProcessToJobObject; 0 when it succeeded, otherwise the error. */
    int assignToJob(long job, long process);

    /** IsProcessInJob, against this job or, when empty, against any; empty when the call failed, with the code in {@link #lastError()}. */
    Optional<Boolean> inJob(long process, OptionalLong job);

    /** Whether this JVM runs inside a job; empty when that could not be read. */
    Optional<Boolean> selfInJob();

    /** SetInformationJobObject with the CPU rate control; 0 when it succeeded, otherwise the error. */
    int setCpuRate(long job, int flags, int rate);

    /** QueryInformationJobObject of the CPU rate control; empty when it failed, with the code in {@link #lastError()}. */
    Optional<CpuRate> cpuRate(long job);

    /** QueryInformationJobObject of the extended limits; empty when it failed. */
    Optional<ExtendedLimits> extendedLimits(long job);

    /**
     * Reads the extended limits, replaces the limit flags and the per-process memory limit, and
     * sets them, so that nothing else of the structure changes; 0 when it succeeded, otherwise the
     * error of the call that failed.
     */
    int setExtendedLimits(long job, int limitFlags, long processMemoryLimit);

    /** The number of processes in the job now (ActiveProcesses); empty when it could not be read. */
    OptionalInt activeProcesses(long job);
}
