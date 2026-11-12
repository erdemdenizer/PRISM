# Testing and verification

Commands use `./mvnw` (Bash, macOS, Linux). In PowerShell use `.\mvnw.cmd`.
The build requires JDK 25 in `JAVA_HOME`; the enforcer stops with a message otherwise.

## Fast check (while iterating)

```
./mvnw -q compile                          # main sources, warnings are errors
./mvnw -q test -Dtest=RateCalculatorTest   # the tests for what you touched
./mvnw -q test -Dtest='Rate*Test,ArchitectureTest'
```

Seconds, not minutes. Use it after each small change. Compiler warnings are errors, and
`-q` hides their text: if the build fails with only "warnings found and -Werror
specified", rerun without `-q`.

## Full gate (before calling work done, before committing)

```
./mvnw spotless:apply     # only if formatting needs fixing
./mvnw verify
```

`verify` runs, in this order:

1. **Enforcer**: JDK 25, Maven 3.10.x, pinned plugin versions, no snapshot dependencies,
   upper-bound dependency versions, banned artifacts (non-JPMS JNA, OSHI FFM).
2. **Compile** with `-Xlint:all` (minus `serial`, `processing`, `path`, `this-escape`);
   any warning fails the build.
3. **Tests** (JUnit 5), including `ArchitectureTest`.
4. **Spotless check**: palantir-java-format for Java, sorted `pom.xml`.
5. **JaCoCo report**: `target/site/jacoco/index.html`. No coverage threshold is enforced
   yet; one will be set by ADR when there is real logic to measure.
6. **SpotBugs**: effort Max, threshold Medium; any finding fails the build.

Do not skip steps (`-DskipTests`, `-Dspotbugs.skip`, ...). A finding is fixed, not
suppressed; a SpotBugs exclusion needs a written reason in `config/spotbugs/exclude.xml`.

## What to test

| Layer | Approach |
|---|---|
| `domain` | Plain unit tests of records, validation, value arithmetic. |
| `application` | Unit tests against hand-written fakes of the `platform` interfaces: rate calculation (zero interval, counter reset and wrap, first sample), bounded histories, state machines (every transition, invalid transitions, failure mid-transition), polling that must not overlap, shutdown. |
| `platform.oshi`, `platform.<os>` | Unit-test what does not need the real OS: parsing of `/proc` and cgroup files from fixture text, error-code mapping, capability reporting. Native calls sit behind a narrow seam (`platform.windows.Win32`) with a hand-written fake that returns what the documentation says the call returns, including its failures. Real native calls are tested on their own system where that is safe (see "Process control" below) and otherwise verified manually and reported as such. |
| `ui` | View models, formatting, the graph arithmetic and the relay to the UI thread are plain unit tests: JavaFX properties need no running toolkit. Views hold layout and binding only; they are rendered as snapshots for visual inspection, and what needs a scene (a color the stylesheet resolved, what a control does with a key, the number of nodes) is asserted in tests tagged `ui-snapshot`. |
| `app` | The monitoring runtime against fake sources, with its real executors and threads: start-up, failures, shutdown in every state, the time bound. |
| Architecture | `ArchitectureTest` checks layer boundaries on every run. |

Rules:

- A test must be able to fail. No assertions on mocks alone, no tests that restate the
  implementation.
- Tests log to the console only (`src/test/resources/logback-test.xml`); a test run
  never writes into the log folder of the person running it.
- Prefer real objects and small fakes over mocking frameworks. Mockito is not a
  dependency; add it only by decision if a real need appears.
- Tests do not depend on the host: no real PIDs, no host locale or time zone, no
  `Thread.sleep` for synchronization. Locale-sensitive code is tested with explicit
  locales, including one where it matters (`tr-TR`: `"I".toLowerCase()` is not `"i"`).
- Code for an operating system it was never run on is unverified; say so in the report.

## UI snapshots

`SnapshotHarness` (test sources, package `prism.srm.ui.snapshot`) renders a node tree
with the application stylesheet to a PNG without launching the application.

```
./mvnw -q -Pui-snapshots test      # writes target/ui-snapshots/<name>-<theme>-<w>x<h>.png
```

```java
@Tag("ui-snapshot")
class ProcessTableSnapshotTest {
    @Test
    void emptyState() {
        SnapshotHarness.render("process-table-empty", Theme.LIGHT, 1100, 700, () -> buildView());
    }
}
```

- `SnapshotHarness.inspect` builds a scene the same way and hands its root to a
  function instead of writing an image, for assertions that need applied CSS or layout.
- `SnapshotHarness.render` takes an optional action that runs after the first layout,
  for a state a control can take only once it has its skin and its size, such as a table
  scrolled to a column.
- Tests tagged `ui-snapshot` are everything that needs the JavaFX toolkit, renders and
  assertions alike. They are excluded from the normal build and run only with the
  `ui-snapshots` profile. `./mvnw verify` therefore does not run them: a change to a
  view or to the shell is not verified until the profile has passed as well. Rules that
  can be stated without a scene are kept out of the views and tested in the normal
  build (for example which status the shell shows for which failure). Without the profile, `-Dtest=SomeSnapshotTest` runs zero tests
  and still reports success; add `-Pui-snapshots`.
- The harness gives its scene the color scheme of the theme (`Scene.Preferences`), so a
  render is the same on a machine in light mode and on one in dark mode, and asks the scene
  for reduced motion, so a render never shows the middle of a transition; the motion
  itself is tested where it is started (`MotionSceneTest`, `SectionTabsTest`). It loads the
  bundled typeface once, as the application does, and fails if that does not work.
- Add one snapshot per screen and per state (empty, loading, error, extreme values) at
  the default and the minimum window size, in both themes. Open the PNGs and compare
  against [UI_GUIDE.md](UI_GUIDE.md).
- Limits: needs a display (no headless rendering); renders at scale 1.0; captures one
  scene only, so popups, tooltips, hover states and window decorations are not in the
  image. Check those in the running app (`./mvnw javafx:run`).
- PNGs are build output. They are not committed and not compared automatically: fonts
  differ per operating system.

## Demo workload

The demo workload (`prism.srm.demo`, DEMO-1 to DEMO-3) is the process every destructive
manual test and demonstration targets (SAFE-6): a separate program that keeps a chosen
share of the machine busy, holds a chosen amount of memory, reports what it does, and
exits by itself. It is built by `./mvnw -q package` (and by `verify`) as a jar without
dependencies, and started with the JDK of the project:

```
java -jar target/prism-0.1.0-demo.jar --cpu 25 --memory 256MiB --duration 5m
java -jar target/prism-0.1.0-demo.jar --help
java -cp target/classes prism.srm.demo.DemoWorkload --duration 10s     # after ./mvnw -q compile
```

| Option | Value | Default | Bound |
|---|---|---|---|
| `--cpu` | share of the whole machine, `0` to `100`, a period for decimals (`6.25`) | `0` | the unit of PROC-2: 100 is every logical processor busy |
| `--memory` | `<n>MiB` or `<n>GiB`, or `0` | `0` | never above the smaller of 1 GiB and a quarter of physical memory (DEMO-2) |
| `--duration` | `<n>s` or `<n>m` | `5m` | `1s` to `30m`; there is no unlimited mode |
| `--workers` | threads for the CPU share | the fewest that can carry it | `1` to the logical processors; at least what the share needs |
| `--help`, `-h` | | | prints the options and the bounds of this machine |

A request above a bound is refused with a message that names the bound, exit code 2;
nothing is clamped, so the process always does what its command line says. The bound on
memory is checked again by the allocator, so it cannot be passed by any argument. On a
machine whose physical memory the JVM does not report, no memory can be requested.

The CPU share is split into the fewest threads that can carry it, each busy for a part of
every 100 ms period and asleep for the rest (25 on 16 processors: four threads, fully
busy; 30: five threads, 96 percent busy). A period that took longer than it should have,
because the sleep overran or the process was suspended, is made up in the next period,
but never by more than one period: a process that was suspended for a minute is back at
its share within 200 ms, which is what a demonstration of suspension and of a CPU limit
needs. The memory is native memory outside the Java heap, allocated in 16 MiB chunks and
written page by page with a different value on every page, so the resident memory of
the process rises by the amount asked for, on top of the JVM's own footprint (about
46 MiB on Windows), and stays: Windows combines identical pages of a process into one
shared page, and with the same byte on every page the private memory of a 256 MiB
workload fell to 23 MiB within a minute while its working set still read 302 MiB.

It prints one line per event, `key=value` pairs in ASCII, and a status line every 5 s:

```
prism-demo 0.1.0
event=start pid=31872 processors=16 physical=29.86GiB cap=1.00GiB cpu=25.0 workers=4 duty=1.00 memory=256MiB duration=20s mode=cpu+memory
event=memory status=allocated bytes=268435456 chunks=16 millis=59
event=status elapsed=5s remaining=15s cpu-self=24.6 memory=256MiB
event=end reason=completed elapsed=20s exit=0
```

`cpu-self` is the share of the machine the process measured for itself from its own CPU
time, to be compared with the CPU column of Prism. The end line names the reason:
`completed` (exit code 0), `allocation-failed` (the system refused a chunk, which is what
a commit limit on the process looks like on Windows; a Linux cgroup limit may instead end
the process without a line; the memory held so far is released and the run ends, exit
code 1), or `interrupted` (Ctrl+C or a termination request; the shutdown hook waits up to
2 s for this line, which says `exit=signal` because the exit code is then the JVM's for
the signal, 130 after Ctrl+C). In Prism
the process is listed as `java` like any other; it is found by its PID, which the start
line prints, and the inspector shows the jar in its command line.

**Tests** (`src/test/java/prism/srm/demo/`): the parsing, the bounds, the split of the CPU
share and the duty-cycle arithmetic are plain unit tests; the memory workload is tested
with a fake allocator that refuses a chunk, so no test takes real memory beyond a 2 MiB
smoke test; `DemoRunTest` runs the whole thing for 300 ms and asserts what was printed,
why it ended and that no worker thread is left; `DemoWorkloadProcessTest` starts the
entry point as a child JVM with no load for one second and checks the exit codes. The
load and the memory are verified against the system's counters and against Prism's
process table by hand ([MILESTONES.md](MILESTONES.md), milestone 6).

## Process control

Milestone 7 tests process control at three levels.

- **Against fakes.** `FakeWin32` (`src/test/java/prism/srm/platform/windows/`) stands in
  for the Win32 seam: handles it counts (the tests check that none is left open),
  `(DWORD)-1` from `SuspendThread` and `ResumeThread`, error codes, thread and process
  exit codes, windows with their owner, visibility and style. The Windows classes are
  tested against it for every documented failure: access denied, a process that exited
  or whose PID now belongs to another start time, a thread that moved to another
  process, a suspension that fails half way and its rollback, a release that fails on a
  live thread. `FakeProcessController` (`application`) does the same one layer up for the
  control service and the suspension coordinator: one action per identity, protection
  before the platform, a listing that misses a held process once, a confirmed exit, a
  target that comes back, the shutdown order, and Resume as the only action once process
  monitoring has ended (`MonitoringRuntimeTest` with a listing that ends with an `Error`,
  `HeldSuspensionsTest` for the page).
- **Against the real system, without touching anything that is not the test's.**
  `JnaWin32Test` calls the mappings on the test's own JVM and only reads: sizes of the
  structures, the creation time and exit code of this process, the threads of this
  process, and the rights the System process (PID 4) refuses, which are asked for and
  never used. `WindowsControlIntegrationTest` suspends, resumes, re-prioritises and
  terminates a child process it starts itself, the demo workload (`DemoTarget`), and
  checks the effect through its CPU time and `GetPriorityClass`; an identity with a
  wrong start time is refused for every action. `WindowsEndTaskWindowTest` (tag
  `ui-snapshot`, since it opens a window) starts `WindowedTarget`, a plain AWT window in
  a child JVM, and checks that a close request makes it close itself with exit code 0.
  These run on Windows only (`@EnabledOnOs`). No test acts on a process it did not
  start: not on Maven, not on the test JVM's parent, not on a system process (SAFE-6).
- **In the UI.** `ProcessActionsTest` drives the action model without a toolkit: what
  is enabled and why, the action in flight, the result that belongs to the selected
  process. `ProcessActionsSceneTest` (tag `ui-snapshot`) checks the confirmation (Cancel
  is the default, the act button is not), the context menu, and actions shown disabled
  with the reason when the report says `UNAVAILABLE`. The snapshots show the actions,
  a result, a failure, "Suspended by Prism", the unavailable state and the Logs page.

Milestone 7.5 adds the interaction around them:

- `ProcessActionsTest` (no toolkit): the level the listing shows and no other (no mark for the
  level asked for until a listing shows it; none for a number that stands for no level), the
  line beside the actions (the action on its way, a result in two parts and its tone, the reason
  never as a result), when a result goes (another selection, a filter, monitoring stopping) and
  when it stays (listings, an exit), and the state "Suspended by Prism" in the inspector.
- `ProcessActionsSceneTest` (tag `ui-snapshot`): the order of the actions in the inspector and in
  the context menu; the mark of the Priority menus and its move with the listing; the focus on the
  table once the action that had it is disabled, and back on the button after a declined
  confirmation; the line keeping its first part where it is narrow; the fade of a new result and
  none with reduced motion; a long name cut in the confirmation while its PID is not. It also
  renders the confirmations (`confirm-*.png`), which are windows of their own.
- `LogsPageSceneTest`: the Time and Severity columns measured from the font
  (`ui.control.TextWidths`) on the 4 px grid and wider for a wider font, no Time or Severity cell
  cut, and Ctrl+C (with Ctrl and Meta both down, whichever the shortcut is) copying the selected
  entry whole, into a test's own clipboard rather than the system's.
- `ResultToneTest`: for every `OperationResult`, the tone of the line is the severity of the log
  entry; `ControlMessagesTest`: a suspension reads the same whoever reports it, and no refusal or
  suspension wording shows a code or a native detail.
- `WindowsProcessControllerTest`: the table of levels is the base priority of each of the five
  classes; `platform.ListedPriorityTest` (Windows, reads only): the listing's number for this JVM
  stands, in the table, for the class this JVM actually runs in, read on its own with
  `GetPriorityClass`, tying the two implementations together. It assumes no class: GitHub's
  Windows runner starts jobs below normal.
- `ProcessesSnapshotTest` adds an action on its way, an action that failed, a long name in the
  result and in the actions, and the keyboard focus on an action (`processes-action-focused`);
  `ProcessActionsSceneTest` the confirmations with the keyboard focus on Cancel, their default
  button (`confirm-*-cancel-focused`).

Milestone 8 brings Linux and macOS, at the same three levels:

- **Against fakes, on every system.** `FakeProc` writes a proc file system into a folder of the
  test's own (the stat files in the form of proc_pid_stat(5), `/proc/stat` with `btime`), so
  `ProcFs` reads real files; `FakeLinux` gives the pidfd calls their documented meaning: a
  descriptor refers to the process that had the PID when it was opened and answers ESRCH once
  another has it; a signal to a zombie succeeds to no effect; `setpriority` sets one thread. It
  counts open descriptors and double closes. `LinuxProcessControllerTest` pins the order of the
  calls (open, then the identity check, then the signal, then close), a PID taken over right
  after the open, the probe on the whole path and the four signal actions `UNAVAILABLE` where it
  fails, with no signal sent, every errno, the stop of a process stopped already, every thread
  set and read back, partial results, the facts. `FakeMacos` and `MacosProcessControllerTest` do
  the same for macOS, where `kill` reaches whatever process has the PID. `ProcStatTest`,
  `LinuxErrorsTest`, `KinfoProcTest` (the offsets of the `kinfo_proc` head, checked on any 64-bit
  system) and the architecture rule of `JnaLinuxTest` need no Linux or macOS.
- **Against the real system** (`@EnabledOnOs(LINUX)`, `@EnabledOnOs(MAC)`): `JnaLinuxTest` and
  `JnaMacosTest` read this JVM (the probe; its start time from `/proc` or `sysctl` equals the
  JDK's and the listing's); `LinuxControlIntegrationTest` and `MacosControlIntegrationTest` act
  on the demo workload they start (`platform.DemoProcess`): a stop and its CPU, SIGTERM with the
  workload's `exit=signal`, SIGKILL with exit code 137, a priority lowered and refused when
  raised again, a stale identity refused by every action, the test's descriptors unchanged.
  `ListedPriorityTest` runs on Linux too, where the nice value comes from field 19 of
  `/proc/self/stat` and the listing shows field 18.
  - They run in the CI workflow (`.github/workflows/ci.yml`: Windows, Ubuntu and macOS, on every
    push to `main` and every pull request). They have passed there on Ubuntu x86_64 and macOS
    arm64 (MILESTONES.md, milestone 8).
  - A job that passes does not show by itself that the tests of its system ran. Each job keeps
    its surefire reports as an artifact, where the tests that ran and those skipped are read
    per system.
- **What a runner gives is read, not assumed.** The runners differ from a desk machine: GitHub's
  Windows runner starts jobs below normal, and its macOS runner starts them at nice -10.
  - The priority tests start from the level or nice value the process actually has.
  - On Linux and macOS that value is read by Prism and checked against `ps`
    (`platform.SpecNice`, `platform.Ps`: read-only, test code only; SAFE-5 binds production
    code).
  - They lower it to the next level of the CTL-6 table, written in the test independently of the
    controllers, never to High, so a level is always left to raise to. They then ask to raise it
    in every case: refused with EACCES without privilege, and allowed where the kernel allows it
    (Linux: `CAP_SYS_NICE` or the workload's `RLIMIT_NICE`; macOS: root). `SpecNiceTest` pins
    these steps for every start from -20 to 19.
  - A stale-identity test asks for a level the workload is not at, so that acting on it would
    show.
- **The CPU time of the workload.** On macOS the JDK reads the CPU time of its own process only
  (`ProcessHandleImpl_macosx.c`).
  - There `platform.DemoProcess` reads it from the process listing.
  - `DemoProcessCpuTest` checks that every reading of the listing lies between two independent
    readings taken around it: the JDK's, or `ps -o time=` on macOS. It also checks that the time
    grows while the workload works, by no more than the elapsed time over all processors.
- **In the UI**, on Windows: the words of each mechanism (`ControlMessagesTest`,
  `ProcessActionsTest`, `ProcessActionsSceneTest`), the confirmation asked with the mechanism,
  every unavailable action named on the page, and the snapshots `processes-actions-signals`,
  `processes-action-priority-denied`, `processes-actions-no-pidfd`, `confirm-end-task-signal`.
- `ArchitectureTest.noProductionCodeStartsAProcess` (SAFE-5): no production class uses
  `ProcessBuilder` or `Runtime.exec`.

The live verification with the running application is in [MILESTONES.md](MILESTONES.md),
milestones 7, 7.5 and 8. Any further manual check of a control action targets the demo workload or
a window of the tester's own making, never an application someone uses.

## Limits and the suspension journal (milestone 9)

- **On a virtual clock.** `VirtualScheduler` stands in for `srm-limiter`: tasks run in
  order as a test advances the clock, and a task may let time pass (a suspend that takes
  110 ms). `SimulatedTarget` is a process that uses a fixed share of the machine while it
  runs and nothing while it is suspended, wired into `FakeProcessController` and
  `FakeProcessSource`. `DutyCycleControllerTest` covers the arithmetic of milestone 9's
  list; `LimitRunTest` covers what a limit holds a target to, cancellation in each phase,
  an exit, every answer of the coordinator, the journal failing while stopped and while
  running, a `RuntimeException` and an `Error` in a step (the target is released either
  way, the `Error` goes on), a step that cannot be scheduled, an unreadable time and a
  limiter thread held up for seconds; `LimitServiceTest` the managed processes (MGD-4
  refusals, MGD-1), the 16 limits and 32 waiting requests, the journal as precondition
  (a limit whose entry cannot be written touches nothing; a failure while limits run
  stops all of them, releases them and keeps the evidence), exits found by the limit and
  by the listing, and the shutdown.
- **The journal on disk.** `SuspensionJournalTest` writes in a folder of its own through
  `FaultyJournalFiles`, the real file operations with switches that make them fail as a
  disk does: full, refused, a move the system will not make, bytes that read back
  otherwise, a lock lost. Dead and live instances are told apart by their locks; a damaged
  file is read as far as it goes and copied aside. The POSIX permissions test runs on
  Linux and macOS only. `JournalFolder` gives each such test its own folder and deletes it
  with a few tries: on Windows a program that scans a file just written may hold it open
  for a moment, which JUnit's `@TempDir` takes for a failure.
- **The coordinator.** `SuspensionCoordinatorJournalTest` (written ahead, not per cycle,
  refused without a declaration, what the user is shown), `SuspensionCoordinatorRecoveryTest`
  (every class of entry after a crash, Resume through `adopt`, Dismiss, carried entries at
  the next shutdown), `ProcessControlRecoveryTest` (the requests of the page).
- **Windows adopt.** `WindowsAdoptTest` drives `WindowsSuspensions.adopt` against
  `FakeWin32` through the thread table of ADR-0011: all 1, any ≥ 2, all 0, mixed, a thread
  gone or replaced, a debugger, a probe that cannot be undone, and a memento-less entry
  that makes no native call at all.
- **On the real system.** `CpuLimitIntegrationTest` (Windows, Linux, macOS) limits the demo
  workload with the controller and the source of the system: a busy target held near its
  limit (the independent measure is the JDK's CPU time, the listing on macOS), a target
  under its limit never suspended, removal, an exit during a limit, a crash simulated by
  ending a session that left a user suspension in its journal and the next session
  listing it and resuming it only on request, and on Linux and macOS a stop another
  program makes, which the limiter neither holds nor continues. `LimitCostProbe` measures
  what one limit costs (run by hand).
- **The emulated limit on a busy machine (2026-10-11).** The hosted two-processor Windows runner
  failed `CpuLimitIntegrationTest` three ways in a row. Two were other processes taking the
  processors: the target fell below its limit, the duty cycle let go, and it overshot after. The
  third was an exiting target's thread refusing a suspend. So the accuracy test measures, for
  each 4 s window, what the other processes used of the test's processors: the system's busy
  ticks, less the target and the test JVM. The unlimited baseline counts only with others at
  most 15 points. A limit window counts only if it and the one before left the target its limit
  plus 15 points. Three such windows are required within twelve, or the test fails with what it
  measured; it never skips. The bounds of plan §8.2 are unchanged.
  - The status check is no longer one instantaneous reading, which a legitimate let-go phase
    can make "not throttling". Instead, "not throttling" may be at most half of the 50 ms
    samples taken while the counted windows ran, and the status must reach throttling or
    adjusting within 5 s.
  - A limiter that never throttles, one that throttles to a third, and a status stuck at "not
    throttling" each fail the test. This was shown by changing `DutyCycleController` in a
    two-processor run.
  - The busy ticks are the whole machine's. Only the stress harness, which pins the test to the
    first processors, says so with `-Dprism.test.pinnedToFirstProcessors=true`; then those
    processors' ticks are read instead.
  - The 50 ms status sampler fails the test if it throws, and must have taken at least half of
    its samples.
  - The exit test now also checks that an exit is logged as one, with no error. One look a
    cycle later (at least 200 ms) covers an exit that took at most 16 ms in the provoked runs;
    a slower exit would fail the test, not pass it.
- **External review corrections.** A suspend failure is retried only when it is transient
  and left nothing held: `WindowsSuspensionsTest` classifies each failure of the Windows
  suspend (a thread that could not be suspended or opened, after a complete rollback, is
  transient; a refused open of the process, a mismatched identity, a failed listing, a
  vanished thread found again and an incomplete rollback are not),
  `SuspensionCoordinatorJournalTest` that the coordinator passes it on only when it holds
  nothing, and `LimitRunTest` that only such a failure is tried again, three in a row at
  most, while every other kind ends the limit; one that holds nothing ends it after one more
  reading a cycle later, as an exit if the target is gone by then, as the failure otherwise. `JournalStallTest` runs the journal
  on a thread of its own, as the application does, with a write that never returns
  (`FaultyJournalFiles.stall`): a release does not wait for it, a declaration and a check
  fail within the bound and fail the journal, the user's suspension goes ahead unrecorded,
  and the shutdown releases and gives up on the close within its bounds;
  `SuspensionCoordinatorJournalTest` has the same stall with the journal written inline.
  `CpuLimitDialogSceneTest` presses Escape in the field of the CPU limit dialog (found in
  the real application, where the field kept Escape to itself), and
  `ProcessActionsSceneTest` puts the result line under the actions in the smallest window.
  The probes of the review (`SuspendPathProfileProbe`, `LimiterScalingProbe`,
  `CoordinatorLatencyProbe`, with `ThreadHolder` for many idle threads) are run by hand.
- **Admission (the Windows performance exception).** `LimitServiceTest`: the allowance for
  1 to 128 logical processors with per-thread suspension and with signals; a refusal that
  says the number and why and makes no call, declares nothing and keeps no percentage;
  a disabled limit that takes no place, an Enable that asks for one again and stays
  disabled when refused, Remove and Unmanage that free one; eight threads asking at once
  with a real limiter thread, of which exactly the allowance start.
- **Hard kills on the real system.** `LimitingChild` writes its journal on an `srm-journal`
  thread as `MonitoringRuntime` does; with `hold` it stops its limiter once its own
  coordinator holds the workload, and `CpuLimitIntegrationTest` checks the stop from
  outside (the kernel's state, no processor time) before it kills the child. Without
  `hold`, the child says "limiting" only once its journal holds the limit's entry as
  written and read back and the limit is at work. Before each kill the test reads the
  child's journal from outside (one entry: the workload, `CPU_LIMITER`); after it, the test
  looks for the dead journal for at most 15 s, reports how long its lock took to go, and
  fails with what it found. Measured with every JVM held to two logical processors and a
  constant busy neighbour: the `hold` child sets up the platform (OSHI, JNA) in 2.6 to 7.7 s
  and holds the workload 7 to 16 s after it starts, within the 30 s bound. A child that sizes
  itself for 16 processors while held to two (not the case on a two-processor runner) spends
  21 to 30 s on the platform alone and fails; that is outside what CI runs, so the bound stays.
- **Journals read later (recovery visibility).** `SuspensionJournalTest`: a locked journal
  of an ended session waits, one of a running session is left alone, and the waiting one is
  read once its lock is free; whether the writer of an instance name runs.
  `SuspensionCoordinatorRecoveryTest`: a journal freed later is listed once and carried
  over; of several journals the waiting one comes later without duplicates; a dismissed
  entry is not listed again; a transfer that cannot be written keeps the file; nothing is
  read once the shutdown has begun; none of them resumes, suspends or adopts.
  `MonitoringRuntimeLimitsTest`: a lock freed after start-up is listed in the same session
  (this failed before the change) and the page is told pending, then complete; a running
  session's journal is neither read nor waited for and the page is told nothing; a shutdown
  while a journal waits ends within its bound, keeps the file and never says the list is
  whole; a journal still locked when Prism stops looking is reported as not checked and kept.
  `RecoveredSuspensionsTest` covers what the summary says in each state, and
  `ProcessesSnapshotTest` (`processes-recovery-pending`, `processes-recovered-pending`,
  `processes-recovery-exhausted`, `processes-recovered-incomplete`) and
  `RecoveredSuspensionsSnapshotTest` (`recovered-list-pending`, `recovered-list-incomplete`)
  render them.
- **The pages.** `LimitsViewModelTest`, `ManagedActionsTest`, `RecoveredSuspensionsTest`
  and `CpuLimitDialogTest` without a toolkit; `LimitsSnapshotTest` and the milestone 9
  states of `ProcessesSnapshotTest` and `OverviewSnapshotTest` for the eye.

## Native limits on Windows (milestone 10)

- **Against fakes, on every system.** `JobObjectStructuresTest` checks the 64-bit sizes and
  field offsets of the four Job Object structures, the flag values, and scans the sources of
  `platform.windows` for `KILL_ON_JOB_CLOSE`, its value and the terminate calls, which must not
  appear. `WindowsJobLimiterTest` drives `WindowsJobLimiter` against `FakeWin32`, whose job
  model loses its name with its last handle: every phase of an assignment (not assigned,
  enforced, other values, uncertain, the target exited), every phase of a change, forget with
  and without survivors, recovery's verdicts, and shutdown, with the invariant that the open job
  handles are exactly the holds. `NativeLimitLedgerTest` covers the ledger's versions, a late
  write, a damaged file and a full disk; `NativeLimitRecordsTest` the per-kind values of a
  pending change. `LimitServiceNativeTest` runs the limit service against `FakeResourceLimiter`
  (native first, the offered fallback and consent, the barrier of an incomplete release, the
  ledger written first, an earlier session's cap, memory limits, shutdown).
  `MonitoringRuntimeNativeLimitsTest` runs the runtime with its executors: shutdown and the exit
  hook clear the native limits, and an earlier session's ledger is read at start-up.
- **On the real system, Windows only, on processes the test starts.**
  `WindowsJobIntegrationTest` (the 20 % cap, the commit limit, a process in another program's
  job, an exited process, forget), `NativeLimitRecoveryIntegrationTest` (a Prism stand-in,
  `NativeLimitingChild`, is killed while it holds a limit: the cap stays, the name is gone, and
  the next start's verdict is measured; also a crash between the two halves of a change and a
  child of an exited target) and `ParentExitsChildSurvivesTest` (through `SpawningTarget`).
  Each first checks that its target is in no job (`NativeValidation`): a child of a test JVM in
  a job inherits it and is refused by design. With `-Dprism.native.required=true` that fails
  the run; without, the test is skipped and says why.
- **The recovery trust boundary (audit, 2026-10-10).** `FaultyJournalFiles.metadataUnknown`
  makes a file's state impossible to tell, as `Files.exists` sees an unreadable one; tests in
  `NativeLimitLedgerTest`, `NativeRecoveryTest` and `NioJournalFilesTest` show that "cannot tell"
  is never "not there", that a session is read as ended only on proof (its lock taken, its Prism
  process gone), and that a folder that cannot be listed is not an empty one.
  `WindowsJobLimiterTest` and `NativeLimitRecoveryIntegrationTest`
  (`recoveryNeverChangesTheJobOfAPrismSessionThatStillRuns`, on the real system) show that recovery
  reads a reopened job and changes nothing.
- **One Prism per user profile (ADR-0013).** `InstanceLockTest` takes real locks across JVMs it
  starts (`InstanceLockHolder`): a second launch refused while the first keeps its journal, a
  hard kill freeing the profile, two launches with one winner, failures that refuse, channels
  closed. `ConcurrentInstancesTest` is the characterization of what two instances would do
  without the rule. The application-level refusal was checked by a real double launch on
  Windows (the second JVM showed only the refusal dialog and ran no `srm-*` thread). Runs that
  start a second Prism (the verification procedures of milestones 7.5 and 8) need the
  developer's own Prism closed first, or another user profile.
- **The `native-validation` check.** `.github/scripts/native-validation.mjs` reads the Windows
  surefire reports and passes only if every native suite ran and none of its tests was skipped,
  failed or ended in error; a skip is never a pass. It runs as a job of its own in `ci.yml`
  after the gate, which it does not change, and by hand:

  ```
  ./mvnw -q test -Dprism.native.required=true -Dtest=WindowsJobIntegrationTest,NativeLimitRecoveryIntegrationTest,ParentExitsChildSurvivesTest,JnaWin32Test
  node .github/scripts/native-validation.mjs target/surefire-reports
  node --test .github/scripts/native-validation.test.mjs
  ```

  `native-validation.yml` runs the same on a self-hosted runner labelled `windows-native`,
  started by hand. M10 is accepted only with this check green on the final commit, or with the
  evidence file of plan §11.6 path C.
- **Path C for milestone 10.** The accepted evidence for commit `fdd08e3` is
  [docs/evidence/m10-native-validation-fdd08e3.md](evidence/m10-native-validation-fdd08e3.md).
  The code changed after it, so the evidence for `a6f12b6`,
  [docs/evidence/m10-native-validation-a6f12b6.md](evidence/m10-native-validation-a6f12b6.md),
  replaces it for the current code; it was accepted on 2026-10-12.
  On GitHub-hosted Windows runners the tests run inside a Job Object, so the hosted check stays
  red there by design. Evidence of this kind holds only for the code of the commit it names:
  - The raw reports are copied with machine-local paths removed, including the
    doubled-backslash paths in Maven's `argLine`.
  - The hashes of the originals and of the copies are listed.
- **By hand.** `JobObjectProbe` (the experiments E0 to E4) and `NativeLimitCostProbe` (the
  cost of a native limit to Prism) are not part of the build.
- **The pages.** `LimitsViewModelNativeTest`, `MemoryLimitDialogTest`;
  `LimitsSnapshotTest` (`limits-native`, `limits-native-result`) and
  `NativeLimitDialogsSceneTest` (`dialog-memory-*`, `dialog-fallback-*`) for the eye.

## OOM Guard and the memory limit (milestone 11)

- **The state machine, without a clock of its own.** `OomStateMachineTest` drives
  `OomStateMachine` with an injected nanosecond clock, through:
  - every transition;
  - the two samples needed to enter a state;
  - the margin and the dwell needed to leave one, one level at a time;
  - flapping input, HOLD on a missing reading, GAP after more than 5 s, OFF;
  - the epoch on a change of the thresholds;
  - the floor in integer arithmetic, without overflow, and ignored at half of physical memory
    or above.
- **The interventions, on a virtual clock.** `OomRig` builds the guard with:
  - `FakeProcessController`, which records every call;
  - the real `SuspensionCoordinator` and a real journal in a folder of the test's own
    (`FaultyJournalFiles` for disk failures);
  - `VirtualScheduler`, so nothing runs on a thread of its own.

  The suites, with the rows of plan A15.9 as literal call sequences:

  | Suite | Covers |
  |---|---|
  | `OomGuardLedgerTest` (M1, parametric) | one reduction at Critical and one emergency action per incident, independent; Terminate once per identity per session |
  | `OomGuardClaimsTest` (M2) | two claims on one physical suspension, and a randomized interleaving over five fixed seeds |
  | `OomGuardRestoreTest` (M4) | the priority raised back at Normal, and never where it cannot be |
  | `OomGuardJournalTest` (M5, O14) | release on a journal failure; an incomplete release keeps its ownership |
  | `OomGuardGateTest` (M8, M10, M11) | every item of the gate, in its order; secondary triggers that never start an action |
  | `ClearanceTest` (M9) | the margin of the memory limit and its two listings |
  | `OomGuardPolicyTest` (M7) | the memory limit, and nothing on an unvalidated system |
  | `OomGuardTest` | defaults, managed only, reused PIDs, HOLD, stop |

  `EffectiveActionTest` is the whole table of the effective action, row by row, macOS
  included. `LimitServiceOomTest` covers OOM management through the limit service and the
  Windows offer of the watched limit. `MonitoringRuntimeOomTest` covers:
  - every sample reaching the state machine while the UI relay is slow;
  - actions only on the limiter thread;
  - the journal fan-out;
  - shutdown and the exit hook;
  - no new thread.
- **The settings file.** `SettingsCodecTest` and `SettingsStoreTest` cover:
  - a missing, corrupt or newer file, and a checksum mismatch;
  - invalid thresholds;
  - termination only for the literal `true` (SET-2);
  - a full disk, a refused write, and coalesced saves;
  - the Turkish default locale.
- **On the real system, on the demo workload only (SAFE-6).** No real memory pressure is made
  (DEMO-2).
  - `OomGuardIntegrationTest` hands observations to the guard directly, with the controller,
    listing, coordinator, journal and limit service of the system. It shows:
    - suspend at Emergency, declared in the journal on disk first, no CPU time while
      suspended, resumed at Normal;
    - the priority lowered at Critical and raised back to the level read before (Windows), or
      not lowered where it cannot be raised back;
    - Terminate sent once;
    - nothing done to a reused identity, nor with the default switches.
  - `OomSignalValidationTest` (P1-7, O17):
    - the reading of available memory falls by at least half of M while the demo holds M of
      touched memory, and rises again after it exits;
    - M is at most 512 MiB and at most a quarter of what is available;
    - it fails, never skips, on Windows and Linux, and only reports on macOS. On Linux a pass is
      evidence for ADR-0015 §7, not a switch: Linux stays not validated until that evidence is
      reviewed.
  - `OomResidentValidationTest` (O17b) checks that the listed resident memory of the demo is
    at least the 256 MiB it holds, and that its record leaves the listing when it exits.
  - Each prints an `oom-signal:` or `oom-resident:` line with what it measured.
  - `OomGuardLifecycleTest` stops OOM Guard between the gate's last check and the native call,
    and holds a native call open while it stops (ADR-0015 §6).
  - `OomGuardRevocationTest` publishes new settings between the gate and the native call. It
    covers the Terminate, Suspend and Lower priority switches, monitoring, the thresholds and the
    memory limit, with positive controls and two interleavings forced on real threads
    (ADR-0015 §2). It fails on the code before the fix (8 of 11).
    `aPublicationCannotCompleteBetweenTheVersionCheckAndTheInFlightCount` holds the start between
    its version read and its in-flight count while another thread publishes. It fails when
    publication is not under the action lock.
  - Mutation evidence for plan A15.9 M1 to M11 and the action start:
    `docs/evidence/m11-mutation-evidence.md`. These are hand-written mutations, not a mutation
    score.
  - Linux before validation: `HostPlatformTest`, `EffectiveActionTest` (the Linux capabilities)
    and `OomGuardPolicyTest` (pressure and the memory limit together) show no native call and a
    warning in the log.
- **The pages.**
  - `OomGuardViewModelTest`, `OomWordsTest`, `ThresholdsDialogTest`, `ManagedActionsTest`.
  - `OomSnapshotTest` renders every state at four sizes in both themes. At 704 × 441 it asserts
    that the managed table shows two rows and that its actions stay above the fold.
  - `OomDialogsSceneTest` checks that Enter never allows termination, that Escape and Enter in
    a thresholds field do what they say, and that choices are disabled, not hidden.
  - The resident-memory offer is in `NativeLimitDialogsSceneTest`.

## Linux native limits (milestone 12)

- **Without Linux** (every OS): `LinuxScopeLimiterTest` drives the scope limiter against
  `FakeScopeManager`, a fake systemd that writes the cgroup files as systemd does, can answer late
  (`lagging`), refuse, not answer, or ignore a property, and `FakeLinux`'s pidfds. It covers
  eligibility and consent before any call, pidfd-only adoption with identity checks before and after
  the open, ownership recorded before any limit, per-kind read-back with a bounded wait, unknown
  answers kept as uncertain, recovery that only classifies, clearing only with proof, the
  scope-membership rule and descriptor accounting. `SystemdScopeManagerTest` pins the exact messages
  (`StartTransientUnit` with `PIDFDs`, `Unit.SetProperties` on the invocation id, one property per call) against `FakeSdBus`.
  `ReadOnlyNativeScanTest` scans the string literals of `platform.linux`: none is exactly `PIDs`,
  `AttachProcessesToUnit`, `StopUnit`, `KillUnit`, `AbandonScope`, `cgroup.kill`, `memory.oom.group`
  or a signal match, and the state-changing calls are only in `SystemdScopeManager`.
  `SurvivingDescendantLifecycleTest` (T-D26, a fake limiter), `SurvivingDescendantScopeLimiterTest`
  (the real scope limiter, ledger and recovery against a fake systemd) and `LimitServiceNativeTest`
  (T-D27) cover surviving descendants and the membership rule.
- **On Linux** (`LinuxNativeLimitIntegrationTest`, `SystemdReadOnlySmokeTest`): skipped unless
  `-Dprism.linux.native.required=true`; with it, a missing capability fails the test. The CI job
  `linux-native-validation` runs them that way and `.github/scripts/linux-native-validation.mjs`
  fails on any skipped, failed or missing suite; afterwards it checks that no `prism-srm-limit-*`
  unit is left. The tests adopt only processes the harness started with `systemd-run --user --scope`.
  On a host without swap the throttled allocation of the `memory.high` test does not finish within its bound
  (the kernel cannot reclaim anonymous memory, so it keeps delaying the allocator); the test accepts
  that only where `SwapTotal` is 0 and still requires `high` events, a PSI stall and a live process. Outside
  CI, run the same command as the desktop user from a shell outside the user manager, such as an SSH session, as on
  the local Ubuntu 24.04 VMs (ADR-0016 and [the Hyper-V record](evidence/m12-hyperv-validation.md)). From a terminal
  inside the user manager, the test of a process outside it should fail, because its child would be eligible; that
  was not run. With
  swap, `memory.max` may swap instead of killing, and the test accepts that; the OOM kill is exercised
  only on a swapless system. Do not run the Windows gate while the VM runs: together they exhaust the host's
  commit charge.

```
./mvnw -Dprism.linux.native.required=true -Dtest='LinuxNativeLimitIntegrationTest,SystemdReadOnlySmokeTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

## The Windows application image (milestone 13)

```
./mvnw -Ppackage-image -DskipTests package
powershell -NoProfile -ExecutionPolicy Bypass -File packaging/windows/verify-image.ps1 \
    -Image target/package/image/PRISM -Results <folder> -Environment host|clean \
    -ProbeJar target/package/verification/image-probe.jar -Work <folder>
```

ADR-0017 binds this: an image is accepted only with `IMAGE ACCEPTED` in `summary.txt`, which only a clean run can give, and a
change of JDK makes the run mandatory again. A check that was not carried out refuses the image as a failed one does. It needs an idle desktop, because it clicks through the real window; each click first checks that
PRISM is under the pointer. Use `-Environment clean` only in a machine without a JDK that is thrown away afterwards
(Windows Sandbox, K6). There it also opens folder windows and changes the system colour scheme; on `host` it does neither.
Results of the two kinds of environment are kept apart, and a property of one is not assumed of the other.

The checks look at what happens to processes: threads counted from outside, CPU measured from outside, memory committed.
The state of a button proves nothing. A first version of this check read the disabled buttons of a row that was not
selected and reported a false PASS for SAFE-2. Each refusal is therefore counted in the log as well, so that a request
that never arrived does not pass as one that was refused; a limit has a floor as well as a ceiling; and a release is
proved only if the process was held at that moment. On Prism's own row the window offers nothing (CAP-2), so there the log
must show that no request reached the service at all.

The checks added when milestone 13 was completed follow the same rule:

- Process control and OOM Guard act on `prism-demo` from the window, and the result is read from outside: the priority
  class, the suspended threads, whether the process lives. End task on the demo, which has no window, must say so and
  must not claim that it ended. OOM Guard is brought to Emergency by thresholds above the memory that is available, so
  that no memory is used for it; the scenario is NOT TESTED on a machine where that cannot be done.
- The keyboard scenario presses real keys. From each sidebar entry, Tab walks the page and comes back to it. Every stop
  must have a UI Automation name. The name of a text field is that of its label: the Windows bridge of JavaFX 25 reads
  the name of a text field from `LABELED_BY` and its value from `TEXT`, and a text field answers `TEXT` with its
  accessible text first, so accessible text on a text field replaces the value a screen reader reads. The 3 px ring
  around each stop must change when the stop gets the focus. The ring of the control that just lost the focus is left
  out, and a stop whose ring also changes without any key is not taken as proof. The element with the focus is UI
  Automation's own focus, not a search for `HasKeyboardFocus`: the selected row of a table reports that property all the
  time. An empty table gives the focus to its placeholder, and its ring is the table's.
- The context menu of a row is opened with the context-menu key; the arrows must move in it and one Escape must close
  it. Shift+F10 opens the same menu, but on Windows it also puts the window into the system's menu mode, which takes the
  next keys; JDK-8090022 describes the same. That is recorded as INFO, and is a FAIL only if Escape never closes the menu.
- A console close is the console of a test launcher closed with `WM_CLOSE` while the limiter holds a process and the user
  holds another. The system ends the process as soon as the JVM's own console handler returns, before the shutdown hooks
  have released anything, so LIFE-5 here rests on Prism's console control handler (`WindowsConsoleClose`). `demo-ctrl-c`
  gives the demo its own console and sends it Ctrl+C while a native limit holds it.

`PackagingConfigTest`, in `verify`, pins the modules, the launcher key, the mandatory checks and the rules of the verdict.

## Manual probes against the real system

Two test classes are named so that the default build does not run them. Each starts the
real platform sources, prints what a person compares with the operating system's own tools
(Task Manager, `Get-Process`, performance counters) and exits by itself:

```
./mvnw -q test -Dtest=HostMonitoringProbe   # ten system samples (CPU, memory, disk, network)
./mvnw -q test -Dtest=HostProcessProbe      # process listing, own threads under load, a child JVM that appears and exits
```

See [DEMO.md](DEMO.md) for how they are used in a demonstration.
