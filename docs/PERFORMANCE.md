# Performance measurements

What monitoring costs, measured, and the cadences those measurements justify (MON-8).
Numbers are from the development machine and say nothing about other systems.

**Machine.** Windows 11 Pro, build 26200; AMD Ryzen 7 5800H, 16 logical processors;
32 GiB. Measured on 2026-10-05 with JDK 25 and OSHI 7.7.0.

**Not measured.** Linux and macOS: nothing has been run there.

## The packaged application (milestone 13)

**MON-12 on the Windows image (2026-11-08).** The protocol below, run on the packaged `PRISM.exe` of commit `c8c44cd`
(the jlink runtime of the image, not the JDK), idle on the Processes page at 1280x800 with nobody at the machine.

| Measure | Value |
|---|---|
| Duration | 600.3 s |
| Processor time used by Prism | 29.05 s |
| Of one logical processor | 4.84 % |
| **Of the machine** | **0.302 %** (budget 2 %, **met**) |
| Heap after GC: start, middle, end | 34 788, 34 380, 36 066 K |
| Handles | 1 173 to 1 186 |
| Threads | 76 to 81 |
| Working set | 276 to 305 MiB |

No growth of the heap was found: the middle reading is below the start, the end 1.3 MiB above it. Details in
[m13-functional-completion.md](evidence/m13-functional-completion.md).

## OOM Guard (milestone 11)

**MON-12, the running application idle (2026-10-12).** The protocol of milestone 9, run on a
frozen copy of the classes, idle with the OOM Guard page on screen and its defaults (monitoring
on, every automatic switch off).

| Measure | Value |
|---|---|
| Duration | 609 s |
| Processor time used by Prism | 31.4 s |
| Of one logical processor | 5.15 % |
| **Of the machine** | **0.322 %** (budget 2 %, **met**) |
| Heap after GC: start, middle, end | 23 300, 25 427, 23 602 K |
| Handles | 1 197 to 1 187 |
| Threads | 81 to 77 |
| Working set | 267 to 265 MiB |
| Close request to the JVM's exit | 361 ms |

**The machine was not quiet.** Unit and integration tests ran during part of the window: the
machine averaged 16.8 % and reached 75 % at the middle sample. The figure is therefore an upper
bound, not the idle cost.

**MON-12 again, on the final code (2026-10-13).** The same protocol was repeated on the javac
classes of commit `3d6e4eb`, which come after the P0 and P1 fixes. PRISM used an isolated
`LOCALAPPDATA`, and no test ran during the window.

| Measure | Value |
|---|---|
| Duration | 610 s |
| Processor time used by Prism | 33.9 s |
| Of one logical processor | 5.56 % |
| **Of the machine** | **0.348 %** (budget 2 %, **met**) |
| Heap after GC: start, middle, end | 23 087, 22 983, 22 980 K |
| Handles | 1 201 to 1 186 |
| Threads | 82 to 77 |
| Working set | 267 to 288 MiB |
| Close request to the JVM's exit | 390 ms |

The machine averaged 8.7 %, from the user's own programs.

**What OOM Guard adds per sample** is one state-machine step on `srm-system-monitor`. It is
integer arithmetic on the sample that is already read, so it is not measured on its own. An
evaluation on `srm-limiter` runs only after a sample, a listing or a change, and with the
default switches it makes no native call.

**The memory signals (`OomSignalValidationTest`, `OomResidentValidationTest`, Windows 11,
2026-10-12).** Not costs: evidence for the platform gate of ADR-0015 §7.
- With the demo holding M = 512 MiB, available memory fell by 540 MiB (1.06 M). It recovered
  by 565 MiB (1.10 M) after the demo exited. The baseline was 10 671 MiB of 30 572 MiB.
- The demo was listed at 52 MiB without memory. Holding 256 MiB, it was listed at 309 MiB in
  two listings, and it left the listing when it exited.

## Native limits on Windows (milestone 10)

**What one native CPU limit costs Prism (2026-10-10).** `NativeLimitCostProbe` (Windows 11,
16 logical processors): the limit service holds the demo workload (4 workers, 25 % of the
machine) at a limit of 10 % through a Job Object; this JVM's processor time over 30 s is set
against the same 30 s asleep.

| Measure | Value |
|---|---|
| This JVM asleep | 0.57 % of one logical processor |
| This JVM while the native limit holds the workload | 0.26 % of one logical processor |
| The limiter's cost (difference) | none measurable (−0.31 %, within the noise) |
| The workload's share under the limit | 10.2 % of the machine (limit 10 %) |

A native limit is a few calls when it is set, changed or cleared, and nothing in between: the
system enforces it. **Met** (plan budget 3 % of one logical processor). This ends the
milestone 9 performance exception for every process limited natively; an emulated limit the
user chooses after a native one was not applied keeps the cost measured below, and the
fallback dialog says so.

**MON-12, the running application idle (2026-10-10).** The protocol of milestone 9 (below),
idle with the Processes page on screen, 612 s: Prism used 59.5 s of processor time, 9.73 % of
one logical processor, **0.608 % of the machine** (budget 2 %, **met**). The machine was busier
than in milestone 9 (17.4 % against 6.2 % on average), which the listing's share follows.
Heap after GC: 33 516, 33 288, 32 452 K; handles 1 159 to 1 155; threads 80 to 77; working set
288 to 287 MiB. Nothing of milestone 10 runs periodically.

**Ten minutes with a native limit (2026-10-10).** The same protocol, with one demo workload
(4 workers, 20 % of the machine) limited to 10 % through the Limits page ("Enforced", a Job
Object), 610 s: Prism used 24.7 s, 4.06 % of one logical processor, **0.254 % of the
machine** (milestone 9, the emulated limit: 0.946 %); **met**. The workload got 11.09 % of the
machine. Heap after GC 44 798, 43 032, 40 622 K; handles 1 193 to 1 188; threads 82 to 79.
Closing took 373 ms; the workload then ran at 19.95 % (its cap was cleared), and the journal
and the ledger were deleted.

## The emulated CPU limiter (milestone 9)

Measured on 2026-10-08 on the development machine (Windows 11, 16 logical processors, about
6,700 threads in the system). Not measured on Linux or macOS beyond what the CI run reports
(run 37943563248, `cpu-limit:` lines of `CpuLimitIntegrationTest`): on Ubuntu, 2 processors,
a 50 % limit held at 49.7 % on average with 94 suspends; on macOS arm64, 5 processors, a
40 % limit held at 38.5 %; on the Windows runner, 2 processors, a 46 % limit at 43.9 %. The
cost of the limiter was not measured there.

**What a suspend costs on Windows.** `SuspendCostProbe` (the milestone 7 controller, the demo
workload with 31 threads, 150 cycles at 5 Hz): suspend median 129.8 ms, p95 202.2 ms; resume
median 0.22 ms. `ListingCostProbe`: one Toolhelp thread snapshot of the whole system takes
about 55 ms, 45 ms of it walking 6,675 threads at about 6.7 µs each inside `Thread32Next`; a
suspend lists the system at least twice. Reading the entries over plain memory and with a
direct JNA mapping gained about 15 % and was not kept. At 5 Hz the probe JVM spent 68 % of
one logical processor.

**The cycle that follows.** The cycle of a limit is ten times the smoothed time of its
suspends, within 200 ms and 2 s (`DutyCycleController.Tuning`): about 1.1 to 1.3 s on this
desktop, 200 ms where a suspend takes microseconds (Linux, macOS). The other constants are
those of the plan: smallest run share 0.05, smoothing 0.5, a dead band of the larger of 10 %
of the limit and 0.5 points, steps of at most 25 % down and 50 % up, three intervals to
judge a status.

**What one limit costs.** `LimitCostProbe` (30 s, a 4-worker demo workload limited to 10 %
of the machine): the JVM used 10.4 % of one logical processor while limiting against 0.16 %
asleep, so **about 10.3 % of one logical processor (0.64 % of the machine) per throttling
limit** on this desktop. The plan's budget was 3 % of one logical processor per target; it is
not met on Windows. (This paragraph first said the cost scales with the number of threads in
the system; the review measurements below show it does not, because the cycle grows with the
suspend.) A limit whose target is under its cap costs nothing of the kind: it suspends
nothing and only reads the processor time once a second. One read of a process's processor
time (`ProcessSource.cpuTime`, `GetProcessTimes` on a pinned handle) took 0.126 ms (median,
p95 0.275 ms).

**What it achieves.** `CpuLimitIntegrationTest`: a 4-worker demo workload using 24.9 % of the
machine, limited to 12 %, used 12.5, 13.4 and 12.9 % in three 4-second windows (mean 12.9 %),
status `ACTIVE_THROTTLING`, 16 suspends in about 20 s. `LimitCostProbe` held its target at
10.4 % against a 10 % limit.

**On the polling path** nothing changed: the listing hands its identities to the limit
service, which looks at a managed process the listing missed on `srm-limiter`, and only
while something is managed.

### External review corrections (2026-10-09)

Measured on the same machine with probes in the test sources that are not part of the
build (`SuspendPathProfileProbe`, `LimiterScalingProbe`, `CoordinatorLatencyProbe`, each
run by hand), the demo workload as the only target, and JFR (`settings=profile`) for the
profile. About 6,250 threads in the system unless said otherwise.

**Where a suspend goes.** One thread listing of the system: the Toolhelp snapshot 7.3 ms,
the walk 62.3 ms through the `THREADENTRY32` structure, 52.7 ms through a raw pointer,
48.0 ms with a direct mapping; with 5,000 more threads (11,240) 110.7 and 92.4 ms. A
suspend as built (median 116 ms, p95 126 ms) lists twice; a prototype that lists once took
60 ms. A resume takes 0.13 ms. In the JFR profile of a running limit, 53 of the limiter
thread's 64 samples are in `Thread32Next` and 5 in `CreateToolhelp32Snapshot`; about 94 %
of its time is in the system's code, not in Java or JNA. The faster mappings save at most
about a quarter of a listing.

**What limits cost** (`LimiterScalingProbe`, 30 s per scenario, JVM processor time as a share
of one logical processor; demo targets of 4 workers, each limited to 6 % of the machine):

| Scenario | JVM, of one logical processor | Suspend (median) | Steps late (p95 / worst) | Held share of the target |
|---|---|---|---|---|
| no limit | 0.16 % | – | – | – |
| 1 limit, cycle 10 suspends | 10.8 % | 100 ms | 1.8 ms | 6.3 % |
| 2 limits | 18.9 % | – | 87 / 96 ms | – |
| 4 limits | 37.9 % | – | 183 / 215 ms | 6.2 to 6.6 % |
| 1 limit, cycle 20 suspends | 5.4 % | – | – | 5.8 % |
| 1 limit, cycle 33 suspends | 3.1 % | – | – | 5.2 % |
| 1 limit, 11,240 threads in the system | 10.7 % | 181 ms | – | – |

The heap stayed at about 8.4 MiB after collection in every scenario. Since the cycle is ten
suspends long, a limit costs about a tenth of a logical processor whatever the number of
threads; more threads only lengthen the cycle. The cost adds up per limit, and the one
limiter thread runs the steps of all limits in turn: from about four limits a step starts up
to 0.2 s late, and at about ten throttling limits the thread would be busy all the time.

**Can 3 % of one logical processor be met?** Not with a suspend that lists every thread of
the system and a cycle of at most 2 s. Measured or derived alternatives, none taken without
an architecture decision:

| Alternative | Cost per limit | What it changes |
|---|---|---|
| A. Cycle of 33 suspends (3.3 s at 6,250 threads, about 6 s at 11,000) | 3.1 % (measured) | a coarser limit: the target runs for up to seconds at a time |
| B. One listing per suspend, cycle 2 s | about 3 % (60 ms per 2 s; prototype measured, not built) | a new suspend path; the identity check of each thread must stay as strong |
| C. Thread handles kept per limit, the system listed again only every few cycles | well under 3 % (not measured) | a redesign of the Windows suspension: handles held between cycles, new threads found late; needs an ADR |
| D. Native CPU rate control (Job Object, milestone 10), the emulated limiter only where that is not available | near zero | moves the Windows limit to milestone 10's mechanism |

The undocumented `NtSuspendProcess` was not considered (ADR-0002). A limit under its cap
costs nothing measurable.

**Decision (2026-10-09): a documented exception with admission control.** The 3 % budget is
recorded as unmet on Windows, not relaxed; native Job Object limits (milestone 10) are the
remedy (ADR-0011). Where suspension goes thread by thread, at most
`max(1, min(4, processors / 4))` CPU limits run at once: 1 on 2 or 4 logical processors, 2
on 8, 4 on 16 or more; 16 with signals (Linux, macOS). Four is the largest number measured
(37.9 % of one logical processor, steps late by up to 0.2 s, held shares within the
tolerance); fewer processors get fewer limits, so the limiter's cost stays a smaller part
of a smaller machine. The rule bounds how many limits run, not what they cost: on a
machine whose suspend takes longer than 200 ms the cycle stops at 2 s and one limit costs
more than a tenth (a 300 ms suspend: about 15 %), which was not measured. `LimiterScalingProbe`
run again with the admission rule in place (16 logical processors, so four limits admitted):
one limit 11.3 %, two 20.2 %, four 37.8 % of one logical processor, steps late by 154 ms at
the 95th percentile and 184 ms at worst with four, held shares 6.1 to 6.6 % against 6 %.

**The coordinator's lock.** `CoordinatorLatencyProbe`: one durable, checked write of the
journal (temp file, `force`, atomic move, read back) takes 5.3 to 6.1 ms (median), 7.5 ms
(p95), 9.6 to 26 ms at worst. A limiter release alone takes 0.6 ms (median, worst 4 ms);
while another thread suspends and resumes a second demo for the user without pause, a
release waits for the lock up to one native suspend: median 207 ms, worst 264 ms (a suspend
took 212 ms alone, 437 ms contended). A fair lock gave no measurable gain (median 266 against
225 ms) and was not kept. The journal is no longer written under the lock (ADR-0011): a
stalled disk now delays no release, which `JournalStallTest` checks with a write that never
returns (a release takes under 0.5 s; a declaration fails within the 2 s bound).

**MON-12 and ten minutes with a limit, the running application (2026-10-09).** The
protocol of milestone 3 (below): the application as `./mvnw -o -q javafx:run` starts it, a
separate instance (the user's own instance kept running beside it), the window visible at
1280 x 800, 90 s of warm-up, then the processor time of the JVM at both ends of 600 s, the
heap in use after a forced collection (`jcmd GC.run`, `GC.heap_info`) at the start, the
middle and the end, the handles and threads of the process every 30 s, class histograms and
thread dumps at both ends. Two runs: idle with the Processes page on screen, and with one
demo workload (4 workers, 20 % of the machine) under a CPU limit of 10 % and the Limits page
on screen.

| Measure | Idle, Processes page | One CPU limit, Limits page |
|---|---|---|
| What else was running (machine, mean of 20 samples) | 6.2 % | 12.4 % |
| Processor time of the JVM over 610 s | 29.5 s | 92.3 s |
| CPU, of one logical processor | 4.83 % | 15.14 % |
| **CPU, of the machine (16 logical processors)** | **0.302 %** | **0.946 %** |
| Heap in use after GC: start, middle, end | 33 365, 35 395, 37 915 K | 53 925, 54 423, 49 721 K |
| Handles: start, end (range of the 30 s samples) | 1 173, 1 166 (1 166 to 1 178) | 1 178, 1 175 (1 171 to 1 210) |
| Threads: start, end | 80, 77 | 81, 79 |
| Working set: start, end | 275, 274 MiB | 283, 311 MiB |
| The limited target's share over the 610 s | – | 10.12 % of the machine (limit 10 %) |
| Close request to exit of the JVM | – | 369 ms; the target ran again (0 of 29 threads suspended), the journal was deleted |

**Met:** idle, a sixth of the MON-12 budget of 2 %; with a limit throttling, under half of it.
The difference between the runs, about 10.3 % of one logical processor, is the cost of one
limit measured above. **The heap:** the idle run's three single readings rise by 4.5 MiB; the
class histograms show where: 8,021 more `com.sun.jna.Memory$MemoryDisposer` with their
cleaner references and boxed addresses (about 1.3 MiB), native memory of OSHI's listings that
JNA's cleaner had not yet released, and 316 more entries of JavaFX's text layout cache (both
seen in milestone 3). Prism's own instances went from 3,750 to 3,812: 49 more
`ProcessIdentity` and 13 more `ProcessSnapshot`, with more processes running. The limit run's
heap fell. **The handles** move by up to about 30 while a limit runs, the thread handles a
suspension holds during a stop phase (the target has 25 to 29 threads), and come back; no
trend in either run. The working set rose by 28 MiB in the limit run and not in the idle
run; as in milestone 3, whether it levels off needs a run of an hour or more.

## Process control (milestone 7)

Milestone 8 changes nothing here: the Linux and macOS controllers run on the same thread, only
when the user asks, and the listing path is the same. A Linux suspension holds one pidfd while it
lasts. Not measured on Linux or macOS.

Process control adds nothing to the polling path. The actions run on their own thread,
`srm-process-control`, only when the user asks; no window, thread or protection fact is
read for a listing. With no process held suspended, a listing passes the suspension
coordinator without a copy or a set being built; with one held, the identities of the
listing are put in a set once and the held ones marked, a few hundred lookups every 2 s.

A suspension costs at most three system-wide Toolhelp thread snapshots and one open handle
per thread of the target for as long as it is held: Prism's handle count rose by 24 for a
27-thread workload and fell back when it was resumed or when the workload was killed.

Measured on 2026-10-08 with the demo workload (MILESTONES.md, milestone 7): Prism's own CPU
over a 153-second run of every action, with the UI read through UI Automation every few
seconds, was 1.1% of the machine; idle with the Processes page on screen it is about 0.25%
(milestone 5). The listing went on every 2 s throughout.

## The visual foundation (milestone 5.5)

The redesign changed the presentation only: the typeface, icons, panels, the page header,
the segmented selector, the sort mark, the inspector and short finite transitions. It was
measured before and after on the same day, with the same scripts, on the same machine
(Windows 11 Pro 26200, 16 logical processors, JDK 25.0.4.1, JavaFX 25.0.4; the application
started from a copy of `target/classes` with the dependency classpath so that the measured
code did not change while the redesign was being written; another instance of Prism of an
older build, left running by the user, and the IDE were alive throughout). Before is the
milestone 5 code at `caba442`.

**Idle cost with the Processes page on screen**, 60 s warm-up, 300 s measured, forced
collection and `jcmd` readings at both ends (the procedure of milestone 3); the page
selected through UI Automation, nothing touched afterwards:

| Measure | Before, run 1 | Before, run 2 | After |
|---|---|---|---|
| CPU of the machine | 0.316% | 0.315% | 0.321% |
| CPU of one logical processor | 5.06% | 5.04% | 5.13% |
| Heap used after GC, start → end | 29 375 → 26 423 K | 28 895 → 28 992 K | 32 534 → 26 537 K |
| `javafx.scene.*` instances, start → end | 58 678 → 58 678 | 58 678 → 58 678 | 54 729 → 54 729 |
| Table cells (`TableRow*`, `TableCell*` instances) | 2 322 → 2 322 | 2 322 → 2 322 | 1 935 → 1 935 |
| Threads, start → end | 59 → 57 | 59 → 57 | 57 → 57 |
| Working set, start → end | 273 → 298 MiB | 260 → 262 MiB | 266 → 279 MiB |
| Process start to window handle | 3 480 ms | 2 113 ms | 2 364 ms |
| Close request to JVM exit | 360 ms | 385 ms | 348 ms |

The idle cost is unchanged within the noise of two runs of the same code (0.001 to 0.006
points); the heap is flat; the scene is smaller, because 28 px rows show fewer rows than
26 px ones did and the table creates cells for what it shows. The first "before" run had the
probes of this milestone running on the machine at the same time; the second did not. The
start-up time is dominated by class loading and JIT and varies by a second between runs; the
three font files (606 KB in the sources, read by the toolkit in `App.init()`) are inside that
noise. The jar grew from 0.29 MB to 0.60 MB (the three font files, compressed).

**Update costs** (`OverviewUpdateCostProbe`, `PerformanceUpdateCostProbe`,
`ProcessesUpdateCostProbe`, 1 000 updates each, medians and 95th percentiles in ms, "with
layout" unless stated):

| Case | Before median / p95 | After median / p95 |
|---|---|---|
| Overview, every value and history | 1.19 / 2.30 | 1.33 / 2.55 |
| Performance CPU, 16 processors | 0.72 / 1.18 | 0.92 / 1.65 |
| Performance Memory | 0.48 / 0.79 | 0.73 / 1.28 |
| Performance Disk, 3 file systems | 1.33 / 2.52 | 1.47 / 2.03 |
| Performance Network, 36 interfaces | 2.30 / 3.56 | 2.35 / 3.03 |
| Processes, 400, nothing changes | 2.72 / 3.32 | 2.54 / 3.13 |
| Processes, 400, a quarter changes, sorted by name | 5.27 / 6.98 | 4.59 / 5.63 |
| Processes, 400, sorted by CPU | 5.70 / 7.17 | 4.98 / 5.79 |
| Processes, 400, all values change | 5.52 / 7.04 | 4.27 / 5.62 |
| Processes, 400, 10 start and 10 exit, one selected | 5.83 / 7.26 | 5.03 / 6.12 |
| Processes, 400, filtered | 2.61 / 3.29 | 2.41 / 2.80 |
| Processes, a key typed in the filter | 2.33 / 7.00 | 1.97 / 5.68 |
| Processes, a sort by another column | 5.88 / 7.96 | 6.95 / 8.82 |
| Allocated per listing, rows and layout, 400 processes | 846 to 1 790 KiB | 779 to 1 572 KiB |

The pages of system metrics cost 0.1 to 0.5 ms more per update: the metric panels have a
surface, a header line of a fixed height and a caption bound to the state, and the Overview
has a page header and a facts panel. That is a fraction of a millisecond once a second. The
Processes page costs less per listing, because fewer rows are on screen; a sort costs about
1 ms more, because the sort mark and the header style change with it. The 95th percentile of
the Overview and of the Memory section rose by more than the 5 % the plan allowed as noise;
the absolute figures (2.55 and 1.28 ms) are the reason that was accepted and recorded here
rather than optimized.

**Transitions.** Every transition is finite (100 to 160 ms) and starts on a user action or a
change of state; nothing runs while the application is idle, which the idle figures above
show. With reduced motion every duration is zero.

**Not measured:** the minimized window, pulse times, hot spots, the 125 % monitor, runs longer
than five minutes, Linux and macOS.

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
