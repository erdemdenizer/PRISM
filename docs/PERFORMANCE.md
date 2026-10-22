# Performance measurements

What monitoring costs, measured, and the cadences those measurements justify (MON-8).
Numbers are from the development machine and say nothing about other systems.

**Machine.** Windows 11 Pro, build 26200; AMD Ryzen 7 5800H, 16 logical processors;
32 GiB. Measured on 2026-10-05 with JDK 25 and OSHI 7.7.0.

**Not measured.** Linux and macOS: nothing has been run there.

## The Processes page (milestone 5)

The page adds no monitoring work: the process monitor has run at its 2-second cadence since
milestone 3, and the page shows what its listings carry. What it adds is work on the JavaFX
Application Thread once every two seconds while the page is on screen, and, when a row is
selected, one read of path and command line on `srm-process-details`. A page that is not on
screen is not brought up to date; the newest listing is kept for it.

**One listing on the JavaFX Application Thread.** Measured with `./mvnw -q -Pui-snapshots
test -Dtest=ProcessesUpdateCostProbe` (`-Dprobe.processes=1000` for the second figure of each
cell): 1 000 listings after 200 to warm up. "Rows" is the page taking the listing: the
comparison with its rows by identity, the values set, the list changed where a row came, went
or moved. "With layout" adds CSS and layout, in which the cells on screen set their texts. The
probe forces a full CSS pass over the window, which the toolkit does not do for a listing: the
first row of the table is that cost alone, about 2.6 ms and 844 KiB, and is to be read as the
base of the others. Medians, 400 and 1 000 processes:

| Case | Rows | With layout |
|---|---|---|
| Nothing changes, sorted by name (the base of the probe) | 0.16 / 0.36 ms | 2.63 / 2.74 ms |
| A desktop at work (a quarter of the rows change), sorted by name | 0.26 / 0.54 ms | 4.65 / 5.81 ms |
| The same, sorted by CPU: the rows move with every listing | 0.30 / 0.61 ms | 5.01 / 5.86 ms |
| Every value of every row changes, sorted by CPU | 0.37 / 0.80 ms | 5.41 / 6.02 ms |
| Ten processes start and ten exit with every listing, sorted by CPU, one row selected | 0.29 / 0.67 ms | 4.68 / 5.54 ms |
| A desktop at work, filtered to one name in eighteen | 0.15 / 0.37 ms | 2.37 / 2.69 ms |

The worst of 1 000 was 16 ms (400) and 72 ms (1 000, one listing; the next worst 18 ms). With
a software render of the whole scene added, an upper bound, the medians are 5 to 9 ms. A
thousand processes cost little more than four hundred: the table creates cells for the rows it
has room for, and a scene test asserts that 1 000 rows have exactly as many nodes as 394. The
JavaFX Application Thread allocated 1.6 to 1.8 MiB per listing, rows and layout together, of
which the forced CSS pass of the probe is 0.84 MiB: well under a megabyte a second of short-lived
garbage at the 2-second cadence. A key typed in the filter costs 2.2 ms (400) and 2.0 ms (1 000)
in the median with layout, and a sort by another column 5.4 and 4.9 ms.

**Idle cost with the page on screen.** Measured on the application as `./mvnw -q javafx:run`
starts it, both monitors at their default cadences, about 260 processes, on 2026-10-06/07.
After a warm-up the processor time of the JVM was read at both ends of 300 seconds, and the
heap in use, the class histogram and the threads after a forced collection at both ends
(`jcmd`). Two runs on two starts of the same code: the page as it opens, sorted by name; and
sorted by CPU, largest first, with one row selected, so that the rows change places with every
listing. The second run began with a click on the header and on a row, and the window was in
front of the person at the machine for part of it.

| Measure | Sorted by name | Sorted by CPU, one row selected |
|---|---|---|
| What else was running (machine, % Processor Time, at the two ends) | 0% and 8% | 3% and 1% |
| Processor time of the JVM over 302 s | 11.53 s | 12.75 s |
| CPU, of one logical processor | 3.82% | 4.22% |
| **CPU, of the machine (16 logical processors)** | **0.239%** | **0.264%** |
| Heap in use after GC, start and end | 25 851 and 31 683 K | 29 651 and 30 437 K |
| Instances of `javafx.scene.*` classes, start and end | 58 735 and 58 735 | 62 384 and 76 473 |
| Instances of `prism.srm.*` classes, start and end | 2 941 and 3 023 | 3 018 and 3 183 |
| `ProcessRow` instances (processes listed), start and end | 258 (259) and 263 (264) | 261 (260) and 261 (262) |
| Table cells, start and end | 162 and 162 | 171 and 225 |
| Threads, start and end | 57 and 57 | 58 and 58 |
| Working set, start and end | 265 and 267 MiB | 260 and 271 MiB |
| Close request to exit of the JVM | 347 to 385 ms | |

Both are an eighth of the budget of MON-12 and in the range of the Overview (0.35%,
milestone 3) and the Performance page (0.42%, milestone 4), none of which were measured side
by side. The heap readings are single readings and show no trend.

**The scene does not grow with the listings.** In the first run no instance of a
`javafx.scene.*` class was added in 150 listings, and the four `prism.srm.*` classes that
changed were the rows, the identities, the snapshots and the sampler's counters, by the number
of processes that came. In the second run the table had 6 more rows of cells at the end (54
cells, 356 nodes), and the rest of the 14 000 added `javafx.scene.*` instances were fonts,
style-cache entries and the properties JavaFX creates lazily on hover and focus, as noted for
the other pages. A watch of the same run every 30 seconds saw the rows go from 18 to 19 and to
20 at two moments 60 seconds apart, and a later watch of ten minutes saw them go on to 27, one
or two at a time, with the window behind others and nothing touching it. The cause was found
by reproducing it: a scene test that gives the page 120 listings in which the selected row
moves from the top to the far end and back leaves the rows at 19, and a real window with
pulses, the same sort, a selected row and processes coming and going leaves them at 17 over
80 listings; but with the three questions an accessibility client asks after every change of
the selection or the focus (`FOCUS_ITEM`, `ROW_AT_INDEX`, `SELECTED_ITEMS`) added after each
listing, the same window goes from 17 to 31 rows in 80 listings and to 32 in 100, where it stays through 400: the pile of rows the flow keeps grows to about twice the rows on screen and no further. JavaFX answers
a question about a row that is not on screen with `VirtualFlow.getPrivateCell`, which takes a
row from its pile or creates one. The running application had such a client: the scripts of
this verification read the table through UI Automation, and Windows keeps asking once a client
has connected. So the growth needs an assistive technology attached and a selected row that
moves out of view; without one the scene does not grow. The running application confirmed
it: an instance that no accessibility client had touched, driven by the keyboard alone, sorted
by CPU with the selected row out of view, kept 25 rows of cells through 90 listings in three
minutes, while its `javafx.scene.*` count moved by 87 instances in all. It is JavaFX's, not
Prism's, and is left as a known limit (milestone 5 in MILESTONES.md). With a screen reader attached over hours it was not
measured; the probe says it is bounded.

**Not measured.** The cost while the window is minimized. Pulse times of the toolkit. Hot
spots. Anything longer than five minutes. The 125% monitor.

## The Performance page (milestone 4)

The page adds no monitoring work: it shows what the system snapshot already carried, so
the cost of a sampling cycle is that of milestones 1 to 3. What it adds is work on the
JavaFX Application Thread once a second, for the section on screen only. A page and a
section that are not on screen are not brought up to date.

**One snapshot on the JavaFX Application Thread.** Measured with `./mvnw -q
-Pui-snapshots test -Dtest=PerformanceUpdateCostProbe`: 1 000 updates per section in
which every value, every history and every table row changes, after 200 to warm up; a
machine of 16 logical processors and 36 network interfaces. The Overview, measured in
the same session with its own probe, is given for comparison.

| Update and layout, per update | Median | Mean | 95th | Worst |
|---|---|---|---|---|
| CPU section, 16 logical processors | 1.02 ms | 1.14 ms | 1.80 ms | 3.66 ms |
| CPU section, 64 logical processors (`-Dprobe.processors=64`) | 1.42 ms | 1.57 ms | 2.52 ms | 5.58 ms |
| Memory section | 0.77 ms | 0.88 ms | 1.39 ms | 3.88 ms |
| Disk section, 3 file systems | 1.54 ms | 1.70 ms | 2.60 ms | 5.33 ms |
| Network section, 36 interfaces | 2.69 ms | 2.89 ms | 4.25 ms | 7.13 ms |
| Overview, for comparison | 1.42 ms | 1.65 ms | 2.81 ms | 5.46 ms |

With a software render of the whole scene to an image added, which is an upper bound
and not the cost on screen, the medians are 4.1 to 6.1 ms. The Network section is the
dearest: every second it sets two labels in each visible cell of two columns. It uses
less than three thousandths of the interval.

**Idle cost with the page on screen.** Measured on the application as `./mvnw -q
javafx:run` starts it, both monitors at their default cadences. A section was selected
and left alone; after a warm-up (60 s, and 30 s for the second section) the processor
time of the JVM was read at both ends of 300 seconds, and the heap in use, the class
histogram and the threads after a forced collection at both ends (`jcmd`). One run per
section, in this order, on the same process.

| Measure | CPU section | Network section |
|---|---|---|
| What else was running (machine, % Processor Time, at the two ends) | 29% and 39% | 28% and 30% |
| Processor time of the JVM over 300 s | 20.06 s | 19.94 s |
| CPU, of one logical processor | 6.69% | 6.65% |
| **CPU, of the machine (16 logical processors)** | **0.418%** | **0.415%** |
| Heap in use after GC, start and end | 19 784 and 17 582 K | 26 325 and 24 401 K |
| Instances of `javafx.scene.*` classes, start and end | 15 133 and 15 133 | 29 201 and 36 921 |
| Instances of `prism.srm.*` classes, start and end | 1 648 and 1 647 | 1 814 and 1 850 |
| Threads, start and end | 57 and 57 | 57 and 57 |
| Working set, start and end | 250 and 265 MiB | 267 and 275 MiB |
| Close request to exit of the JVM | | 400 ms |

The CPU figure includes the forced collections and `jcmd` attachments at the end of each
interval. It is a fifth above the 0.35% measured with the Overview on screen in
milestone 3, on another day and under another load; the two were not measured side by
side, so the difference is not attributed. Both are a fifth of the budget of MON-12.

**The scene does not grow with the updates.** In the first interval no instance of a
`javafx.scene.*` class was added in 300 updates. In the second the number rose by 7 720,
and it was 40 141 when looked at again three minutes later. It then stayed at exactly
40 141 in seven histograms taken 20 seconds apart, with 36 rows receiving new rates
every second, and so did every class watched for a leak (36 rows, 30 and 18 cells, 15
table rows, 39 tooltips, 183 labels, 1 143 fonts). Changing the section back and forth
left it between 39 909 and 40 022. So an update adds nothing. What added the instances
in between was not isolated. The window was moved to a second monitor scaled to 125%
during that time, by the person at the machine, and the classes that had grown in a
histogram taken then were those of the style cache, fonts and lazily created properties
(`CascadingStyle`, `CalculatedValue`, `StyleCacheEntry$Key`, `Font`); that fits a change
of scale and pointer movement over the table, and is the effect noted for the Overview
below, but it is a reading of the histogram and not a controlled measurement. A test
asserts for each section that 200 updates leave the scene with exactly its nodes.

**Not measured.** The cost while the window is minimized. Pulse times of the toolkit.
Hot spots. Anything longer than five minutes. The two monitors of the machine side by
side.

## Idle cost of the running application (milestone 3, MON-12)

MON-12: with the window visible and idle, Prism averages below 2% of total machine CPU
capacity, and its heap does not grow over a 10-minute run. **Met: a third of a percent of
the machine, and no growth of Prism's own data.**

Measured on the application as `./mvnw -q javafx:run` starts it, with both monitors at
their default cadences. Processes are listed although no page shows them before
milestone 5: the measurement is to cover the cost the product will have. After a
90-second warm-up, the processor time of the JVM (`TotalProcessorTime` of the process)
is read at the start and the end of 600 seconds; the heap in use is read after a forced
collection (`jcmd <pid> GC.run`, then `GC.heap_info`) at the start, after 300 seconds
and at the end; `GC.class_histogram` and `Thread.print` are taken at both ends. The
window was visible and not minimized at all three points. Two runs: the second is of
the final code, the first of the code before the review changes.

| Measure | Run 1 | Run 2, final code |
|---|---|---|
| What else was running (machine, % Processor Time, mean of ten samples) | 30.8% | 36.3% |
| Processor time of the JVM over 600.3 s | 31.66 s | 33.41 s |
| CPU, of one logical processor | 5.27% | 5.56% |
| **CPU, of the machine (16 logical processors)** | **0.330%** | **0.348%** |
| Heap in use after GC: start, 300 s, end | 17 155, 17 006, 17 795 K | 15 580, 17 447, 16 847 K |
| Instances of `javafx.scene.*` classes, start and end | 7 483 and 7 483 | 9 220 and 9 260 |
| Threads, start and end | 58 and 57 | 57 and 57 |
| Working set, start and end | 218 and 246 MiB | 228 and 263 MiB |
| Close request to exit of the JVM | 370 ms | 370 ms |

The CPU figure includes the three forced collections and the `jcmd` attachments of the
measurement itself. It is lower than the two probes of milestones 1 and 2 taken alone
(0.40% and 0.34%), because those included the start of the JVM, the test harness and
their own printing.

**The heap.** The three readings of a run are single readings and do not make a trend:
in run 2 the middle one is the highest. What the class histograms of run 2 show between
start and end:

- Nothing of Prism accumulates. Its instances went from 1 287 to 1 324, all of them
  `ProcessIdentity` and the sampler's per-process counters: there were 13 more processes.
- The scene does not grow. The 40 additional `javafx.scene.*` instances are no nodes:
  they are properties and drag-detection state that JavaFX creates on the first mouse
  movement over a node (`Node$15`, `Scene$DragDetectedState`, `TransferMode`). In run 1,
  without mouse movement over the window, the count did not change at all. A test
  asserts the same for 200 updates.
- What does grow is JavaFX's cache of laid-out text: `com.sun.javafx.text.LayoutCache`
  went from 533 to 2 589 entries, with their text runs about 0.5 MiB. Every distinct
  text a label shows gets an entry, and the uptime changes every second. JavaFX bounds
  this cache itself: `PrismTextLayout` counts its size and clears the whole table when
  the maximum (`prism.cacheLayoutSize`) is passed. That is read from the byte code of
  JavaFX 25.0.4, not observed: no run was long enough to reach the maximum, and its
  default value was not looked up. The heap will therefore rise for a while and fall
  back; how high was not measured.
- OSHI's native memory handles fluctuate by several thousand (`com.sun.jna.Memory`
  disposers went from 17 435 to 11 383), which moves a single reading by more than the
  whole difference.

**The working set** rose by about 30 MiB in ten minutes in both runs while the heap
did not. It is not the heap: it holds compiled code, native memory of JavaFX and of
OSHI, and what the system keeps mapped. Whether it levels off was not measured; a run
of an hour or more is needed to say.

**One snapshot on the JavaFX Application Thread.** Measured with `./mvnw -q
-Pui-snapshots test -Dtest=OverviewUpdateCostProbe`: 1 000 updates in which every value
and every history changes, so that all four graphs redraw, after 200 to warm up.

| Per update | Median | Mean | 95th | Worst |
|---|---|---|---|---|
| View model, texts, Canvas commands, CSS and layout | 1.14 ms | 1.36 ms | 2.51 ms | 6.10 ms |
| The same plus a software render of the whole scene to an image | 4.46 ms | 4.87 ms | 6.99 ms | 14.96 ms |

The second row renders everything, where the toolkit redraws what changed, so it is an
upper bound and not the cost on screen. The application does this once a second.

**Not measured.** The cost while the window is minimized: sampling and drawing go on
then, and nothing in the specification asks them to stop. Pulse times of the toolkit.
Hot spots (no profile was taken, the total being a sixth of the budget). A scaled
display. Anything longer than ten minutes.

**The cadences stay** at 1 second for system metrics and 2 seconds for processes (MON-8).

## Process enumeration (milestone 2)

Measured with `./mvnw -q test -Dtest=HostProcessProbe -Dprobe.cycles=100
-Dprobe.busyThreads=0 -Dprobe.child=false`: 100 cycles at the 2-second cadence through
`ProcessMonitor` and the real source. Three runs, because the machine's own load changes
the result more than anything in Prism does. Only the first is of the final code, which
reads the Windows virtual sizes in every cycle; the other two were made before that read
existed.

| Measure | Normal use, final code | Normal use, without the virtual-size read | Heavy load, without the virtual-size read |
|---|---|---|---|
| What else was running | 29-38% CPU | 30-45% CPU | a game at 36% of the machine, 19% idle |
| Processes per cycle | 388 to 394 | 393 to 400 | 394 to 409 |
| Enumeration time, median | 43.4 ms | 34.2 ms | 55.2 ms |
| Enumeration time, mean | 45.8 ms | 36.9 ms | 65.4 ms |
| Enumeration time, 95th percentile | 58.7 ms | 46.5 ms | 114.4 ms |
| Enumeration time, worst of 99 | 93.7 ms | 86.1 ms | 186.5 ms |
| First enumeration | 1214 ms | 848 ms | 2067 ms |
| CPU of the measuring JVM over 200 s | 5.44% of one logical processor, 0.340% of the machine | 4.07% of one logical processor, 0.255% of the machine | 7.42% of one logical processor, 0.464% of the machine |
| Heap in use after GC, cycle 2 and after cycle 100 | 11.7 and 15.6 MiB | 11.7 and 12.5 MiB | 11.8 and 14.9 MiB |

The first enumeration includes OSHI's set-up and one user lookup per process. The CPU
figure includes the JIT compiler, the test harness and the probe's printing, so it is an
upper bound for the monitoring itself.

The heap figures are two single readings per run and do not establish a trend. Four
runs of 14 cycles ended between 0.8 MiB below and 4.0 MiB above their second cycle, and
the three runs of 100 cycles between 0.8 and 3.9 MiB above it, so the difference did not
follow the number of cycles; that speaks against a leak per cycle and proves nothing
more. The ten-minute heap check of the running application is above, under MON-12.

### The Windows virtual size

OSHI's process listing has no virtual size on Windows, so it is read separately, once
per listing for all processes ([ADR-0006](adr/0006-process-io-and-windows-virtual-size.md)).
Three sources of the same number were measured at the 2-second cadence with about 392
processes, the machine under load (the listing itself took 60 to 68 ms in the median):

| Source, one read for all processes | Cycles | Median | Mean | 95th | Worst |
|---|---|---|---|---|---|
| `Win32_Process` through WMI | 60 | 620 ms | 637 ms | 842 ms | 928 ms |
| "Virtual Bytes" counter through PDH | 30 | 128 ms | 133 ms | 175 ms | 258 ms |
| "Virtual Bytes" counter through `HKEY_PERFORMANCE_DATA` | 60 | 12.6 ms | 12.5 ms | 15.1 ms | 18.1 ms |

With the WMI query the measuring JVM used 33.9% of one logical processor instead of
6.6%, and the WMI provider host used processor time on top of that. The registry read is
the one used.

What it adds to a listing, measured in pairs: 100 cycles, each listing made once with
and once without the read, in alternating order, the machine under load.

| `ProcessSource.processes()` | Median | Mean | 95th | Worst |
|---|---|---|---|---|
| Without the virtual-size read | 57.2 ms | 60.2 ms | 83.5 ms | 179.9 ms |
| With the virtual-size read | 69.9 ms | 74.7 ms | 121.0 ms | 159.9 ms |
| Difference within a pair | 13.9 ms | 14.5 ms | | |

The read alone, 30 times in a row: 7.2 ms in the median in normal use, 13.9 ms under
load. Of 39 256 process readings in the paired run, 7 had no virtual size: processes
that exited between the listing and the read.

### What each field costs

Measured once with 390 processes, calling OSHI directly, in normal use.

| Field | Cost | Decision |
|---|---|---|
| Everything in the normal reading | 32 to 63 ms for all processes | Read every cycle. |
| Virtual size on Windows | 7 to 14 ms for all processes | Read every cycle, with one read for all processes. |
| User name | 70 ms for all processes (0.18 ms each) | On Windows, read once per process identity and kept until the process exits. Reading it every cycle would triple the cycle. |
| Command line | 21 136 ms for all processes (54 ms each) | Never read in a cycle. On demand, one process at a time. |
| Executable path | Collected by OSHI as part of its own listing | Not taken from the listing. Returned with the command line on demand, so that the reading holds only what every process has. |
| Thread details, arguments, environment, working directory | Not measured | Never read. No requirement needs them. |

On demand, `ProcessSource.details` looks the process up twice, before and after reading
the command line, so that a PID that changed hands in between is noticed. Under heavy
load it took 78 to 130 ms for a process of the same user and 420 to 520 ms for a
protected process, where path and command line come back absent. With a single lookup
and the machine in normal use the figures were 18 to 37 ms and about 180 ms.

### Cadence

**Process enumeration stays at 2 seconds** (MON-8). With the virtual sizes, in normal use
a cycle occupies the sampling thread for 43 ms in the median and 94 ms at worst, 2.2%
and 4.7% of the interval, and costs about a third of a percent of the machine. Under
load the paired measurement gave 70 ms and 160 ms, 3.5% and 8.0% of the interval.
A 1-second cadence would double that cost for a table a person cannot read faster;
nothing measured asks for a slower one.

## System metrics (milestone 1)

Measured with `./mvnw -q test -Dtest=HostMonitoringProbe -Dprobe.samples=60` at the
1-second cadence, machine in normal use.

| Measure | Value |
|---|---|
| Normal cycle, time in the source, median | 10.2 ms |
| Cycle that enumerates devices again, every 10 s | 106 to 160 ms |
| Mean cycle | 20.6 ms |
| First cycle | 1105 ms |
| CPU of the measuring JVM over 60 s | 6.44% of one logical processor, 0.40% of the machine |

Enumerating disks, file stores and interfaces afresh costs about 80, 27 and 22 ms;
refreshing objects already held costs 1 to 2 ms per disk and about 0.25 ms per interface.
That is why device objects are held and enumerated again only every 10 seconds.

**System metrics stay at 1 second** (MON-8).
