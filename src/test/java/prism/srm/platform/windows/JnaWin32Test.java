package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinNT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * The JNA mappings on the running system, used only in ways that change nothing: this JVM and the
 * system process are opened for reading, and the rights a control action would need are asked of
 * the system process only to see them refused (SAFE-6). Nothing is suspended, set or terminated.
 */
@EnabledOnOs(OS.WINDOWS)
class JnaWin32Test {

    private static final int SYSTEM_PID = 4;

    private final JnaWin32 win32 = new JnaWin32();
    private final int ownPid = (int) ProcessHandle.current().pid();

    /** The documented sizes of the structures the snapshots fill (64-bit). */
    @Test
    void theToolhelpStructuresHaveTheirDocumentedSizes() {
        assertThat(Platform.is64Bit()).isTrue();
        assertThat(new Tlhelp32.THREADENTRY32().size()).isEqualTo(28);
        assertThat(new Tlhelp32.PROCESSENTRY32().size()).isEqualTo(568);
        assertThat(JnaWin32.WS_EX_TOOLWINDOW).isEqualTo(0x80);
        assertThat(WindowsSuspensions.THREAD_RIGHTS).isEqualTo(0x0002 | 0x0800);
    }

    /** Milestone 9: the processor time of this JVM agrees with the JDK's, and a thread's creation time is read. */
    @Test
    void theProcessorTimeAndAThreadsCreationTimeAreReadAndNoDebuggerIsAttached() {
        Win32.Opened opened = win32.openProcess(
                WindowsProcessAccess.QUERY | com.sun.jna.platform.win32.WinNT.PROCESS_QUERY_INFORMATION, ownPid);
        assertThat(opened.ok()).isTrue();
        try {
            long jdkBefore = ProcessHandle.current()
                    .info()
                    .totalCpuDuration()
                    .orElseThrow()
                    .toMillis();
            long read = win32.processorTime(opened.handle()).orElseThrow() / 10_000;
            long jdkAfter = ProcessHandle.current()
                    .info()
                    .totalCpuDuration()
                    .orElseThrow()
                    .toMillis();
            // The same counters, read one after the other; both advance by the clock tick.
            assertThat(read).isBetween(jdkBefore - 50, jdkAfter + 50);
            assertThat(win32.debuggerPresent(opened.handle())).contains(false);
        } finally {
            win32.close(opened.handle());
        }
        int threadId = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId();
        Win32.Opened thread = win32.openThread(WindowsSuspensions.THREAD_RIGHTS, threadId);
        assertThat(thread.ok()).isTrue();
        try {
            long created = WindowsProcessVirtualSizes.filetimeToEpochMillis(
                    win32.threadCreationTime(thread.handle()).orElseThrow());
            assertThat(Instant.ofEpochMilli(created))
                    .isAfterOrEqualTo(ProcessHandle.current()
                            .info()
                            .startInstant()
                            .orElseThrow()
                            .minusSeconds(1))
                    .isBeforeOrEqualTo(Instant.now());
        } finally {
            win32.close(thread.handle());
        }
    }

    /** The source the limiter reads on Windows, on this JVM. */
    @Test
    void theCpuTimeSourceReadsThisJvmByItsIdentity() {
        prism.srm.domain.ProcessIdentity own = new prism.srm.domain.ProcessIdentity(
                ownPid, ProcessHandle.current().info().startInstant().orElseThrow());

        assertThat(new WindowsProcessCpuTimes(win32).cpuTime(own))
                .isInstanceOf(prism.srm.domain.CpuTimeReading.Read.class);
        assertThat(new WindowsProcessCpuTimes(win32)
                        .cpuTime(new prism.srm.domain.ProcessIdentity(
                                ownPid, own.startTime().minusSeconds(1))))
                .isEqualTo(prism.srm.domain.CpuTimeReading.GONE);
    }

    @Test
    void thisJvmIsReadThroughTheMappingsAsTheJdkSeesIt() {
        Win32.Opened opened = win32.openProcess(WindowsProcessAccess.QUERY, ownPid);
        assertThat(opened.ok()).isTrue();
        try {
            long created = WindowsProcessVirtualSizes.filetimeToEpochMillis(
                    win32.creationTime(opened.handle()).getAsLong());
            assertThat(Instant.ofEpochMilli(created))
                    .isEqualTo(ProcessHandle.current().info().startInstant().orElseThrow());
            assertThat(win32.processExitCode(opened.handle())).hasValue(Win32.STILL_ACTIVE);
            assertThat(win32.critical(opened.handle())).contains(false);
            assertThat(win32.imagePath(opened.handle()).orElseThrow().toLowerCase(java.util.Locale.ROOT))
                    .endsWith("java.exe");
            assertThat(win32.priorityClass(opened.handle())).isPresent();
        } finally {
            win32.close(opened.handle());
        }
        assertThat(win32.processListed(ownPid)).contains(true);
        // Windows PIDs are multiples of four: 3 is never one.
        assertThat(win32.processListed(3)).contains(false);
    }

    @Test
    void theThreadsOfThisJvmBelongToItByTheirHandles() {
        Win32.ThreadListing threads = win32.threadsOf(ownPid);
        assertThat(threads.ok()).isTrue();
        assertThat(threads.threadIds()).isNotEmpty();
        Win32.Opened thread = win32.openThread(
                WinNT.THREAD_QUERY_LIMITED_INFORMATION, threads.threadIds().get(0));
        assertThat(thread.ok()).isTrue();
        try {
            assertThat(win32.processIdOfThread(thread.handle())).isEqualTo(ownPid);
            assertThat(win32.threadExitCode(thread.handle())).hasValue(Win32.STILL_ACTIVE);
        } finally {
            win32.close(thread.handle());
        }
    }

    /**
     * SAFE-4 on the real system, without acting on it: the rights to terminate, to set the priority
     * and to suspend are refused for the system process with ERROR_ACCESS_DENIED, and that is how
     * the identity check reports them.
     */
    @Test
    void theSystemProcessRefusesTheRightsOfEveryActionWithAccessDenied() {
        assertThat(refusal(WinNT.PROCESS_TERMINATE)).isEqualTo(5);
        assertThat(refusal(WinNT.PROCESS_SET_INFORMATION)).isEqualTo(5);
        List<Integer> systemThreads = win32.threadsOf(SYSTEM_PID).threadIds();
        assertThat(systemThreads).isNotEmpty();
        Win32.Opened thread = win32.openThread(WinNT.THREAD_SUSPEND_RESUME, systemThreads.get(0));
        if (thread.ok()) {
            win32.close(thread.handle());
        }
        assertThat(thread.error()).isEqualTo(5);

        WindowsProcessAccess access = new WindowsProcessAccess(win32);
        WindowsProcessAccess.Opening opening =
                access.open(new ProcessIdentity(SYSTEM_PID, Instant.EPOCH), WinNT.PROCESS_TERMINATE);
        assertThat(opening.failure().map(failure -> failure.result())).contains(OperationResult.ACCESS_DENIED);
    }

    /**
     * Process control finds a process by the start time the listing gave it. Both come from the
     * same FILETIME, truncated alike; this checks it for every process this user may open.
     */
    @Test
    void theCreationTimeOfEveryOpenableProcessIsTheStartTimeOfTheListing() {
        Map<ProcessIdentity, Long> listed = new WindowsProcessVirtualSizes().virtualSizes();
        long listedAt = Instant.now().toEpochMilli();
        int compared = 0;
        int differing = 0;
        for (ProcessIdentity identity : listed.keySet()) {
            Win32.Opened opened = win32.openProcess(WindowsProcessAccess.QUERY, (int) identity.pid());
            if (!opened.ok()) {
                continue;
            }
            try {
                Optional<Long> created = win32.creationTime(opened.handle()).stream()
                        .boxed()
                        .findFirst()
                        .map(WindowsProcessVirtualSizes::filetimeToEpochMillis);
                // A PID whose process started after the listing was given to a new process meanwhile.
                if (created.isPresent() && created.get() <= listedAt) {
                    compared++;
                    if (created.get() != identity.startTime().toEpochMilli()) {
                        differing++;
                    }
                }
            } finally {
                win32.close(opened.handle());
            }
        }
        assertThat(compared).isGreaterThan(20);
        assertThat(differing).isZero();
    }

    @Test
    void anErrorCodeIsDescribedWithItsSystemText() {
        assertThat(win32.describe(5)).startsWith("5 (").hasSizeGreaterThan(6);
    }

    /** Opens PID 4 with the rights only to see them refused; closes the handle should they be granted. */
    private int refusal(int rights) {
        Win32.Opened opened = win32.openProcess(rights, SYSTEM_PID);
        if (opened.ok()) {
            win32.close(opened.handle());
        }
        return opened.error();
    }

    /**
     * What the fakes assume about failures, on the real system and without effect: ResumeThread on
     * a handle without THREAD_SUSPEND_RESUME fails with (DWORD) -1 and ERROR_ACCESS_DENIED, which
     * the mapping hands over; an ID that no thread or process has (Windows IDs are multiples of
     * four) is refused with ERROR_INVALID_PARAMETER, and the identity check calls it not found.
     */
    @Test
    void failuresReachTheCallerWithTheirCodes() {
        int ownThread = win32.threadsOf(ownPid).threadIds().get(0);
        Win32.Opened readOnly = win32.openThread(WinNT.THREAD_QUERY_LIMITED_INFORMATION, ownThread);
        assertThat(readOnly.ok()).isTrue();
        try {
            assertThat(win32.resumeThread(readOnly.handle())).isEqualTo(new Win32.Counted(-1, 5));
        } finally {
            win32.close(readOnly.handle());
        }

        assertThat(win32.openThread(WinNT.THREAD_QUERY_LIMITED_INFORMATION, 3).error())
                .isEqualTo(WindowsSuspensions.ERROR_INVALID_PARAMETER);
        assertThat(new WindowsProcessAccess(win32)
                        .open(new ProcessIdentity(3, Instant.EPOCH), 0)
                        .failure()
                        .map(failure -> failure.result()))
                .contains(OperationResult.PROCESS_NOT_FOUND);
    }
}
