package prism.srm.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Milestone 10, stage 0: the experiments E0, E1, E3, E4 and the lifetime part of E2 of the plan, on
 * the real system and on processes this probe starts and ends itself (SAFE-6). It prints what the
 * system answered; it asserts nothing, because the answers are what is being established. Run by
 * hand: {@code ./mvnw -q test -Dtest=JobObjectProbe}.
 */
@EnabledOnOs(OS.WINDOWS)
class JobObjectProbe {

    private static final JobNative.Kernel32Jobs JOBS = JobNative.Kernel32Jobs.INSTANCE;
    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final int RIGHTS =
            WinNT.PROCESS_SET_QUOTA | WinNT.PROCESS_TERMINATE | WinNT.PROCESS_QUERY_LIMITED_INFORMATION;
    private static final int CAP =
            JobNative.JOB_OBJECT_CPU_RATE_CONTROL_ENABLE | JobNative.JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;

    @Test
    void experiments() throws Exception {
        say(
                "environment",
                "processors=" + PROCESSORS + " os=" + System.getProperty("os.version") + " selfInJob="
                        + inJob(Kernel32.INSTANCE.GetCurrentProcess(), null));
        e3AndE0OnAnEmptyJob();
        e0OnAProcessAndE2Lifetime();
        e1AssignAnExitedProcess();
        e4AProcessInAJobPrismDoesNotHold();
    }

    /** E3 (DFSS probe) and E0 (how to clear the CPU rate and the commit limit) on a job with no process. */
    private void e3AndE0OnAnEmptyJob() {
        HANDLE job = JOBS.CreateJobObjectW(null, null);
        say("E3", "create anonymous job ok=" + (job != null) + err());
        try {
            setRate("E3 set ENABLE|HARD_CAP 2000", job, CAP, 2000);
            readRate("E3 read", job);
            setRate("E0 set 0/0", job, 0, 0);
            readRate("E0 read after 0/0", job);
            setRate("E0 re-set 2000", job, CAP, 2000);
            setRate("E0 set 0/2000", job, 0, 2000);
            readRate("E0 read after 0/2000", job);
            setRate("E0 set ENABLE|HARD_CAP 10000", job, CAP, 10000);
            readRate("E0 read after 10000", job);
            setRate("E0 set ENABLE|HARD_CAP 0 (expected refused)", job, CAP, 0);
            readRate("E0 read after rate 0", job);

            setMemory("E3 set PROCESS_MEMORY 1 GiB", job, JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY, 1L << 30);
            readMemory("E3 read", job);
            setMemory("E0 clear memory flag", job, 0, 0);
            readMemory("E0 read after clear", job);
            readAccounting("E3 accounting", job);
        } finally {
            close(job);
        }
    }

    /**
     * E0 on a real process: the cap holds the workload near 20 %, clearing restores its use, a
     * change of the rate takes effect on the live job; then the lifetime part of E2: with the cap in
     * force the only handle is closed and the job is looked for by name.
     */
    private void e0OnAProcessAndE2Lifetime() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--cpu", "50", "--duration", "120s")) {
            int pid = (int) demo.identity().pid();
            String name = "Local\\Prism.Probe." + pid + "."
                    + demo.identity().startTime().toEpochMilli();
            HANDLE process = Kernel32.INSTANCE.OpenProcess(RIGHTS, false, pid);
            say("E0", "open demo ok=" + (process != null) + err() + " inJob(NULL)=" + inJob(process, null));
            HANDLE job = JOBS.CreateJobObjectW(null, new WString(name));
            say("E0", "create named job " + name + " ok=" + (job != null) + err());
            boolean closed = false;
            try {
                say("E0", "baseline use=" + use(demo, 6) + " %");
                setRate("E0 set 2000 before assignment", job, CAP, 2000);
                boolean assigned = JOBS.AssignProcessToJobObject(job, process);
                say(
                        "E0",
                        "assign ok=" + assigned + err() + " inJob(job)=" + inJob(process, job) + " inJob(NULL)="
                                + inJob(process, null));
                say("E0", "capped use=" + use(demo, 6) + " % (cap 20 %)");
                setRate("E0 clear 0/0 on the live job", job, 0, 0);
                readRate("E0 read", job);
                say("E0", "use after clear 0/0=" + use(demo, 6) + " %");
                setRate("E0 change to 1000 on the live job", job, CAP, 1000);
                say("E0", "use at 10 %=" + use(demo, 6) + " %");
                setRate("E0 clear by 10000", job, CAP, 10000);
                readRate("E0 read", job);
                say("E0", "use at 10000=" + use(demo, 6) + " %");
                setMemory(
                        "E0 set PROCESS_MEMORY 4 GiB on the live job",
                        job,
                        JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY,
                        4L << 30);
                readMemory("E0 read", job);
                setMemory("E0 clear memory on the live job", job, 0, 0);
                readMemory("E0 read", job);
                readAccounting("E0 accounting", job);

                setRate("E2 set 2000 again", job, CAP, 2000);
                say("E2", "capped use=" + use(demo, 6) + " %");
                close(job);
                closed = true;
                HANDLE reopened = JOBS.OpenJobObjectW(
                        JobNative.JOB_OBJECT_QUERY | JobNative.JOB_OBJECT_SET_ATTRIBUTES, false, new WString(name));
                say(
                        "E2",
                        "after the last handle closed: OpenJobObjectW ok=" + (reopened != null) + err()
                                + " inJob(NULL)=" + inJob(process, null));
                if (reopened != null) {
                    close(reopened);
                }
                say("E2", "use with the job unreachable=" + use(demo, 6) + " % (still capped?)");
            } finally {
                if (!closed) {
                    close(job);
                }
                close(process);
            }
        }
    }

    /** E1: assignment of a process that has exited while its handle is open. */
    private void e1AssignAnExitedProcess() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--cpu", "1", "--duration", "1s")) {
            int pid = (int) demo.identity().pid();
            HANDLE process = Kernel32.INSTANCE.OpenProcess(RIGHTS, false, pid);
            say("E1", "open ok=" + (process != null) + err());
            boolean exited = demo.exitsWithin(Duration.ofSeconds(10));
            IntByReference code = new IntByReference();
            Kernel32.INSTANCE.GetExitCodeProcess(process, code);
            say("E1", "exited=" + exited + " exitCode=" + code.getValue());
            HANDLE job = JOBS.CreateJobObjectW(null, null);
            try {
                boolean assigned = JOBS.AssignProcessToJobObject(job, process);
                say("E1", "assign exited process ok=" + assigned + err() + " inJob(job)=" + inJob(process, job));
                readAccounting("E1 accounting", job);
            } finally {
                close(job);
                close(process);
            }
        }
    }

    /** E4: a process already in a job of someone else; what nesting and IsProcessInJob answer. */
    private void e4AProcessInAJobPrismDoesNotHold() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--cpu", "1", "--duration", "60s")) {
            int pid = (int) demo.identity().pid();
            HANDLE process = Kernel32.INSTANCE.OpenProcess(RIGHTS, false, pid);
            HANDLE other = JOBS.CreateJobObjectW(null, null);
            HANDLE second = JOBS.CreateJobObjectW(null, null);
            try {
                say("E4", "assign to the other job ok=" + JOBS.AssignProcessToJobObject(other, process) + err());
                say(
                        "E4",
                        "inJob(NULL)=" + inJob(process, null) + " inJob(other)=" + inJob(process, other)
                                + " inJob(second)=" + inJob(process, second));
                boolean nested = JOBS.AssignProcessToJobObject(second, process);
                say("E4", "nested assignment to an empty job ok=" + nested + err() + " (the product never does this)");
                say(
                        "E4",
                        "after nesting inJob(other)=" + inJob(process, other) + " inJob(second)="
                                + inJob(process, second));
            } finally {
                close(second);
                close(other);
                close(process);
            }
        }
    }

    private static void setRate(String what, HANDLE job, int flags, int rate) {
        JobObjectStructures.CpuRateControlInformation info = new JobObjectStructures.CpuRateControlInformation();
        info.ControlFlags = flags;
        info.CpuRate = rate;
        boolean ok =
                JOBS.SetInformationJobObject(job, JobNative.JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION, info, info.size());
        say(what, "ok=" + ok + (ok ? "" : err()));
    }

    private static void readRate(String what, HANDLE job) {
        JobObjectStructures.CpuRateControlInformation info = new JobObjectStructures.CpuRateControlInformation();
        IntByReference length = new IntByReference();
        boolean ok = JOBS.QueryInformationJobObject(
                job, JobNative.JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION, info, info.size(), length);
        say(
                what,
                "ok=" + ok + (ok ? "" : err()) + " flags=0x" + Integer.toHexString(info.ControlFlags) + " rate="
                        + info.CpuRate + " returned=" + length.getValue());
    }

    private static void setMemory(String what, HANDLE job, int flags, long bytes) {
        JobObjectStructures.ExtendedLimitInformation info = new JobObjectStructures.ExtendedLimitInformation();
        IntByReference length = new IntByReference();
        JOBS.QueryInformationJobObject(job, JobNative.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, info.size(), length);
        info.BasicLimitInformation.LimitFlags =
                (info.BasicLimitInformation.LimitFlags & ~JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY) | flags;
        info.ProcessMemoryLimit = bytes;
        boolean ok =
                JOBS.SetInformationJobObject(job, JobNative.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, info.size());
        say(what, "ok=" + ok + (ok ? "" : err()));
    }

    private static void readMemory(String what, HANDLE job) {
        JobObjectStructures.ExtendedLimitInformation info = new JobObjectStructures.ExtendedLimitInformation();
        IntByReference length = new IntByReference();
        boolean ok = JOBS.QueryInformationJobObject(
                job, JobNative.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, info.size(), length);
        say(
                what,
                "ok=" + ok + (ok ? "" : err()) + " limitFlags=0x"
                        + Integer.toHexString(info.BasicLimitInformation.LimitFlags) + " processMemoryLimit="
                        + info.ProcessMemoryLimit + " peakProcess=" + info.PeakProcessMemoryUsed + " returned="
                        + length.getValue() + " size=" + info.size());
    }

    private static void readAccounting(String what, HANDLE job) {
        JobObjectStructures.BasicAccountingInformation info = new JobObjectStructures.BasicAccountingInformation();
        IntByReference length = new IntByReference();
        boolean ok = JOBS.QueryInformationJobObject(
                job, JobNative.JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION, info, info.size(), length);
        say(
                what,
                "ok=" + ok + (ok ? "" : err()) + " active=" + info.ActiveProcesses + " total=" + info.TotalProcesses
                        + " returned=" + length.getValue());
    }

    private static String inJob(HANDLE process, HANDLE job) {
        IntByReference result = new IntByReference();
        boolean ok = JOBS.IsProcessInJob(process, job, result);
        return ok ? String.valueOf(result.getValue() != 0) : "failed" + err();
    }

    /** The demo's use of the whole machine over the window, as the JDK reads its processor time. */
    private static String use(DemoTarget demo, int seconds) throws InterruptedException {
        long before = demo.cpu().toNanos();
        long start = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        long used = demo.cpu().toNanos() - before;
        double share = 100.0 * used / ((System.nanoTime() - start) * (double) PROCESSORS);
        return String.format(java.util.Locale.ROOT, "%.1f", share);
    }

    private static void close(HANDLE handle) {
        if (handle != null && !Kernel32.INSTANCE.CloseHandle(handle)) {
            say("close", "CloseHandle failed" + err());
        }
    }

    private static String err() {
        return " error=" + Native.getLastError();
    }

    private static void say(String what, String text) {
        System.out.println("job-probe: " + what + ": " + text);
    }
}
