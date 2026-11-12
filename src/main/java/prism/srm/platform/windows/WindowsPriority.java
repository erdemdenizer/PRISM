package prism.srm.platform.windows;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import java.util.Map;
import java.util.OptionalInt;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;

/**
 * Change Priority on Windows (CTL-6): the priority class of the process, one of five. {@code
 * REALTIME_PRIORITY_CLASS} is never used and has no level.
 *
 * <p>SetPriorityClass needs {@code PROCESS_SET_INFORMATION}; the class is then read back with
 * GetPriorityClass, and only a class the system holds counts as set: Microsoft does not say for
 * every class whether a standard user may set it, so the read-back decides.
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setpriorityclass
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getpriorityclass
 *
 * <p>The process listing reports the base priority of a process, which for a thread of normal
 * priority is 4, 6, 8, 10 and 13 for the five classes; other numbers occur and stand for no level.
 * https://learn.microsoft.com/en-us/windows/win32/procthread/scheduling-priorities
 */
final class WindowsPriority {

    /**
     * The level each base priority of the listing stands for: the base priority of a process of
     * normal thread priority in each class (Scheduling Priorities, linked above). A table only;
     * nothing is read for it.
     */
    static final Map<Integer, PriorityLevel> BASE_PRIORITIES = Map.of(
            4, PriorityLevel.LOW,
            6, PriorityLevel.BELOW_NORMAL,
            8, PriorityLevel.NORMAL,
            10, PriorityLevel.ABOVE_NORMAL,
            13, PriorityLevel.HIGH);

    private final Win32 win32;
    private final WindowsProcessAccess access;

    WindowsPriority(Win32 win32, WindowsProcessAccess access) {
        this.win32 = win32;
        this.access = access;
    }

    static int priorityClass(PriorityLevel level) {
        return switch (level) {
            case LOW -> Kernel32.IDLE_PRIORITY_CLASS.intValue();
            case BELOW_NORMAL -> Kernel32.BELOW_NORMAL_PRIORITY_CLASS.intValue();
            case NORMAL -> Kernel32.NORMAL_PRIORITY_CLASS.intValue();
            case ABOVE_NORMAL -> Kernel32.ABOVE_NORMAL_PRIORITY_CLASS.intValue();
            case HIGH -> Kernel32.HIGH_PRIORITY_CLASS.intValue();
        };
    }

    ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
        WindowsProcessAccess.Opening opening = access.open(target, WinNT.PROCESS_SET_INFORMATION);
        if (opening.failure().isPresent()) {
            return opening.failure().get();
        }
        try (PinnedProcess process = opening.process().get()) {
            int wanted = priorityClass(level);
            int error = win32.setPriorityClass(process.handle(), wanted);
            if (error != 0) {
                String text = "SetPriorityClass failed: " + win32.describe(error);
                return error == WindowsProcessAccess.ERROR_ACCESS_DENIED
                        ? ControlOutcome.of(OperationResult.ACCESS_DENIED, error, text)
                        : ControlOutcome.of(OperationResult.NATIVE_ERROR, error, text);
            }
            OptionalInt held = win32.priorityClass(process.handle());
            if (held.isEmpty()) {
                int readError = win32.lastError();
                return ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        readError,
                        "the priority class could not be read back: " + win32.describe(readError));
            }
            if (held.getAsInt() != wanted) {
                return ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        String.format(
                                java.util.Locale.ROOT,
                                "the system holds class 0x%X, not the 0x%X that was set",
                                held.getAsInt(),
                                wanted));
            }
            return ControlOutcome.success("priority class set and read back");
        }
    }
}
