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
| 6 | Demo workload | DEMO-1 to DEMO-3 |
| 7 | Process control on Windows | CTL-1 to CTL-9, CAP-1 to CAP-5, PROC-6, SAFE-1 to SAFE-5, UI-2 (actions), UI-6 |
| 8 | Process control on Linux and macOS | CTL-3, CTL-5, CTL-6 for those systems |
| 9 | Managed Processes and emulated CPU limiter | MGD-1 to MGD-4, LIM-1 to LIM-4 (emulated path), LIM-7, LIM-8, LIFE-4 to LIFE-6, UI-4 |
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

## 6. Demo workload

**Objective.** A safe target for every destructive demonstration that follows.

**Areas.** A new isolated package `prism.srm.demo` with its own `main`, using only the
JDK (including `com.sun.management` for the physical memory size). `ArchitectureTest`
gains a rule for it in both directions: it depends on no other `prism.srm` package and
none depends on it. `docs/TESTING.md` documents its use.

**Acceptance criteria.** DEMO-1 to DEMO-3: controllable CPU load and memory use from
command-line arguments; the memory bound and the maximum duration cannot be exceeded by
any argument; it reports allocation failures and its own progress.

**Tests.** Argument validation and clamping; duty-cycle arithmetic.

**Manual verification.** Run it at 50% CPU and 256 MiB and watch it in Prism's process
table and in Task Manager; confirm it exits by itself.

**Reviewers.** `architecture-reviewer` (the new package rule).

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

**Reviewers.** `native-api-reviewer` (required), `ui-critic`, `test-reviewer`.

**Quality gate.** Full, plus snapshots.

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
- MGD-1 to MGD-4; LIM-1 to LIM-4 for the emulated path; LIM-7; LIM-8.
- The limiter holds the target near the requested share of total machine capacity by
  feedback on measured, normalized CPU use; it never derives its split from the limit
  alone, and it does not suspend a target that is under its cap (LIM-8).
- The Limits page shows the implementation type per process and never shows Emulated
  as native (UI-4, CAP-3).
- A process the user suspended stays suspended while a limit runs on it and after the
  limit is cancelled (CTL-9, LIFE-2).
- Cancelling a limit, an error, and a controlled shutdown each leave the target running.
- The journal is written per limit, not per duty cycle, and holds the fields of LIFE-6.
- After Prism is killed mid-limit, the next start resumes the target and logs it
  (LIFE-4). A journal entry whose PID or start time does not match the live process is
  discarded without touching that process, and a suspension the user requested is never
  resumed by recovery (LIFE-6).

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
- The registry (PID reuse, exit, refusal of Prism itself and of protected processes).
- Journal recovery: matching identity, reused PID with a different start time, process
  gone, an entry with only a user reason (left suspended and taken over), an entry with
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

## 12. Linux native limits

**Gated**: starts when a Linux machine with cgroups v2 is available, because ADR-0003
cannot be accepted without running it.

**Objective.** CPU and memory limits through cgroups v2 for the processes an
unprivileged user can move.

**Areas.** `platform.linux`: the capability probe, creation and removal of
application-owned child cgroups, `cpu.max`, `memory.high`, `memory.max`; ADR-0003 moves
from Proposed to Accepted or is replaced.

**Acceptance criteria.** LIM-6 in full; a failed probe reports `UNAVAILABLE` and the
limit service falls back to the emulated limiter; limits are set only in cgroups
created by Prism; those are removed on shutdown. The CPU limit is converted from the
whole-machine percentage with the logical processor count. The UI text for
`memory.max` and `memory.high` matches the kernel's documented behavior and promises
neither a kill of the target nor a limit that is never exceeded.

**Tests.** The `cpu.max` conversion (for example 25% on 8 processors is
`200000 100000`; 1% on 1 processor is `1000 100000`; 100% is `max`); parsing of `/proc/<pid>/cgroup`, mount
information and controller lists from fixture text; the probe's decision table; file
operations against a temporary directory standing in for the cgroup file system.

**Manual verification.** On Linux: limit the demo workload started from a desktop
launcher and from a terminal and compare its CPU share with the cap; drive it into
`memory.high` and into `memory.max` and record what actually happens; try a process in
a login session scope and a system service and confirm the honest refusal; confirm
cleanup after exit.

**Reviewers.** `native-api-reviewer` (required).

**Quality gate.** Full, run on Linux.

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

**Tests.** Shutdown ordering with failing steps; snapshots of every page and state.

**Manual verification.** Full demonstration script with the demo workload: monitoring,
control, limits, OOM Guard, shutdown. Install and run the packaged image.

**Reviewers.** All four review agents, at the final boundary.

**Quality gate.** Full, plus snapshots.
