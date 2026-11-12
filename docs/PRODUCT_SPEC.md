# Product specification: PRISM — Process & Resource Interactive System Manager

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

**Verification status.** The Prism application itself has been run by hand on Windows only.
Since milestone 8 the full test suite also runs in CI on Ubuntu x86_64 and macOS arm64,
including tests that act on the real system there, and milestone 12 runs it on a local Ubuntu
virtual machine as well. Code for Linux and macOS is written
against primary documentation with its logic unit-tested, and is **unverified** until it has
been run on that system: by such a test, for what that test exercises, or by hand. Every
milestone report states which systems the work was actually run on, and how.

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
  On Linux and macOS a stop is a signal that any program allowed to signal the process may
  end: if another program continues a process Prism stopped, Prism does not notice and
  still shows it as suspended by Prism until Resume. Prism does not suspend a process that
  is stopped already, so it never takes over a stop that existed before it acted. A stop
  another program adds while Prism holds the process ends with Prism's resume, and Prism
  cannot see it. After a crash, Prism takes over a stop only at the user's request (LIFE-6).
- **PROC-7** Snapshots of exited processes are not retained.

## 5. Process control

- **CTL-1** Actions: End Task (graceful), Force Kill, Suspend, Resume, Change Priority.
- **CTL-2** Each action returns an operation result. The user sees a plain message
  derived from the result; native error codes and exceptions go to the log, never to
  the primary message.
- **CTL-3** Portable Java APIs are preferred where they preserve the semantics an action
  requires. A documented native API is used instead only where it is needed to keep the
  action on the process identity (PROC-3) or to report the system's answer accurately
  (SAFE-4); this is not a general licence to replace portable APIs. Force Kill on Windows
  uses `ProcessHandle`, as in milestone 7. On Linux, End Task, Force Kill, Suspend and
  Resume send their signals through a pidfd (`pidfd_open`, `pidfd_send_signal`), which
  refers to exactly one process; where the system does not provide pidfd signalling,
  these four actions are unavailable. On macOS they send their signals with `kill(2)`
  immediately after the process identity is checked; no documented interface makes the
  check and the signal atomic, which is a stated limitation of that system. Everything
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
  `ACCESS_DENIED`, which the UI explains. This includes raising it back to a level the
  process had before. On Linux the nice value belongs to each thread, so a level is set on
  every thread of the process, one by one (not atomically); macOS has one value per
  process.
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
| CPU limit | `NATIVE_HARD` (Job Object), else `EMULATED` | `EMULATED`. `NATIVE_HARD` (systemd scope, `cpu.max`) exists but is not enabled (LIM-6, K16) | `EMULATED` |
| Memory limit | `NATIVE_HARD` (Job Object commit limit), else `EMULATED` policy | `EMULATED` policy once the memory signals are validated (ADR-0015 §7), `UNAVAILABLE` until then. `NATIVE_HARD` (`memory.max`) and `NATIVE_SOFT` (`memory.high`) through a systemd scope exist but are not enabled (LIM-6, K16) | `EMULATED` policy once the memory signals are validated, `UNAVAILABLE` until then |

The `EMULATED` CPU limit suspends and resumes its target (LIM-8), so it needs Suspend and
Resume; where they are `UNAVAILABLE` (Linux without pidfd signalling) the emulated CPU
limit is `UNAVAILABLE` too, with the same reason.

- **CAP-1** Operating-system differences stay behind the platform abstraction. UI and
  application code never branch on the operating system; they ask for capabilities.
- **CAP-2** The UI disables an unavailable function and explains why.
- **CAP-3** An emulated mechanism is never presented as native.
- **CAP-4** Processes of other users, elevated processes, services and protected
  processes are generally out of reach without elevation. Operations on them are
  expected to return `ACCESS_DENIED`, which is a normal outcome and is shown as such.
- **CAP-5** A capability report names the mechanism behind a level (for example close
  request, Job Object, systemd scope, duty cycle), so the UI can show the right explanation
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
    only when its last handle is closed and all its processes have terminated. A job's
    name goes with Prism's last handle, so after a crash the next start cannot reach it,
    and a job still reachable by name has a running holder. Recovery is therefore
    read-only ([ADR-0012](adr/0012-windows-native-limits-ledger-and-recovery.md), accepted
    2026-10-10, as measured in milestone 10): on restart Prism reads the ledger of the
    earlier session, reports each process as possibly still limited, changes no job and
    takes no destructive action. Such a limit lasts until the process exits; no second
    limiter is offered for it meanwhile. The user may let it run or close and restart the
    process, which Prism can then limit normally.
- **LIM-6** Linux native limits use transient scopes of the systemd user manager, whose
  cgroup v2 limits the kernel enforces ([ADR-0016](adr/0016-linux-native-limits-through-systemd-scopes.md),
  Accepted; it replaces the delegated-subtree design of
  [ADR-0003](adr/0003-linux-native-limits-with-cgroups-v2.md)).
  - **Not enabled in the product (K16).** Until desktop application ownership and lifecycle
    have been validated on a real desktop Linux and the product owner has approved it, the
    product reports native limits as `UNAVAILABLE` for every process ("not validated for
    desktop applications"). A Linux user gets the emulated CPU limit (LIM-8). The memory
    limit falls to the monitored policy (LIM-9), which is itself `UNAVAILABLE` until the
    memory signals on Linux are validated (ADR-0015 §7). No setting, property or environment
    variable changes this. The product's limiter opens no bus connection, creates no scope and looks for no
    limits of earlier sessions. The mechanism below runs only on disposable processes that
    the test harness starts and names to the limiter.
  - **Preconditions.** Capability discovery is read-only: the unified cgroup v2 hierarchy
    with no v1 hierarchy mounted, `libsystemd` with every needed function, a reachable user
    manager of systemd 255 or later (adoption by pidfd), and the needed controllers
    delegated to it. It never creates a unit and never hands over a pidfd. A process is
    eligible only if it runs inside the user's own `user@UID.service`, outside any scope with
    Prism's name, and its real, effective, saved and file system user IDs are the user's.
    Anything else is `UNAVAILABLE` or refused, with the reason.
  - **Identity.** Prism opens a pidfd for the target and checks the identity (PID and start
    time) and the user IDs after the open, then creates the scope with that descriptor
    (`PIDFDs`). Prism's messages never name a process by PID number; a manager that does not
    accept `PIDFDs` makes native limits unavailable, with no weaker fallback.
  - **Adoption is one-way.** The target moves into its own scope,
    `prism-srm-limit-<pid>-<startMillis>.scope` in `app.slice`, created without limits and with
    `OOMPolicy=continue`, `ManagedOOMPreference=omit` and `CollectMode=inactive-or-failed`. It
    stays there until it exits: Prism never moves it back, and the unit it came from may
    already be gone. Processes it starts afterwards share the scope. An external stop of the
    scope terminates its processes. Adoption needs explicit consent per process that states
    all of this, and that the desktop environment or service manager may see the process
    differently.
  - **Ownership.** Before any limit is set, the unit name, its invocation id and its cgroup
    path are recorded durably. Every limit write goes to the unit object addressed by that
    invocation id, never by name, so a unit that later takes the name is never changed. Before
    a change, Prism proves the unit is the recorded invocation with the recorded cgroup, and
    that the target is alive, the same process, with the same user IDs, and in that cgroup.
    Before a clearing it proves the recorded invocation; comparing the cgroup there too is
    required before K16 can be lifted. A scope that merely carries Prism's name is never
    changed. Any process inside such a scope gets no second limit, native or emulated,
    except the target this limiter holds.
  - **CPU limit.** `cpu.max`, set through `CPUQuotaPerSecUSec`. The whole-machine percentage P
    is converted with the online logical processor count N: `P x N x 10000` microseconds per
    second, which the kernel shows as `P x N x 1000` per period of 100000 microseconds. A limit
    of 100% is set explicitly as the whole machine, not as `max`. `NATIVE_HARD`.
  - **`memory.max`** is a hard boundary on the cgroup's memory, `NATIVE_HARD`. When usage
    reaches it and reclaim cannot reduce it, the cgroup may enter an out-of-memory state and
    the kernel may invoke its OOM killer inside that cgroup. Some allocations fail instead, and
    usage may exceed the limit temporarily in circumstances the kernel documents. With swap
    available the kernel may swap instead of killing, because `memory.swap.max` is not
    touched. Prism does not claim that the target process itself will be killed. It needs a
    separate, explicit consent that the kernel may terminate the process or one of its
    children, and the UI describes the risk in these terms before the limit is applied.
  - **`memory.high`** is a throttling boundary and the intended default: above it the
    cgroup's processes are throttled and put under reclaim pressure. It does not itself invoke
    the OOM killer and may be breached under extreme conditions. `NATIVE_SOFT`. The mechanism
    implements both boundaries, but the limit service does not yet request `memory.high`;
    settling that is required before K16 can be lifted.
  - Both memory boundaries count only memory charged after the move; memory charged before it
    stays with the old cgroup.
  - **Read-back.** A limit is reported as applied only after `cpu.max`, `memory.high` or
    `memory.max` reads back as requested, within a bounded wait (LIM-4). A refusal means nothing
    was done. A call without an answer, or a value the read-back does not show, keeps the
    limit uncertain: no second limiter starts, and nothing is claimed.
  - **Removing a limit and cleanup.** Removing a limit sets its property to `infinity` and
    reads it back. The process stays in the now unlimited scope, the scope-membership rule
    keeps a second limiter from it, and systemd collects the scope when its last process has
    exited. Prism writes no cgroup file and never stops, kills or abandons a scope, never moves
    a process out of one, and never writes `cgroup.kill` or `memory.oom.group`.
  - **After a crash.** Limits stay until the next start clears them or the processes exit.
    Recovery first classifies without changing anything. It then clears a scope only when
    the proof holds at that moment: the recorded unit and invocation id equal the live unit's,
    and the recorded process is a member, or has exited and others remain. A scope without
    that proof is listed and limits on its members are blocked; no user action overrides this.
  - Prism is not run as root. If a precondition fails, the capability is reported honestly and
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
  - A controlled shutdown, or an exit the shutdown hook covers, never leaves a target
    suspended: the limiter resumes on cancellation, on error and on shutdown. After a hard
    kill the target may stay suspended; the next start lists it for the user (LIFE-6).
- **LIM-9** The emulated memory policy watches the resident memory of the Managed
  Process against its limit and, when the limit is exceeded, applies that process's
  emergency action under the same rules and switches as OOM Guard (section 9). It acts only
  on a process whose OOM management is on, and only while OOM monitoring is on.

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
  explicit per-process choice, each made by the user. Terminate means Force Kill, sent at
  most once per process identity per session.
- **OOM-10** In `EMERGENCY` Prism acts on one candidate at a time, ordered by OOM
  priority and then by resident memory, and re-evaluates after a settle interval
  (default 5 s) before acting on another.
- **OOM-11** Once the state is back to `NORMAL`, processes that OOM Guard suspended are
  resumed one at a time and priorities it lowered are restored. Where a lowered priority
  cannot be raised back without privilege, automatic priority reduction is unavailable
  (CAP-2).
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

The product is named **PRISM — Process & Resource Interactive System Manager**. It follows
[UI_GUIDE.md](UI_GUIDE.md): a mature desktop system utility, dense, aligned, scannable,
keyboard-operable, with restrained visuals, one bundled typeface and one icon family. No
dashboard cards, gradients, glass, neon, oversized type, decorative charts, pills, heavy
rounding, wasted space or animation of the monitoring data.

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
- **UI-7 Settings.** Application-wide settings: appearance (System, Light or Dark; System
  follows the appearance of the operating system and reacts to a change of it while Prism
  runs, and is the default), the location of logs and settings, and a summary of detected
  capabilities. The theme architecture and the System default exist from milestone 5.5;
  the page that shows the choice and persists it belongs to milestone 13, as does the
  native window chrome matching the effective appearance on Windows (a light native title
  bar above a dark window is not acceptable in the final release).
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
  (LIFE-2); clears native limits Prism applied; releases native handles; stops executors;
  flushes logs. It runs off the JavaFX Application Thread and is bounded in time. On Linux,
  clearing sets the scope's limit properties to `infinity`; Prism removes no scope and moves no
  process, and a scope stays until its processes exit (LIM-6).
- **LIFE-2** Processes the user suspended manually are left as the user set them.
- **LIFE-3** A cleanup step that fails is logged and does not prevent the remaining
  steps.
- **LIFE-4** If Prism is killed without a controlled shutdown, native limits on Windows
  and Linux remain in force and an emulated suspension may remain. The next start of
  Prism reads the journal and resumes nothing by itself, because no target system lets
  Prism prove that a stop it finds is still its own; it lists what it may have left
  suspended (LIFE-6), reports the native limits an earlier session recorded, and logs all
  of it. On Windows it changes none of them (LIM-5, ADR-0012). On Linux the scope mechanism
  clears only a scope whose ownership it proves again at that moment (LIM-6); the product
  applies no native limit there (K16), so it has none to report or clear.
- **LIFE-5** A JVM shutdown hook covers exits that bypass the controlled shutdown: it
  blocks further suspensions and releases the suspensions held by the limiter or OOM
  Guard, under the same rule as LIFE-1. It is safe to run after a controlled shutdown
  has already completed.
- **LIFE-6** The suspension journal makes a crash visible and keeps recovery from acting
  on what it cannot verify.
  - Every suspension Prism holds is journaled, whatever its reason. An entry stores the
    process identity (PID and start time), the reasons for which Prism holds the
    suspension (user, limiter, OOM Guard), enough metadata for diagnosis (process name,
    the limit or OOM state that caused it, the identity of the Prism instance that
    wrote it), and when the entry was created and last updated. On Windows an entry also
    records the threads Prism holds (ID and creation time).
  - On restart Prism reads the live process again and requires PID and start time to
    match exactly. An entry that does not match is stale: that process is never resumed
    or modified, and the entry is discarded and logged.
  - An entry written by a Prism instance that is still running is left alone.
  - After a crash Prism resumes nothing by itself. It closes an entry whose process is
    gone, has another start time, or is running, and lists every other entry as possibly
    left suspended by an earlier session, saying what it could and could not check.
  - Prism resumes a listed process only when the user asks. At that moment the process
    must still be stopped (Linux `T`, never `t`; macOS stopped); on Windows, every
    recorded thread that still exists must carry exactly one suspension and none more.
    The user is told first that this does not prove the stop is Prism's: another program
    may have stopped the process after the crash, or, on Windows, removed Prism's
    suspension and added its own.
  - On Windows, an entry that does not record which threads Prism suspended is listed for
    manual intervention only: Prism offers no resume for it, because an anonymous
    suspension count does not show whose it is.
  - Without such a request Prism ends no stop and lowers no suspension count that it finds
    after a crash.
  - Prism makes no suspension of its own (CPU limiter, OOM Guard) before the entry that
    lets the next start find it has been written durably; if the journal cannot be
    written, those features are off and Prism says why.
  - Recovery and shutdown never resume a suspension the user requested.
- **LIFE-7** One Prism runs per user profile
  ([ADR-0013](adr/0013-one-prism-per-user-profile.md), accepted 2026-10-10). A second launch
  for the same user is refused before anything is monitored, limited or recovered: it says
  that Prism is already running, or still closing, and exits without touching the running
  one. The ownership is an operating-system lock that ends with the process, so a launch
  after a hard kill is not kept out.

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
