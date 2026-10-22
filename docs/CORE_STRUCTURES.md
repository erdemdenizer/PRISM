# Core structures of the milestone 5 checkpoint

Every class named here exists in this repository; paths are given so the code can be opened
next to this text. The explanations are written for a project meeting: what each structure is
for, how it relates to the others, and which operating-system concept it stands for.

## 1. Domain records (`src/main/java/prism/srm/domain/`)

Immutable Java records. They cross every layer boundary, depend on the JDK only, and validate
their values in the compact constructor.

| Record / enum | Responsibility | OS concept | Why it is needed |
|---|---|---|---|
| `ProcessIdentity(long pid, Instant startTime)` | What makes a process *the same* process. | **PID reuse.** The kernel reuses PIDs; a PID alone may name a different process a moment later. PID + start time does not repeat. | Everything that compares two readings of a process (CPU deltas, selection in the table, later: suspension and recovery) compares identities, so a new process with an old PID never inherits history. |
| `ProcessReading` | One raw reading of a process: name, parent PID, state, **cumulative** CPU time, resident / private / virtual memory, user, thread count, priority, cumulative bytes read and written. Absent values are `Optional`, never zero. | Per-process accounting as the OS exposes it (`/proc/<pid>/stat`, Windows performance counters, `proc_pidinfo`). | Rates cannot be read; they are computed from two cumulative readings. The reading is the input to that computation. |
| `ProcessSnapshot` | The state of one process after a monitoring cycle: the same facts plus **rates**: CPU % of the whole machine (0 to 100), I/O bytes per second. | CPU utilization normalized over all logical processors; I/O throughput. | The UI shows snapshots, never readings: a snapshot carries values that are already comparable between cycles. |
| `ProcessState` | `NEW, RUNNING, SLEEPING, WAITING, STOPPED, SUSPENDED, ZOMBIE, OTHER`. | **Process states** of the classic lifecycle. | Windows has states for threads only, so the value is absent there; Linux and macOS report one. The table shows what the system says and invents nothing. |
| `ProcessDetails(identity, executablePath, commandLine)` | Facts that are too expensive to read for every process on every cycle. | Process image and command line. | Read on demand for the selected process only (requirement PROC-5), measured at about 54 ms per process on Windows. |
| `SystemSnapshot` | The system after one cycle: processor info, OS info, CPU usage, memory, file systems, disk rates, network usage, uptime and process count, plus `SystemHistory` (bounded 60 s histories). | System-wide counters. | One immutable object per second is handed to the UI; a part is absent when its source failed or the system does not report it. |
| `CpuSample`, `CpuUsage`, `MemorySample`, `DiskCounters`, `DiskRates`, `NetworkCounters`, `NetworkUsage`, `FileSystemUsage`, `MetricHistory` | The parts of a system snapshot and the raw counters they are computed from. | CPU ticks (user, system, idle), physical vs. logical devices, byte counters. | Rates come from counter deltas over monotonic time and tolerate counter reset and wrap (`application/RateCalculator`). |

## 2. Platform boundary (`src/main/java/prism/srm/platform/`)

Interfaces that describe *what* is read from the operating system, without saying how. Code
above this package never branches on the operating system.

| Type | Responsibility | Notes |
|---|---|---|
| `ProcessSource` | `processes()` reads every process with the values that are cheap for all of them; `details(identity)` reads the path and command line of one process on demand and returns empty when that identity no longer exists. | A process vanishing during enumeration never fails the reading. Called only on background threads. |
| `SystemMetricsSource` | Processor and OS facts, cumulative CPU ticks, memory, file systems, disk and network counters, uptime and process count. | Computes no rates and keeps no history. |
| `HostPlatform` | Detects the running system once (`HostSystem`: `WINDOWS, LINUX, MACOS, OTHER`), creates the implementations for it and hands them out. The only place that names an implementation package. | Also resolves the per-user folders (log folder) before logging starts. |
| `DiskLayerSource`, `ProcessVirtualSizeSource` | Facts OSHI lacks on a system: which disks are physical; the virtual size of each process on Windows. | Implemented in `platform.windows`; a no-op elsewhere. |
| `UserFolders` and `platform.windows/linux/macos/*UserFolders` | Where the log file goes on each system (`%LOCALAPPDATA%`, XDG directories, `~/Library`). | The only Linux and macOS code in this checkpoint; unit-tested with an injected environment, not run there. |

Implementation: `platform.oshi.OshiHost` creates OSHI's `SystemInfo` once and builds
`OshiSystemMetricsSource` and `OshiProcessSource` from the same objects.
`OshiProcessSource.processes()` asks OSHI for all processes in one call and never queries a
single process for its command line or threads during polling. `platform.windows` contains
`WindowsDiskLayers` (Storage Management API) and `WindowsProcessVirtualSizes` (the "Virtual
Bytes" performance counter, matched to processes by PID and creation time).

## 3. Application services (`src/main/java/prism/srm/application/`)

No JavaFX, no OSHI, no JNA. Tested against hand-written fakes of the platform interfaces.

| Class | Responsibility | OS / concurrency concept |
|---|---|---|
| `SamplingLoop<T>` | The periodic cycle both monitors share: take a sample, deliver it to listeners, wait, repeat. Runs on an injected `ScheduledExecutorService` with `scheduleWithFixedDelay`, so cycles never overlap and never queue up. A `RuntimeException` is logged once per kind and the loop continues; an `Error` ends it and the termination listeners are told. | **Thread lifecycle and scheduling**: a bounded periodic task on a dedicated, named thread; no busy waiting. |
| `SystemMonitor` | A `SamplingLoop` over `SystemSampler`: every second, reads the metrics source, computes rates, appends to 60 s bounded histories, publishes a `SystemSnapshot`. | Counter deltas over `System.nanoTime()`. |
| `ProcessMonitor` | A `SamplingLoop` over `ProcessSampler`: every 2 s, reads all processes, computes per-identity CPU % and I/O rates from the previous reading of the **same identity**, drops everything about processes that exited. | **Process enumeration**, PID reuse, per-process CPU accounting normalized to the number of logical processors. |
| `RateCalculator`, `DeviceRateTracker` | Rate from two counter readings; skips a sample on reset, wrap or a vanished device; totals over physical devices only. | Monotonic clocks, counter semantics. |
| `BoundedHistory` | Fixed-capacity ring of samples. | Bounded memory for a long-running tool. |
| `ProcessDetailsService` | Reads path and command line of one process on its own single-thread executor with a queue of one; a newer request replaces the one waiting; the result names the identity it was asked for. | Producer/consumer with coalescing; the answer is dropped if the selection changed meanwhile. |
| `ActivityLog`, `FailureLog` | Bounded in-memory activity log written through SLF4J; "log once per kind" for repeated failures. | — |

## 4. Composition root and threads (`src/main/java/prism/srm/app/`)

| Class | Responsibility |
|---|---|
| `App` | JavaFX `Application`: sets up logging in `init()`, builds the window in `start()`, asks for the controlled shutdown in `stop()`. The only place where UI, services and platform are wired together. |
| `MonitoringRuntime` | Owns the executors and threads: `srm-startup` (creates `HostPlatform`, about a second of OSHI set-up, so it never blocks the UI), `srm-system-monitor`, `srm-process-monitor`, `srm-process-details`, and `srm-shutdown` (the only non-daemon thread; the process ends when the shutdown sequence returns, nothing calls `System.exit`). |
| `ShutdownSequence` | Stop both monitors, wait for start-up to end, shut the executors down, bounded by 5 s; a failing step is logged and the rest still run. |
| `PlatformSources`, `MainWindow`, `MinimumWindowSize`, `LoggingSetup`, `Launcher` | Fakes-or-real source selection for tests; window title and sizes; per-monitor minimum size; log folder before the first logger; classpath entry point. |

## 5. User interface (`src/main/java/prism/srm/ui/`)

The UI receives immutable snapshots and renders them; it calls nothing in the operating system.

| Class | Responsibility |
|---|---|
| `LatestValueRelay<T>` (`ui`) | The bridge from a sampling thread to the JavaFX Application Thread: keeps the newest value and has **at most one** `Platform.runLater` task pending, so a slow frame never accumulates stale updates. One relay per kind of message (system snapshot, process listing, process details, failures). |
| `ui.shell.Shell`, `Sidebar`, `StatusBar`, `Destination` | Window content: navigation with seven destinations (four disabled at this checkpoint), the page area, the status bar. Only the page on screen is given new snapshots. |
| `ui.overview.OverviewPage`, `OverviewViewModel` | The Overview page and its properties. |
| `ui.performance.PerformancePage` and the CPU, Memory, Disk, Network sections | One resource at a time; the per-processor grid is one Canvas, so 64 processors cost as many nodes as 4. |
| `ui.processes.ProcessesPage` | Filter field, `ProcessTable`, `ProcessDetailsPane`; asks for details through a function that returns at once. |
| `ui.processes.ProcessTable` | The `TableView` with the nine `ProcessColumn`s, header sorting, the context menu `SortMenu` for keyboard users. |
| `ui.processes.ProcessTableModel`, `ProcessRow`, `ProcessOrder`, `ProcessFilter` | One row per process identity, updated in place; a listing changes the shown list by at most three events (removed, added, permuted). A total order (chosen columns, then PID, then start time) so re-sorting never shuffles unchanged rows. Case-insensitive filter without a locale. |
| `ui.processes.ProcessesViewModel`, `ProcessDetailsModel`, `ProcessText` | State of the page and the text it shows; details are shown only if their identity is still the selected one. |
| `ui.graph.CanvasGraph`, `HistoryGraph`, `HistoryGrid`, `AutoScale`, `PlotGeometry` | History graphs drawn on a Canvas, never one node per sample. |
| `ui.metric.*`, `ui.format.ValueFormatter` | Shared metric panels and readouts; number formatting with an explicit locale. |

## 6. How a value travels (one cycle)

1. `srm-process-monitor` thread: `SamplingLoop` calls `ProcessSampler.sample()`.
2. `OshiProcessSource.processes()` asks the OS for all processes and returns `ProcessReading`s.
3. `ProcessSampler` matches each reading with the previous one **by identity**, computes CPU %
   and I/O rates, forgets processes that are gone, and produces `List<ProcessSnapshot>`.
4. The loop hands the list to its listeners; `MonitoringRuntime`'s listener offers it to a
   `LatestValueRelay`.
5. The relay schedules at most one task on the JavaFX Application Thread; `Shell.showProcesses`
   gives the list to `ProcessesPage.show`, and `ProcessTableModel.update` changes rows in place.
6. Selecting a row asks `ProcessDetailsService` for that identity's path and command line on
   `srm-process-details`; the answer returns through another relay and is shown only if that
   row is still selected.

`ArchitectureTest` (`src/test/java/prism/srm/ArchitectureTest.java`) asserts the layer rules
above on every build: JavaFX only in `ui` and `app`, OSHI and JNA only in the implementation
packages, no class in `ui` calls `Platform.runLater`, `domain` depends on the JDK only.
