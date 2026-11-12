package prism.srm.platform.windows;

import com.sun.jna.platform.win32.WinNT;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.SuspendResult;

/**
 * Suspend and Resume on Windows (CTL-5, ADR-0002): every thread of the process, one at a time.
 * Windows has no documented whole-process suspend; the undocumented NtSuspendProcess is not used.
 * The method is not atomic: a thread created after the last listing runs on, which is why it is
 * {@code NATIVE_SOFT}.
 *
 * <p><b>Suspend.</b> With the process held open, the system's threads are listed (a Toolhelp
 * snapshot is always system-wide; the threads of the process are picked by their owner) up to three
 * times, until a listing finds no thread that is not held already. Each thread not held yet is
 * opened with {@code THREAD_SUSPEND_RESUME | THREAD_QUERY_LIMITED_INFORMATION}; GetProcessIdOfThread
 * confirms that the ID, which may have been reused since the listing, still belongs to the
 * process; then SuspendThread adds one to its suspend count and the handle is kept. A thread that
 * cannot be opened with {@code ERROR_INVALID_PARAMETER} is taken to have ended since the listing
 * and passed over, but only once: the code is not documented for OpenThread, so an ID that the
 * next listing still names as a thread of the process and that still cannot be opened is a
 * failure. Only held threads are skipped by the next listing, so an ID that was passed over and is
 * now a new thread of the process is suspended too. If the third listing still finds new threads,
 * the result says so.
 *
 * <p><b>A failure part way</b> (a thread that may not be opened, or SuspendThread failing) is
 * rolled back: each thread Prism suspended gets one ResumeThread. If all of that is undone, the
 * handles are closed and the original failure is returned. It is marked transient, the one kind a
 * CPU limit tries again, only when the thread that failed is shown to have ended (GetExitCodeThread
 * on its open handle gives a code other than {@code STILL_ACTIVE}) and the process is shown to be
 * running (GetExitCodeProcess gives {@code STILL_ACTIVE}); a refusal of OpenThread, a thread that
 * still runs, and an answer the system did not give are not. If a live thread keeps Prism's
 * increment, the failure is returned together with a suspension that holds exactly the threads
 * still owned, so the increment is not forgotten and Resume can try again.
 *
 * <p><b>Resume</b> calls ResumeThread once on each owned thread and never twice: SuspendThread and
 * ResumeThread return the previous suspend count, or {@code (DWORD) -1} on failure. What
 * ResumeThread does on a thread that has ended is not documented, so a failure is judged by the
 * thread's exit code: an ended thread is released, a live one stays owned. When the process has
 * exited, every thread has ended.
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-suspendthread
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-resumethread
 * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getexitcodethread
 * https://learn.microsoft.com/en-us/windows/win32/procthread/thread-security-and-access-rights
 *
 * <p>A {@code RuntimeException} part way, which no call here is expected to throw, is rolled back
 * like a failure before it goes on, so no thread is left suspended without a hold on it.
 *
 * <p>WOW64: Microsoft says a 64-bit program <em>can</em> call Wow64SuspendThread on a 32-bit
 * thread, and that for a 32-bit caller it is the same as SuspendThread; it does not say what
 * SuspendThread from a 64-bit program does to such a thread. Prism reads no thread context, where
 * the two differ, and uses SuspendThread for every thread; MILESTONES.md (milestone 7) records what
 * a 32-bit target showed.
 * https://learn.microsoft.com/en-us/windows/win32/api/wow64apiset/nf-wow64apiset-wow64suspendthread
 */
final class WindowsSuspensions {

    static final int THREAD_RIGHTS = WinNT.THREAD_SUSPEND_RESUME | WinNT.THREAD_QUERY_LIMITED_INFORMATION;
    static final int ERROR_INVALID_PARAMETER = com.sun.jna.platform.win32.WinError.ERROR_INVALID_PARAMETER;
    static final int LISTINGS = 3;

    private final Win32 win32;
    private final WindowsProcessAccess access;

    WindowsSuspensions(Win32 win32, WindowsProcessAccess access) {
        this.win32 = win32;
        this.access = access;
    }

    SuspendResult suspend(ProcessIdentity target) {
        WindowsProcessAccess.Opening opening = access.open(target, 0);
        if (opening.failure().isPresent()) {
            return SuspendResult.failed(opening.failure().get());
        }
        PinnedProcess process = opening.process().get();
        List<WindowsSuspension.Hold> holds = new ArrayList<>();
        Pass pass;
        try {
            pass = suspendThreads(process, holds);
        } catch (RuntimeException unexpected) {
            WindowsSuspension taken = new WindowsSuspension(win32, process, holds);
            release(taken);
            if (taken.outstanding() == 0) {
                taken.close();
                throw unexpected;
            }
            return new SuspendResult(
                    ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            unexpected + "; rollback left " + taken.outstanding()
                                    + " live thread(s) suspended by Prism"),
                    Optional.of(taken));
        }
        ControlOutcome failure = pass.failure();
        WindowsSuspension suspension = new WindowsSuspension(win32, process, holds);
        if (failure == null && holds.isEmpty()) {
            boolean exited = process.exited();
            suspension.close();
            return SuspendResult.failed(
                    exited
                            ? ControlOutcome.of(
                                    OperationResult.PROCESS_NOT_FOUND, "the process exited; no thread was left")
                            : ControlOutcome.of(
                                    OperationResult.NATIVE_ERROR, "the system listed no thread of a running process"));
        }
        if (failure == null && process.exited()) {
            // Its threads ended with it: nothing is held, and there is nothing to resume.
            suspension.close();
            return SuspendResult.failed(ControlOutcome.of(
                    OperationResult.PROCESS_NOT_FOUND, "the process exited while it was being suspended"));
        }
        if (failure == null) {
            return SuspendResult.suspended(
                    ControlOutcome.success(holds.size() + " thread(s) suspended one by one"
                            + (pass.settled()
                                    ? ""
                                    : "; threads were still being created after " + LISTINGS
                                            + " listings, and a newer one may run on")),
                    suspension);
        }
        Release rollback = release(suspension);
        if (suspension.outstanding() == 0) {
            OptionalInt processCode = win32.processExitCode(process.handle());
            boolean running = processCode.isPresent() && processCode.getAsInt() == Win32.STILL_ACTIVE;
            suspension.close();
            // A thread that ended under Prism's hand, of a process shown to run, undone completely:
            // the next attempt may succeed. Anything else is not to be retried as if nothing happened.
            return pass.threadEnded() && running
                    ? SuspendResult.transientFailure(failure)
                    : SuspendResult.failed(failure);
        }
        String detail = failure.detail().orElse("suspend failed") + "; rollback left " + suspension.outstanding()
                + " live thread(s) suspended by Prism: " + rollback.describe(win32);
        return new SuspendResult(
                new ControlOutcome(OperationResult.NATIVE_ERROR, failure.nativeCode(), Optional.of(detail)),
                Optional.of(suspension));
    }

    /** Undoes what the suspension still owns. On success the suspension is closed. */
    ControlOutcome resume(WindowsSuspension suspension) {
        if (suspension.closed()) {
            return ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, "the suspension was closed; no handle is left to resume through");
        }
        Release release = release(suspension);
        int outstanding = suspension.outstanding();
        if (outstanding == 0) {
            suspension.close();
            // The exit is read once, in release(): the result says what that pass did.
            return release.processExited
                    ? ControlOutcome.of(
                            OperationResult.PROCESS_NOT_FOUND,
                            "the process has exited; its " + release.ended
                                    + " remaining thread handle(s) were closed and nothing more was resumed")
                    : ControlOutcome.success(release.released + " thread(s) released"
                            + (release.ended > 0 ? ", " + release.ended + " had ended" : ""));
        }
        String detail = outstanding + " live thread(s) still suspended by Prism: " + release.describe(win32);
        return release.firstError.isPresent()
                ? ControlOutcome.of(OperationResult.NATIVE_ERROR, release.firstError.getAsInt(), detail)
                : ControlOutcome.of(OperationResult.NATIVE_ERROR, detail);
    }

    /**
     * What the listings came to: the failure that stopped them, or null; whether the last found
     * nothing new; and whether the failure was that of a thread shown to have ended.
     */
    private record Pass(ControlOutcome failure, boolean settled, boolean threadEnded) {
        Pass(ControlOutcome failure, boolean settled) {
            this(failure, settled, false);
        }
    }

    /** What became of one listed thread, or the failure that stops the suspension. */
    private record Step(boolean held, boolean ended, ControlOutcome failure, boolean failedAsItEnded) {
        static final Step HELD = new Step(true, false, null, false);
        static final Step ENDED = new Step(false, true, null, false);
        static final Step FOREIGN = new Step(false, false, null, false);

        static Step failed(ControlOutcome failure) {
            return new Step(false, false, failure, false);
        }

        /** A failure of a thread that had ended by then: the one failure a later attempt may not meet. */
        static Step failedAsItEnded(ControlOutcome failure) {
            return new Step(false, false, failure, true);
        }
    }

    /** Lists and suspends threads into the holds. */
    private Pass suspendThreads(PinnedProcess process, List<WindowsSuspension.Hold> holds) {
        Set<Integer> held = new HashSet<>();
        Set<Integer> ended = new HashSet<>();
        for (int listing = 0; listing < LISTINGS; listing++) {
            Win32.ThreadListing threads = win32.threadsOf(process.pid());
            if (!threads.ok()) {
                return new Pass(
                        ControlOutcome.of(
                                OperationResult.NATIVE_ERROR,
                                threads.error(),
                                "the threads could not be listed: " + win32.describe(threads.error())),
                        false);
            }
            boolean heldNew = false;
            for (int threadId : threads.threadIds()) {
                if (held.contains(threadId)) {
                    continue;
                }
                Step step = suspendThread(process.pid(), threadId, holds);
                if (step.failure() != null) {
                    return new Pass(step.failure(), false, step.failedAsItEnded());
                }
                if (step.held()) {
                    held.add(threadId);
                    heldNew = true;
                } else if (step.ended() && !ended.add(threadId)) {
                    return new Pass(
                            ControlOutcome.of(
                                    OperationResult.NATIVE_ERROR,
                                    ERROR_INVALID_PARAMETER,
                                    "thread " + threadId + " is listed again but cannot be opened: "
                                            + win32.describe(ERROR_INVALID_PARAMETER)),
                            false);
                }
            }
            if (!heldNew) {
                return new Pass(null, true);
            }
        }
        return new Pass(null, false);
    }

    private Step suspendThread(int pid, int threadId, List<WindowsSuspension.Hold> holds) {
        Win32.Opened opened = win32.openThread(THREAD_RIGHTS, threadId);
        if (!opened.ok()) {
            if (opened.error() == ERROR_INVALID_PARAMETER) {
                // The thread ended since it was listed.
                return Step.ENDED;
            }
            OperationResult result = opened.error() == WindowsProcessAccess.ERROR_ACCESS_DENIED
                    ? OperationResult.ACCESS_DENIED
                    : OperationResult.NATIVE_ERROR;
            return Step.failed(ControlOutcome.of(
                    result, opened.error(), "OpenThread " + threadId + " failed: " + win32.describe(opened.error())));
        }
        int owner = win32.processIdOfThread(opened.handle());
        int ownerError = owner == 0 ? win32.lastError() : 0;
        if (owner != pid) {
            boolean ended = owner == 0 && threadEnded(opened.handle());
            win32.close(opened.handle());
            if (owner == 0) {
                // Ownership cannot be confirmed: a thread is never suspended on a guess.
                ControlOutcome failure = ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        ownerError,
                        "GetProcessIdOfThread failed for thread " + threadId + ": " + win32.describe(ownerError));
                return ended ? Step.failedAsItEnded(failure) : Step.failed(failure);
            }
            // The ID was given to a thread of another process since the listing.
            return Step.FOREIGN;
        }
        Win32.Counted suspended = win32.suspendThread(opened.handle());
        if (!suspended.ok()) {
            boolean ended = threadEnded(opened.handle());
            win32.close(opened.handle());
            OperationResult result = suspended.error() == WindowsProcessAccess.ERROR_ACCESS_DENIED
                    ? OperationResult.ACCESS_DENIED
                    : OperationResult.NATIVE_ERROR;
            ControlOutcome failure = ControlOutcome.of(
                    result,
                    suspended.error(),
                    "SuspendThread " + threadId + " failed: " + win32.describe(suspended.error()));
            return ended ? Step.failedAsItEnded(failure) : Step.failed(failure);
        }
        holds.add(new WindowsSuspension.Hold(opened.handle(), threadId));
        return Step.HELD;
    }

    /**
     * Whether the thread of an open handle has ended: GetExitCodeThread gives a code other than
     * {@code STILL_ACTIVE}. Not when the call fails, and not for a thread that ended with the code
     * 259, which reads as running (both err toward not trying again).
     */
    private boolean threadEnded(long thread) {
        OptionalInt code = win32.threadExitCode(thread);
        return code.isPresent() && code.getAsInt() != Win32.STILL_ACTIVE;
    }

    /** A thread a memento names: its ID and its creation time. */
    record Recorded(int threadId, long creationTime) {}

    /** Reads a memento; empty if it is not one this version wrote. */
    static Optional<List<Recorded>> parseMemento(String memento) {
        if (!memento.startsWith(WindowsSuspension.MEMENTO_VERSION)) {
            return Optional.empty();
        }
        String body = memento.substring(WindowsSuspension.MEMENTO_VERSION.length());
        List<Recorded> recorded = new ArrayList<>();
        if (body.isEmpty()) {
            return Optional.of(recorded);
        }
        try {
            for (String part : body.split(",", -1)) {
                int at = part.indexOf('@');
                if (at <= 0) {
                    return Optional.empty();
                }
                recorded.add(new Recorded(
                        Integer.parseUnsignedInt(part.substring(0, at)), Long.parseLong(part.substring(at + 1))));
            }
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
        return Optional.of(recorded);
    }

    /** A thread probed during an adoption: its hold, which carries the probe's increment, and the count before it. */
    private record Probe(WindowsSuspension.Hold hold, int previous) {}

    /**
     * Checks whether the threads an earlier session recorded still carry exactly the one suspension
     * it made, and if so takes them over (ADR-0011). Called only when the user asks to resume such a
     * process. The suspend count of a thread cannot be read without changing it, so each thread is
     * probed: SuspendThread returns the count before it, and the probe's own increment is taken back
     * at once, from a hold, so that it is never forgotten. Incrementing first never takes the count
     * below what it was found at. Every thread is probed before anything is decided:
     *
     * <ul>
     *   <li>A thread that has ended, or whose ID now names another thread (another owner, or another
     *       creation time), is passed over: it carries nothing of Prism's.
     *   <li>Any thread found at two or more: another suspension holds it as well, another program's
     *       or the process's own. Nothing is taken over and only the probes are undone.
     *   <li>Every thread at zero, or none left: the process is not suspended any more.
     *   <li>Some at zero and some at one: another program has resumed part of it. Nothing is taken over.
     *   <li>Every thread at one: each probe is undone, which must leave one, and each thread's one
     *       increment is taken over by a suspension that undoes it once. That this increment is
     *       Prism's is likely, not proven: another program may have taken Prism's away and added its
     *       own. The user is told so before asking.
     * </ul>
     *
     * Without a memento nothing is called at all: which threads Prism held is not known, and a count
     * found on threads it did not record is no evidence that the suspension is Prism's. A process
     * under a debugger is never touched, and neither is one whose debugger state cannot be read.
     * https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-suspendthread
     * https://learn.microsoft.com/en-us/windows/win32/api/debugapi/nf-debugapi-checkremotedebuggerpresent
     */
    AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
        if (memento.isEmpty()) {
            return refused(
                    OperationResult.UNSUPPORTED,
                    "no record of which threads Prism suspended; an anonymous suspension count is no evidence");
        }
        Optional<List<Recorded>> recorded = parseMemento(memento.get());
        if (recorded.isEmpty()) {
            return refused(OperationResult.INVALID_ARGUMENT, "the record of the threads is unreadable");
        }
        WindowsProcessAccess.Opening opening = access.open(target, WinNT.PROCESS_QUERY_INFORMATION);
        if (opening.failure().isPresent()) {
            ControlOutcome failure = opening.failure().get();
            return failure.result() == OperationResult.PROCESS_NOT_FOUND
                    ? AdoptResult.of(AdoptResult.Verdict.GONE, failure)
                    : AdoptResult.of(AdoptResult.Verdict.REFUSED, failure);
        }
        PinnedProcess process = opening.process().get();
        Optional<Boolean> debugged = win32.debuggerPresent(process.handle());
        if (debugged.isEmpty() || debugged.get()) {
            process.close();
            return refused(
                    OperationResult.INVALID_ARGUMENT,
                    debugged.isEmpty()
                            ? "whether a debugger is attached could not be read: " + win32.describe(win32.lastError())
                            : "the process is under a debugger");
        }
        List<Probe> probes = new ArrayList<>();
        ControlOutcome failure = null;
        try {
            for (Recorded thread : recorded.get()) {
                Step step = probe(process.pid(), thread, probes);
                if (step.failure() != null) {
                    failure = step.failure();
                    break;
                }
            }
        } catch (RuntimeException unexpected) {
            // The probes made so far are undone, as a failed suspend is rolled back; one that cannot
            // be undone is returned held, never left behind unseen.
            AdoptResult undone = undoProbes(
                    process,
                    probes,
                    AdoptResult.Verdict.REFUSED,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, unexpected.toString()));
            if (undone.suspension().isEmpty()) {
                throw unexpected;
            }
            return undone;
        }
        if (failure != null) {
            return undoProbes(process, probes, AdoptResult.Verdict.REFUSED, failure);
        }
        if (probes.isEmpty()) {
            boolean exited = process.exited();
            process.close();
            return exited
                    ? AdoptResult.of(
                            AdoptResult.Verdict.GONE,
                            ControlOutcome.of(OperationResult.PROCESS_NOT_FOUND, "the process has exited"))
                    : AdoptResult.of(
                            AdoptResult.Verdict.NOT_SUSPENDED,
                            ControlOutcome.of(
                                    OperationResult.INVALID_ARGUMENT,
                                    "none of the recorded threads is left; nothing to resume"));
        }
        long higher = probes.stream().filter(p -> p.previous() >= 2).count();
        long zero = probes.stream().filter(p -> p.previous() == 0).count();
        if (higher > 0) {
            return undoProbes(
                    process,
                    probes,
                    AdoptResult.Verdict.REFUSED,
                    ControlOutcome.of(
                            OperationResult.INVALID_ARGUMENT,
                            higher + " thread(s) carry another suspension as well (another program's, or the"
                                    + " process's own); nothing was changed"));
        }
        if (zero == probes.size()) {
            return undoProbes(
                    process,
                    probes,
                    AdoptResult.Verdict.NOT_SUSPENDED,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "no recorded thread is suspended any more"));
        }
        if (zero > 0) {
            return undoProbes(
                    process,
                    probes,
                    AdoptResult.Verdict.REFUSED,
                    ControlOutcome.of(
                            OperationResult.INVALID_ARGUMENT,
                            zero + " of " + probes.size()
                                    + " recorded threads were resumed by another program; Prism does not touch the"
                                    + " rest"));
        }
        return takeOver(process, probes);
    }

    /** Opens and probes one recorded thread; a failure stops the adoption. */
    private Step probe(int pid, Recorded thread, List<Probe> probes) {
        Win32.Opened opened = win32.openThread(THREAD_RIGHTS, thread.threadId());
        if (!opened.ok()) {
            if (opened.error() == ERROR_INVALID_PARAMETER) {
                // OpenThread documents no error codes: the thread is taken to have ended only when
                // a listing of the process no longer shows it, as the suspend path does.
                Win32.ThreadListing listed = win32.threadsOf(pid);
                if (!listed.ok()) {
                    return Step.failed(ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            listed.error(),
                            "the threads could not be listed: " + win32.describe(listed.error())));
                }
                if (listed.threadIds().contains(thread.threadId())) {
                    return Step.failed(ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            ERROR_INVALID_PARAMETER,
                            "thread " + Integer.toUnsignedString(thread.threadId())
                                    + " is listed but cannot be opened: " + win32.describe(ERROR_INVALID_PARAMETER)));
                }
                return Step.ENDED;
            }
            return Step.failed(ControlOutcome.of(
                    opened.error() == WindowsProcessAccess.ERROR_ACCESS_DENIED
                            ? OperationResult.ACCESS_DENIED
                            : OperationResult.NATIVE_ERROR,
                    opened.error(),
                    "OpenThread " + Integer.toUnsignedString(thread.threadId()) + " failed: "
                            + win32.describe(opened.error())));
        }
        int owner = win32.processIdOfThread(opened.handle());
        int ownerError = owner == 0 ? win32.lastError() : 0;
        if (owner != pid) {
            win32.close(opened.handle());
            return owner == 0
                    ? Step.failed(ControlOutcome.of(
                            OperationResult.NATIVE_ERROR,
                            ownerError,
                            "GetProcessIdOfThread failed: " + win32.describe(ownerError)))
                    : Step.FOREIGN;
        }
        OptionalLong created = win32.threadCreationTime(opened.handle());
        if (created.isEmpty()) {
            int error = win32.lastError();
            win32.close(opened.handle());
            return Step.failed(ControlOutcome.of(
                    OperationResult.NATIVE_ERROR, error, "GetThreadTimes failed: " + win32.describe(error)));
        }
        if (created.getAsLong() != thread.creationTime()) {
            // The ID belongs to a newer thread of the process: Prism never held it.
            win32.close(opened.handle());
            return Step.FOREIGN;
        }
        Win32.Counted counted = win32.suspendThread(opened.handle());
        if (!counted.ok()) {
            win32.close(opened.handle());
            return Step.failed(ControlOutcome.of(
                    counted.error() == WindowsProcessAccess.ERROR_ACCESS_DENIED
                            ? OperationResult.ACCESS_DENIED
                            : OperationResult.NATIVE_ERROR,
                    counted.error(),
                    "SuspendThread failed: " + win32.describe(counted.error())));
        }
        probes.add(new Probe(new WindowsSuspension.Hold(opened.handle(), thread.threadId()), counted.value()));
        return Step.HELD;
    }

    /**
     * Every thread was found at one: each probe is undone, which must leave one, and the threads'
     * increments are taken over. If a thread does not answer as found, it changed while it was
     * checked: nothing is taken over, the remaining probes are undone, and the handles of the
     * threads already checked are closed without resuming them.
     */
    private AdoptResult takeOver(PinnedProcess process, List<Probe> probes) {
        List<WindowsSuspension.Hold> taken = new ArrayList<>();
        for (int i = 0; i < probes.size(); i++) {
            WindowsSuspension.Hold hold = probes.get(i).hold();
            Win32.Counted undone = win32.resumeThread(hold.handle());
            if (undone.ok() && undone.value() == 2) {
                taken.add(hold);
                continue;
            }
            // This thread's probe is undone if the call succeeded; it still holds the probe if not.
            List<Probe> remaining = new ArrayList<>(probes.subList(i + (undone.ok() ? 1 : 0), probes.size()));
            new WindowsSuspension(win32, process, taken).closeHandlesOnly();
            if (undone.ok()) {
                win32.close(hold.handle());
            }
            return undoProbes(
                    process,
                    remaining,
                    AdoptResult.Verdict.REFUSED,
                    ControlOutcome.of(
                            OperationResult.INVALID_ARGUMENT,
                            undone.ok()
                                    ? "a thread's suspension changed while it was checked (found " + undone.value()
                                            + " instead of 2); nothing was taken over"
                                    : "a probe could not be undone: " + win32.describe(undone.error())));
        }
        return AdoptResult.adopted(
                ControlOutcome.success(taken.size() + " thread(s) found with exactly one suspension each"),
                new WindowsSuspension(win32, process, taken));
    }

    /**
     * Takes back the probes' own increments. What cannot be taken back stays held, by a suspension
     * returned with the refusal, so that Prism's own increment is not forgotten.
     */
    private AdoptResult undoProbes(
            PinnedProcess process, List<Probe> probes, AdoptResult.Verdict verdict, ControlOutcome outcome) {
        List<WindowsSuspension.Hold> holds = new ArrayList<>();
        for (Probe probe : probes) {
            holds.add(probe.hold());
        }
        WindowsSuspension probing = new WindowsSuspension(win32, process, holds);
        Release undone = release(probing);
        if (probing.outstanding() == 0) {
            probing.close();
            return AdoptResult.of(verdict, outcome);
        }
        return new AdoptResult(
                AdoptResult.Verdict.REFUSED,
                ControlOutcome.of(
                        OperationResult.NATIVE_ERROR,
                        outcome.detail().orElse("refused") + "; " + probing.outstanding()
                                + " probe(s) could not be undone and stay held by Prism: " + undone.describe(win32)),
                Optional.of(probing));
    }

    private static AdoptResult refused(OperationResult result, String detail) {
        return AdoptResult.of(AdoptResult.Verdict.REFUSED, ControlOutcome.of(result, detail));
    }

    /** What one pass of releasing came to. */
    private record Release(boolean processExited, int released, int ended, int failed, OptionalInt firstError) {
        String describe(Win32 win32) {
            return released + " resumed, " + ended + " ended, " + failed + " failed"
                    + (firstError.isPresent() ? " (" + win32.describe(firstError.getAsInt()) + ")" : "");
        }
    }

    /** One ResumeThread on each owned thread, judged as described above. */
    private Release release(WindowsSuspension suspension) {
        // Read once; the result is decided from it. A process that ends after this read but before
        // the calls below is reported as resumed: its threads were let go either way, and the next
        // check of the process tells the rest.
        boolean processExited = suspension.processExited();
        int released = 0;
        int ended = 0;
        int failed = 0;
        OptionalInt firstError = OptionalInt.empty();
        for (WindowsSuspension.Hold hold : suspension.owned()) {
            if (processExited) {
                suspension.released(hold);
                ended++;
                continue;
            }
            Win32.Counted resumed = win32.resumeThread(hold.handle());
            if (resumed.ok()) {
                suspension.released(hold);
                released++;
                continue;
            }
            OptionalInt exitCode = win32.threadExitCode(hold.handle());
            if (exitCode.isPresent() && exitCode.getAsInt() != Win32.STILL_ACTIVE) {
                suspension.released(hold);
                ended++;
                continue;
            }
            // A live thread keeps Prism's increment: the hold stays owned and its handle open.
            failed++;
            if (firstError.isEmpty()) {
                firstError = OptionalInt.of(resumed.error());
            }
        }
        return new Release(processExited, released, ended, failed, firstError);
    }
}
