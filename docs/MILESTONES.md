# Milestones

The implementation plan for [PRODUCT_SPEC.md](PRODUCT_SPEC.md). Each milestone is a
vertical slice with behavior that can be verified, not a layer built in advance:
interfaces and types are introduced by the milestone that first needs them.

Conventions for every milestone:

- **Quality gate**: `./mvnw verify` passes and the diff is reviewed with the
  `quality-gate` skill. Milestones with UI also render snapshots
  (`./mvnw -q -Pui-snapshots test`) and inspect them.
- **Verification is on Windows** unless stated. Code for Linux or macOS that has not been
  run there is reported as unverified.
- **Destructive manual tests** target only the demo workload (milestone 6 onward; SAFE-6).
- A milestone is done when every acceptance criterion has evidence: a test, a command
  with its output, or a described manual check.
- DEL-2 and DEL-3 are constraints on delivery, not work items, so no milestone
  implements them.

| # | Milestone | Main requirements |
|---|---|---|
| 1 | System metrics backend and snapshot model | MON-1 to MON-11 |
| 2 | Process snapshot backend and enumeration cost | PROC-1 to PROC-5, PROC-7 |
| 3 | Application shell and Overview | UI-1, UI-8, MON-12, LOG-1 to LOG-3, LIFE-1 (first steps) |
| 4 | Performance page | UI-3 |
| 5 | Processes page (read-only) | UI-2 (table) |
| 5.5 | Visual foundation: tokens, theme modes, typeface, icons, shell and page structure | UI-1 to UI-3 (presentation), UI-7 (theme architecture), UI-8 |
| 6 | Demo workload | DEMO-1 to DEMO-3 |
| 7 | Process control on Windows | CTL-1 to CTL-9, CAP-1 to CAP-5, PROC-6, SAFE-1 to SAFE-5, UI-2 (actions), UI-6 |
| 7.5 | Interaction polish of process control | CTL-6 (current level), CTL-8, CAP-2, PROC-6 (inspector), UI-2 (actions), UI-6, UI-8 |
| 8 | Process control on Linux and macOS | CTL-3, CTL-5, CTL-6 for those systems |
| 9 | Managed Processes and emulated CPU limiter | MGD-1, MGD-2, MGD-4, MGD-3 (CPU limit, enabled), LIM-1 to LIM-4 (emulated path), LIM-7, LIM-8, LIFE-4 to LIFE-6, UI-4 |
| 10 | Windows native limits | LIM-3 and LIM-4 (native path), LIM-5 |
| 11 | OOM Guard and memory policy | OOM-1 to OOM-13, LIM-9, SET-1, SET-2, UI-5 |
| 12 | Linux native limits | LIM-6 |
| 13 | Settings, shutdown hardening, polish, packaging | UI-7, LIFE-1 to LIFE-6, DEL-1 |

---

## 1. System metrics backend and snapshot model

**Objective.** Sample CPU, memory, disk, network and system facts off the UI thread into
immutable snapshots with a 60-second bounded history. No UI.

**Areas.**
- `domain`: immutable sample and snapshot records.
- `platform`: `SystemMetricsSource` (raw counters and facts) and the platform entry
  point that hands it out, so nothing outside `platform` names an implementation
  package. `platform/package-info.java` is corrected: result types live in `domain`.
- `platform.oshi`: the OSHI implementation, with OSHI objects created once.
- `application`: the system monitor: periodic sampling with `scheduleWithFixedDelay` on
  an injected `ScheduledExecutorService`, rate calculation, bounded histories, delivery
  of the newest snapshot to listeners.
- `ArchitectureTest`: all five layers now contain classes, so
  `withOptionalLayers(true)` is removed.

**Acceptance criteria.**
- Every value in MON-2 to MON-6 is produced; values the system does not report are absent.
- Rates are correct for a known pair of counter readings; a counter reset, wrap or
  vanished device yields a skipped sample (MON-10).
- Histories never exceed their capacity (MON-7).
- A slow source cannot make cycles overlap or pile up (MON-9).
- A source that throws leaves the metric unavailable, is logged once, and monitoring
  continues (MON-11).
- No JavaFX type appears in these packages; `ArchitectureTest` passes without optional
  layers.

**Tests.** Rate calculation (zero interval, first sample, reset, wrap) with an injected
clock; history bounds; failure handling; non-overlap on a real single-thread scheduler
with a fake source blocked on a latch, asserting that no second cycle starts while the
first is held. All against a hand-written fake `SystemMetricsSource`.

**Manual verification.** A probe class in test sources, named so the default build does
not run it, prints ten consecutive samples:
`./mvnw -q test -Dtest=HostMonitoringProbe`. Compare memory, uptime and process count
with Task Manager. Compare CPU % with a *% Processor Time* counter, for example
`Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'"`, not
with Task Manager's CPU graph: Task Manager shows the frequency-adjusted *% Processor
Utility*, which is usually higher (ADR-0005). The probe also lists every interface with
its layer; the network total must equal the physical adapters and not the sum of all.

**Not verified on Linux and macOS.** Nothing in this milestone has been run there. Known
open points, to be settled on a real machine before the behavior is relied on:
- Disk and network totals are absent, because the layer of every device is `UNKNOWN`.
  A layer source for Linux (sysfs) and one for macOS are not written yet.
- Disks are enumerated on every poll on Linux, because OSHI refreshes a single disk by
  repeating the whole enumeration. The cost at the 1 s cadence is not measured.
- Whether OSHI's disk list on macOS contains synthesized APFS disks next to the hardware
  disks is not known.
- Reading OSHI's code suggests that its process count on macOS may be higher than the
  real one. This is an observation only: it has not been reproduced, and no correction
  is applied.

**Reviewers.** `architecture-reviewer` (first real classes in four layers),
`test-reviewer`.

**Quality gate.** Full.

## 2. Process snapshot backend and enumeration cost

**Objective.** Enumerate processes into immutable snapshots with Task Manager style
CPU %, and measure what an enumeration cycle costs.

**Areas.**
- `domain`: process snapshot, process identity, state, the raw process reading, and the
  on-demand process details.
- `platform`: `ProcessSource`, and `ProcessVirtualSizeSource` for a system whose
  process listing lacks the virtual size; `platform.oshi`: the process source;
  `platform.windows`: the virtual sizes.
- `application`: the process monitor: per-identity CPU and I/O-rate deltas, removal of
  exited processes. The sampling loop of the system monitor is extracted so that both
  monitors use it.
- `docs/PERFORMANCE.md`: the measurements and the cadence they justify.

**Acceptance criteria.**
- PROC-1 fields are produced; absent values are absent, not zero.
- CPU % is normalized to the whole machine and never exceeds 100% (PROC-2); a process
  seen once has no CPU value.
- A PID reused by a new process is a new identity and no delta is computed across the
  two (PROC-3).
- The start time of a running process is identical across repeated reads and across
  restarts of the reader, checked on Windows with the probe. Later recovery logic
  compares identities exactly and depends on this.
- A process vanishing during enumeration never fails the snapshot (PROC-4).
- Path and command line are not read during polling (PROC-5).
- No data is retained for exited processes (PROC-7).
- The time of one enumeration cycle is measured over at least 100 cycles and recorded;
  the 2-second cadence is kept or changed with the reason recorded (MON-8).

**Tests.** Normalization with a multi-core raw load above 1.0; first sample; PID reuse;
invalid and partially readable processes from a fake source; retention after exit.
Virtual sizes from a second source: matched by identity, absent for a missing row and
for a reused PID, one read per listing, a failed read costing nothing else.

**Manual verification.** The probe prints the top ten processes by CPU and the cycle
time; compare with Task Manager while a CPU-heavy process runs:
`./mvnw -q test -Dtest=HostProcessProbe`. The probe loads four of its own threads for a
while, which must show as four logical processors' worth of the machine, and starts one
child JVM that sleeps and exits, which must appear and disappear. Process CPU is busy
time on the whole-machine scale (ADR-0005), so it is compared with processor time, not
with Task Manager's frequency-adjusted figure. For the cost measurement without the
artificial load: `-Dprobe.cycles=100 -Dprobe.busyThreads=0 -Dprobe.child=false`.
The probe also prints the virtual size of the eight oldest processes, to compare with
`Win32_Process.VirtualSize`, and fails when its own process has none.

**Brought forward from milestone 5.** `ProcessSource.details`, the on-demand read of one
process's path and command line, exists already, because PROC-5 could not be measured or
verified without it. It returns no current values. The application service that uses
it and the details pane stay in milestone 5.

**Windows behavior and limits, found while verifying.**
- What OSHI returns as the virtual size is the page file usage from
  `WTS_PROCESS_INFO_EX`, a different quantity held in a 32-bit field. It is never used.
  The virtual size comes from the "Virtual Bytes" counter of the Process performance
  object, read once per listing for all processes and matched by PID and creation time
  ([ADR-0006](adr/0006-process-io-and-windows-virtual-size.md)). It is the quantity of
  `Win32_Process.VirtualSize`; the WMI class itself costs about 620 ms per query and is
  not used. A process that exits between the listing and that read has no virtual size
  in that snapshot.
- The I/O rates are the system's per-process I/O counters ("IO Read Bytes/sec" and
  "IO Write Bytes/sec" of the Process performance object), which count file, network
  and device I/O together. They are process I/O, not disk activity, and nothing in
  Prism calls them disk rates. No documented, cheap per-process counter for disks
  alone is known on Windows; tracing disk I/O per process with ETW is out of scope.
- User, path and command line are absent for processes this user may not open (about a
  third of the processes on the development machine). That is the expected outcome
  without elevation.
- State is unavailable in the normal snapshot. Windows has states for threads, not for
  a process, and OSHI sets every process to running without asking the system; that
  synthetic value is never passed on. OSHI's suspended-state scan and any enumeration
  of threads stay off, because they are too expensive per poll (PROC-6). The state
  stays absent on Windows until a later requirement brings a mechanism that is both
  reliable and affordable. `Win32_Process.ExecutionState` is not one: it is in the
  class, but the Windows provider does not fill it (measured: empty for all processes).
- The parent PID is the creating process as recorded at creation. About 7% of the
  processes on the development machine name a parent that no longer exists, so a process
  tree must check that a parent started before its child.
- Microsoft's documentation gives the unit of the process times in `WTS_PROCESS_INFO_EX`
  as milliseconds. OSHI treats them as 100-nanosecond units, and the measurement agrees
  with OSHI: one thread busy for 2 s added 2015 ms. The probe's load check guards this.

**Not verified on Linux and macOS.** Nothing in this milestone has been run there. What
follows is from reading OSHI's code.
- Linux start time is the boot time, which OSHI reads once per JVM with one-second
  granularity, plus the process's start in clock ticks. It is exact within one run of
  Prism. Whether it is identical after a restart of Prism, and after the system clock is
  set back, is not known; recovery logic that compares identities across restarts
  (LIFE-6) must not rely on it before that is measured.
- I/O counters of processes of other users cannot be read without privilege on Linux,
  and OSHI then returns zero. Prism reports the counters as absent whenever both are
  zero, so a process that has done no I/O at all also shows no I/O rate there.
- The I/O counters are narrower than on Windows. On Linux, `read_bytes` and
  `write_bytes` of `/proc/<pid>/io` count what the process caused to be fetched from or
  sent to the storage layer; `write_bytes` includes writes that were cancelled later. On
  macOS OSHI reads `ri_diskio_bytesread` and `ri_diskio_byteswritten` of
  `proc_pid_rusage`; the names say disk I/O, and Apple does not document the fields. The name "I/O" holds
  for all three systems; "disk" would not.
- Private resident memory is the anonymous resident memory on Linux and absent on macOS,
  where OSHI returns the physical footprint instead.
- The user is read as an ID on every poll and turned into a name once per ID, because a
  process can change its user while it runs.
- On macOS an unprivileged user can read only their own processes; the others are
  expected to be missing from the list.

**Reviewers.** `performance-check` skill, `test-reviewer`.

**Quality gate.** Full.

## 3. Application shell and Overview

**Objective.** The first working UI slice: a window with navigation, a live Overview
page, clean start-up and shutdown, and persisted logs.

**Areas.**
- `ui`: shell (navigation, status bar), Overview page, a Canvas history graph control,
  view models, component stylesheets.
- `app`: wiring, ownership of executors, start-up, and the first steps of the
  controlled shutdown, run on a dedicated thread.
- `application`: the activity log (bounded in memory, written through SLF4J).
- `platform`: operating-system detection in the entry point; per-user folders in
  `platform.windows`, `platform.linux` and `platform.macos`.
- Logging: a size-bounded rolling file. The folder comes from `platform` and is handed
  to Logback before the first logger is used (`App` therefore holds no static logger);
  Logback flushes through its own shutdown hook.

**Acceptance criteria.**
- The window is titled "Prism System Resource Manager" and shows the seven navigation
  destinations; pages that do not exist yet are disabled and say so.
- Overview shows live CPU, memory, disk and network values with 60-second graphs drawn
  on a Canvas, system facts, and updates without blocking input (UI-1, MON-9).
- At most one UI update is pending at any time.
- Closing the window stops monitoring and executors off the JavaFX Application Thread,
  and the JVM exits.
- Start-up, shutdown and the detected system are in the log file and the activity log;
  no sampling tick is logged (LOG-1 to LOG-3). The log folder has one source of truth.
- MON-12 is measured with the window visible for ten minutes: average CPU of Prism and
  heap before and after. It is met, or the cadence is changed and the reason recorded.

**Tests.** View-model logic (unit formatting with explicit locales, coalescing);
activity log bounds; per-user folder resolution for each system with an injected
environment; snapshot tests of Overview in both themes, at default and minimum size, in
loading and unavailable states.

**Manual verification.** Run the app; compare with Task Manager; resize to the minimum;
navigate by keyboard; confirm the JVM exits and `jcmd <pid> Thread.print` shows only
named, expected threads while running. The Linux and macOS folder rules are unverified
on real systems.

**Decided while building.**
- Navigation is a sidebar at the left, not a tab strip; the specification names the
  destinations and not their arrangement ([UI_GUIDE.md](UI_GUIDE.md), Shell).
- Both monitors run from this milestone on, although nothing shows process snapshots
  before milestone 5, so that MON-12 is measured with the cost the product will have.
- The log folders and the bounds of the log file are in
  [ADR-0007](adr/0007-per-user-folders-and-log-files.md).
- Numbers follow the format locale of the user; unit symbols and all text are English.
  The window opens in the light theme: the choice of theme belongs to Settings (UI-7).

**Deferred from UI-1.** The Overview does not show the OOM Guard state (milestone 11)
or the number of Managed Processes (milestone 9), and no part of it leads to a page
yet, because no other page exists. Each is added by the milestone that brings what it
shows.

**Behavior and limits, found while verifying.**
- A monitor that an `Error` ends while it runs tells the application
  ([ARCHITECTURE.md](ARCHITECTURE.md), "A monitor that ends says so"). The `Error` is
  not caught: the sampling loop announces its end from a `finally` block. If system
  monitoring ends, the Overview says so and shows no values, because the last ones
  would look current. If process monitoring ends, the Overview goes on and the status
  bar says "Monitoring degraded". Either is recorded once. A monitor that hangs inside
  a call of the operating system without ever returning is a different case and is not
  detected: it throws nothing, and seeing it would need a clock on the JavaFX
  Application Thread.
- A value the system does not report and a value whose read failed look the same in a
  snapshot, so the Overview says "Unavailable" for both and its tooltip points to the
  log file for the cause.
- The first snapshot has no rates, because a rate needs two readings. The Overview shows
  "Reading..." for a value that is missing from the first cycle and "Unavailable" only
  from the second cycle on.
- A thread dump of the running application shows, next to the four `srm-` threads, the
  threads of the JVM and of JavaFX, `JNA Cleaner`, workers of the JDK's common pool
  (OSHI's process listing uses parallel streams) and one unnamed daemon thread,
  `Thread-N`, without a Java stack. That one is created when the JavaFX toolkit starts,
  before any code of Prism runs; it is not Prism's to name.
- The snapshot harness renders a scene, not a window: a 720 x 480 window leaves a scene
  of about 704 x 441 on Windows 11, which is rendered as well. The harness has no window
  focus, so the focus ring is rendered by forcing its pseudo-class; that a key pressed
  by a person moves the focus is checked by hand.

**Not verified on Linux and macOS.** Nothing in this milestone has been run there. The
folder rules are unit-tested with an injected environment only. How the window, the
fonts and the graphs render, and whether the process exits cleanly, is not known.

**Reviewers.** `ui-critic`, `architecture-reviewer`, `performance-check` skill (idle cost).

**Quality gate.** Full, plus snapshots.

## 4. Performance page

**Objective.** Detailed resource views.

**Areas.** `ui`: the Performance page and its view models; additions to `application`
or `domain` only where a displayed value is not yet available.

**Acceptance criteria.** UI-3 in full: CPU with total and per-logical-processor history
and processor information; memory with swap/pagefile; disk rates and capacity per file
system; network rates per interface with history. Graphs are Canvas-drawn from bounded
buffers; the scene graph does not grow while the page is open.

**Tests.** View-model and formatting tests; snapshots with fake data for 4 and 64
logical processors, extreme values, no swap, and no network interface.

**Manual verification.** Compare each section with Task Manager under load from a
CPU-heavy process and a large file copy.

**Decided while building.**
- The page shows one resource at a time, chosen in a row of four entries at its top
  (CPU, Memory, Disk, Network). The four views are built once; choosing one puts it in
  the place of the one shown ([UI_GUIDE.md](UI_GUIDE.md), Performance page).
- The page needs nothing that a snapshot did not already carry: `application`, `domain`
  and `platform` are unchanged.
- The histories are those of MON-7 and no others. "Network rates per interface with
  history" is therefore built as the rate of every interface (MON-5) next to the history
  of the total over the physical adapters (MON-7); no history is kept for a single
  interface. The disk rates are the total over the physical disks and nothing else: a
  snapshot has no rates of single disks (ADR-0005).
- The utilization of all logical processors is drawn on one Canvas, as a grid of small
  histories, so the page has as many nodes for 64 logical processors as for 4.
- File systems and interfaces are tables. Interfaces are listed with their layer,
  physical adapters first, because the total is made of those.
- A file system is shown with its capacity and the space available to the current user.
  No "used" figure is shown: capacity minus available space is not the used space where
  blocks are reserved or a quota applies.
- A snapshot goes to the page on screen only, and on the Performance page to the section
  on screen only; whatever is put on screen is given the newest snapshot first. A page
  that is not shown costs nothing.
- What the Overview and the Performance page share (the metric panel, the readouts, the
  facts, the line that says why a page has no values) moved to `prism.srm.ui.metric`.

**Behavior and limits, found while verifying.**
- Compared on Windows with the system's counters, not by eye with Task Manager. Two
  processes, each pinned to one logical processor, showed as 100 % in the cells of those
  two processors and nowhere else, so the cells are in the processor order of the system.
  Memory in use, available, total and the pagefile agreed with `Win32_OperatingSystem`
  and `Win32_PageFileUsage` to the last digit shown. Capacity and available space agreed
  with `Win32_LogicalDisk`. Writing and reading a 2 GiB file gave 280 to 430 MiB/s and
  445 to 490 MiB/s on the page against 320 to 430 and 420 to 500 MiB/s of the
  `PhysicalDisk` counter read at about the same moments; a download gave 24.8 and
  26.9 MiB/s against 26.4 MiB/s. The CPU figure is busy time and is compared with
  *% Processor Time* (ADR-0005).
- A file that is read from the system's file cache causes no disk reads, and the page
  shows none: copying a file that was just written showed as writes only.
- When there is no disk total the page says "No total: the system may not say which
  disks are physical." It cannot say more: a snapshot does not carry why a total is
  missing, and has no list of the disks. For the network the page names the reason when
  the interfaces show it (an interface whose layer is unknown, or no physical adapter).
- A value of one processor that was skipped in a cycle, and a rate an interface does not
  have yet, are shown as an en dash, never as zero.
- The values of the single processors are drawn on the Canvas and are not available to a
  screen reader; it is given the total and the name of the grid.
- From about a hundred logical processors on, the grid is higher than the default window
  and the page scrolls. 64 fit the default window. In the smallest window 16 fit, in two
  rows of eight, and from 17 on the page scrolls.
- The minimum size of the window, set in milestone 3, did not hold when the window was
  moved to a monitor with another scale: on a monitor at 125% it could be made
  576 x 384 units small, which is 720 x 480 in pixels of that monitor. The cause is in
  how JavaFX 25 hands the size over: a stage tells its native window the minimum in
  pixels of the scale at that moment, when the window is created and when `minWidth` or
  `minHeight` changes, and not when the scale changes. Corrected in this milestone
  without native code: `MinimumWindowSize` hands the size over again whenever the output
  scale of the stage changes. Measured on the two monitors of the development machine
  (100% and 125%): the window now stops at 720 x 480 pixels on the first and at
  900 x 600 pixels on the second, which is 720 x 480 units on both, in both directions
  and repeatedly. Moved at its smallest size and not resized, the window arrives on the
  scaled monitor at exactly 900 x 600 pixels and on the unscaled one at 724 x 490: a
  few units larger than the minimum, never smaller. Crossing back and forth without
  resizing adds one to three pixels each time (724 x 490, 901 x 603, 725 x 492,
  903 x 606): JavaFX carries the frame of the monitor the window leaves into its size
  on the one it enters, which it did before the correction too (1116 x 739 pixels become
  1391 x 914, which is 1112.8 x 731.2 units). Not tried: dragging by hand, a scale
  changed in the system settings while Prism runs, a start on the scaled monitor, scales
  other than 125%. The correction rests on how JavaFX 25.0.4 behaves; a change of the
  JavaFX version repeats the measurement on two monitors.
- Measured again in the final verification of milestone 5.5, with the title bar dragged by
  a scripted mouse: the same numbers (900 x 600 pixels on the monitor at 125%, 720 x 480 on
  the one at 100%, three crossings without drift, a larger size chosen at 125% kept in
  units at 100%). The first pass of that verification reported a failure that was not
  one: a measuring process that is only system-DPI-aware is shown that window as 720 x 480
  virtualized pixels and captures it cut, and so does `GetWindowRect` from such a process.
  Measure with a per-monitor-DPI-aware process (`SetProcessDpiAwarenessContext`, which
  Windows PowerShell 5.1 accepts and PowerShell 7 does not), or read
  `DwmGetWindowAttribute(DWMWA_EXTENDED_FRAME_BOUNDS)`, which is physical for every caller.
- The snapshot harness does not draw the scroll bar of the page area, and it has no
  window focus. That the page scrolls, and that keys pressed by a person move through
  the entries and the tables, is to be checked by hand; the second was not done in this
  milestone, because it needs the keyboard of the person at the machine.

**Deferred.**
- A history per interface, and rates per disk: no requirement asks for them (MON-7).
- The reason for a missing total as a value of the snapshot, which would let the Disk
  section name it exactly, as the Network section does where it can.
- The title of a panel with two series stands 8 px higher than that of a panel with one
  value. This is the accepted metric panel of milestone 3; it shows only now that
  sections change in place. Left to the polish milestone.

**Not verified on Linux and macOS.** Nothing in this milestone has been run there.
- Neither total exists on those systems for now (milestone 1), so the Disk and the
  Network section show "Unavailable" with the note above, the histories stay empty, and
  only the file systems and the rates of the single interfaces are shown. This state is
  rendered with fake data only.
- What OSHI reports as swap on macOS, where the system grows and shrinks its swap files,
  has not been looked at.
- How the fonts, the text drawn on the Canvas and the tables render is not known. The
  width of a processor cell is measured from the font, so it should follow, but that was
  not seen.

**Reviewers.** `ui-critic`, `performance-check` skill (graph rendering).

**Quality gate.** Full, plus snapshots.

## 5. Processes page (read-only)

**Objective.** The process table as a first-class, read-only surface.

**Areas.** `ui`: Processes page, table model, details pane; `application`: on-demand
process details (PROC-5); `app`: the executor those reads run on. `platform` is unchanged:
path and command line are read by `ProcessSource.details`, added in milestone 2.

**Acceptance criteria.**
- Columns Name, PID, CPU, Memory, I/O, User, Threads, Priority, State (UI-2).
- Sorting by every column; text filter; selection and scroll position survive refreshes.
- Rows update in place by process identity; the list is not rebuilt each cycle.
- Long names truncate with a tooltip; numeric columns are right-aligned.
- Path and command line of the selected process load on demand, off the UI thread.

**Tests.** Table-model diffing by identity; sort and filter logic; details loading with
a vanished process; snapshots: empty, loading, about 1,000 rows, long names, 100% CPU,
very large memory values.

**Manual verification.** Sort, filter and scroll while the table refreshes; keep a row
selected while processes start and exit; operate the table by keyboard only.

**Decided while building.**
- No read of one process's current values was added to `ProcessSource`, although this
  plan named it here. Nothing in this milestone needs it: the details pane shows the
  current values of the row, which are those of the listing, so pane and table cannot
  disagree. ARCHITECTURE.md already had that read arrive with the limiter; it is now
  listed under milestone 9, which is the first to use it.
- What a column shows. Memory is the resident memory, the one memory value every system
  has. I/O is the read rate and the write rate added up, and exists only when both do. CPU
  is the share of the whole machine with one decimal (PROC-2, ADR-0005). Priority is the
  number the system gives; the five levels of CTL-6 come with milestone 7. State is what
  the system reports and an en dash where it reports none, which is every row on Windows
  (PROC-6). Nothing is called "disk".
- The values of PROC-1 that have no column are in the details pane: start time, parent
  PID, private memory, virtual size, and the read and the write rate each on its own.
- The table opens sorted by name, which changes least between listings. Without a column
  the rows are in the order of their PIDs. A process without a value in the sorted column
  comes last in either direction; equal values are ordered by PID and then start time, so
  the order is total and a listing never shuffles rows that did not change.
- The filter looks for its text in the name, the PID and the user of the rows the table
  holds. Case is ignored character by character, without a locale.
- The selected row stays selected through listings, sorts and a filter that keeps it.
  When its process exits, nothing is selected and the pane says that the process has
  exited; the selection never passes to a neighbor, and never to a process that took over
  the PID. A filter that takes the row out leaves nothing selected as well.
- Rows are kept per process identity and brought up to date in place; the list the table
  shows is changed by at most three announcements per listing (rows that left, rows that
  came, one permutation), and by none when nothing came, went or moved.
- Details are asked for once per selection. They are read on `srm-process-details`, one at
  a time; of the requests that wait, only the newest is kept. A result names the identity
  it was asked for and is shown only if that is the selected one by then.
- The shell holds its pages in a map, and a system snapshot or a listing is given only to
  the pages that show it. Only the newest listing is kept for the page that is not on
  screen.

**Behavior and limits, found while verifying.**
- Compared on Windows with the system's own figures, read by a script through UI
  Automation from the running application at about the same moments. The number of
  processes was that of `Get-Process` and of `Win32_Process` (287). For the 18 rows on
  screen, name, thread count and base priority were equal and the memory was within 1.4%
  of the working set. A process that kept one of 16 logical processors busy showed 6.0 to
  6.3% against 5.9 to 6.2% from `Win32_PerfFormattedData_PerfProc_Process` divided by 16;
  writing 1 MiB every 100 ms it showed 9.4 to 9.9 MiB/s of I/O against 7.2 to 10.9 MiB/s
  of that counter. Its path, its command line of 645 characters, its start time and its
  parent PID were those of `Win32_Process`; private memory 113.6 against 114.1 MiB;
  virtual size 2.1 TiB in both.
- The details of a process of the same user were on screen within about 0.4 s of the
  selection, the time of the script's own reads included. For a process this user may not
  open, path and command line say "Not available: the system does not report it, or access
  was denied."; a snapshot cannot tell the two apart.
- The row "Idle" (PID 0) has the idle share of the machine as its CPU, because that is the
  processor time the system accounts to it. Sorted by CPU, largest first, it is the first
  row on an idle machine.
- When the selected process was ended, the pane said that it had exited 1.95 s later, with
  the next listing, and no row was selected.
- A command line can hold line breaks, as that of a script handed to a shell does. Shown
  as they were, they made the one line of the pane seven and took their height from the
  table. Every control character is now shown as a blank.
- The table keeps its scroll offset through listings. A process that starts or exits
  above the rows on screen moves those rows by one: the offset stays, the content shifts.
- Sorting by a click on a header, scrolling by the wheel, the arrow, Page, Home and End
  keys, Tab and Shift+Tab between navigation, filter and table, typing in the filter,
  Escape, and the right and left arrow keys in the smallest window were done with real
  mouse and keyboard events on the running application, sent by a script, not by a person.
  The rows stayed in order through the listings, and the row selected by the click stayed
  selected while it moved. The window did not go below 720 x 480.
- Sorting without a mouse goes through the context menu of the table (Shift+F10 or the
  Menu key): "Sort by" with the nine columns, then "Ascending" and "Descending". It sets
  the same sort order a header click sets, so both go through the one sort policy. Added
  in a correction pass after the first review found that a header takes a click and
  nothing else sorted; the menu is the place the actions of milestone 7 join. Done with
  real keys on Windows, sent by a script: the Menu key opens the menu with no item focused,
  Down focuses "Sort by", Right opens the columns on "Name", Down moves, Enter chooses;
  Down and Up from the top reach "Ascending" and "Descending". A synthetic Shift+F10 opened
  the menu and, at the same time, the window's system menu of Windows, which F10 alone
  opens; whether a person's Shift+F10 does the same was not checked. The row selected
  before the sorts stayed selected through them. The accent frame of the focused table, read
  from the pixels of the captures, was there after Tab had reached the table and still there
  after the menu had closed.
- The stock sort arrow stands at the right end of its header. In a column of numbers the
  header text therefore stands 12 px left of its values while that column is sorted.
- For a screen reader, a cell of a number with its unit carries both as one text, and the
  filter has a name through a label that is not drawn. Read back through UI Automation;
  no screen reader was run.
- With an accessibility client attached (the scripts of this verification read the table
  through UI Automation), the table created a row now and then while the selected row moved
  out of view with every listing: 18 to 27 rows in ten minutes. Reproduced in a test window
  by asking the table what such a client asks after a change of the selection; without those
  questions the same window keeps its rows. JavaFX answers a question about a row that is not
  on screen by taking or creating a row (`VirtualFlow.getPrivateCell`); in the test window the rows stopped at about twice those on
  screen. Left as a known limit; the figures are in PERFORMANCE.md. The same table watched
  for three minutes in an instance no accessibility client had ever touched, sorted by CPU
  with the selected row out of view, kept its rows (25 throughout, 90 listings).
- Not looked at in the running application: tooltips and hover states, which need a
  pointer that rests. That a cut name has a tooltip and a name that fits has none is
  asserted in a scene.

**Deferred.**
- Actions, their context menu and the mark on Managed Processes (milestones 7 and 9);
  type-to-find, which the specification does not ask for.
- A sort mark of Prism's own that stands beside the header text.
- Tooltips in the tokens of the guide. They have the stock look on every page, since
  milestone 3; a popup does not take the theme of the window, so this belongs with theme
  switching (milestone 13).
- On the tables of the Performance page a selected row without the focus is as hard to
  find as it was here, and its muted units are just below the contrast the guide asks for
  in the dark theme. The stylesheet they share was not changed.
- Copying the path or the command line.

**Not verified on Linux and macOS.** Nothing in this milestone has been run there.
- The State column has values there. It is rendered with fake data only.
- The priority is a number of another scale, and negative numbers occur.
- The I/O rates of other users' processes are absent on Linux, and private memory on
  macOS; both show as an en dash. On macOS an unprivileged user sees only their own
  processes, so the count is smaller than the system's.
- The fixed widths of the columns and of the details were measured for Segoe UI. A wider
  system font could cut a caption or a number.
- What a screen reader is given was read back on Windows only.

**Reviewers.** `ui-critic`, `performance-check` skill (table updates), `test-reviewer`.

**Quality gate.** Full, plus snapshots.

## 5.5. Visual foundation

**Objective.** Make the application look intentionally designed without changing what it
shows: one typeface, one icon family, a theme that follows the operating system, page
headers, panels where grouping needs them, a rational process table, a real inspector, and
restrained motion ([UI_GUIDE.md](UI_GUIDE.md), "Quiet Precision").

**Areas.**
- `ui`: the design tokens and the type roles (`app.css`, `fonts-fallback.css`); the bundled
  typeface (`Typefaces`, [ADR-0008](adr/0008-bundled-typeface-and-icons.md)); the icons
  (`ui.icon`); the page header (`ui.control`); the facts panel; the sidebar with icons and
  a page transition; the segmented selector of the Performance page with its sliding
  surface; the metric panels on their surfaces with an unframed graph; the process table
  with widths measured from the font (`ColumnWidths`), an adaptive user column, a ceiling
  for the name column, a sort mark beside the header text and muted missing values; the
  inspector; the durations of the motion and the reduced-motion rule (`Motion`).
- `domain`: `ThemeMode`.
- `app`: `ThemeController` ([ADR-0009](adr/0009-theme-follows-platform-preferences.md));
  the typeface is loaded in `App.init()`.
- Tests: the snapshot harness renders a theme through `Scene.Preferences` and asks for
  reduced motion; scene tests for the theme controller, the typeface, the icons, the sort
  mark, the column widths and the motion.
- Documents: this file, [UI_GUIDE.md](UI_GUIDE.md) (rewritten), [PRODUCT_SPEC.md](PRODUCT_SPEC.md)
  (UI-7), [TESTING.md](TESTING.md), [PERFORMANCE.md](PERFORMANCE.md), [ARCHITECTURE.md](ARCHITECTURE.md),
  `THIRD-PARTY-NOTICES.md`.

**Acceptance criteria.**
- Every page keeps its information architecture and its monitoring semantics; only
  presentation changes. Sorting, filtering, selection, keyboard operation and in-place row
  updates of milestone 5 are unchanged and their tests pass.
- The window follows the system appearance and a change of it while running; a forced
  light or dark mode is kept whatever the system prefers; a popup takes the scheme of the
  window.
- The typeface loads once; without it the application starts on the system font and logs why.
- The process table needs no horizontal scrolling in the default window, keeps its first
  four columns whole in the smallest window, and in a wide window stops the name column at
  its ceiling and the user column at its own.
- The idle cost, the heap, the threads, the scene size and the update costs of milestone 5
  are not worse beyond noise; nothing animates on a timer or on data.

**Tests.** `ThemeControllerSceneTest`, `TypefacesTest`, `IconTest`, `MotionSceneTest`,
`SortIndicatorSceneTest`, `ProcessTableWidthTest`, the extended `SectionTabsTest`,
`MainWindowTest`, `StylesheetsTest` and `ArchitectureTest`; the snapshot suites of the three
pages in both themes at 1100 x 700, 720 x 480 and 704 x 441, with the wide windows, plus
`processes-selected-filter-focused`.

**Manual verification.** Run the app: hover, tooltips, the context menu and the transitions;
change the system appearance in the settings of the operating system while Prism runs and
see the window follow; the minimum window on a monitor at 100 % and one at 125 %.

**Decided while building.**
- JavaFX registers the Medium and the SemiBold face under the family names "IBM Plex Sans
  Medm" and "IBM Plex Sans SmBld" (measured); the stylesheets name them, and a face
  registered under any other name counts as not loaded, so a different registration on
  another system falls back instead of rendering a wrong face.
- The scene of a popup does not take the color scheme forced on the scene of its owner
  (measured with JavaFX 25.0.4); the theme controller sets the scheme on every window that
  opens. The media query resolves in a scene that is in no window, which the harness needs.
- The selected row carries a 2 px accent bar at its left edge in both selection states,
  because the inactive selection surface alone cannot be told from the hover surface.
- The selected destination of the navigation keeps its name in `-srm-text`: the accent is
  never text. Its icon is the accent, its name SemiBold.
- The panels cost height and width. At 704 x 441 the Overview fits with 66 px graphs, the
  Processes page shows three rows above the inspector, and the CPU section fits with one row
  of processor cells; a second row scrolls, where 16 processors fitted in milestone 4.
- Widths are measured from the font, so the table is not laid out for one machine: the
  expected values on Windows with Plex Sans are PID 72, CPU 72, Memory 88, I/O 100, Threads
  64, Priority 60, State 84; the name column takes 160 to 520 px, the user column 64 to
  160 px as its names need. The parts the units stand in are 16, 24 and 36 px: "%" is
  12.1 px in Plex, so a 12 px part cut it to an ellipsis, which a test now counts. With
  these widths the fifth column misses the smallest window by 3 px, so the first four are
  the ones promised whole.
- A page is given its height by hand (the greater of the viewport and what it needs), not by
  the scroll pane fitting it to the viewport: fitted, a CPU section with many processors
  was cut instead of scrolled.
- No commit was made while building; the milestone is committed once after external
  acceptance.

**Not verified on Linux and macOS.** Nothing in this milestone has been run there. The family
names the toolkit registers the faces under, the platform preferences for the color scheme
and for reduced motion, and how the icons and the 1.5 px lines rasterize are expected to be
the same and are unverified.

**Reviewers.** `ui-critic`, `architecture-reviewer`, `test-reviewer`, `performance-check` skill.

**Quality gate.** Full, plus snapshots.

## 6. Demo workload

**Objective.** A safe target for every destructive demonstration that follows.

**Areas.** A new isolated package `prism.srm.demo` with its own `main`, using only the
JDK (including `com.sun.management` for the physical memory size and the CPU time of its
own process). `ArchitectureTest` has a rule for it in both directions: it depends on no
other `prism.srm` package and none depends on it, and it uses nothing but `java..` and
`com.sun.management..`. The build packages it as a jar of its own,
`target/prism-<version>-demo.jar`, through a second execution of the
jar plugin; no new plugin, no new module. [TESTING.md](TESTING.md) documents its use.

**Acceptance criteria.** DEMO-1 to DEMO-3: controllable CPU load and memory use from
command-line arguments; the memory bound and the maximum duration cannot be exceeded by
any argument; it reports allocation failures and its own progress.

**Tests.** The parsing of every option and its bounds (`ArgumentParserTest`,
`QuantitiesTest`, with the default locale set to `tr-TR` for one case); the cap
(`MemoryBudgetTest`: 1 GiB wins on a large machine, a quarter of physical memory on a
small one, no cap when the size is unknown); the split of the share into workers and its
whole-machine semantics (`CpuPlanTest`); the bounded debt of the duty cycle
(`DutyCycleTest`, a suspension in the sleep and one in the busy part); the allocation in
chunks, the cap inside the allocator and the failure path with a fake allocator that
refuses a chunk (`MemoryWorkloadTest`); a worker interrupted in its sleep (`CpuWorkerTest`);
runs of 600 ms with their lines, their reason and no worker thread left, a worker with a
fractional duty, the stop before the start and the stop while the run waits with a busy
worker, which must end within the 2 s of the shutdown hook, the allocation failure, and the
arithmetic of the share the process reports for itself (`DemoRunTest`); the entry point as
a child JVM, with the JDK option variables removed from its environment: exit codes 0 and 2
and the help text (`DemoWorkloadProcessTest`). 93 tests, none of which takes real memory
beyond a 2 MiB smoke test or runs for more than a few seconds.

**Manual verification.** Run it at 25% CPU and 256 MiB, each alone and both together, and
watch it in Prism's process table next to the system's counters; confirm it exits by
itself, refuses what is above a bound, and reports Ctrl+C.

**Decided while building.**
- The command line: `--cpu <percent of the whole machine>`, `--memory <n>MiB|<n>GiB`,
  `--duration <n>s|<n>m`, `--workers <n>`, `--help`. Percentages take decimals (one logical
  processor of sixteen is `6.25`); `--workers` exists for the limiter demonstrations of
  milestone 9, which need one busy thread and several. There is no interactive mode and no
  third-party library.
- A request above a bound is refused with a message naming the bound (exit code 2), never
  clamped: the process does what its command line says, and the bound is seen. The 30-minute
  maximum and the cap are checked by the parser and again by the record and the allocator,
  so no argument passes them. A duration of 0 is an error: there is no unlimited mode.
- The share of the machine is carried by the fewest threads that can carry it, each busy
  for a fraction of a 100 ms period, so `workers * duty / processors` is the share. The
  busy part owed after a period that ran long is carried into the next period, but by at
  most one period: a target that was suspended for a minute (milestone 7) or held back by a
  limit (milestone 9) is back at its share after 200 ms instead of burning flat out to repay
  what it missed.
- The memory is native memory from a confined `Arena`, in 16 MiB chunks, so the amount
  does not depend on the heap size of the JVM (a jar manifest cannot set one, and 1 GiB of
  `byte[]` would not fit the default heap of a small machine), and it is released by
  `close()` on the thread that created it. The one place that catches an `Error` in the
  project is the chunk allocation: an `OutOfMemoryError` there is the system refusing a
  native allocation, which is what a memory limit on the process looks like from the inside
  (DEMO-3); it is reported with what was held, everything is released and the run ends with
  exit code 1. Nothing goes on as if the memory were there.
- Every page is written with a different value. With the same byte on every page, the
  private working set of a 256 MiB workload fell from 285 to 23 MiB about 40 seconds into a
  run while the working set still read 302 MiB, in Prism and in the system's counter alike:
  Windows combines identical pages of a process into one shared page, and a demonstration of
  memory pressure would have demonstrated nothing. With a number on every page the private
  working set stayed at 285 MiB for 150 s.
- Output is one `event=...` line per event, `key=value` pairs in ASCII, a status line every
  5 s with the share of the machine the process measures for itself (`cpu-self`), and an end
  line with the reason (`completed`, `allocation-failed`, `interrupted`) and the exit code.
  The shutdown hook waits up to 2 s for the end line after Ctrl+C; `main` then returns
  without `System.exit`, because the JVM holds its shutdown lock while the hook runs, and the
  exit code is the JVM's for the signal (130 after Ctrl+C, 143 after a termination request),
  which is why that end line says `exit=signal` instead of a number of its own.
- Whatever ends the run, also a failure nothing expected, the `finally` of the run stops the
  workers and releases the memory: the worker threads are not daemon threads, and a run that
  left them going would keep the JVM alive and busy, against DEMO-2.
- `MetricPanel` (milestone 5.5) held a private `VBox` subclass whose `@Override
  computeBaselineOffset()` overrides nothing in JavaFX 25.0.4 and does not compile with
  it; the method was never called, so the panels as accepted never had its effect. It was
  removed, which changes nothing on screen; a baseline of its own for the two-series panel
  stays a deferred polish item (milestone 4, "Deferred").

**Behavior and limits, found while verifying.** On Windows 11, 16 logical processors,
measured against `Win32_PerfFormattedData_PerfProc_Process` every 2 s and against Prism's
process table read through UI Automation, after 30 s of settling:
- `--cpu 25`: the counter gave 24.93% of the machine (23.75 to 25.94 over 21 samples),
  Prism's row 24.94% (24.70 to 25.10), the process's own figure 24.8 to 25.1. With
  `--memory 256MiB` added: 24.89%, 24.95% and 24.5 to 25.0. The tolerance for a
  demonstration is therefore about one point on a 2-second sample and a fraction of a point
  on a minute, on an otherwise idle machine; under other load the share of a process is still
  its own busy time and is not reduced by the limiter arithmetic of a later milestone.
- `--memory 256MiB`: the working set rose to 302.6 MiB (Prism's Memory column the same), the
  private working set to 284.3 MiB, against 46 MiB and 28 MiB for a run without memory: the
  256 MiB plus the JVM's own footprint. The allocation of 16 chunks took about 60 ms.
- Prism showed the process as `java`, PID, user, 24 to 27 threads, base priority 8, no state;
  the inspector showed the start time to the second, the parent PID, the path of `java.exe`
  and the command line with the jar and the arguments, all equal to `Win32_Process`. No
  special monitoring path exists for it.
- A 5-second run exited by itself 5.18 s after it was started, exit code 0; `--memory 2GiB`,
  `--memory 9GiB`, `--duration 31m`, `--duration 0s`, `--cpu 101` and `--cpu 25 --workers 2`
  each ended with exit code 2 and a one-line reason, without a process that lived.
- Ctrl+C: the process printed `event=end reason=interrupted` and exited with 130 within
  19 ms of the console event. The event has to be a real one: a console control event
  generated from a process whose "ignore Ctrl+C" flag was inherited through the scripting
  chain (a process group created by the tools) never reaches the JVM, and a key press sent to
  a Windows Terminal window lands in whichever tab has the focus. The verification cleared
  the flag and started the workload on a console it shared.
- Prism's own cost while the workload ran: 0.19% of the machine in the median of the samples
  next to the 25% of the workload, with single samples of 1 to 6% at the moments the script
  read the table through UI Automation and selected the row; the same order as its idle
  cost (PERFORMANCE.md). The workload adds nothing to Prism; it is one more process in the
  listing.
- The Windows timer: `Thread.sleep` overran by about 1 ms on this machine (median 2.0 ms for
  a 1 ms sleep, 100.8 for 100 ms), not by the 15.6 ms quantum; the bounded debt corrects what
  there is and is kept for machines where there is more.

**Deferred.**
- A memory ramp over time (`--ramp`), for the OOM Guard demonstration of milestone 11; several
  instances or a few starts do the same until then.
- Going on after a refused allocation with what was allocated; the run ends instead.
- A name of its own in the process list (`prism-demo` instead of `java`), which needs a
  launcher of its own (jpackage, milestone 13).

**Not verified on Linux and macOS.** Nothing in this milestone has been run there. Expected
differences: the JVM may report fewer processors inside a cgroup CPU quota, which changes the
split and not the share; a refused allocation cannot be reported under a cgroup memory limit,
because the kernel overcommits and the OOM killer ends the process without a line (milestone
12 records what happens); page combining is a Windows mechanism, and the per-page values cost
nothing elsewhere.

**Reviewers.** `architecture-reviewer` (the new package rule), `test-reviewer`.

**Quality gate.** Full.

## 7. Process control on Windows

**Objective.** End Task, Force Kill, Suspend, Resume and Change Priority on Windows,
with explicit results, protection rules, capability-driven UI, and the Logs page.

**Areas.**
- `domain`: operation result, capability level and mechanism, priority level.
- `platform`: `ProcessController` with its capability report and the facts that mark a
  process as protected; the portable `ProcessHandle` implementation of Force Kill.
- `platform.windows`: close request, per-thread suspend and resume, priority class,
  critical-process detection, with the JNA mappings that `jna-platform` lacks.
- `application`: the control service (protection rules, result messages, activity log)
  and the suspension coordinator, for now with the "user" reason only.
- `ui`: actions and confirmations on the Processes page, enabled and explained from the
  capability report with no operating-system check; the Logs page.

**Acceptance criteria.**
- CTL-1 to CTL-9 on Windows, each action returning an operation result.
- The UI enables, disables and explains actions from the capability report alone
  (CAP-1 to CAP-3, CAP-5); a fake capability report with different levels changes the
  UI without code changes.
- Prism's own process and protected processes are refused before any native call
  (SAFE-2, SAFE-3).
- An elevated or system process yields `ACCESS_DENIED` with a plain message; nothing
  crashes (SAFE-4, CAP-4).
- Processes suspended through Prism show as "Suspended by Prism" (PROC-6).
- Every native handle is closed on every path.
- Each native function, constant and structure cites its Microsoft Learn page.
- Actions and results appear in the Logs page (UI-6).

**Tests.** Protection rules; result-to-message mapping; the control service against a
fake controller (process vanished, denied, unsupported); the suspension coordinator
(suspend, resume, process exit, PID reuse); view models driven by fake capability
reports.

**Manual verification.** Each action on the demo workload; End Task on a windowed and a
windowless process; an action on a system process; handle count of Prism stable after
many operations.

**Decided while building.**
- Every action starts at one place, `WindowsProcessAccess.open`: `OpenProcess` with the
  rights the action needs plus `PROCESS_QUERY_LIMITED_INFORMATION`, then
  `GetExitCodeProcess` (anything but `STILL_ACTIVE` is an exit) and `GetProcessTimes`,
  whose creation time, converted as OSHI and the JDK convert it, must equal the start time
  of the listed identity to the millisecond. The handle stays open for the whole action,
  so the PID cannot pass to another process meanwhile. `ERROR_ACCESS_DENIED` is
  `ACCESS_DENIED`; another error is `PROCESS_NOT_FOUND` only when a Toolhelp process
  snapshot confirms the PID is gone, and otherwise `NATIVE_ERROR` with the code kept.
- End Task posts `WM_CLOSE` to the visible, unowned top-level windows of the process,
  tool windows only when nothing else is there. One accepted post is `SUCCESS`, reported
  as "Close request sent; the application may ignore it". No window is `UNSUPPORTED` with
  a pointer to Force kill; every post refused with 5 (UIPI) is `ACCESS_DENIED`. Prism
  never waits for the exit and never falls back to termination.
- Force Kill opens the process with `PROCESS_TERMINATE` itself first, so a refusal is a
  clean `ACCESS_DENIED`, and then calls `ProcessHandle.destroyForcibly` while its handle
  pins the PID. Success is "Force termination requested"; the exit is shown by the next
  listing, never claimed.
- Suspend lists the threads of the process with a system-wide Toolhelp snapshot, opens
  each with `THREAD_SUSPEND_RESUME | THREAD_QUERY_LIMITED_INFORMATION`, checks with
  `GetProcessIdOfThread` that it still belongs to the process, suspends it and keeps the
  handle. Up to three passes catch threads created meanwhile; it is still not atomic,
  hence `NATIVE_SOFT`, and a success after a third pass that still found new threads says so.
  Only held threads are skipped by the next pass: an ID passed over because OpenThread
  refused it with 87 (taken as "ended", a code OpenThread does not document) is tried again
  if the next listing still names it, and fails the suspension if it is refused again. A
  process that exits while it is being suspended is `PROCESS_NOT_FOUND`, and a
  `RuntimeException` part way, which nothing is expected to throw, is rolled back before it
  goes on. Prism owns exactly one increment per held handle and resumes each
  at most once. When a suspension fails half way, everything taken is given back; a
  `ResumeThread` that fails on a thread that still runs (`GetExitCodeThread`) keeps that
  thread's handle and ownership, the coordinator records the process as incomplete, and
  Resume tries again. A thread or process that has ended releases its share without a
  call.
- `SuspensionCoordinator` is the only caller of suspend and resume. It holds the reasons
  per identity (the user's only, for now), does not stack a second suspension, and
  publishes the held identities, which the listing shows as "Suspended by Prism". A held
  process missing from a listing is not taken for gone: one check per identity at a
  time asks `liveness` on the control thread, and only `PROCESS_NOT_FOUND` forgets it.
  After three undecided checks in a row the log says so and the suspension is kept.
- Change Priority sets one of the five classes (no Realtime) with `SetPriorityClass` and
  reads it back with `GetPriorityClass`; a different class read back is `NATIVE_ERROR`.
  The table shows the base priority of the next listing (4, 6, 8, 10, 13).
- Protection, before any native call that acts: Prism's own PID; PID 0 and 4; a process
  `IsProcessCritical` reports as critical; an executable with the name of a well-known
  system process. When criticality cannot be read, the process is not treated as
  protected; the action then meets the system's own access check.
- A failure that the system reports without a code in the return value (GetExitCodeProcess,
  GetProcessTimes, GetProcessIdOfThread, GetPriorityClass, EnumWindows) keeps its
  `GetLastError` code in the result. A failed EnumWindows is `NATIVE_ERROR`, not "no window".
- One action at a time per process identity: a second request while one is in flight is
  answered at once, not queued, and the inspector disables every action of that process
  meanwhile. All actions run on one thread, `srm-process-control`, behind a queue of 32;
  a full queue is answered with "Too many actions are waiting" and nothing is done.
- Shutdown: refuse new actions, let the action in flight finish (within the 5-second
  bound of the sequence), release the automatic reasons (none yet), keep what the user
  suspended, close the handles of those suspensions without resuming, log what stays
  suspended.
- A suspension Prism holds stays releasable when process monitoring ends with a failure.
  From then on the control service takes Resume only; End Task, Force Kill, Suspend and
  Change Priority, which need the process as it is now, are answered "Prism is not
  monitoring" at once, also one that was already queued. Resume goes through the
  coordinator and the suspension Prism kept, with no listing. The coordinator hands over the
  processes it holds, with their names, each time that changes; once monitoring has stopped,
  the Processes page shows them under the notice, each with a plain Resume button, the
  result of the last Resume, and the advice to resume them before restarting Prism, because a
  restart leaves them suspended. A process that had exited when it is resumed is
  `PROCESS_NOT_FOUND`, "has exited; Prism no longer holds it suspended", and its handles are
  closed without a call.
- The UI knows only the capability report. An `UNAVAILABLE` action stays in its place,
  disabled, with the reason on the page; Linux and macOS get that report until
  milestone 8. End Task and Force Kill ask first, with Cancel as the default button.
- The Logs page lists this session's activity newest first, with a filter and a button
  that opens the log folder; earlier sessions are in the files there.

**Behavior and limits, found while verifying.** On Windows 11 Pro 26200, 16 logical
processors, a standard (not elevated) user, through a second Prism instance driven by UI
Automation and real mouse input, against the demo workload; the system's view read with
`Get-Process` and `Win32_Thread`:
- Suspend of a 27-thread workload at 25%: all 27 threads in `ThreadWaitReason` 5
  (suspended), its CPU 0% over 4 s, the row "Suspended by Prism" at 0.0%, Suspend
  disabled and Resume enabled. Resume: 0 threads suspended, 24.88% over the next 4 s.
  Prism's handle count rose from 1174 to 1198 while the threads were held and was 1172
  after Resume.
- The five levels read back by the system as `Idle`/4, `BelowNormal`/6, `Normal`/8,
  `AboveNormal`/10 and `High`/13, and the Priority column showed the same numbers with
  the next listing. High needs no privilege for a standard user here.
- Force kill: the workload exited 10 ms after the confirmation, exit code 1; the row was
  gone with the next listing and the inspector said the PID has exited.
- End task on the windowless workload: `UNSUPPORTED`, the workload kept running. On a
  plain AWT window: the application ran its own close handler and exited with 0, 185 ms
  after the confirmation.
- Cancel in the confirmation did nothing. A close request to the main window while a
  confirmation is open is ignored, as Windows does for a window that a modal dialog
  disabled; the confirmation stays and is answered first.
- A workload killed from outside while suspended: the next listing missed it, the check
  found it gone, the log says it "exited while Prism held it suspended", and Prism's
  handle count fell back by its 24 thread handles (1204 to 1179).
- A workload killed between the listing and the click: `PROCESS_NOT_FOUND`, "is no
  longer running".
- Priority on Prism itself, on System (PID 4) and on Idle (PID 0): refused by Prism with
  its own message, logged as a warning, before any call to the system.
- A 32-bit process (`SysWOW64\ping.exe`, started for the check): all 7 threads suspended
  through `SuspendThread` from 64-bit Prism (`Win32_Thread` wait reason 5), no output for the
  4 s of the suspension, 0 suspended and one line a second again after Resume. This closes
  the WOW64 point of ADR-0002 for suspension.
- Access denied (SAFE-4, SAFE-6) was verified without acting on any process that is not
  Prism's: `OpenProcess` on PID 4 with `PROCESS_TERMINATE` and with
  `PROCESS_SET_INFORMATION`, and `OpenThread` with `THREAD_SUSPEND_RESUME` on one of its
  threads, each fail with 5, which the identity check reports as `ACCESS_DENIED`
  (`JnaWin32Test`). For a standard user even `PROCESS_QUERY_LIMITED_INFORMATION` is
  refused with 5 on `wininit`, `csrss`, `smss`, `services`, `lsass`, `winlogon` and
  `svchost`, so every action on them stops at the first `OpenProcess` with
  `ACCESS_DENIED` and nothing else is called. It also means `IsProcessCritical` and the
  name list cannot be read for them unless Prism runs elevated; they are a second line
  that matters there. An action through the UI that ends in `ACCESS_DENIED` from the
  system was not carried out live: no elevated process of the user's was used as a
  target.
- `IsProcessCritical` answered `false` for this JVM and for `explorer`; a `true` answer
  was not observed (it needs a readable critical process, which means an elevated Prism).
- The creation time from `GetProcessTimes` equalled the listed start time for every
  process this user could open (`JnaWin32Test`), closing that point of ADR-0002.
- Prism's own CPU over the whole 153-second run, with every action and the UI
  Automation reads: 1.1% of the machine; the listings continued every 2 s, and the UI
  answered every read within about 100 ms. Closing the window ended Prism in 380 ms.
- These figures are from the last run, on the build that was reviewed and corrected; an
  earlier run before the review corrections gave the same results.
- Recovery after process monitoring failed, through a harness outside the repository:
  Prism's `MonitoringRuntime` with the real Windows controller, coordinator and process
  listing, the process source wrapped to throw an `Error` on demand, as a fatal error in it
  would. The UI cannot be made to fail that way without a seam in the product, so the page
  itself was checked by its tests and snapshots, not live. The workload at 25%: Suspend left
  27 of 27 threads suspended and 0.00% CPU; after the listing failed, End Task, Force Kill,
  Suspend and Change Priority were answered "Prism is not monitoring; nothing was done", and
  the workload stayed suspended; Resume answered "was resumed", 0 threads suspended and
  24.89% CPU again; a second Resume "Prism does not hold ... suspended for you". The held
  list held the workload until the Resume and was empty after it. The harness's handle count
  was 635 before, 629 to 635 over five suspend and resume cycles, 660 while suspended and 627
  after the Resume.

**Deferred.**
- The current level marked in the Priority menu, with the level taken from the listing; a
  result line that keeps the disabled reason apart from the last result; motion on the result
  line. Done in milestone 7.5.
- Multiple selection; actions on a process tree.
- A journal of suspensions for a crash (LIFE-6, milestone 9). Until then a suspension
  the user made outlives a crash of Prism, which is also the policy after a controlled
  shutdown.
- End Task for packaged (UWP/WinUI) applications, whose frame windows belong to
  `ApplicationFrameHost`: the target has no window of its own and gets `UNSUPPORTED`.
- Force Kill through `ProcessHandle.destroyForcibly`, as decided, reopens the process by PID
  with more rights than `PROCESS_TERMINATE`: the JDK asks for `PROCESS_DUP_HANDLE` as well
  (`ProcessHandleImpl_win.c`). Where Prism may terminate but not duplicate handles, the JDK
  finds no process and the result is `NATIVE_ERROR` with a detail that says so. Terminating
  through the handle Prism already holds (`TerminateProcess`) would need only the right the
  action needs; it changes the decided mechanism and is left for a decision.
- An exit code of 259 (`STILL_ACTIVE`) is taken as "still running", as GetExitCodeProcess and
  GetExitCodeThread warn it may not be. Every decision this affects errs on the safe side: a
  process that exited with 259 is not forgotten while suspended, and a thread of it keeps
  its handle and Prism's increment until shutdown. `WaitForSingleObject` would decide it,
  but needs `SYNCHRONIZE` on every open, which may refuse processes that are opened today.
- The protection facts are read through an open of their own, before the open of the action;
  both check the identity, so the cost is one more `OpenProcess` per action.
- End Task on `explorer` posts `WM_CLOSE` to its visible windows, which may bring up the
  "Shut Down Windows" dialog of the desktop; not tried (SAFE-6).
- At the smallest window the table shows two rows, not three: three rows and the actions in
  the inspector do not fit 480 px, and UI-8 keeps the page from scrolling there
  (UI_GUIDE.md, window behavior). The widths of the Time and Severity columns of the Logs
  page are fixed in code, not measured from the typeface.

**Not verified on Linux and macOS.** Nothing in this milestone acts there. Both get the
`UNAVAILABLE` report, and the UI shows every action disabled with the reason; that path
is covered by tests with a fake report, not run on those systems.

**Reviewers.** `native-api-reviewer` (required), `ui-critic`, `test-reviewer`.
`architecture-reviewer` as well. Their findings and what was done:
- `.gitignore` ignored every `logs/` directory, the source packages `ui.logs` too: fixed, they
  would not have been committed.
- Suspension ownership: `ArchitectureTest` now holds that only the coordinator suspends,
  resumes or closes a suspension (CTL-9); the listing is passed through the coordinator, which
  returns at once while nothing is held; the activity log tells its listener under its lock,
  so the UI never gets an older log after a newer one, and a failing listener reaches no one.
- Native: the points above under "Decided while building" (codes kept, EnumWindows checked,
  thread IDs tried again, exceptions rolled back, a closed suspension answered, the WOW64
  check, citations of the PID and TID reuse rule to Microsoft's own blog where the reference
  documentation is silent).
- Tests: the cases the reviewer found unguarded have tests now (an incomplete suspension
  whose retry fails, unreadable exit codes, the queue of 32 overflowing, the shutdown bound
  with an action stuck inside the coordinator, undecided checks, the listing passes, failure
  codes on the real system); the fake Win32 fails a test on a handle closed twice and models
  rights a process refuses; the runtime gets Prism's PID injected, so no test depends on the
  PID of the JVM that runs it; the tests that start child processes have a time bound.
- UI: the reason of one unavailable action is on the page; a refusal is in the warning color
  and only a failure of the system in the error color, as in the log; the result goes when
  the filter hides its process; the default button of a confirmation has the accent border;
  the Priority menu button has the Lucide chevron; the Logs filter has its clear button and
  a message has its tooltip only while it is cut.
- Left as they are: `levelOf` was removed with the menu mark it was for (deferred); the
  labels of actions and levels stay in `ControlMessages`.

**Quality gate.** Full, plus snapshots.

## 7.5. Interaction polish of process control

**Objective.** Make the five actions of milestone 7 easier to read before and after they are
used, complete from the keyboard and sound in the smallest window, without a new system
function and without changing what an action does. The deferred items of milestone 7 and the
small interface debts its acceptance noted.

**Areas.**
- `domain`: `ControlProfile` (how each action is provided, and the level each priority number of
  the listing stands for); `ProcessControlResult` gains an explanation beside its message.
- `platform`: `ProcessController.listedPriorities()`, a fixed table; on Windows the base
  priorities 4, 6, 8, 10 and 13 of the five classes. No Win32 call changed or was added.
- `application`: `ControlMessages` gives every result as a sentence and an explanation, and the
  text of an action on its way; the activity log writes the two as one text.
- `app`: the profile takes the place of the capabilities on their way to the page.
- `ui`: the action model and bar, the shared result line, the priority items, the state in the
  inspector heading, the confirmation, the Logs columns; `ui.control.TextWidths`.

**Acceptance criteria.**
- The Priority menu, in the inspector and in the context menu, marks the level the last listing
  shows, and none for a number that stands for no level; the mark never moves on a request
  alone. Five levels, no Realtime.
- The line beside the actions keeps a result (a sentence in the tone of the result and a muted
  explanation) apart from a reason (muted); an action on its way names itself. Native codes stay
  in the log.
- A new result fades in over 120 ms, nothing moves, and nothing does with reduced motion.
- The actions stand in the order of their consequences, End task and Force kill set apart.
- The keyboard reaches every action, keeps a place to be after an action, and declines a
  confirmation by default.
- A confirmation names the process and its PID with any name length.
- The Logs columns fit their contents in whatever typeface the table draws with.
- The smallest window still shows the page without scrolling it (UI-8).

**Decided while building.**
- The level comes from the listing through the profile: the page reads no priority of its own
  and knows no system's numbers. A change of priority is shown as asked in the result ("Priority
  of java (PID n) set to High. The table shows it with the next listing.") while the mark stays
  where the listing has it, until a listing shows the new number. Choosing the marked level sends
  nothing. The control's accessible text and tooltip carry the current level ("Priority, current:
  Normal"; "unknown (9)" for a number that stands for none).
- A result stays while its process stays selected, through listings and when the process exits
  and leaves the table; it goes with another selection, a filter that hides the process, a new
  action on it, and when monitoring stops (the line then says why, also with nothing selected).
  A result that comes after the selection moved is not shown under the other process. Nothing
  goes on a timer.
- Order: Suspend, Resume, Priority, then 16 px apart End task… and Force kill…; the context menu
  the same, with a separator. Force kill has no color of its own.
- The focus: when the action that had it is disabled because the request is on its way, the
  focus goes to the table; after a declined confirmation it is back on the button that asked.
- "Suspended by Prism" stands after the PID in the inspector heading while the listing shows it,
  the words and the source of the State column.
- The confirmation asks its question without the name ("Force this process to quit?") and names
  the process on a line of its own: the name cut with an ellipsis where it is long (the data is
  never cut; a tooltip and the accessible text carry it whole) and "PID n" beside it, never cut.
  It takes the font fallback of the window it belongs to.
- Logs: Time and Severity are measured from the table's typeface on the 4 px grid; Ctrl+C copies
  the selected entry whole.
- The smallest window keeps two rows of the table (milestone 7): the line beside the actions adds
  no height, and moving the actions into the heading does not fit 720 px.
- A long name in a result is cut in the middle, so the start of the name, "(PID n)" and what
  happened stay in view; the explanation gives way first, below 48 px of room it is not shown.
- The keyboard focus on a button is the accent border with the halo of a focused text field, so
  it is never mistaken for the default button of a dialog, which has the border alone. Disabled
  menu items and the label of a disabled menu button are drawn at full opacity in the disabled
  text color (the stock stylesheet dimmed them to 40 %, below 3:1); the line that names an
  unmapped priority is muted, as a fact.
- The tone of the line and the severity of the log entry come from one mapping
  (`ControlMessages.severity`); the line of the inspector and the line of the held processes share
  one state (`ResultLineState`); the table and the Logs measure text with one helper
  (`TextWidths`).

**Behavior and limits, found while verifying.** On the development machine (Windows 11 Pro
26200, a 100 % and a 125 % monitor, the dark appearance, animations on), through a second Prism
instance driven by UI Automation and real keyboard and mouse input, against the demo workload.
The run was repeated on the build after the review fixes with the same results (except the
default-button focus fix of the confirmation passes, which is verified by snapshots only):
- Priority: the menu had Normal checked; right after choosing High the control said "current:
  Normal" while the system already reported High; after the next listing it said High, the menu
  had High checked and the row showed 13. Set back to Normal the same way.
- Suspend: "java (PID n) was suspended. Its threads were stopped one by one." with 27 of 27
  threads suspended and 0 % CPU; "Suspended by Prism" in the heading after the listing. Resume:
  "was resumed.", 0 suspended, 24.94 %; Prism's handle count 1172 before and after (1173 before
  and after in the repeat).
- Keyboard: from the selected row one Tab reached Suspend, Space suspended, and the focus was on
  the row; Tab reached Resume (Suspend disabled), Space resumed; Tab through Suspend and Priority
  to End task…, Enter opened the confirmation with Cancel focused, Escape closed it, nothing was
  sent, and the focus was back on End task…. Shift+F10 opened the context menu in the new order.
- End task on the windowless workload: "has no window to ask to close. Use Force kill instead.",
  the workload ran on. Force kill: exit code 1 at once, "Force termination requested for java
  (PID n). The table shows when it has ended."
- 125 %: the window at 120 DPI; asked for less, the smallest size was 900 x 600 pixels, 720 x 480
  units, laid out like the one at 100 %. At the smallest width the line beside the actions shows
  only its first letters; its tooltip and the Logs page carry it whole (as in milestone 7).
- Monitoring failure, through the harness of milestone 7 on this build: the same behavior, with
  "Nothing was done to java (PID n)." and the explanation "Prism is not monitoring processes."
- Not verified live: the light appearance and reduced motion, which would need a change of the
  system's settings; both are covered by snapshots and scene tests. A long process name, since
  the workload is always "java"; covered by snapshots.

**Deferred.**
- A name of its own for the workload, to see a long name live (milestone 13, with jpackage).
- Disabling actions on protected processes before they are tried (milestone 7 decision: refused
  when tried).
- An announcement of a result to a screen reader as it arrives; JavaFX has no live region, so the
  line is read when it is reached.

**Not verified on Linux and macOS.** Nothing here acts there; their profile has no levels, so no
level is marked and no number is called unknown.

**Reviewers.** `ui-critic`, `test-reviewer`, `architecture-reviewer` (the profile and the result
model cross the layers). `native-api-reviewer` was not needed: no Win32 call changed.
- `architecture-reviewer`: no must-fix. The profile lost a static factory nothing needed; the
  tone of the line now follows the log severity instead of a second mapping.
- `ui-critic`: must-fix the contrast of disabled menu items, the unmapped priority line read as
  a disabled choice, and a focused button that looked like a default one; all three fixed as
  above. Should-fix long names in results (cut in the middle), fixtures in today's wording, dead
  CSS, the gap before the line, the lasting consequence of Force kill in the text color.
- `test-reviewer`: must-fix two real defects, the result of a Resume of a held process shown
  beside the inspector's actions after monitoring stopped, and the answer to an action on a
  process that exited meanwhile dropped; both fixed with tests. The priority scene tests now
  check and fire an item as the menu does. Added: the Windows table, the listing tied to the
  table on the real system, one mapping of tone and severity, the narrow line and its tooltip,
  Ctrl+C with Ctrl and Meta, Logs cells not cut.
- Confirmation passes after the fixes: `test-reviewer` confirmed the three fixes and found the
  answer to an action on a process that exited could still reach the inspector if monitoring
  stopped meanwhile, a path no test held; a test now does (it fails without the line that
  forgets the exited process). `ui-critic` confirmed the three fixes and found that the default
  button of a dialog kept its own background with the keyboard focus, so the halo did not show
  on Cancel; the default button now takes the halo too (`confirm-*-cancel-focused`).

**Quality gate.** Full, plus snapshots: `./mvnw -o clean verify` 1022 tests, SpotBugs 0,
Spotless clean; `./mvnw -o -Pui-snapshots test` 613 tests, 475 snapshots.

## 8. Process control on Linux and macOS

**Objective.** The same control actions through POSIX mechanisms. The UI needs no
change: it already reads capabilities.

**Areas.** `platform.linux`, `platform.macos`: signals and priority through a small JNA
mapping (`kill`, `getpriority`, `setpriority`), errno mapping, protected-process facts.
The mapping is deliberately duplicated in the two packages: implementation packages may
not depend on each other, and JNA may not appear in the `platform` root.

**Acceptance criteria.**
- CTL-3, CTL-5 and CTL-6 for both systems; errno values map to operation results.
- The capability reports match the table in section 6 of the specification.
- The report states plainly that this code has not been run on Linux or macOS, unless
  it has.

**Tests.** Errno-to-result mapping; priority-level-to-nice mapping; capability reports
per system; selection of the implementation by detected system.

**Manual verification.** On Linux and on macOS when a machine is available: each action
on the demo workload, and lowering then raising a priority. Until then: none, and the
milestone is recorded as implemented but unverified on those systems.

**Reviewers.** `native-api-reviewer` (required).

**Quality gate.** Full.

**Status.** Done and accepted. Implemented, tested against fakes on every system, and run on the
real system in CI: Windows x64, Ubuntu x86_64 and macOS arm64 (run
[37802133378](https://github.com/erdemdenizer/PRISM-internal/actions/runs/37802133378), commit `c44f87a`,
full gate on all three). ADR-0010 is Accepted. What that run verified, and what it did not, is
under **Cross-platform verification** below; in short, the native paths are verified by
integration tests on those two Linux and macOS systems, and the running application has been used
on Windows only.

**Decided while building** (the reviewed plan and its external review;
[ADR-0010](adr/0010-unix-process-control-and-process-identity.md)).
- **Linux signals through a pidfd only.** End Task (SIGTERM), Force Kill (SIGKILL), Suspend
  (SIGSTOP) and Resume (SIGCONT) open a pidfd, check the identity in `/proc/<pid>/stat` after
  the open, signal through the descriptor and close it; a suspension holds its pidfd until it is
  resumed or let go. The whole path is probed once, on first use, on Prism's own process (open,
  signal 0, close). Where it fails (ENOSYS, a seccomp EPERM, ENODEV, any other failure, or an
  architecture other than x86_64 and aarch64), the four actions are `UNAVAILABLE` and the
  activity log says why once; there is no `kill(2)` fallback. Change Priority stays offered.
- **The system calls go through `syscall(2)`** with the numbers 434 and 424, on x86_64 and
  aarch64 only. The plan looked for glibc's wrappers (2.36) first; that was dropped, because a
  missing symbol throws an `Error` in JNA, which Prism does not catch, and on those two
  architectures the wrapper makes the same system call.
- **The start time** is computed from the boot time the process listing used (OSHI's, handed
  over by `HostPlatform`), not read again: after a clock step a second `btime` can differ by a
  second, and no identity would match again (native review).
- **macOS checks, then acts**: `sysctl(KERN_PROC_PID)` through a minimal JNA structure over the
  head of `kinfo_proc` (offsets checked by a test on any 64-bit system, `p_pid` checked at run
  time), then `kill(2)` or `setpriority(2)`. The race between them is stated: the process must
  exit and be collected and xnu hand out every other PID up to 99999 in microseconds. A read
  that fails is never taken for an exit (architecture and native reviews).
- **Priority**: Linux sets every thread in `/proc/<pid>/task`, reads the list again for threads
  started meanwhile, asks after each listing whether the process is still the identity, and
  reads every thread back; macOS sets the process and reads it back (`getpriority` with JNA
  clearing errno right before the call). Without privilege a priority can only be lowered, also
  not raised back (EACCES); the UI says so. On Linux the listing's number is nice + 20, so the
  M7.5 mark works; on macOS the listing shows a Mach priority and no level is marked.
- **Suspension ownership**: a process stopped already (T, t, SSTOP) is not suspended; one
  killed while Prism held it stopped is answered as gone on Resume. A continue by another
  program is not noticed; dropping the hold on the listing's state was deferred, because that
  state is not reliable enough (OSHI reads the leading thread on Linux and maps macOS states
  wrongly).
- **The words follow the mechanism** (CAP-5): "Asked X to terminate.", "The system stopped the
  whole process.", the privilege a raised priority needs, "The system confirmed it." where the
  table shows no level; the confirmation asks "Ask this process to terminate?". The page names
  every action that is not available ("Not available on this system: End task, Force kill,
  Suspend, Resume."). Windows reads as before.
- **PID 0 and below** are never passed to a call on either system (`kill(0)` would signal
  Prism's own process group).
- **SAFE-5** is now a test: no production class uses `ProcessBuilder`, `Runtime.exec` or a JNA
  mapping of `system`, `popen`, `posix_spawn`, `fork` or `exec*`.
- **PRODUCT_SPEC**: CTL-3 rewritten (the narrow change the external review approved); notes in
  CTL-6 (per-thread on Linux, no raising back) and PROC-6 (a continue by another program).

**Behavior and limits.**
- Linux 5.3 or later on x86_64 or aarch64 for End Task, Force Kill, Suspend and Resume.
- On Linux, nice is weighed only against processes of the same autogroup or cgroup (sched(7)).
- End Task on a process Prism has stopped is delivered and waits for Resume.
- A process whose main thread has ended while others run reads as a zombie and is refused.
- OSHI's macOS state mapping does not match xnu (`SSTOP` reads as new): the State column on
  macOS may be wrong. Found from bytecode and headers, not run; not fixed here.

**Windows, after these changes**, on the development machine (same method as milestone 7.5):
priority, Suspend and Resume (27 of 27 threads, 0 % CPU while held), the keyboard path, End task
without a window, Force kill with exit code 1, the smallest window, and the monitoring-failure
harness of milestone 7 all behave as before. Run after the implementation and again on the final
build after the review fixes; Prism's handle count before Suspend and after Resume was 1170 and
1171 in the first run, 1171 and 1170 in the second.

**Reviewers.** `native-api-reviewer`, `architecture-reviewer`, `test-reviewer`, `ui-critic`.
- native: must-fix the boot time read twice (fixed as above) and a failed sysctl read taken for
  an exit (fixed); should-fix PID 0 guards, jna-platform's `close`; considered: errno around
  getpriority, descriptor safety after an open, a check after each thread listing.
- architecture: must-fix the same failed-read case; should-fix the probe that could throw and be
  retried (isolated, once), the controller contract for Unix stops (Javadoc); `LinuxPriority`
  extracted, the words of a failure follow the mechanism, a positive control for SAFE-5.
- test: must-fix the demo's `--cpu` share on most processor counts, the descriptor count that
  the child's pipes would change, the race between the exit and the last output line; should-fix
  Resume of a zombie (a defect, fixed), and tests for an unknown boot time, a malformed stat
  file, priority without pidfd, double closes, no way to signal by PID, errno on the real
  system.
- ui-critic: must-fix End task and Force kill sharing "termination"; should-fix the order of the
  unavailable line, the priority and Resume tooltips, "handle".
- Confirmation passes of all four: no must-fix left. Taken from them: a positive control for the
  native branch of the SAFE-5 scan and the Windows calls that start a process; a fixed size for
  the `kinfo_proc` head, so that loading the macOS mapping touches nothing native; a line in the
  activity log when the boot time or the clock ticks cannot be read.

- After the reviews, an external review asked whether the Linux read-back of a priority could
  answer success with a thread unread. It could: a thread whose stat file was refused or failed
  to read was left out of the count. Now such a thread is unverified unless a second listing shows
  that it has ended, and an unverified thread makes the change a native error that says how many
  could not be read; a test pins it (and fails without the fix).

**Deferred.**
- At the smallest window the line beside the actions shows only its first letters (as in
  milestone 7, `processes-actions-partly-unavailable`); with several actions unavailable the
  reason is then in the tooltips only. A layout that gives the line its own row below a width
  needs the two-rows rule of the smallest window checked first.
- Reconciling a continue by another program; OSHI's macOS state mapping; a nice source for the
  macOS listing; a retry of the pidfd probe after a passing shortage (EMFILE).

**Gate, as run.** `./mvnw -o clean verify`: 1190 tests (22 skipped: they run on Linux or macOS
only), SpotBugs 0 (one exclusion with its reason, for the JNA structure), Spotless clean.
`./mvnw -o -Pui-snapshots test`: 633 tests, 495 snapshots.

**Cross-platform verification.** The first CI run (on `c23cf9d`) failed: 19 test cases, 34
executions across the three jobs. None was a defect of Prism. The cases were:
- a Windows test class that loaded `kernel32` on Ubuntu and macOS;
- two tests that assumed the Normal class, where GitHub's Windows runner starts jobs below
  normal;
- a test that read the CPU time of a child through the JDK, which on macOS reads it only for
  its own process;
- a test that assumed nice 0, where GitHub's macOS runner starts jobs at nice -10.

A test-only change (`c44f87a`) made each expectation an independent reading instead of an
assumption, and nothing was weakened (TESTING.md). Run 37802133378 then passed the full gate on
all three systems:

| | Windows x64 | Ubuntu x86_64 | macOS arm64 |
|---|---|---|---|
| Tests | 1212, 23 skipped | 1201, 38 skipped | 1201, 36 skipped |
| Real-system control tests | `WindowsControlIntegrationTest` 7, `JnaWin32Test` 7, `WindowsPriorityTest` 16 | `LinuxControlIntegrationTest` 7, `JnaLinuxTest` 15 | `MacosControlIntegrationTest` 7, `JnaMacosTest` 6 |
| Spotless, SpotBugs, JaCoCo | clean, 0, report | clean, 0, report | clean, 0, report |

Every skipped test is one gated to another system. Which tests ran was read from each job's
surefire reports.

- **Verified on Linux** (Ubuntu x86_64, kernel 6.17):
  - the pidfd probe, and the four signals through a pidfd on the demo workload;
  - a stop holding the CPU at zero;
  - a priority lowered on every thread, and raising it back refused with EACCES;
  - a stale identity refused by every action;
  - no pidfd left open.
- **Verified on macOS** (arm64, Darwin 25.6):
  - the `sysctl` identity, and the four signals after the check;
  - a process stopped already refused;
  - the nice value read back by `getpriority` and by `ps` alike;
  - lowering it, and raising it back refused with EACCES;
  - a stale identity refused;
  - the listing's CPU time of another process, checked against `ps`.
- **Not verified:**
  - aarch64 Linux, and Linux without pidfd (fakes only);
  - the privileged priority paths;
  - x86_64 macOS;
  - OSHI's macOS state column;
  - the running application, with its UI, on Linux or macOS. The actions there were run by
    integration tests, not by a user.

[ADR-0010](adr/0010-unix-process-control-and-process-identity.md) records the same, with the
runner details.

## 9. Managed Processes and emulated CPU limiter

**Objective.** Managed Processes, the Limits page, and a working CPU limit on every
system through the emulated limiter.

**Areas.**
- `domain`: managed-process settings, limit values.
- `platform`: `ResourceLimiter` with its per-process capability, implemented for now on
  every system by a limiter that reports `UNAVAILABLE`, so the limit service has one
  path from the start; the read of one process's current counters on `ProcessSource`,
  with which the limiter measures its target.
- `application`: the managed-process registry; the limit service (native first, emulated
  fallback); the emulated CPU limiter on its own scheduled executor; the suspension
  coordinator gains the "limiter" reason, the suspension journal and its recovery at
  start-up.
- `app`: the JVM shutdown hook (LIFE-5).
- `ui`: the Limits page; manage and limit actions and the managed mark on the Processes
  page.

**Acceptance criteria.**
- MGD-1, MGD-2, MGD-4; MGD-3 for the CPU limit and enabled (the memory limit enters the
  model in milestone 10, the OOM fields in milestone 11); LIM-1 to LIM-4 for the emulated
  path; LIM-7; LIM-8.
- The limiter holds the target near the requested share of total machine capacity by
  feedback on measured, normalized CPU use; it never derives its split from the limit
  alone, and it does not suspend a target that is under its cap (LIM-8).
- The Limits page shows the implementation type per process and never shows Emulated
  as native (UI-4, CAP-3).
- A process the user suspended stays suspended while a limit runs on it and after the
  limit is cancelled (CTL-9, LIFE-2).
- Cancelling a limit, an error, and a controlled shutdown each leave the target running.
- The journal is written per limit, not per duty cycle, and holds the fields of LIFE-6.
- After Prism is killed mid-limit, the next start lists the target as possibly
  suspended and the user resumes it after Prism's verification (LIFE-4, ADR-0011); on
  Windows an entry without its threads is listed for manual intervention only. A journal
  entry whose PID or start time does not match the live process is discarded without
  touching that process, and a suspension the user requested is never resumed by
  recovery (LIFE-6).
- The limiter suspends nothing before its journal entry is written durably and read back;
  a journal that fails stops every limit and keeps the evidence (LIFE-6, ADR-0011).
- **Performance exception (accepted 2026-10-09, ADR-0011).** The plan's budget of at most
  3 % of one logical processor per throttled target is **not met on Windows**: about 10 %
  is measured, the cost of the documented per-thread suspension. It is recorded as unmet,
  not relaxed. Bounded by admission: with per-thread suspension at most
  `max(1, min(4, processors / 4))` CPU limits run at once (16 with any other mechanism);
  a disabled limit takes no place; a refused request says the number and why and changes
  nothing. Milestone 10 ends the exception for natively limited processes; it stays for an
  emulated fallback the user chooses.

**Tests.**
- The limiter against a fake controller, a fake CPU reading and an injected clock:
  - a single-threaded target on an 8-processor machine (at most 12.5% of the machine)
    under a 30% limit is never suspended, and under a 5% limit converges near 5%;
  - a multi-threaded target using 80% of the machine under a 20% limit converges near
    20%;
  - a noisy reading inside the dead band does not change the split; one update never
    moves the split by more than the bound;
  - cancellation in each phase, target exit mid-cycle, controller failure, and an
    identity mismatch before a suspend or resume.
- The coordinator with overlapping reasons.
- Admission: the allowance per number of logical processors and per mechanism, a refusal
  that changes nothing, Disable, Enable, Remove and Unmanage, and requests from many
  threads at once.
- The registry (PID reuse, exit, refusal of Prism itself and of protected processes).
- Journal recovery: matching identity, reused PID with a different start time, process
  gone, an entry with only a user reason (left suspended, listed, resumed on request after
  verification), an entry with
  a user reason and an automatic one (left suspended), an entry written by an instance
  that is still running (left alone), a corrupt file.
- The controller with a zero or missing CPU reading, and at both ends of its range.
- The shutdown hook after a completed shutdown.

**Manual verification.** Limit the demo workload with one thread and with several, and
compare the observed share with the cap; cancel the limit; suspend it manually during a
limit; kill Prism from Task Manager during a limit and restart it.

**Reviewers.** `test-reviewer`, `architecture-reviewer`, `performance-check` skill (timer
frequency), `native-api-reviewer` (suspension safety).

**Quality gate.** Full, plus snapshots.

**Status.** Done and accepted (external technical review, 2026-10-09). Implemented per the
accepted plan revision 3 and ADR-0011, tested against fakes on every system, and run on the
real system: on Windows 11 by hand (the running application, the demo workload, a hard kill
and the recovery review) and in CI on Windows x64, Ubuntu x86_64 and macOS arm64 (run
[37943563248](https://github.com/erdemdenizer/PRISM-internal/actions/runs/37943563248), the code of
this milestone on a temporary validation branch, full gate on all three, no unexpected skip).
`CpuLimitIntegrationTest` held the demo workload near its limit on all three systems and
covered a hard kill in a stop phase checked from outside: listed and resumed only on request
on Linux and macOS, listed for manual intervention on Windows. **Accepted with a documented
performance exception:** one throttled Windows target costs about 10 % of one logical
processor (docs/PERFORMANCE.md), above the plan's 3 % budget, which stays unmet and is not
relaxed; admission control bounds how many emulated limits run (ADR-0011), and native Job
Object limits in milestone 10 end the exception for every process limited natively. Not
verified: the running application on Linux and macOS. The
`ResourceLimiter` of the Areas above is not built in this milestone: no native limit exists
yet and nothing would call it; it comes with its first implementation and its consumer in
milestone 10 (LIM-3 falls back to the emulated limit meanwhile).

## 10. Windows native limits

**Objective.** Hard CPU and memory limits through Job Objects, as decided in ADR-0002.

**Areas.** `platform.windows`: the Job Object implementation of `ResourceLimiter` with
its JNA mappings and structures; `application`: the limit service now receives native
levels on Windows; `ui`: implementation type and mechanism-specific warnings on the
Limits page.

**Acceptance criteria.**
- LIM-5 in full; LIM-3 and LIM-4 for the native path.
- A failed assignment is reported with its result and the emulated fallback is offered;
  success is never shown for a failed step.
- Limits are cleared on controlled shutdown (LIFE-1).
- Every job is named deterministically from the process identity, as specified in
  ADR-0002.
- Recovery after a crash is tested experimentally on Windows: Prism is killed while a
  limit is active, and the next start attempts to reopen the named job, validate it
  against the live process identity and its job membership, and clear the limits.
  Whether the attempt succeeds is the thing being established; either outcome is
  recorded in ADR-0002 and the user-facing wording follows the result.
- When reopening or validation fails, Prism reports the process as possibly still
  limited and takes no destructive action (LIFE-4).
- A CPU limit on Windows is native first; the emulated limit is used only when native
  assignment failed and the user chose the fallback, which states its cost and is subject
  to the admission rule of milestone 9. The cost of the limiter for a natively limited
  process is measured (expected: nothing beyond reading the job's accounting), which ends
  the milestone 9 performance exception for it.
- `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` is never used.
- Structure layouts are checked against the documented sizes.

**Tests.** Structure size and field offsets for 64-bit; flag composition; rate
conversion; job-name derivation from a process identity; the recovery decision
(reopened and valid, reopened but identity or membership mismatch, not found) against a
fake; limit-service selection and failure paths against a fake limiter.

**Manual verification.** Cap the demo workload at 20% and compare with Task Manager; set
a commit limit and watch the workload report an allocation failure; try a process that
already belongs to a job; exit Prism and confirm the limit is gone; kill Prism during a
limit, restart it, and confirm whether the limit was found and cleared.

**Reviewers.** `native-api-reviewer` (required), `test-reviewer`.

**Quality gate.** Full, plus snapshots.

**Decided while building.** Per the accepted plan revision 4.1 and
[ADR-0012](adr/0012-windows-native-limits-ledger-and-recovery.md), which amends decision 5 of
ADR-0002. A job's name goes with its last handle, so after a crash or a hard kill the next
start is expected not to find the job: the limit lasts until the process exits, and Prism
lists it as possibly still limited. Assignment and the change of a held job are separate
operations, and a durable ledger record precedes each of them. A process in a job Prism does
not hold is refused before a job is made. The emulated CPU limit is offered only after this
process's native limit was not applied, never over a native cap that may be in force, and runs
only with the user's consent. The memory limit is native only. Recovery is read-only and an
unsettled earlier ledger blocks a second CPU controller whatever today's native capability is
(recovery audits of 2026-10-10; ADR-0012, accepted by the product owner on 2026-10-10).
The last manual check above, written before these audits and kept as written, therefore
confirms that the restarted Prism lists the process as possibly still limited (or as held by a
running program) and that the cap stays measured until the process exits; it does not expect
the limit to be cleared. Clearing on a controlled exit is unchanged. One Prism runs per user profile
(ADR-0013, accepted on 2026-10-10): a second launch is refused before monitoring starts, because two instances
could otherwise run an emulated limit over each other's native cap (`ConcurrentInstancesTest`).

**Status.** Done and accepted (independent technical review and product owner, 2026-10-12).
Merged into `main` by PR #2 with a merge commit, `351647318222089caae3cd7fb2e3e87b9d9b5459`, which keeps every
milestone 10 commit. The tested code is `a6f12b62c6f6b65f61f2b06a31712b11addee99d`; the merged
head `53c545c` adds only `docs/` to it.

- **Native verification, plan §11.6 path C, accepted.**
  - Accepted first for `fdd08e3` (2026-10-10,
    [docs/evidence/m10-native-validation-fdd08e3.md](evidence/m10-native-validation-fdd08e3.md)).
  - Accepted again for the changed code at `a6f12b6` (2026-10-12,
    [docs/evidence/m10-native-validation-a6f12b6.md](evidence/m10-native-validation-a6f12b6.md)):
    the four native suites ran with `-Dprism.native.required=true` in a clean worktree, 20 tests,
    0 skipped, 0 failures, 0 errors, and `native-validation.mjs` passed.
- **Code changed between the two (Windows CI investigation, 2026-10-11).** The hosted Windows
  `verify` job failed three times in the milestone 9 test `CpuLimitIntegrationTest`.
  - One failure was a product misreport, fixed in `0a9792a`: a thread of an exiting target
    refused a suspend, and the limit ended as a failure, not an exit (ADR-0011, amended
    2026-10-11).
  - Two were the test measuring while other processes took the processors, fixed in `a6f12b6`
    (docs/TESTING.md).
  - No native platform code changed.
- **Three systems.** `verify (windows-latest)`, `verify (ubuntu-latest)` and
  `verify (macos-latest)` passed at the merged head `53c545c` (run 38092157501). Windows passed
  in three separate executions there. Linux and macOS ran this code only in CI.
- **The hosted `native-validation` check stays red, and is not counted as green.**
  - **Why:** on GitHub's `windows-latest` runner the test JVM and its children run inside a Job
    Object (`selfInJob=true childInJob=true`). Prism refuses such processes by design (plan D1),
    so 10 of the 11 native integration tests are skipped there, and the check fails as it must.
  - **What it did not find:** no native test failed or erred there.
  - **What stands for it:** the accepted path C evidence. The check was neither weakened nor
    bypassed.
- **Manual acceptance, both PASS (2026-10-11 and 2026-10-12, Windows 11 build 26200).** The
  target was the disposable demo workload only. The developer keeps the report, screenshots,
  measurements and logs outside the repository, with SHA-256 hashes.
  - **Native 20 % cap against Task Manager.**
    - Under the cap, the demo's own processor time over 10 s windows measured 19.0 to 21.3 %
      (mean 20.3 %). Task Manager showed 17 to 23, and 21 in the screenshot (its utility figure,
      ADR-0005). The job, read for query only, held the demo alone at `ENABLE | HARD_CAP` and
      rate 2000.
    - After Remove CPU limit the job held no rate and the demo ran at 58.5 to 59.7 %.
  - **Hard kill, restart, warning and clean-up**, in two runs.
    - **The cap outlived the kill.** After Prism was ended with `taskkill /F`, the demo stayed
      at 20.2 to 20.7 %, the job's name was gone (error 2), and the demo stayed in a job. In the
      first run it stayed capped until it ended by itself, 30 minutes after it started.
    - **The restarted Prism.** It logged "may still be limited by an earlier Prism session
      (CPU 20 %) … Prism starts no second limiter for it". Its Limits page said the same above
      the table, and it made no new job. For the first run's exited target it logged that
      Prism cannot prove the limit never took effect.
    - **The clean-up.** The demo was ended after its identity (PID, start time, command line, no
      child process) was checked again. The job's members, read before the kill, were the demo
      alone. Prism then closed with a controlled shutdown.
- **Known limitations, not blocking.**
  - **Not run:** 32-bit targets, a system with Dynamic Fair Share Scheduling, and recovery
    across users or elevation.
  - **The second limiter by hand:** its refusal was seen in the notice, the log and the absence
    of a new job. It was not chosen by hand from the context menu.
  - **On a restart:** the earlier-session cap is shown as a notice above the Limits table, not
    as a table row, because the new session does not manage the process.
  - **A busy hosted runner** can fail `CpuLimitIntegrationTest` with "too busy" (it fails, never
    skips). No self-hosted `windows-native` runner is set up.
  - **The UI snapshot tests** run by hand.

Earlier measurements, before the release candidate. Measured on the real system (ADR-0012):
a 20 % cap holds the demo workload at 21 %. After the holder of the job is killed the cap stays
at 20.7 %, the name is gone (error 2), and the next start says "unreachable". A commit limit of 256 MiB refuses an allocation. A process the target
started stays limited after the target exits, until Prism clears it. The native limiter costs
nothing measurable (docs/PERFORMANCE.md), which ends the milestone 9 performance exception for
every process limited natively. The native suites ran with `-Dprism.native.required=true` and
no skip on the development machine. Not verified: 32-bit targets, recovery across users or
elevation, and Dynamic Fair Share Scheduling.

## 11. OOM Guard and memory policy

**Objective.** The OOM state machine, its page, persisted settings, interventions
limited to Managed Processes, and the emulated memory policy that reuses them.

**Areas.** `domain`: state, thresholds; `application`: the state machine (pure, with an
injected clock), the intervention policy, the settings store, the emulated memory
policy (LIM-9), the "OOM Guard" reason in the suspension coordinator; `ui`: the OOM
Guard page and a state indicator in the shell.

**Acceptance criteria.**
- OOM-1 to OOM-13; LIM-9; SET-1 and SET-2.
- With default settings nothing is ever lowered, suspended or terminated.
- The page separates monitoring from automatic intervention (UI-5).
- Terminate is reachable only through the global switch and an explicit per-process
  choice.

**Tests.** Every transition; hysteresis, dwell and flapping input; the absolute floor;
an unavailable memory reading; candidate ordering; action downgrade under each
combination of switches; Terminate never without both opt-ins; managed-only; recording
of actions; the memory policy against its limit; settings load, save, missing and
corrupt file.

**Manual verification.** Raise the thresholds temporarily so the states are reached
without real memory pressure, ramp the demo workload, and observe state changes; then
enable automatic suspend, manage the workload with action Suspend, and confirm it is
suspended, logged, and resumed after recovery.

**Reviewers.** `test-reviewer` (state machine), `ui-critic`, `architecture-reviewer`.

**Quality gate.** Full, plus snapshots.

**Decided while building.** Per the approved plan revision 3 (decisions O1 to O17b),
[ADR-0014](adr/0014-settings-file.md) and [ADR-0015](adr/0015-oom-guard-interventions.md),
which amends §3 of ADR-0004.
- **The state machine.** It is pure and runs on the system monitor's thread, once per sample.
  - It enters a state after two consecutive samples.
  - It leaves one level at a time, after a margin of 3 points and a dwell of 10 s.
  - A sample that cannot be read holds the state. A gap of more than 5 s, or monitoring off,
    starts a new epoch, as does any change of the thresholds.
  - The absolute floor counts only when it is above zero and below half of physical memory,
    in integer arithmetic.
- **Interventions.** They run on the limiter thread, and only through one gate.
  - The gate checks, in order:
    - the trigger: a sample for pressure, a listing for the memory limit;
    - the settings version, and whether OOM Guard has stopped;
    - whether monitoring is on;
    - the observation: no older than 5 s, not HOLD, GAP or OFF;
    - whether the reading itself meets the level;
    - the effective action;
    - the listing, the protection rules, and the protection facts read just before acting.
  - A settings change, a new list of managed processes, a journal failure or the end of
    monitoring can end a hold. None of them starts an action.
  - A process gets at most one priority reduction at Critical and one emergency action per
    incident. These are independent: a reduction at Critical does not use up the emergency
    action. Terminate is sent at most once per process identity per session.
- **Suspension claims.** System pressure and the memory limit hold separate logical claims on
  one physical OOM Guard suspension in the coordinator.
  - The suspension is declared in the journal before the process is suspended, and the
    declaration is withdrawn when a suspend fails.
  - The physical release comes with the last claim.
  - When the journal fails, every claim is released (O14). If a release is incomplete, the
    ownership and its record stay.
- **Priority reduction** is offered only where a lowered priority can be raised back (Windows).
  On Linux and macOS it is unavailable (O16).
- **The memory signal.** It is validated on Windows only. On Linux, macOS and any other system
  it is not, so there every automatic switch is unavailable and no memory limit is offered (O17,
  O17b a). Linux fails closed until its evidence is reviewed (external review, 2026-10-12;
  ADR-0015 §7 names that evidence).
- **The memory limit (LIM-9)** is the watched resident memory.
  - On a validated Linux it would be set directly; until then it is not offered there.
  - On Windows it is offered with the user's consent after a native cap was not applied.
  - It acts only while OOM management is on for the process, and it is never called a cap.
- **Settings.** They are kept in a per-user file of their own (`prism-settings 1`, with a
  checksum). A missing or corrupt file gives the defaults. A file from a newer version is left
  untouched and disables saving. Termination is on only for the literal `true` (SET-2).
- **Not done:** the optional demo `--ramp` (O12) is not built. The manual verification uses
  `--memory` and raised thresholds.

**Status.** **Accepted with documented exceptions** (2026-10-13, the product owner's delegated
closure decision). It was merged into `main` through pull request #3 with a merge commit, as for
milestone 10.
- The decision, the exceptions E1–E5, and what stays deferred are in
  [docs/evidence/m11-acceptance-decision.md](evidence/m11-acceptance-decision.md).
- External technical review gave GO for the implementation, the Windows automated acceptance and
  the cross-platform technical verification.
- History: until 2026-10-12 the work was uncommitted. It was committed and pushed on 2026-10-13.

**CI status, stated plainly.**
- The three `verify` jobs (Windows, Linux, macOS) pass.
- The workflow as a whole stays **red**, because the hosted `native-validation` check fails:
  the hosted Windows runner puts every process in a Job Object, so the native suites skip, and
  a skip is not a pass.
- Under the milestone 10 policy, the path C evidence stands for that check: 20/20, 0 skipped,
  rerun for `3d6e4eb`. The check is not hidden or disabled.

Linux is reported as not validated: it monitors, warns and logs,
and makes no automatic intervention, until the evidence named in ADR-0015 §7 is reviewed. An
intervention cannot begin after OOM Guard stops (ADR-0015 §6, `OomGuardLifecycleTest`).
The mandatory Windows acceptance tests (runbook 1–6 and 7A) were run on 2026-10-12, automated
through UI Automation on the real application with disposable demo processes, and not observed
by a person. The run found one defect, which was fixed: the OOM management and thresholds
dialogs did not grow when their content grew, so their buttons could fall off the window.
An external audit of that handoff found that the result line under the OOM table kept an
earlier "Not in effect: … off" explanation after the switch was turned on. The line now drops
an explanation once the settings it described have changed, including for a result that
arrives after the change (`OomGuardViewModelTest`); the acceptance tests were repeated.
The branch was then committed and pushed, and draft pull request #3 ran CI on all three systems
for commit `3d6e4eb`:
- the full gate passed on Windows, Linux and macOS;
- the macOS job passed on its rerun, after one intermittent milestone 9 test;
- the hosted `native-validation` check stays red, as in milestone 10, and path C was run again
  for `3d6e4eb`.

Linux and macOS stay not validated for OOM interventions. The evidence is in
[m11-ci-cross-platform-3d6e4eb.md](evidence/m11-ci-cross-platform-3d6e4eb.md). A confirmed P2
of milestones 9/10 was left for a separate fix: the CPU and memory limit dialogs did not grow for
their validation text, so their buttons were clipped. It was fixed afterwards in a
maintenance change: branch `fix/limit-dialog-sizing`, pull request #4 (2026-10-13), reviewed
externally with GO. The window now follows those lines, as the OOM dialogs do
(`LimitDialogsSizingSceneTest`).
Test 7B is deferred. The results and evidence are in the milestone's handoff archive. What was run, where, and what waits for the user is in the
milestone report.

## 12. Linux native limits

**Gated** on a real Linux system with cgroups v2, because neither design could be accepted
without running it. It ran first on GitHub-hosted Ubuntu and then on a local Ubuntu VM. Planning
replaced ADR-0003's delegated subtree with systemd transient scopes
([ADR-0016](adr/0016-linux-native-limits-through-systemd-scopes.md), Accepted).

**Objective.** A complete, tested mechanism for CPU and memory limits through systemd transient
scopes, proven on disposable processes the test harness starts. In the product every process
stays `UNAVAILABLE` (K16): desktop application ownership and lifecycle are not validated, and
enabling native limits needs new evidence and the product owner's approval.

**Areas.** `platform.linux`: read-only capability discovery, the sd-bus mapping, adoption by
pidfd, the scope limiter (`cpu.max`, `memory.high`, `memory.max` through unit properties), proven
clearing and recovery, the scope-membership rule; `application`: ledger format 2 with scope
provenance; CI: the `linux-native-validation` job. ADR-0016 moved from Proposed to Accepted with this
milestone's acceptance, and ADR-0003 became Superseded.

**Acceptance criteria.**
- LIM-6 holds for the mechanism on real systemd and cgroup v2, on disposable, test-owned processes.
- K16 holds: the limiter `HostPlatform` gives the product admits no process, opens no bus and clears
  nothing, with no setting, property or environment variable that changes this, and a test fails if
  a second composition appears in the main sources.
- A failed precondition reports `UNAVAILABLE` with its reason, and the limit service falls back to the
  emulated limiter. An unanswered step stays uncertain and starts no second limiter.
- Limits are set only on scopes Prism created and proves as its own: a foreign or unproven scope
  is refused, and a write reaches only the recorded invocation.
- Removing a limit reads back as none. No process is moved back, no scope is stopped, no other unit
  changes, and no `prism-srm-limit-*` unit stays once its processes have exited.
- The CPU limit is converted from the whole-machine percentage with the online logical processor count.
- The text for `memory.max` and `memory.high` matches the kernel's documented behavior and promises
  neither a kill of the target nor a limit that is never exceeded.
- The full gate passes on Windows, Linux and macOS at the final commit.

**Tests.** The conversion (25% on 8 processors is `200000 100000`; 1% on 1 processor is
`1000 100000`; the limiter sets 100% explicitly, `400000 100000` on 4 processors); parsing of `/proc/<pid>/cgroup`,
mount information and controller lists from fixture text; the readiness decision table; the limiter
against a fake systemd that answers late, refuses, does not answer or ignores a property; the exact
bus messages; a source scan for forbidden calls and for K16; the integration suite on real systemd in
required mode, where a missing capability or a skip fails.

**Manual verification, under K16.** On Linux: run the integration suite as the desktop user through
`user@UID.service`; confirm the refusal of a login session scope and of a process outside the user
manager; observe where processes land when started from GNOME Shell and from a terminal, with a
disposable probe and without moving anything; confirm no scope or unit change is left behind.

**Manual verification before K16 can be lifted.** Not part of this milestone's closure: limit the demo
workload started from a desktop launcher and from a terminal and compare its CPU share with the cap;
drive it into `memory.high` and into `memory.max` and record what actually happens; run Prism's own
window on a Linux desktop; confirm cleanup after exit; close D1, D2, D6, D8, D11 and D14 of the
[risk register](evidence/m12-risk-register.md).

**Reviewers.** `native-api-reviewer` (required).

**Quality gate.** Full, run on Windows, on Linux and on macOS.

**Status (2026-11-07): accepted with K16 active and three documented exceptions; implemented, not enabled.** Stages S1 to S6 are in the code: ledger format 2
with scope provenance, read-only recovery and proven clearing, the read-only systemd foundation, pidfd-only adoption, CPU
quota, `memory.high`, `memory.max` with separate consent, the scope-membership rule, limit writes addressed to the scope's
invocation id, and the `linux-native-validation` CI job. Evidence by criterion is in the
[acceptance matrix](evidence/m12-acceptance-matrix.md), the Linux runs in
[m12-hyperv-validation.md](evidence/m12-hyperv-validation.md), the open risks in the
[risk register](evidence/m12-risk-register.md). The full gate passed at commit `f0f4279` on Windows, on Linux and on the
macOS test machine (local user: kerem). The product owner accepted the milestone on 2026-11-07:
[acceptance decision](evidence/m12-acceptance-decision.md). One written criterion stays open as deferred, the text for
`memory.max` and `memory.high`, because K16 keeps that text unreachable on Linux. The other two exceptions are the OOM kill, not
observed at the final candidate, and the hosted `native-validation` check, which fails and stays visible.

K16 is an active safety restriction in the current product: native limits stay `UNAVAILABLE` for every process, because desktop
application ownership and lifecycle are not validated. It can be lifted later only after separate validation and with the
product owner's explicit approval. Accepting this milestone does not lift it.

## 13. Settings, shutdown hardening, polish, packaging

**Objective.** Finish the product surface and make it deliverable.

**Areas.** `ui`: the Settings page, theme switching, remaining states; `app`: the full
shutdown sequence; packaging configuration for jpackage on Windows; documentation.

**Acceptance criteria.**
- UI-7 and UI-8 across all pages.
- LIFE-1 to LIFE-6 verified step by step, including a failing cleanup step.
- A Windows application image built with jpackage starts and runs on a machine without
  a JDK (DEL-1). Any build plugin added for this is recorded in an ADR.
- Every page is operable by keyboard and passes the UI guide checklist in both themes.
- On Windows the native title bar matches the effective appearance of the window: dark
  in `DARK`, light in `LIGHT`, and in `SYSTEM` whatever the operating system prefers at
  the moment, also after that preference changes while Prism runs; the title text and the
  caption buttons keep their contrast in both. A light native title bar above a dark Prism
  window does not ship. Milestone 5.5 leaves the title bar as Windows draws it.

**Native title bar.** The window keeps the real Windows chrome: minimize, maximize and
restore, close, resizing, dragging, Snap Layouts, DPI scaling, accessibility, the window
manager's keyboard behavior, native shadows. Investigate the smallest documented Windows
mechanism that colors that chrome and fits the platform boundary (`prism.srm.platform.windows`,
a capability like any other); no fake caption buttons, no recreated resizing, no web-style
title bar, no frameless window unless the investigation proves there is no robust native
way, and no branching on the operating system in `ui` or `application`. An ADR only if the
chosen mechanism is architectural enough to need one. The application logo and the icon in
the title bar are a separate, still deferred matter.

**Tests.** Shutdown ordering with failing steps; snapshots of every page and state.

**Manual verification.** Full demonstration script with the demo workload: monitoring,
control, limits, OOM Guard, shutdown. Install and run the packaged image.

**Reviewers.** All four review agents, at the final boundary.

**Quality gate.** Full, plus snapshots.
