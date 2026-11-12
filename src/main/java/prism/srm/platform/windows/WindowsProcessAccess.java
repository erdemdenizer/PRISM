package prism.srm.platform.windows;

import com.sun.jna.platform.win32.WinNT;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;

/**
 * The one way process control reaches a process: open it, and accept it only if it is still the
 * process the identity names. Every action goes through {@link #open}, so none of them can act on
 * a PID alone.
 *
 * <p>{@link #open} asks for the rights the action needs and {@code
 * PROCESS_QUERY_LIMITED_INFORMATION} on top, which reading the exit code and the creation time
 * needs. Then:
 *
 * <ol>
 *   <li>OpenProcess failed with {@code ERROR_ACCESS_DENIED}: {@code ACCESS_DENIED}. That is the
 *       answer for elevated processes, processes of other users and protected ones (SAFE-4).
 *   <li>It failed with any other error: the code alone does not say why. Microsoft documents
 *       {@code ERROR_INVALID_PARAMETER} only for the idle process; what a PID that no longer exists
 *       gives is not documented. So the system's process list is read: a PID it does not list is
 *       {@code PROCESS_NOT_FOUND}; one it lists, or a list that cannot be read, is {@code
 *       NATIVE_ERROR}. The code goes with the outcome either way.
 *   <li>The process has an exit code other than {@code STILL_ACTIVE}: it has exited, and only
 *       another handle keeps its object: {@code PROCESS_NOT_FOUND}.
 *   <li>Its creation time, in the milliseconds the process listing uses, differs from the start
 *       time of the identity: the PID belongs to another process now: {@code PROCESS_NOT_FOUND}.
 * </ol>
 *
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openprocess
 * https://learn.microsoft.com/en-us/windows/win32/procthread/process-security-and-access-rights
 */
final class WindowsProcessAccess {

    static final int QUERY = WinNT.PROCESS_QUERY_LIMITED_INFORMATION;
    static final int ERROR_ACCESS_DENIED = com.sun.jna.platform.win32.WinError.ERROR_ACCESS_DENIED;

    /** Windows PIDs are 32-bit unsigned numbers. */
    private static final long LARGEST_PID = 0xFFFF_FFFFL;

    private final Win32 win32;

    WindowsProcessAccess(Win32 win32) {
        this.win32 = win32;
    }

    /** A process opened and checked, or why it is not. Exactly one of the two is present. */
    record Opening(Optional<PinnedProcess> process, Optional<ControlOutcome> failure) {
        static Opening of(PinnedProcess process) {
            return new Opening(Optional.of(process), Optional.empty());
        }

        static Opening failed(ControlOutcome failure) {
            return new Opening(Optional.empty(), Optional.of(failure));
        }
    }

    Opening open(ProcessIdentity identity, int rights) {
        if (identity.pid() > LARGEST_PID) {
            return Opening.failed(ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "no Windows PID is that large"));
        }
        int pid = (int) identity.pid();
        Win32.Opened opened = win32.openProcess(rights | QUERY, pid);
        if (!opened.ok()) {
            return Opening.failed(openFailure(pid, opened.error()));
        }
        PinnedProcess process = new PinnedProcess(win32, identity, opened.handle());
        Optional<ControlOutcome> mismatch = check(process, identity);
        if (mismatch.isPresent()) {
            process.close();
            return Opening.failed(mismatch.get());
        }
        return Opening.of(process);
    }

    /** Whether the identity still runs; changes nothing. */
    ControlOutcome liveness(ProcessIdentity identity) {
        Opening opening = open(identity, 0);
        if (opening.failure().isPresent()) {
            return opening.failure().get();
        }
        opening.process().get().close();
        return ControlOutcome.success();
    }

    private ControlOutcome openFailure(int pid, int error) {
        String text = "OpenProcess failed: " + win32.describe(error);
        if (error == ERROR_ACCESS_DENIED) {
            return ControlOutcome.of(OperationResult.ACCESS_DENIED, error, text);
        }
        Optional<Boolean> listed = win32.processListed(pid);
        if (listed.isPresent() && !listed.get()) {
            return ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, error, text + "; the system lists no such PID");
        }
        return ControlOutcome.of(
                OperationResult.NATIVE_ERROR,
                error,
                text + (listed.isPresent() ? "; the PID is listed" : "; the process list could not be read"));
    }

    private Optional<ControlOutcome> check(PinnedProcess process, ProcessIdentity identity) {
        OptionalInt exitCode = win32.processExitCode(process.handle());
        if (exitCode.isEmpty()) {
            int error = win32.lastError();
            return Optional.of(ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, error, "GetExitCodeProcess failed: " + win32.describe(error)));
        }
        if (exitCode.getAsInt() != Win32.STILL_ACTIVE) {
            return Optional.of(ControlOutcome.of(
                    OperationResult.PROCESS_NOT_FOUND, "the process has exited with code " + exitCode.getAsInt()));
        }
        OptionalLong creation = win32.creationTime(process.handle());
        if (creation.isEmpty()) {
            int error = win32.lastError();
            return Optional.of(ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, error, "GetProcessTimes failed: " + win32.describe(error)));
        }
        long createdMillis = WindowsProcessVirtualSizes.filetimeToEpochMillis(creation.getAsLong());
        if (createdMillis != identity.startTime().toEpochMilli()) {
            return Optional.of(ControlOutcome.of(
                    OperationResult.PROCESS_NOT_FOUND,
                    "PID " + identity.pid() + " belongs to a process started at " + createdMillis + " ms, not at "
                            + identity.startTime().toEpochMilli() + " ms"));
        }
        return Optional.empty();
    }
}
