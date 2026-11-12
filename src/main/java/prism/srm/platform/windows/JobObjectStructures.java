package prism.srm.platform.windows;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;

/**
 * The Job Object structures Prism reads and writes, mapped field by field from their documented
 * layout. The 8-byte members ({@code LARGE_INTEGER}, {@code SIZE_T}, {@code ULONG_PTR}) are Java
 * {@code long}, so the layout is that of 64-bit Windows on any 64-bit system that computes it, and
 * the tests check every size and offset (the limiter refuses to run on a 32-bit JVM). Under the
 * Windows x64 ABI each member is aligned to its size and a structure is padded to its widest
 * member, which is what JNA's default alignment does on Windows.
 */
final class JobObjectStructures {

    private JobObjectStructures() {}

    /**
     * {@code JOBOBJECT_BASIC_LIMIT_INFORMATION}, 64 bytes: two LARGE_INTEGER at 0 and 8, LimitFlags
     * at 16 (4 bytes of padding), two SIZE_T at 24 and 32, ActiveProcessLimit at 40 (padding),
     * Affinity at 48, PriorityClass at 56, SchedulingClass at 60.
     * https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information
     */
    @Structure.FieldOrder({
        "PerProcessUserTimeLimit",
        "PerJobUserTimeLimit",
        "LimitFlags",
        "MinimumWorkingSetSize",
        "MaximumWorkingSetSize",
        "ActiveProcessLimit",
        "Affinity",
        "PriorityClass",
        "SchedulingClass"
    })
    public static final class BasicLimitInformation extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public long MinimumWorkingSetSize;
        public long MaximumWorkingSetSize;
        public int ActiveProcessLimit;
        public long Affinity;
        public int PriorityClass;
        public int SchedulingClass;

        int offsetOf(String field) {
            return fieldOffset(field);
        }
    }

    /**
     * {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION}, 144 bytes: the basic limits at 0, IO_COUNTERS
     * (reserved, 48 bytes) at 64, then ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed and
     * PeakJobMemoryUsed, SIZE_T each, at 112, 120, 128 and 136. ProcessMemoryLimit is the committed
     * virtual memory a process of the job may have when LimitFlags holds
     * JOB_OBJECT_LIMIT_PROCESS_MEMORY.
     * https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information
     */
    @Structure.FieldOrder({
        "BasicLimitInformation",
        "IoInfo",
        "ProcessMemoryLimit",
        "JobMemoryLimit",
        "PeakProcessMemoryUsed",
        "PeakJobMemoryUsed"
    })
    public static final class ExtendedLimitInformation extends Structure {
        public BasicLimitInformation BasicLimitInformation = new BasicLimitInformation();
        public WinNT.IO_COUNTERS IoInfo = new WinNT.IO_COUNTERS();
        public long ProcessMemoryLimit;
        public long JobMemoryLimit;
        public long PeakProcessMemoryUsed;
        public long PeakJobMemoryUsed;

        int offsetOf(String field) {
            return fieldOffset(field);
        }
    }

    /**
     * {@code JOBOBJECT_CPU_RATE_CONTROL_INFORMATION}, 8 bytes: ControlFlags, then a union of DWORD
     * CpuRate, DWORD Weight and two WORDs MinRate and MaxRate. Prism writes only CpuRate (with ENABLE
     * and HARD_CAP), so the union is one int; on read-back its meaning depends on the flags, and a
     * percentage is derived only when the flags say CpuRate.
     * https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_cpu_rate_control_information
     */
    @Structure.FieldOrder({"ControlFlags", "CpuRate"})
    public static final class CpuRateControlInformation extends Structure {
        public int ControlFlags;
        public int CpuRate;

        int offsetOf(String field) {
            return fieldOffset(field);
        }
    }

    /**
     * {@code JOBOBJECT_BASIC_ACCOUNTING_INFORMATION}, 48 bytes: four LARGE_INTEGER times at 0 to 24,
     * then TotalPageFaultCount, TotalProcesses, ActiveProcesses and TotalTerminatedProcesses, DWORD
     * each, at 32, 36, 40 and 44. Prism reads ActiveProcesses: whether anything is left in a job
     * whose first process exited.
     * https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_accounting_information
     */
    @Structure.FieldOrder({
        "TotalUserTime",
        "TotalKernelTime",
        "ThisPeriodTotalUserTime",
        "ThisPeriodTotalKernelTime",
        "TotalPageFaultCount",
        "TotalProcesses",
        "ActiveProcesses",
        "TotalTerminatedProcesses"
    })
    public static final class BasicAccountingInformation extends Structure {
        public long TotalUserTime;
        public long TotalKernelTime;
        public long ThisPeriodTotalUserTime;
        public long ThisPeriodTotalKernelTime;
        public int TotalPageFaultCount;
        public int TotalProcesses;
        public int ActiveProcesses;
        public int TotalTerminatedProcesses;

        int offsetOf(String field) {
            return fieldOffset(field);
        }
    }
}
