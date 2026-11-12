package prism.srm.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * The Job Object functions, rights and information classes Prism uses (ADR-0002, milestone 10).
 * {@code jna-platform} 5.19.1 maps none of them, so each is written here from the page Microsoft
 * documents it on. The errors are read with {@link Native#getLastError()}, which JNA saves right
 * after each call; their codes are those of {@code WinError}.
 *
 * <p>What is deliberately absent: {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}, {@code
 * JOB_OBJECT_LIMIT_JOB_MEMORY}, {@code JOB_OBJECT_TERMINATE} and {@code TerminateJobObject}. Prism
 * never terminates a process to manage a limit, and a job it closes never takes its processes with
 * it (ADR-0002).
 */
final class JobNative {

    private JobNative() {}

    /**
     * Job access rights.
     * https://learn.microsoft.com/en-us/windows/win32/procthread/job-object-security-and-access-rights
     */
    static final int JOB_OBJECT_ASSIGN_PROCESS = 0x0001;

    static final int JOB_OBJECT_SET_ATTRIBUTES = 0x0002;
    static final int JOB_OBJECT_QUERY = 0x0004;

    /**
     * {@code JOBOBJECTINFOCLASS} values, from the pages of QueryInformationJobObject and
     * SetInformationJobObject.
     * https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-queryinformationjobobject
     */
    static final int JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION = 1;

    static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    static final int JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION = 15;

    /**
     * {@code LimitFlags}: the per-process committed-memory limit. The only limit flag Prism sets.
     * https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information
     */
    static final int JOB_OBJECT_LIMIT_PROCESS_MEMORY = 0x00000100;

    /**
     * {@code ControlFlags} of the CPU rate. ENABLE must accompany HARD_CAP.
     * https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_cpu_rate_control_information
     */
    static final int JOB_OBJECT_CPU_RATE_CONTROL_ENABLE = 0x1;

    static final int JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP = 0x4;

    /** Functions of kernel32.dll. Names end in W where the function takes a string; the mapper leaves them as they are. */
    interface Kernel32Jobs extends StdCallLibrary {

        Kernel32Jobs INSTANCE = Native.load("kernel32", Kernel32Jobs.class, W32APIOptions.DEFAULT_OPTIONS);

        /**
         * {@code HANDLE CreateJobObjectW(LPSECURITY_ATTRIBUTES lpJobAttributes, LPCWSTR lpName)}:
         * the job, with {@code JOB_OBJECT_ALL_ACCESS}, or NULL. If the name exists already, the
         * existing job and {@code ERROR_ALREADY_EXISTS}; a name of another kind of object, NULL and
         * {@code ERROR_INVALID_HANDLE}. The name is a {@code WString} so that it stays wide even
         * under {@code -Dw32.ascii=true}.
         * https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-createjobobjectw
         */
        HANDLE CreateJobObjectW(Pointer attributes, WString name);

        /**
         * {@code HANDLE OpenJobObjectW(DWORD dwDesiredAccess, BOOL bInheritHandle, LPCWSTR
         * lpName)}: the job, or NULL; {@code ERROR_FILE_NOT_FOUND} when no job has the name. Only
         * named jobs can be opened, and a name lives only while a handle is open.
         * https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-openjobobjecta
         */
        HANDLE OpenJobObjectW(int desiredAccess, boolean inheritHandle, WString name);

        /**
         * {@code BOOL AssignProcessToJobObject(HANDLE hJob, HANDLE hProcess)}: the job handle needs
         * {@code JOB_OBJECT_ASSIGN_PROCESS}, the process handle {@code PROCESS_SET_QUOTA} and {@code
         * PROCESS_TERMINATE}. The association cannot be broken.
         * https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject
         */
        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);

        /**
         * {@code BOOL IsProcessInJob(HANDLE ProcessHandle, HANDLE JobHandle, PBOOL Result)}: with a
         * NULL job, whether the process is in any job. The process handle needs {@code
         * PROCESS_QUERY_LIMITED_INFORMATION}, a job handle {@code JOB_OBJECT_QUERY}.
         * https://learn.microsoft.com/en-us/windows/win32/api/jobapi/nf-jobapi-isprocessinjob
         */
        boolean IsProcessInJob(HANDLE process, HANDLE job, IntByReference result);

        /**
         * {@code BOOL QueryInformationJobObject(HANDLE hJob, JOBOBJECTINFOCLASS, LPVOID, DWORD,
         * LPDWORD)}: needs {@code JOB_OBJECT_QUERY}; with a NULL job, the job of the calling
         * process. The structure is read back by JNA after the call.
         * https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-queryinformationjobobject
         */
        boolean QueryInformationJobObject(
                HANDLE job, int informationClass, Structure information, int length, IntByReference returned);

        /**
         * {@code BOOL SetInformationJobObject(HANDLE hJob, JOBOBJECTINFOCLASS, LPVOID, DWORD)}:
         * needs {@code JOB_OBJECT_SET_ATTRIBUTES}. The CPU rate fails with {@code ERROR_NOT_SUPPORTED}
         * where Dynamic Fair Share Scheduling is in effect.
         * https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-setinformationjobobject
         */
        boolean SetInformationJobObject(HANDLE job, int informationClass, Structure information, int length);
    }
}
