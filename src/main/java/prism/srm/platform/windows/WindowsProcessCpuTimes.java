package prism.srm.platform.windows;

import java.util.OptionalLong;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ProcessCpuTimeSource;

/**
 * The processor time of one process on Windows, for the emulated CPU limiter, which reads its
 * target about once a second. OSHI's lookup of one process builds its maps of every process on
 * Windows; this opens the one process instead.
 *
 * <p>The process is opened through {@link WindowsProcessAccess} with {@code
 * PROCESS_QUERY_LIMITED_INFORMATION}, which checks its exit code and its creation time against the
 * identity and holds it open, so the time read next is the identity's: GetProcessTimes gives the
 * kernel and the user time, summed over the threads, in 100-nanosecond units. Both advance by the
 * clock tick (about 15.6 ms by default). A process that may not be opened is unreadable, not gone.
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocesstimes
 */
public final class WindowsProcessCpuTimes implements ProcessCpuTimeSource {

    private final Win32 win32;
    private final WindowsProcessAccess access;

    public WindowsProcessCpuTimes() {
        this(new JnaWin32());
    }

    WindowsProcessCpuTimes(Win32 win32) {
        this.win32 = win32;
        this.access = new WindowsProcessAccess(win32);
    }

    @Override
    public CpuTimeReading cpuTime(ProcessIdentity identity) {
        WindowsProcessAccess.Opening opening = access.open(identity, 0);
        if (opening.failure().isPresent()) {
            ControlOutcome failure = opening.failure().get();
            return failure.result() == OperationResult.PROCESS_NOT_FOUND
                    ? CpuTimeReading.GONE
                    : new CpuTimeReading.Unreadable(
                            failure.detail().orElse(failure.result().name()));
        }
        try (PinnedProcess process = opening.process().get()) {
            OptionalLong time = win32.processorTime(process.handle());
            if (time.isEmpty()) {
                int error = win32.lastError();
                return new CpuTimeReading.Unreadable("GetProcessTimes failed: " + win32.describe(error));
            }
            return new CpuTimeReading.Read(time.getAsLong() / 10_000);
        }
    }
}
