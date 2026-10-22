# Product specification: Prism System Resource Manager

Prism is a desktop utility for an Operating Systems course. It monitors system
resources in real time, monitors and controls processes, limits the resources of
selected processes, and guards against running out of memory. It is meant to behave
like a real lightweight system utility and to demonstrate operating-system concepts
(processes, threads, scheduling priority, suspension, resource limits, memory pressure,
platform APIs), not to imitate the look of an existing task manager.

Requirements are numbered (`MON-1`, `CTL-3`, ...) so milestones and tests can cite them.
The implementation order is in [MILESTONES.md](MILESTONES.md). Structure and rules are in
[ARCHITECTURE.md](ARCHITECTURE.md); the design language is in [UI_GUIDE.md](UI_GUIDE.md).

## 1. Scope

**Target systems:** Windows 10/11 x64, modern Linux distributions using cgroups v2, modern
macOS. As much code as reasonably possible is shared; native mechanisms are used where
resource management genuinely differs. Feature parity is never faked.

**Verification status.** The team can currently run Prism on Windows only. Code for Linux
and macOS is written against primary documentation with its logic unit-tested, and is
**unverified** until it has been run on that system. Every milestone report states which
systems the work was actually run on.

**Qualities.** Fast, responsive, stable, conservative with its own resources, visually
restrained, understandable, safe around system processes, honest about limitations.

**Not in scope:** GPU, temperature or per-process network monitoring; services, startup
items or remote machines; any feature that needs Prism to run elevated; limiting
processes of other users; I/O limits; rules that manage processes across Prism restarts.

## 2. Terms

- **Observed process**: any process visible in monitoring.
- **Managed process**: a process the user explicitly selected for automatic management.
- **Process identity**: PID together with start time, so a reused PID is never mistaken
  for an earlier process.
- **Capability level**: how a mechanism is provided on this system or for this process.

  | Level | Meaning |
  |---|---|
  | `NATIVE_HARD` | Enforced by the operating system; the target cannot ignore it. |
  | `NATIVE_SOFT` | Provided by a native operating-system mechanism, but without a strict, atomic, mandatory guarantee over the whole target. Covers requests the target may decline (graceful termination), preferences the scheduler weighs (priority), mechanisms that are not atomic (per-thread suspension on Windows), and throttling boundaries (`memory.high`). |
  | `EMULATED` | Enforced by Prism itself, approximately, and only while Prism runs. |
  | `UNAVAILABLE` | Not possible here. The UI disables it and says why. |

- **Operation result**: every control or limit operation returns exactly one of
  `SUCCESS`, `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, `UNSUPPORTED`, `INVALID_ARGUMENT`,
  `NATIVE_ERROR`.

## 3. System monitoring

- **MON-1** System metrics come from OSHI. Long-lived OSHI objects are created once.
- **MON-2** CPU: total utilization (0-100%), utilization per logical processor, logical
  processor count, and physical core count, package count and processor name when the
  system reports them. Utilization is busy time, the share of time the processors were
  not idle, on every system. On Windows it therefore corresponds to *% Processor Time*
  and can differ from Task Manager, which shows the frequency-adjusted *% Processor
  Utility* ([ADR-0005](adr/0005-system-metric-semantics.md)).
- **MON-3** Memory: physical total, available, used (total minus available), percent
  used; swap/pagefile total and used when the system has one.
- **MON-4** Disk: local file systems with total and usable space; aggregate read rate
  and write rate in bytes per second. The aggregate is the sum over physical disks; a
  logical disk on top of another one is never added to it.
- **MON-5** Network: interfaces with their receive and send rates; aggregate download
  and upload rate in bytes per second. Every interface is listed with its own rates. The
  aggregate is the sum over physical adapters; filter drivers, virtual adapters and
  tunnels are never added to it.
- **MON-4 and MON-5** An aggregate is shown as absent, not estimated, when the system
  does not say which devices are physical. That is currently the case on Linux and
  macOS ([ADR-0005](adr/0005-system-metric-semantics.md)).
- **MON-6** System: uptime, process count, operating system name, version and build.
- **MON-7** A bounded history of about 60 seconds is kept for total CPU, CPU per logical
  processor, memory used, disk read and write rate, and network download and upload rate.
- **MON-8** Default cadence: system metrics every 1 s, process enumeration every 2 s.
  The values may be tuned when measurements justify it; the reason is recorded.
- **MON-9** No monitoring work runs on the JavaFX Application Thread. A polling cycle
  never overlaps itself; when a cycle runs long the next one is delayed or skipped. UI
  updates are coalesced so stale updates never queue.
- **MON-10** Rates use a monotonic clock. A counter that resets, wraps, or belongs to a
  device that disappeared yields a skipped sample, never a negative or absurd rate.
- **MON-11** A failing metric source does not stop monitoring. The metric is shown as
  unavailable and the failure is logged once per kind of failure, not per tick.
- **MON-12** Initial budget, to be confirmed by measurement in milestone 3: with the
  window visible and idle, Prism averages below 2% of total machine CPU capacity on the
  development machine, and its heap does not grow over a 10-minute run.

## 4. Processes

- **PROC-1** Per process, when available: name, PID, parent PID, state, CPU %, resident
  memory, private resident memory, virtual size, user, thread count, priority, I/O
  read and write rate, start time. A value the system does not provide is shown as
  absent, never as zero. The I/O rates are the process's I/O as the system accounts it,
  not disk activity: Windows counts file, network and device I/O together; Linux counts
  storage I/O only; macOS reports disk I/O fields whose exact meaning Apple does not
  document. Disk activity of the whole system is MON-4.
- **PROC-2** CPU % follows the Task Manager convention: 100% means every logical
  processor of the machine is saturated. It is computed between two consecutive samples
  of the same process identity. OSHI reports per-core load that can exceed 100% for a
  multi-threaded process; Prism divides by the logical processor count. A process seen
  for the first time shows no CPU value until a second sample exists.
- **PROC-3** Processes are tracked by process identity (PID and start time).
- **PROC-4** A process that exits, or whose permissions change, while a snapshot is
  being collected never fails the snapshot. The process is dropped or shown with the
  fields that could be read.
- **PROC-5** Executable path and command line are read on demand for the selected
  process, not on every poll (they are expensive to query on Windows).
- **PROC-6** State is shown as reported by the system, plus "Suspended by Prism" for
  processes Prism suspended. On Windows a process suspended by another tool is not
  detected, because detection is too expensive to run on every poll; this is a
  documented limitation. Windows keeps states for threads, not for a process, so on
  Windows the normal snapshot has no system-reported state: the State column shows it
  as unavailable, until a later requirement brings a reliable and affordable mechanism.
- **PROC-7** Snapshots of exited processes are not retained.

## 5. Process control

- **CTL-1** Actions: End Task (graceful), Force Kill, Suspend, Resume, Change Priority.
- **CTL-2** Each action returns an operation result. The user sees a plain message
  derived from the result; native error codes and exceptions go to the log, never to
  the primary message.
- **CTL-3** Portable Java APIs are used where they are semantically sufficient: Force
  Kill on every system and End Task on Linux and macOS use `ProcessHandle`. Everything
  else uses documented native APIs.
- **CTL-4** End Task on Windows posts a close request to the target's top-level windows.
  Windows has no equivalent of SIGTERM, so this is a request the application may ignore,
  and it is `UNSUPPORTED` for a process without windows. Success means "close
  requested", and the UI says so.
- **CTL-5** Suspend and Resume use SIGSTOP/SIGCONT on Linux and macOS, and documented
  per-thread suspension on Windows. Windows has no documented whole-process suspend;
  the per-thread method is not atomic, and the UI describes it as such.
- **CTL-6** Priority is one of five levels mapped per system. Realtime is never offered.

  | Level | Windows priority class | Linux / macOS nice |
  |---|---|---|
  | Low | Idle | 19 |
  | Below normal | Below normal | 10 |
  | Normal | Normal | 0 |
  | Above normal | Above normal | -5 |
  | High | High | -10 |

  On Linux and macOS an unprivileged user can only lower a priority; raising it returns
  `ACCESS_DENIED`, which the UI explains.
- **CTL-7** Control actions run off the JavaFX Application Thread; the outcome is shown
  to the user and written to the activity log.
- **CTL-8** End Task and Force Kill ask for confirmation naming the process and its PID.
- **CTL-9** Suspension has a single owner inside Prism, which records why each process
  is suspended: by the user, by the CPU limiter, or by OOM Guard. A process is resumed
  only when no reason remains, so the limiter never resumes a process the user or OOM
  Guard suspended.

## 6. Capabilities per system

The level that can be reached at best. The level that actually applies to a given
process is determined when the mechanism is applied and is shown to the user.

| Mechanism | Windows | Linux | macOS |
|---|---|---|---|
| End Task | `NATIVE_SOFT` (close request; needs a window) | `NATIVE_SOFT` (SIGTERM) | `NATIVE_SOFT` (SIGTERM) |
| Force Kill | `NATIVE_HARD` | `NATIVE_HARD` | `NATIVE_HARD` |
| Suspend / Resume | `NATIVE_SOFT` (per thread, not atomic) | `NATIVE_HARD` | `NATIVE_HARD` |
| Change Priority | `NATIVE_SOFT` | `NATIVE_SOFT` (lowering only) | `NATIVE_SOFT` (lowering only) |
| CPU limit | `NATIVE_HARD` (Job Object), else `EMULATED` | `NATIVE_HARD` (cgroup `cpu.max`) when the probe passes, else `EMULATED` | `EMULATED` |
| Memory limit | `NATIVE_HARD` (Job Object commit limit), else `EMULATED` policy | `NATIVE_HARD` (`memory.max`) or `NATIVE_SOFT` (`memory.high`) when the probe passes, else `EMULATED` policy | `EMULATED` policy |

- **CAP-1** Operating-system differences stay behind the platform abstraction. UI and
  application code never branch on the operating system; they ask for capabilities.
- **CAP-2** The UI disables an unavailable function and explains why.
- **CAP-3** An emulated mechanism is never presented as native.
- **CAP-4** Processes of other users, elevated processes, services and protected
  processes are generally out of reach without elevation. Operations on them are
  expected to return `ACCESS_DENIED`, which is a normal outcome and is shown as such.
- **CAP-5** A capability report names the mechanism behind a level (for example close
  request, Job Object, cgroup, duty cycle), so the UI can show the right explanation
  and warning for it without knowing the operating system.

## 7. Managed processes

- **MGD-1** Only a Managed Process may automatically receive resource limits, OOM
  actions, automatic suspension, or emergency termination.
- **MGD-2** A process becomes managed only by an explicit user action. It is identified
  by process identity for the current session. When it exits it leaves the managed list
  and the event is logged. The managed list is not restored after Prism restarts.
- **MGD-3** Settings per Managed Process: CPU limit, memory limit, enabled/disabled,
  OOM management on/off, OOM priority (which candidates are acted on first), and
  emergency action: Notify only (default), Lower priority, Suspend, or Terminate.
- **MGD-4** Prism itself and protected processes (section 10) cannot be managed.

## 8. Resource limits

- **LIM-1** One contract for all systems: set and remove a CPU limit, set and remove a
  memory limit, and report capabilities.
- **LIM-2** A CPU limit is a whole-number percentage (1-100) of total machine capacity,
  the same unit as PROC-2. A memory limit is a number of bytes.
- **LIM-3** For each process Prism uses the native mechanism when it is valid for that
  process and otherwise the emulated fallback. The platform reports a native level or
  `UNAVAILABLE` for the process; the level in effect (native, or `EMULATED` after
  falling back) is shown per process.
- **LIM-4** A limit is reported as applied only if every required step succeeded. A
  failed native step is reported with its result and never shown as success.
- **LIM-5** Windows uses Job Objects ([ADR-0002](adr/0002-windows-process-control-and-job-objects.md)).
  - The CPU limit is a hard cap on the share of the whole machine.
  - The memory limit caps committed memory, not resident memory. A process that reaches
    it sees allocation failures and may crash; the UI states this before applying.
  - A process cannot be removed from a job. Removing a limit means clearing the limit.
  - Assignment can fail for a process that already belongs to a job, and for processes
    Prism may not open. The failure is reported and the emulated fallback is offered.
  - Limits are cleared on controlled shutdown. Jobs are never configured to kill their
    processes when Prism closes (`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` is never used).
  - Every job Prism creates is named, and the name is derived deterministically from the
    process identity (PID and start time) as a valid Windows object name.
  - If Prism is killed, the job and its limits stay on the process: a job is destroyed
    only when its last handle is closed and all its processes have terminated. Named
    jobs can be opened by name, so on restart Prism may attempt to reopen a surviving
    Prism job, validate it against the live process identity, and inspect and clear its
    limits.
    If reopening or validation fails, Prism reports the process as possibly still
    limited and takes no destructive action. Recovery is an attempt, not a guarantee;
    milestone 10 establishes experimentally on Windows whether it works.
- **LIM-6** Linux uses cgroups v2 ([ADR-0003](adr/0003-linux-native-limits-with-cgroups-v2.md),
  not yet validated on a real system).
  - A native limit is claimed only after a runtime probe passes: the unified hierarchy
    is mounted, Prism has a delegated subtree, the needed controller is available, and
    the target can be moved (same user, inside the user's delegated part of the tree).
  - `cpu.max` is the CPU limit. Prism's whole-machine percentage P is converted with the
    logical processor count N: quota = P / 100 x N x period, with the default period of
    100000 microseconds. A limit of 100% is written as `max`.
  - `memory.max` is a hard boundary on the cgroup's memory. When usage reaches it and
    reclaim cannot reduce it, the cgroup may enter an out-of-memory state and the kernel
    may invoke its OOM killer inside that cgroup. Some allocations fail instead, and
    usage may exceed the limit temporarily in circumstances the kernel documents. Prism
    does not claim that the target process itself will be killed; the UI describes the
    risk in these terms before the limit is applied.
  - `memory.high` is a throttling boundary: above it the cgroup's processes are
    throttled and put under reclaim pressure. It does not itself invoke the OOM killer
    and may be breached under extreme conditions. It is offered as the soft
    alternative (`NATIVE_SOFT`).
  - Prism sets limits only in cgroups it created, and removes only those, when they are
    empty. Moving a process back to the cgroup it came from is the only write outside
    them.
  - Prism is not run as root. If the probe fails the capability is reported honestly and
    the emulated fallback is used.
- **LIM-7** macOS has no documented way to impose a hard CPU or memory cap on an
  arbitrary running process. CPU limiting is emulated; memory limits are monitored
  policies. Both are labelled as such.
- **LIM-8** The emulated CPU limiter ([ADR-0004](adr/0004-emulated-limiting-and-suspension-recovery.md))
  suspends and resumes the target in a duty cycle steered by feedback.
  - The limit is the whole-machine percentage of LIM-2. The limiter measures the
    target's CPU utilization normalized to the whole machine (the unit of PROC-2) and
    adapts the run/suspend split of its cycle toward the requested cap.
  - The split is never taken from the limit alone: "30% limit" does not mean "run 30% of
    the time", because a target may use anything from a fraction of one logical
    processor to many of them.
  - A target that is already under its cap is not suspended at all.
  - Each adjustment is bounded, and measurements are smoothed with a dead band around
    the cap, so the split does not oscillate.
  - Its cycle is not shorter than 100 ms unless measurement later justifies otherwise,
    and it never runs on the JavaFX Application Thread.
  - The process identity is revalidated before every suspend and resume; on a mismatch
    the limiter stops and touches nothing.
  - It is approximate and is presented as approximate and emulated.
  - It can be cancelled at any time, and cancelling leaves the target running.
  - A target is never left suspended because Prism exits: the limiter resumes on
    cancellation, on error and on shutdown, and the suspension journal (LIFE-6) lets the
    next start of Prism resume a target that a crashed Prism left suspended.
- **LIM-9** The emulated memory policy watches the resident memory of the Managed
  Process against its limit and, when the limit is exceeded, applies that process's
  emergency action under the same rules and switches as OOM Guard (section 9).

## 9. OOM Guard

OOM Guard watches memory pressure and can intervene on Managed Processes. **Monitoring**
and **automatic intervention** are separate things and the UI keeps them visibly apart.

- **OOM-1** States: `NORMAL`, `WARNING`, `CRITICAL`, `EMERGENCY`.
- **OOM-2** The primary signal is available physical memory, as a share of total and as
  an absolute number of bytes. "Percent used" alone is not the signal. Swap pressure and
  short-term trend are not used in the first version; they may be added if measurement
  shows they improve decisions.
- **OOM-3** Default thresholds, all configurable:

  | State | Entered when available memory is |
  |---|---|
  | `WARNING` | at or below 15% |
  | `CRITICAL` | at or below 8% |
  | `EMERGENCY` | at or below 5%, or below an absolute floor (default 256 MiB) |

- **OOM-4** Hysteresis prevents flapping. A more severe state is entered after two
  consecutive samples meet its threshold. A state is left only after available memory
  has stayed above the entry threshold plus a margin (default 3 percentage points) for
  a dwell time (default 10 s), and only one level at a time.
- **OOM-5** Defaults are safe: monitoring ON, warnings and logging ON, automatic
  priority reduction OFF, automatic suspend OFF, automatic termination OFF.
- **OOM-6** What each state does:

  | State | Always | Only if enabled |
  |---|---|---|
  | `NORMAL` | nothing | |
  | `WARNING` | visible warning, log entry | |
  | `CRITICAL` | visible warning, log entry | lower the priority of Managed Processes whose emergency action is Lower priority or stronger |
  | `EMERGENCY` | visible warning, log entry | apply the emergency action of a suitable Managed Process |

- **OOM-7** Automatic actions affect Managed Processes with OOM management on, and
  nothing else. OOM Guard never acts on a process merely because it uses the most memory.
- **OOM-8** A Managed Process's emergency action takes effect only as far as the global
  switches allow. Suspend requires automatic suspend to be enabled; Terminate requires
  emergency termination to be enabled. Otherwise the action falls back to Notify only,
  and the UI shows that it is not in effect.
- **OOM-9** Terminate is never enabled implicitly: it needs the global switch and an
  explicit per-process choice, each made by the user.
- **OOM-10** In `EMERGENCY` Prism acts on one candidate at a time, ordered by OOM
  priority and then by resident memory, and re-evaluates after a settle interval
  (default 5 s) before acting on another.
- **OOM-11** Once the state is back to `NORMAL`, processes that OOM Guard suspended are
  resumed one at a time and priorities it lowered are restored.
- **OOM-12** Every automatic action is recorded with time, state, process, action and
  result.
- **OOM-13** OOM Guard evaluates every memory sample, not the coalesced feed the UI
  receives, and acts off the monitoring thread. When a memory reading is unavailable it
  holds its current state and starts no new automatic action.

## 10. Safety

- **SAFE-1** Prism never elevates itself and never requires running as
  Administrator/root.
- **SAFE-2** Prism never acts destructively on itself: it cannot terminate, suspend,
  limit or manage its own process.
- **SAFE-3** Protected processes cannot be terminated, suspended, limited or managed
  through Prism. They are identified by facts, not by name alone: the system idle and
  kernel processes (PID 0 and 4 on Windows), PID 1 on Linux and macOS, Linux kernel
  threads, and processes Windows itself marks as critical. A name list may add to this
  but is never the only protection.
- **SAFE-4** Permission failures are detected and reported as `ACCESS_DENIED`.
- **SAFE-5** No shell command is ever assembled from process names, arguments or any
  other data taken from a process.
- **SAFE-6** Destructive tests use the demo workload (section 15), never third-party
  applications.

## 11. User interface

The product is named **Prism System Resource Manager**. It follows
[UI_GUIDE.md](UI_GUIDE.md): a mature desktop system utility, dense, aligned, scannable,
keyboard-operable, with restrained visuals. No dashboard cards, gradients, glass, neon,
oversized type, decorative charts, pills, heavy rounding or wasted space.

Primary navigation: Overview, Processes, Performance, Limits, OOM Guard, Logs, Settings.

- **UI-1 Overview.** A compact summary: current CPU, memory, disk and network values
  with their 60-second histories; system facts (MON-6); the OOM Guard state; the number
  of Managed Processes. Each part leads to its page.
- **UI-2 Processes.** The primary surface.
  - Columns: Name, PID, CPU, Memory, I/O, User, Threads, Priority, State. The I/O
    column is process I/O (PROC-1) and is never labelled "Disk".
  - Sortable columns, search/filter, selection that stays stable across refreshes,
    long names truncated without breaking layout, a visible mark on Managed Processes.
  - Actions, where supported: End Task, Force Kill, Suspend, Resume, Change Priority,
    Manage Process, Set CPU Limit, Set Memory Limit, add to or remove from OOM management.
- **UI-3 Performance.**
  - CPU: current %, 60-second history, per logical processor, processor information.
  - Memory: used, total, available, percentage, swap/pagefile, 60-second history.
  - Disk: read and write rates, capacity per file system.
  - Network: download and upload rates with history.
- **UI-4 Limits.** Managed Processes with process identity, CPU limit, memory limit,
  active/inactive status, and the implementation type (Native Hard, Native Soft,
  Emulated, Unavailable). Enable, disable, edit, remove. Limitations are stated, not
  hidden.
- **UI-5 OOM Guard.** Current state, available memory, thresholds, Managed Processes
  with their emergency actions, recent automatic actions. Settings: OOM monitoring
  on/off, the three thresholds, and, in a clearly separate "automatic intervention"
  group, automatic priority reduction, automatic suspend, and emergency termination.
- **UI-6 Logs.** The activity log of the session, filterable, with a way to open the
  log folder.
- **UI-7 Settings.** Application-wide settings: appearance (light or dark), the location
  of logs and settings, and a summary of detected capabilities.
- **UI-8** Every view defines its loading, empty, error and unavailable states, and
  works at the minimum window size.

## 12. Logs

- **LOG-1** Logs are persisted in a per-user application folder, in size-bounded
  rolling files, and are visible inside the application.
- **LOG-2** Logged: startup and shutdown; detected operating system; capabilities;
  platform failures; process actions and their results; limit changes; OOM state
  changes; OOM actions; meaningful monitoring errors; cleanup failures.
- **LOG-3** Sampling ticks are not logged.

## 13. Settings

- **SET-1** Global settings (thresholds, intervention switches, appearance) persist
  between runs in a per-user settings file. A missing or unreadable file means defaults,
  plus a log entry; it never prevents startup.
- **SET-2** Persisted settings never enable automatic termination by default or by
  migration.

## 14. Lifecycle

- **LIFE-1** A controlled shutdown, in order: stops monitoring, limiting and OOM Guard
  scheduling and waits for work in flight; releases every suspension held by the
  limiter or by OOM Guard, which resumes the process unless the user also suspended it
  (LIFE-2); clears native limits Prism applied; removes Linux cgroups
  Prism created; releases native handles; stops executors; flushes logs. It runs off
  the JavaFX Application Thread and is bounded in time.
- **LIFE-2** Processes the user suspended manually are left as the user set them.
- **LIFE-3** A cleanup step that fails is logged and does not prevent the remaining
  steps.
- **LIFE-4** If Prism is killed without a controlled shutdown, native limits on Windows
  and Linux remain in force and an emulated suspension may remain. The next start of
  Prism recovers suspensions from the journal under the rules of LIFE-6, attempts to
  clear the native limits it can still reach and validate (LIM-5), reports the ones it
  cannot, and logs all of it.
- **LIFE-5** A JVM shutdown hook covers exits that bypass the controlled shutdown: it
  blocks further suspensions and releases the suspensions held by the limiter or OOM
  Guard, under the same rule as LIFE-1. It is safe to run after a controlled shutdown
  has already completed.
- **LIFE-6** The suspension journal makes crash recovery safe.
  - Every suspension Prism holds is journaled, whatever its reason. An entry stores the
    process identity (PID and start time), the reasons for which Prism holds the
    suspension (user, limiter, OOM Guard), enough metadata for diagnosis (process name,
    the limit or OOM state that caused it, the identity of the Prism instance that
    wrote it), and when the entry was created and last updated.
  - On restart Prism reads the live process again and requires PID and start time to
    match exactly. An entry that does not match is stale: that process is never resumed
    or modified, and the entry is discarded and logged.
  - An entry written by a Prism instance that is still running is left alone.
  - Recovery releases only the reasons owned by the CPU limiter or OOM Guard. The
    process is resumed only if no user reason remains.
  - A suspension the user requested is never resumed by recovery or by shutdown. Its
    user reason is taken over by the restarted Prism, so the process is again shown as
    suspended by Prism and the user can resume it; this is logged.

## 15. Demo workload

- **DEMO-1** A separate, safe program consumes CPU and memory in a controlled way so
  that monitoring, control, limits and OOM Guard can be demonstrated.
- **DEMO-2** It enforces conservative upper bounds: memory never above the smaller of
  1 GiB and a quarter of physical memory, and it exits by itself after a bounded
  duration (default 5 minutes, at most 30).
- **DEMO-3** It reports what it is doing and what happens to it (for example an
  allocation failure under a memory limit), so the effect of a limit can be shown.

## 16. Delivery

- **DEL-1** One shared source project. Native packages are built with jpackage
  separately on Windows, Linux and macOS; nothing is cross-built.
- **DEL-2** Windows on ARM64 is not a supported build host (no JavaFX 25 artifacts).
- **DEL-3** Platform-specific validation and packaging on CI runners may come later.
