# 0002. Windows process control and Job Object limits

- Status: Accepted
- Date: 2026-10-05

## Context

Prism must suspend, resume, re-prioritize, close and limit other, already-running
processes on Windows without running elevated, using documented APIs only. Checked
against Microsoft Learn and the `jna-platform` 5.19.1 jar:

- Windows has no SIGTERM equivalent and no documented whole-process suspend.
  `SuspendThread` is documented; `NtSuspendProcess` is not.
- `ProcessHandle.destroy()` terminates forcibly on Windows
  (`supportsNormalTermination()` is false).
- `jna-platform` maps `OpenProcess`, `CloseHandle`, the Toolhelp snapshot functions,
  `OpenThread`, `SetPriorityClass` and `EnumWindows`, but **not** `SuspendThread`,
  `ResumeThread`, or any Job Object function, structure or constant. Its `PostMessage`
  returns `void`, so a failure cannot be seen.
- Job Objects: "After a process is associated with a job, the association cannot be
  broken." The job "is destroyed when its last handle has been closed and all associated
  processes have been terminated", unless `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` is set,
  so a job and its limits outlive Prism. "To obtain a handle for an existing job
  object, use the OpenJobObject function and specify the name given to the object when
  it was created. Only named job objects can be opened."
  `JOB_OBJECT_LIMIT_PROCESS_MEMORY` limits committed memory: a commit beyond the limit
  fails. CPU rate control expresses the rate as a portion of the CPU of the entire
  system, in units of 1/100 of a percent. A process already in a job can be assigned
  only under the nested-job rules.

## Decision

1. **End Task** posts `WM_CLOSE` to the target's top-level windows, found with
   `EnumWindows` and `GetWindowThreadProcessId`. It is `NATIVE_SOFT`: a request. For a
   process without windows it returns `UNSUPPORTED` and the UI offers Force Kill.
2. **Force Kill** uses `ProcessHandle.destroyForcibly()`.
3. **Suspend / Resume** enumerate the target's threads with a Toolhelp snapshot and
   call `SuspendThread` / `ResumeThread` on each (`THREAD_SUSPEND_RESUME`). It is
   reported as `NATIVE_SOFT` because it is not atomic: a thread created during
   enumeration is missed, and Microsoft warns the function was designed for debuggers.
   The undocumented process-level call is not used.
4. **Priority** uses `SetPriorityClass` with Idle, Below normal, Normal, Above normal,
   High. Realtime is not offered.
5. **Limits** use one Job Object per managed process.
   - CPU: `JOBOBJECT_CPU_RATE_CONTROL_INFORMATION` with `ENABLE | HARD_CAP`.
   - Memory: `JOB_OBJECT_LIMIT_PROCESS_MEMORY`.
   - Every job is **named**, and the name is derived deterministically from the process
     identity: `Local\Prism.Job.<pid>.<start time in milliseconds>`. This is a valid
     object name in the session namespace: the `Local\` prefix is the documented way to
     address it, the remainder contains no backslash, and it is far shorter than
     `MAX_PATH`. Name comparison is case-sensitive, so the format is fixed.
   - Prism keeps the job handle open for as long as the limit exists and records the
     limited process identities in the per-user folder.
   - **Recovery after a crash.** A job with a process still in it is not destroyed when
     Prism's handle is lost, and named jobs can be opened with `OpenJobObject`. On
     restart Prism attempts to reopen the job named for a recorded identity. Whether
     that attempt succeeds once Prism's last handle is gone is exactly what milestone 10
     establishes by experiment. If it succeeds, Prism validates before changing
     anything: the process with exactly that PID and start time is alive, and
     `IsProcessInJob` confirms it is in the reopened job. Only then does it inspect and
     clear the limits.
   - If the job cannot be reopened or the validation fails, Prism reports the process
     as possibly still limited and does nothing else: no termination, no new job, no
     guess. Those limits then last until the target exits.
   - Recovery is an attempt. The API permits it, but milestone 10 must demonstrate it
     on Windows before the product describes it to users as working.
   - Removing a limit clears the limit flags; the process stays in the job.
   - On controlled shutdown every limit Prism applied is cleared.
   - `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` is never set.
   - If `AssignProcessToJobObject` or any other required step fails, the operation
     returns that result; nothing is reported as limited.
6. The missing native functions and structures are mapped in `platform.windows`, each
   with its Microsoft Learn URL, and structure sizes are covered by tests.

## Alternatives considered

- **`NtSuspendProcess`**: atomic, but undocumented. Rejected by the project's rule on
  documented APIs.
- **`taskkill` or other commands**: shelling out where an API exists. Rejected.
- **Leaving limits in force after Prism exits**: simpler, but leaves a process capped
  with nothing visible managing it. Rejected in favor of clearing on shutdown.
- **Working-set limits as the memory limit**: softer, but does not bound memory use.
  May be added later as a `NATIVE_SOFT` option.

## Consequences

- A memory limit can make the target fail or crash when an allocation is refused. The
  UI must say so before applying it.
- Processes Prism cannot open with `PROCESS_SET_QUOTA | PROCESS_TERMINATE` (elevated,
  other users', services, protected) cannot be limited; this surfaces as
  `ACCESS_DENIED`.
- Processes already in a job (common for browsers and some launchers) may refuse
  assignment; the emulated limiter is the fallback.
- If Prism is killed, native limits stay on the process until a later run reopens the
  named job and clears them, or until the target exits.
- To verify in milestones 7 and 10 against Microsoft Learn or by test before use:
  `IsProcessCritical` for protected-process detection, the behavior of `SuspendThread`
  on WOW64 threads (`Wow64SuspendThread`), the exact effect of clearing limit flags,
  the signatures and access rights of `OpenJobObjectW` and `IsProcessInJob`, and,
  experimentally, reopening a Prism job by name after Prism was killed (including the
  access the default security descriptor grants a later run of the same user).

Sources: Microsoft Learn pages for `SuspendThread`, `TerminateProcess`,
`SetPriorityClass`, `CreateJobObjectW`, `AssignProcessToJobObject`,
`JOBOBJECT_CPU_RATE_CONTROL_INFORMATION`,
`JOBOBJECT_BASIC_LIMIT_INFORMATION`, `JOBOBJECT_EXTENDED_LIMIT_INFORMATION`, "Job
Objects", "Nested Jobs", "Terminating a Process", "Process Security and Access Rights".
