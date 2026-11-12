package prism.srm.platform.windows;

import com.sun.jna.platform.win32.WinNT;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.ProcessController;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * Process control on Windows (ADR-0002). Every action opens the process through {@link
 * WindowsProcessAccess}, which checks the identity and holds the process open for the action.
 *
 * <p><b>Force Kill</b> uses the JDK's {@link ProcessHandle#destroyForcibly()} (CTL-3), which on
 * Windows calls TerminateProcess; termination is asynchronous, so success means it was started.
 * The JDK answers {@code false} alike for a process it may not open, one that exited, and a
 * TerminateProcess that failed. So the process is first opened here with {@code
 * PROCESS_TERMINATE}, which tells a refusal apart before the JDK is asked, and after a {@code
 * false} the exit code of the process held open tells an exit apart from a failure.
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-terminateprocess
 *
 * <p><b>Protection facts</b>: PIDs 0 and 4 are the idle and the kernel process; IsProcessCritical
 * says whether Windows marks a process as critical (Windows 8.1 and later, {@code
 * PROCESS_QUERY_LIMITED_INFORMATION}, a right that is not withheld from protected processes); the
 * executable's name is compared with a short list of system processes as an additional defense
 * only. A fact that cannot be read is absent; whether that protects the process is decided above.
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-isprocesscritical
 */
public final class WindowsProcessController implements ProcessController {

    /** The executables of the system's own processes; a name never protects on its own merits. */
    private static final Set<String> SYSTEM_EXECUTABLES =
            Set.of("smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe", "lsaiso.exe");

    private static final Map<ControlAction, Capability> CAPABILITIES = Map.copyOf(capabilitiesOfWindows());

    private final Win32 win32;
    private final WindowsProcessAccess access;
    private final WindowsEndTask endTask;
    private final WindowsPriority priority;
    private final WindowsSuspensions suspensions;
    private final LongFunction<Optional<Boolean>> destroyForcibly;

    public WindowsProcessController() {
        this(new JnaWin32(), pid -> ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly));
    }

    /**
     * @param destroyForcibly asks the JDK to terminate a PID: empty when the JDK finds no such live
     *     process, otherwise what {@link ProcessHandle#destroyForcibly()} returned
     */
    WindowsProcessController(Win32 win32, LongFunction<Optional<Boolean>> destroyForcibly) {
        this.win32 = win32;
        this.access = new WindowsProcessAccess(win32);
        this.endTask = new WindowsEndTask(win32, access);
        this.priority = new WindowsPriority(win32, access);
        this.suspensions = new WindowsSuspensions(win32, access);
        this.destroyForcibly = destroyForcibly;
    }

    @Override
    public Map<ControlAction, Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Map<Integer, PriorityLevel> listedPriorities() {
        return WindowsPriority.BASE_PRIORITIES;
    }

    /** A standard user may set any of the five classes, High included (milestone 7), and the listing shows the class a process has. */
    @Override
    public Restorability priorityRestorability() {
        return Restorability.RESTORABLE;
    }

    @Override
    public ProtectionFacts protectionFacts(ProcessIdentity target) {
        if (target.pid() == 0 || target.pid() == 4) {
            return new ProtectionFacts(true, Optional.empty(), false);
        }
        WindowsProcessAccess.Opening opening = access.open(target, 0);
        if (opening.process().isEmpty()) {
            return ProtectionFacts.NONE;
        }
        try (PinnedProcess process = opening.process().get()) {
            Optional<Boolean> critical = win32.critical(process.handle());
            boolean listed = win32.imagePath(process.handle())
                    .map(WindowsProcessController::fileName)
                    .map(SYSTEM_EXECUTABLES::contains)
                    .orElse(false);
            return new ProtectionFacts(false, critical, listed);
        }
    }

    @Override
    public ControlOutcome liveness(ProcessIdentity target) {
        return access.liveness(target);
    }

    @Override
    public ControlOutcome requestClose(ProcessIdentity target) {
        return endTask.requestClose(target);
    }

    @Override
    public ControlOutcome terminate(ProcessIdentity target) {
        WindowsProcessAccess.Opening opening = access.open(target, WinNT.PROCESS_TERMINATE);
        if (opening.failure().isPresent()) {
            return opening.failure().get();
        }
        try (PinnedProcess process = opening.process().get()) {
            // The PID cannot change hands while the handle is open, so the JDK acts on this process.
            Optional<Boolean> destroyed = destroyForcibly.apply(target.pid());
            if (destroyed.orElse(false)) {
                return ControlOutcome.success("termination started");
            }
            if (process.exited()) {
                return ControlOutcome.of(
                        OperationResult.PROCESS_NOT_FOUND, "the process exited before it was terminated");
            }
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR,
                    destroyed.isEmpty()
                            ? "the process runs, but the JDK could not open it (ProcessHandle.of asks for more"
                                    + " rights than PROCESS_TERMINATE, PROCESS_DUP_HANDLE among them)"
                            : "ProcessHandle.destroyForcibly returned false for a live process");
        }
    }

    @Override
    public SuspendResult suspend(ProcessIdentity target) {
        return suspensions.suspend(target);
    }

    @Override
    public ControlOutcome resume(Suspension suspension) {
        if (!(suspension instanceof WindowsSuspension windows)) {
            return ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "not a suspension this controller made");
        }
        return suspensions.resume(windows);
    }

    @Override
    public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
        return priority.changePriority(target, level);
    }

    /**
     * Windows keeps suspension per thread, and its count cannot be read without changing it, so
     * whether a process is suspended is never read when nothing has been asked for.
     */
    @Override
    public StopState stopState(ProcessIdentity target) {
        return StopState.UNKNOWN;
    }

    @Override
    public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
        return suspensions.adopt(target, memento);
    }

    /** The last part of a Windows path, in lower case without a locale. */
    static String fileName(String path) {
        int separator = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return path.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static Map<ControlAction, Capability> capabilitiesOfWindows() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        map.put(ControlAction.END_TASK, Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST));
        map.put(
                ControlAction.FORCE_KILL,
                Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.PROCESS_HANDLE_TERMINATE));
        map.put(
                ControlAction.SUSPEND,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION));
        map.put(
                ControlAction.RESUME,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION));
        map.put(
                ControlAction.CHANGE_PRIORITY,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PRIORITY_CLASS));
        return map;
    }
}
