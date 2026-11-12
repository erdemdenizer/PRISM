# 0012. Windows native limits: job lifecycle, the native-limit ledger, and recovery

- Status: Accepted (product owner, 2026-10-10). Supersedes decision 5 of
  [ADR-0002](0002-windows-process-control-and-job-objects.md) where they differ: recovery after
  a crash is read-only and does not clear a reopened job. ADR-0002 itself is unchanged.
- Date: 2026-10-10; accepted 2026-10-10

## Context

ADR-0002 decided that limits on Windows use one named Job Object per managed process, and
left one question to experiment: whether a later run can reopen the job by name after Prism
was killed. Microsoft Learn answers it before any experiment ("Life Cycle of an Object"): a
temporary object "can be accessed by name only as long as its handle count is nonzero. Once
the handle count decrements to zero, the object's name is removed from the object manager's
namespace." A process's membership in a job is a reference, not a handle. So when Prism's
last handle goes, by a crash or otherwise, the job and its limits live on with the process,
and the name is gone.

Milestone 10 measured this and the other open points on Windows 11 (build 26200, 16 logical
processors, a test JVM in no job), on the demo workload only:

| Experiment | Result |
|---|---|
| E0: clearing the CPU rate | `ControlFlags = 0` is accepted and reads back `0/0`; `CpuRate = 10000` with `ENABLE \| HARD_CAP` is accepted; `CpuRate = 0` with `ENABLE` is refused with error 87 |
| E0, live | baseline 49.8 %, cap 20 % gives 21.5 %, cleared 49.6 %, cap 10 % gives 9.5 %, `CpuRate = 10000` gives 49.9 % |
| E1: assignment of a process that has exited | `AssignProcessToJobObject` returns FALSE with error 5; `IsProcessInJob` FALSE |
| E2: the holder of the job killed | `OpenJobObjectW(name)` fails with error 2; `IsProcessInJob(target, NULL)` TRUE; the 20 % cap stays (21.6 % before the kill, 20.7 % after) |
| E2, next start | the verdict is "unreachable": the process may still be limited until it exits |
| E2, target exited too | "gone, children unknown"; a process it started after the limit stays capped (21.2 %) |
| E2, crash between the two calls of a change | the system holds CPU 30 % with the old memory limit; the record admits it as one of its per-kind values |
| E3: Dynamic Fair Share Scheduling | absent here (the probe sets and reads back a rate of 2000) |
| E4: a process in a job of another program | nested assignment would succeed; Prism refuses before it creates a job |
| E5: 20 % of the machine | 59.1 % before, 21.0 % capped, 58.7 % cleared |
| E6: a commit limit of 256 MiB | an allocation of 512 MiB stops at 80 MiB more; once cleared, 512 MiB is allocated |
| E7: the target exits, a process it started lives on | Prism counts two survivors (the child and its `conhost.exe`), clears its limits, and lets the job go; the child runs at 59 % again |

## Decision

1. **Assignment and change are separate operations.** `apply` makes a new named job, sets the
   limits on it while it is empty, assigns the process, and reads everything back.
   `AssignProcessToJobObject` is irreversible from the moment it is called, so the result says
   what is known: not assigned (proven by `IsProcessInJob`), assigned and enforced, assigned
   but holding other values, or uncertain. Only "not assigned" closes the job handle. `change`
   alters the job Prism already holds and never assigns; it reports applied, partial (one kind
   changed, the other not) or unverified (no read-back).
2. **The job handle stays open while the process lives in this session**, also after its
   limits are removed and after Unmanage. Closing it would remove the name, and a later limit
   would find the process in a job Prism no longer holds.
3. **A process already in a job Prism does not hold is refused** before a job is made. A
   nested cap would be a share of the outer job, not of the whole machine, and membership
   cannot be undone. The emulated limit is offered instead.
4. **The native-limit ledger.** Before an assignment, and before a change that leaves a limit
   in force, Prism writes a record and waits up to 2 s for it to be on disk
   (`native-limits-<instance>.ledger`, beside the suspension journal, on the journal's thread).
   The record holds the identity and the values, in the states intended, pending change,
   applied, and cleared. The file is written whole, from the newest model at the time of the
   write, with a version, so a late write never brings back what a newer one removed. A ledger
   that cannot be written refuses new native limits and leaves those in force. A pending change
   is read per kind: the possible states of the system are exactly the cross product of the old
   and new value of each kind, because each kind is set by its own call.
5. **Recovery.** At start-up the ledger of each ended session is read: under the dead session's
   lock, as for the suspension journal, or, for a ledger whose journal the system says is gone,
   only on proof that its session ended: this session takes that session's lock, which a running
   session holds for as long as it runs, and the Prism process its instance name records does not
   run. A lock that is held or cannot be taken, a journal whose state cannot be told, or a process
   that runs leaves the ledger alone and unsettled. Each record is looked at. The name is expected not to exist, and then Prism decides as follows:
   - The process is gone and the record says cleared: nothing remains.
   - The process is gone and the record says otherwise: processes it started may still be
     limited ("gone, children unknown"). An intended record does not prove that the
     assignment never happened.
   - The process lives and is in no job: it was never assigned.
   - The process lives and is in a job: it may still be limited until it exits.
   **Recovery changes no job (audit of the recovery trust boundary, 2026-10-10).** A name lives
   only while a handle to the job is open, and a process that ends closes its handles: a job that
   can still be opened by its name therefore has a running holder, perhaps a Prism session that
   still runs, which recovery cannot prove has ended. Such a job is opened with
   `JOB_OBJECT_QUERY` only, its values and the process's membership are read, and the verdict is
   "held elsewhere": the process may be limited, and no second limiter is offered. This replaces
   the clearing of a reopened job that ADR-0002 decision 5 and plan D4 allowed (D4 was already
   recorded as practically unreachable); it needs external acceptance. A process an earlier
   session may still cap gets no second limiter, native or emulated, until it exits.
6. **When the target exits**, Prism asks the job how many processes are left in it, clears its
   own limits, verifies the clearing, and only then closes the handle and removes the record.
   If it cannot verify, the handle and the record stay as a residue, shown on the Limits page
   and retried.
7. **Shutdown and the exit hook** clear and verify every native limit, close every job handle,
   and remove each verified record; an empty ledger is deleted. A limit not verified as cleared
   stays in the ledger for the next start. After that, every further request is refused.
8. **The emulated limit is offered, never assumed.** It is offered only on a system that has
   native CPU limits, and only when this process's native limit was not applied and nothing
   native may be in force. The user consents for the process. With an unknown or possibly
   active native CPU cap it is neither offered nor started. On a system without native CPU
   limits the milestone 9 path runs unchanged.
9. `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, `TerminateJobObject` and `TerminateProcess` appear
   nowhere in the limiter; a test scans the sources.

## What this means for the user

- **Why recovery is read-only.** After a crash the job's name is gone with Prism's last handle.
  A job still reachable by name has a running holder, which Prism cannot prove has ended, so
  Prism reads and reports and changes nothing it cannot prove is its own.
- **Why a limit can outlive a crash.** The system keeps a job and its limits while a process is
  in it. A hard kill closes Prism's handles but not the job, so the cap stays until the process
  exits. A controlled exit and the shutdown hook clear every limit first.
- **After a hard kill and a restart.** The Limits page and the activity log list each process an
  earlier session may still cap ("Capped by an earlier session" or "held by a running program"),
  with the values recorded. No emulated limit is offered or started for it while that may be
  so, and none for processes that ran while an unsettled ledger was written.
- **What the user can safely do.** Let the process run under the earlier cap, or close and
  restart it (from its own window, or End task) when the cap is in the way: a restarted
  process is a new identity in no job, and Prism can limit it normally. A sign-out or restart
  of Windows ends every job. Prism never ends a process by itself for this.

## Alternatives considered

- **Keeping the name alive by putting a handle into the target** (`DuplicateHandle`): it
  injects a handle into a foreign process, beyond the documented purpose. Rejected.
- **One operation for assignment and change**: they differ in what can be undone and in what
  is evidence. Rejected.
- **Closing the job handle when the target exits without checking for children**: processes
  it started would stay capped with nothing managing them. Rejected.
- **Recording limits in the suspension journal**: its meaning is suspension; mixing the two
  complicates both recoveries. Rejected for a ledger file of its own.
- **Nested assignment shown as `NATIVE_SOFT`**: still not a share of the whole machine, and
  irreversible. Rejected.

## Consequences

- After a crash or a hard kill, a native limit lasts until the process exits; the next start
  lists it and cannot clear it. The CPU limit dialog says so before the first limit, and the
  exit hook is the real safety net.
- A process stays in Prism's job after its limits are removed (one handle per process for the
  session). Processes it starts afterwards join the job without limits.
- A commit limit makes the target fail when it is reached. Measured: the demo JVM under a
  20 % CPU cap and a 512 MiB commit cap fails within seconds.
- There is a millisecond window between the check that a process is in no job and the
  assignment, in which another program could put it in a job. The cap would then be nested.
  No documented API closes the window.
- **File states (audit of the recovery trust boundary).** Recovery never takes "cannot tell" for
  "not there": a ledger is read at once and is absent only when the system says so
  (`NoSuchFileException`); a file's state is present, absent or unknown (`JournalFiles.state`); a
  folder is empty only when it is not there, and one that cannot be listed is a failure. An
  unknown state keeps the ledger as evidence and the barrier up.
- **Unsettled ledgers (external audit, 2026-10-10).** A ledger of another session that cannot be
  read, is damaged, waits for its session's lock, or could not be carried over stays on disk as it
  is. While it does, no emulated CPU limit is offered or started for a process that started no
  later than its last write (2 s slack); a process started later cannot be named in it. The
  barrier and a known earlier cap hold whatever today's native CPU capability is (final source
  audit): Dynamic Fair Share Scheduling turned on since, or a failed probe, says nothing of a
  kernel cap an earlier session made. Where no other session's ledger exists, as on Linux and
  macOS, nothing is unsettled and the milestone 9 emulated path is unchanged. An emulated
  limit found to overlap a recovered cap is stopped through its own release. A damaged ledger is
  read again by every start and is never retired by Prism; after a reboot it blocks nothing, since
  every process then started after it. The bound assumes the clock does not step back between a
  process's start and the write of its record. A second Prism instance of the same user running at
  the same time is kept out by ADR-0013 (Accepted); without that rule it would not be protected
  against, as `ConcurrentInstancesTest` shows.
- A shutdown that cannot take the limiter in time says so (`ReleaseResult.timedOut`), names the
  holds known then, removes no ledger record, and refuses every later assignment.
- Not verified: 32-bit target processes, recovery across users or elevation, and a system with
  Dynamic Fair Share Scheduling (the probe reports the CPU limit unavailable there, untested).
- **Verification (2026-10-10).** The native suites ran at commit `fdd08e3` (Windows 11 build
  26200, outside any Job Object): 20 tests, none skipped, none failed. An independent external
  reviewer accepted this as plan §11.6 path C evidence
  ([docs/evidence/m10-native-validation-fdd08e3.md](../evidence/m10-native-validation-fdd08e3.md)).
  - The hosted `native-validation` check stays red: GitHub's Windows runner runs the tests inside
    a Job Object, where this design refuses every target, so the suites are skipped.
  - The three-system CI gate is green for that commit.
  - **2026-10-11:** the code changed in the milestone 9 limiter and a test (not in the native
    code). The native suites ran again at `a6f12b6` with the same result (20 tests, none
    skipped), recorded in
    [docs/evidence/m10-native-validation-a6f12b6.md](../evidence/m10-native-validation-a6f12b6.md)
    and accepted by the independent reviewer (2026-10-12).

Sources: Microsoft Learn pages for `CreateJobObjectW`, `OpenJobObjectW`,
`AssignProcessToJobObject`, `IsProcessInJob`, `QueryInformationJobObject`,
`SetInformationJobObject`, `JOBOBJECT_CPU_RATE_CONTROL_INFORMATION`,
`JOBOBJECT_EXTENDED_LIMIT_INFORMATION`, `JOBOBJECT_BASIC_ACCOUNTING_INFORMATION`, "Job
Objects", "Kernel object namespaces", "Life Cycle of an Object".
