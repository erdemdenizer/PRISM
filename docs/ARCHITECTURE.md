# Architecture

This document describes the structure every milestone builds on: the layers, which way
dependencies point, and the threading and resource rules. Product behavior is specified
in [PRODUCT_SPEC.md](PRODUCT_SPEC.md); decisions and their reasons are recorded in
[adr/](adr/README.md).

## Technology baseline

Frozen by [ADR-0001](adr/0001-technology-baseline.md). Exact versions are pinned in `pom.xml`.

| Area | Choice |
|---|---|
| Language and runtime | Java 25, Eclipse Temurin JDK 25 LTS |
| UI toolkit | JavaFX 25 |
| Build | Maven 3.10.0 through the Maven Wrapper |
| System information | OSHI 7.7.0, JNA backend (`oshi-core`) |
| OS-specific control | JNA (`jna-jpms`, `jna-platform-jpms`), the same artifacts OSHI uses |
| Logging | SLF4J API with Logback |
| Tests | JUnit 5, AssertJ, ArchUnit |
| Quality | Spotless (palantir-java-format), SpotBugs, JaCoCo, Maven Enforcer |
| Packaging | jpackage |

JDK 21, 26 and 27 are not used. The OSHI FFM backend is not used unless a demonstrated
technical requirement appears and a new ADR records it. Pinned versions change only for
a concrete reason, after compatibility verification and a passing full gate.

The application runs on the classpath (no `module-info.java`). `javafx:run` starts
`prism.srm.app.App`. `prism.srm.app.Launcher` is the entry point for classpath launches,
which the Windows application image uses (ADR-0017); the plain jar from `./mvnw package`
does not bundle its dependencies and is not runnable on its own.

## Layers

Base package: `prism.srm`.

```
app            composition root: start-up and wiring
 |
 v
ui  ---------> application ---------> platform (interfaces)
 |                  |                      |
 |                  v                      v
 +-------------> domain <------------ platform.oshi | windows | linux | macos
```

| Package | Contains | May depend on | Must not contain |
|---|---|---|---|
| `app` | `Launcher`, `App`, construction and wiring of everything else | all layers | business logic |
| `ui` | JavaFX views, controls, view models, stylesheets | `application`, `domain` | business logic, native or OSHI calls, blocking work |
| `application` | use cases, schedulers, samplers, rate calculation, state machines | `domain`, `platform` (interfaces) | JavaFX types, JNA or OSHI types |
| `domain` | immutable records, enums, result types; the appearance preference (`ThemeMode`: system, light, dark), which the settings will persist and the composition root applies | JDK only | I/O, threads, framework types |
| `platform` | OS-neutral interfaces, selection of the implementation for the running OS, and portable implementations that need only the JDK (for example `ProcessHandle`) | `domain`; its implementation sub-packages for selection only | JNA, OSHI, or OS-specific code |
| `platform.oshi` | cross-platform implementations backed by OSHI | `platform`, `domain` | JavaFX; code only one OS can run; references to another implementation package |
| `platform.windows`, `.linux`, `.macos` | OS-specific implementations: JNA mappings, Win32, systemd scopes and cgroup files read, `/proc`, POSIX | `platform`, `domain` | JavaFX; references to another implementation package |
| `demo` | the demo workload, a separate program with its own `main` (DEMO-1 to DEMO-3) | the JDK only, `com.sun.management` included | any other package of Prism, a logger, JavaFX, JNA, OSHI; and nothing depends on it |

Rules that follow from the table:

- JavaFX appears only in `ui` and `app`.
- JNA and OSHI appear only in the four implementation packages
  (`platform.oshi`, `.windows`, `.linux`, `.macos`), never in `platform` itself.
- The implementation packages do not depend on each other; shared types go in `platform`.
- The implementation packages are reached only through `platform`; selecting the
  implementation for the running OS is `platform`'s job, so nothing above it names one.
- `domain` depends on the JDK only (no logging, no framework types).
- `domain` types cross every boundary; nothing else does. A `platform` interface never
  exposes a JNA, OSHI or OS-specific type in its signatures.
- Business logic lives in `application` and `domain`, never in a JavaFX controller or view.

`src/test/java/prism/srm/ArchitectureTest.java` enforces these rules with ArchUnit on
every test run.

## Design rules

- Snapshots and value types are immutable; prefer records.
- Dependencies are passed through constructors. No DI framework, service locator,
  singleton registry, or global mutable state.
- Operations on the operating system return an explicit result (`SUCCESS`,
  `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, `UNSUPPORTED`, `INVALID_ARGUMENT`,
  `NATIVE_ERROR`) instead of throwing for expected outcomes. Any process can exit
  between two calls. Native error codes travel with the result for logging and are
  never the user-facing message.
- Capabilities of control and limit mechanisms are reported as `NATIVE_HARD`,
  `NATIVE_SOFT`, `EMULATED` or `UNAVAILABLE`, and the UI shows that level truthfully.
- Code above `platform` never branches on the operating system. It asks a `platform`
  interface for a capability and acts on the answer.
- An abstraction needs a reason: a real boundary or a second implementation. The
  `platform` interfaces are such a boundary; most other things are not.

## Product structure

How the product in [PRODUCT_SPEC.md](PRODUCT_SPEC.md) maps onto the layers. Each piece
is introduced by the milestone that first needs it ([MILESTONES.md](MILESTONES.md));
names may be refined then, responsibilities may not move between layers.

| Layer | Responsibility |
|---|---|
| `domain` | System and process snapshots, process identity (PID and start time), operation result, capability level, priority level, limit values, Managed Process settings, OOM state and thresholds. |
| `platform` | The single entry point that detects the operating system and hands out the implementations, so nothing above names an implementation package. The interfaces: `SystemMetricsSource` and `ProcessSource` (raw readings, including a single-process read), `DiskLayerSource` and `ProcessVirtualSizeSource` (facts OSHI lacks on a system), `ProcessController` (terminate, suspend, resume, priority, and the facts that mark a process as protected), `ResourceLimiter` (native limits), the per-user folders. `UnavailableProcessController`, which reports every action `UNAVAILABLE` on a system without a controller of its own. |
| `platform.oshi` | The metric and process sources. |
| `platform.windows` | Disk layers from the Storage Management API ([ADR-0005](adr/0005-system-metric-semantics.md)), process virtual sizes from the Process performance object ([ADR-0006](adr/0006-process-io-and-windows-virtual-size.md)), close request, per-thread suspension, priority class, critical-process detection, Job Object limits ([ADR-0002](adr/0002-windows-process-control-and-job-objects.md), [ADR-0012](adr/0012-windows-native-limits-ledger-and-recovery.md): `WindowsJobLimiter`, `JobNative`, `JobObjectStructures`, `JobNames`), per-user folders. |
| `platform.linux` | Process control: signals through a pidfd, nice values per thread, protection facts from `/proc` ([ADR-0010](adr/0010-unix-process-control-and-process-identity.md)); native limits through transient systemd scopes, adopted by pidfd and changed only through the scope's invocation id ([ADR-0016](adr/0016-linux-native-limits-through-systemd-scopes.md), Accepted; replaces ADR-0003; implemented but not enabled in the product: K16); per-user folders. |
| `platform.macos` | Process control: signals and the nice value after an identity check through `sysctl` ([ADR-0010](adr/0010-unix-process-control-and-process-identity.md)); per-user folders. Its `ResourceLimiter` reports `UNAVAILABLE`. |
| `application` | System and process monitors (scheduling, rates, histories); on-demand process details (`ProcessDetailsService`); control service (protection rules, messages); suspension coordinator; Managed Process registry; limit service (native first, emulated fallback); emulated CPU limiter and suspension journal ([ADR-0004](adr/0004-emulated-limiting-and-suspension-recovery.md)); the native-limit ledger, its controller and the recovery of earlier sessions' native limits (`NativeLimitLedger`, `NativeLimitController`, `NativeRecovery`, [ADR-0012](adr/0012-windows-native-limits-ledger-and-recovery.md)); OOM Guard state machine and intervention policy; activity log; settings store. |
| `ui` | Shell and the seven pages; view models; the Canvas history graphs. Sub-packages: `ui.shell` (navigation, status bar, the place of the current page), one package per page (`ui.overview`, `ui.processes`, `ui.performance`, `ui.limits` and `ui.logs` so far), `ui.metric` (what the pages that show monitoring share: the metric panel, the readouts a view model fills, a fact with its caption, what a page says when monitoring is not running), `ui.graph` (the history graph, the grid of small histories, and their arithmetic), `ui.format` (values to text, with an explicit locale and, for moments, an explicit time zone), `ui.icon` (the Lucide subset as path data and the view that draws one), `ui.control` (what every page uses and no page owns: the page header, the line about an action with its tone, the dialog that asks for a CPU limit). `ui` itself holds what all of them share: the stylesheet locations, the bundled typeface (`Typefaces`), the durations of the micro-motion and the reduced-motion rule (`Motion`), and the relay that carries snapshots to the JavaFX Application Thread. The shared packages lie below the pages: none of them depends on a page or on the shell (`ArchitectureTest`). |
| `app` | Wiring, executor ownership, start-up and controlled shutdown. `MonitoringRuntime` owns the sampling executors, the executor for on-demand process details, the executor for process actions, and their threads; `App` only builds the window and hands over. `ThemeController` applies the appearance mode (`domain.ThemeMode`: system, light or dark) to the scene of the window and to every popup through `Scene.Preferences` ([ADR-0009](adr/0009-theme-follows-platform-preferences.md)); it is created in `App.start()` and closed in `App.stop()`. `MainWindow` holds the title and the sizes of the window; `MinimumWindowSize` keeps its smallest size at the same number of JavaFX units on every monitor, because a stage hands that size to the native window in pixels of the scale at that moment and not again when the scale changes. |

Points that follow from the product:

- **One owner for the platform objects.** `HostPlatform` is created once at start-up. It
  detects the operating system, creates the implementations for it and hands out the
  sources. `OshiHost` in `platform.oshi` is the only class that creates OSHI's
  `SystemInfo`; every OSHI-backed source is created there from the same objects, so a
  second source never sets OSHI up again. Where OSHI lacks a fact, an OS package supplies
  it through an interface in `platform` (`DiskLayerSource`, `ProcessVirtualSizeSource`),
  because implementation packages may not depend on each other. `ArchitectureTest`
  enforces all three points. The process source is created in `OshiHost` from the same
  OSHI objects as the metrics source.
- **One kind of sampling loop.** `SystemMonitor` and `ProcessMonitor` each have their own
  `SamplingLoop` and cadence. The loop owns the fixed-delay schedule, the listeners and
  the failure policy: a `RuntimeException` is logged once per kind and the loop goes on,
  an `Error` is logged and ends it.
- **A monitor that ends says so.** An `Error` is never caught to keep a monitor alive,
  so a monitor can end while the application runs. Whatever leaves a cycle ends the
  loop, and the loop then calls its termination listeners from a `finally` block: the
  failure is neither caught nor changed, and `stop()` calls nobody. `MonitoringRuntime`
  listens to both monitors. It records the end once in the activity log and hands the
  set of failures so far (`MonitoringFailure`: start-up, system monitoring stopped,
  process monitoring stopped) to the UI through a `LatestValueRelay` of its own, so at
  most one such task waits on the JavaFX Application Thread. A monitor that ends once a
  shutdown has begun is not a failure and is not reported. The shell then stops calling
  it "Monitoring": when system monitoring has ended, the Overview says so and shows no
  values, and a snapshot that was still on its way is dropped; when only process
  monitoring has ended, the Overview goes on and the status bar says that monitoring is
  degraded. Nothing is started again: what an `Error` ended stays ended for the session.
- **Process rates are per identity.** A process is its PID together with its start time.
  The process sampler keeps the counters of the processes in the latest reading only and
  compares a reading with the previous one of the same identity, so a reused PID starts
  without history and nothing remains of a process that exited. Path and command line
  are never part of a reading; `ProcessSource.details` reads them for one process on
  demand and returns nothing else. The read of one process's current counters that the
  emulated limiter needs is a separate method, added with the limiter.
- **Process details are read on demand, one at a time, and answered by identity.**
  `ProcessDetailsService` in `application` reads the path and the command line of one
  process when the Processes page asks for them (PROC-5), on the executor it is given, and
  hands the result to its listener: read, process gone, or failed. A result names the
  process identity it was asked for. The executor is `MonitoringRuntime`'s: one thread
  and room for one waiting request, where a newer request takes the place of the one
  waiting. A walk through the table therefore costs one read at a time and builds no
  queue, and neither the reads nor process monitoring wait for each other. The page asks
  once per selection and shows an answer only if the identity it names is the one
  selected by then; an answer for a process that exited, or for an earlier holder of the
  same PID, is dropped. No details are kept beyond those of the selected process.
- **The process table is one row per process identity.** `ProcessTableModel` in
  `ui.processes` compares each listing with its rows by identity: a process that is
  still there keeps its row and gets the new snapshot, a new one gets a row, and the row
  of one that is gone is dropped with everything it held. The list the table shows is
  changed and never replaced: after a listing at most one change removes the rows that
  left, one adds those that came, and one permutation puts the rows in order if they are
  not. Sorting and filtering are the model's, on the rows it holds; the table only shows
  its list and tells it which columns the user chose. The order is total (the chosen
  columns, then PID, then start time; a missing value last in either direction), so
  sorting again never shuffles rows that did not change. The selection is a row, and so
  an identity: it survives listings and sorts, and when its process exits or the filter
  takes the row out, nothing is selected.
- **Process I/O is not disk activity.** The per-process read and write rates are the
  process's I/O as the system accounts it, and every name says I/O. Windows counts file,
  network and device I/O together; the counters of Linux and macOS are narrower. Disk
  rates exist for the whole system only
  ([ADR-0006](adr/0006-process-io-and-windows-virtual-size.md)).
- **A value from a second source is matched by identity.** On Windows the virtual size
  is read separately from the process listing, once per listing for all processes, and
  given to a process only when PID and start time both match. A process the second
  source does not hold has no virtual size, and a failed read costs the virtual sizes of
  that listing and nothing else.
- **Totals count every byte once.** Sources report each disk and interface with its layer
  and the epoch of its counters. The rate tracker in `application` adds up physical
  devices only and produces no total when a layer is unknown
  ([ADR-0005](adr/0005-system-metric-semantics.md)).
- **Capabilities.** A capability report carries a level and the mechanism behind it.
  `platform` reports native levels or `UNAVAILABLE`, per system and, for limits, per
  process. It never reports `EMULATED`: the limit service in `application` derives the
  level in effect when it falls back. Every system has a `ResourceLimiter`, even if it
  only reports `UNAVAILABLE`, so `application` never branches on the system. The UI
  chooses its explanations and warnings from the mechanism, not from the system.
- **One owner for suspension.** The suspension coordinator in `application` is the only
  caller of `ProcessController` suspend and resume. It keeps the reasons a process is
  suspended (user, limiter, OOM Guard) per process identity, resumes when the last
  reason is cleared, and keeps the suspension journal. The limiter and OOM Guard ask
  the coordinator; they never call the controller directly. Journal recovery acts only
  on an entry whose PID and start time match the live process exactly, releases only
  the limiter and OOM Guard reasons, and takes over a user reason without resuming.
  As built in milestone 7: `SuspensionCoordinator` holds, per identity, the reasons and
  the opaque `Suspension` the controller returned (on Windows, the open handles of the
  threads Prism suspended, one increment each). It does not stack a second suspension,
  keeps a suspension whose release failed on a live thread as incomplete and retries it,
  and never takes a process missing from one listing for gone: it asks
  `ProcessController.liveness` on the control thread, one check per identity at a time,
  and forgets the suspension only on `PROCESS_NOT_FOUND`. There is no journal yet.
- **What process control is on a system** reaches the user interface once, as a
  `ControlProfile`: the capability of each action and the table of the five priority levels by
  the numbers the listing shows (CTL-6, "mapped per system"). The table comes from
  `ProcessController.listedPriorities()`, a fixed table that reads nothing, so the page marks the
  current level of a process without knowing how the system numbers priorities, and without a
  query of its own. On Linux the listing shows the nice value plus 20; on macOS it shows a Mach
  priority that stands for no level, so the table is empty there and no level is marked.
- **Process control on Linux and macOS** ([ADR-0010](adr/0010-unix-process-control-and-process-identity.md)).
  `HostPlatform.controllerFor` gives each system its controller; making one touches nothing
  native.
  - Linux: End Task, Force Kill, Suspend and Resume are signals sent through a pidfd. The
    descriptor is opened first and the identity checked in `/proc/<pid>/stat` after it, so the
    signal reaches exactly the identity (the start time computed from the boot time the listing
    used, which `HostPlatform` hands over from OSHI); a suspension holds its pidfd until it is resumed or let
    go. The path is probed once, on first use; where it fails (a kernel before 5.3, a seccomp
    filter, an architecture other than x86_64 and aarch64) the four actions are `UNAVAILABLE`,
    never sent with `kill(2)`, and `ProcessController.limitations()` gives the reason, which the
    runtime writes to the activity log once.
  - macOS: every action reads the process entry with `sysctl(KERN_PROC_PID)` and checks PID,
    start time and state, then calls `kill(2)` or `setpriority(2)`. No documented interface
    makes the two one: a PID handed to another process between them would be acted on, which
    needs the process to exit and be collected and xnu to hand out every other PID up to
    99999 in microseconds. Stated, not closed.
  - Priority has no descriptor-based call on either system; the same check-then-set race holds
    for it on Linux too, per thread (Linux sets every thread in `/proc/<pid>/task`).
  - A process stopped already is not suspended, so Prism never continues a stop it did not
    make. A continue by another program is not noticed: the listing's state is not reliable
    enough to drop a hold on (OSHI reads the leading thread on Linux and maps macOS states
    wrongly), so that is deferred.
- **The words follow the mechanism.** `ProcessControlService` hands `ControlMessages` the
  mechanism of the action and whether the listing shows levels (`ControlMessages.Means`); the
  page and the confirmation take the mechanism from the profile. A close request and a
  termination signal, a per-thread suspension and a stop signal, read differently, without
  the words naming a system.
- **A result** carries two sentences from `ControlMessages`, the one source of their words: what
  came of the request, and what that does not say. The page shows them apart; the activity log
  writes them as one text (`ProcessControlResult.text()`), with the native detail after them.
  How grave a result is comes from one mapping too (`ControlMessages.severity`): the severity of
  the log entry, and through it the tone of the line on the page.
- **When process monitoring ends**, the runtime tells the control service so before it
  hands the failure on: from then on the service takes Resume only, which releases a
  suspension through the coordinator and needs no listing; every other action is answered
  at once. The coordinator hands the processes it holds, with their names, to a listener
  each time that changes, and the Processes page offers Resume for them once monitoring has
  stopped.
- **Process actions** go through `ProcessControlService` in `application`: one action in
  flight per process identity (a second request for it is answered at once, not
  queued), protection rules before the platform, then the controller or the
  coordinator, a plain message from `ControlMessages`, an entry in the activity log, and
  the result, which names the request it answers. Every action runs on one thread,
  `srm-process-control`.
- **Emulated limiting** lives in `application` and is OS-neutral
  ([ADR-0011](adr/0011-suspension-journal-and-recovery.md)). `LimitService` owns the
  managed processes (`ManagedProcessRegistry`) and one `LimitRun` per limit; a
  `LimitRun` reads the target's CPU time through the single-process read of
  `ProcessSource` (`cpuTime`; on Windows a `ProcessCpuTimeSource` with
  `GetProcessTimes`), lets `DutyCycleController` adapt its run/suspend split toward the
  cap, and suspends through the coordinator for the reason `CPU_LIMITER`. Everything of
  the limits runs on `srm-limiter`, so a slow process enumeration can never delay a
  resume. A limit's journal entry is declared before its first suspend and withdrawn when
  it ends; its cycles write nothing. A journal that fails stops every limit. A suspend that
  fails is tried again at the next cycle only when the platform classed it transient (a
  thread could not be opened, matched or suspended, and the rollback released everything)
  and the coordinator holds nothing for the target, at most three times in a row; a
  refusal, a mismatched or vanished process, a stop by another program, an incomplete
  rollback and a journal failure each end the limit at once (milestone 9 review). A failure
  that holds nothing is confirmed by one more reading of the processor time a cycle later: a
  target gone by then ends the limit as an exit, since a thread of an exiting process can refuse
  a suspend (ADR-0011, amended 2026-10-11).
- **The suspension journal** (`SuspensionJournal`, file operations behind `JournalFiles`)
  is one file per instance in `UserFolders.state()/journal`, with a lock file the
  instance holds; the coordinator owns it, and `JournalWriter` writes it on `srm-journal`,
  never under the coordinator's lock (milestone 9 review): a change numbers what the
  journal should hold and returns, the writer writes only the newest, and what must be on
  disk before it acts (a limit's declaration before its first suspend, the user's
  suspension before the process is touched, entries carried over) waits for its number
  for at most `SuspensionCoordinator.JOURNAL_WAIT` (2 s). A wait that runs out fails the
  journal for the session, which stops every automatic suspension; a release never waits
  for the journal. At start-up, before anything is suspended,
  `MonitoringRuntime` reads the journals of dead instances and the coordinator lists what
  they hold (`recover`), resuming nothing; the user resumes a listed process through
  `ProcessControlService.recover`, and only the coordinator calls
  `ProcessController.adopt` (`ArchitectureTest`). A journal whose session has ended but
  whose lock the system still holds (Windows lets the locks of a killed process go
  "depending upon available system resources") is not read then: the activity log says
  the list may be incomplete, and `srm-recovery` looks again after 1, 2, 4, 8, 15, 30 and
  60 s, listing what it reads as at start-up and resuming nothing; one still waiting after
  that stays for the next start. The runtime tells the user interface whether the list is
  whole (`RecoveryCheck`: `PENDING`, then `COMPLETE` or `EXHAUSTED`), and the summary beside
  the filter of the Processes page says so even while nothing is listed. The journal of a
  session that still runs (its PID alive
  with the start its instance name records) is never read or waited for, and an entry the
  user dismissed is not listed again in the session.
- **Protection rules** (Prism itself, protected processes, Managed-only automation) are
  enforced in `application`, before any platform call. `platform` supplies the facts
  (for example "this process is critical"), not the policy.
- **OOM Guard** receives every memory sample from the system monitor, not the coalesced
  UI feed, and hands interventions to another executor. Its state machine is a pure
  function of its inputs and an injected clock, so every transition is unit-testable
  without real memory pressure. As built (milestone 11, [ADR-0015](adr/0015-oom-guard-interventions.md)):
  - `OomStateMachine` runs on `srm-system-monitor`, once per sample, and each observation is
    handed to `OomGuard`.
  - `OomGuard` lives on `srm-limiter`. Every intervention passes its one gate, `authorize`,
    and its view is the only `OomView`.
  - The limit service tells it the managed processes and the memory limits it watches. Process
    monitoring hands it each listing, before the limit service gets it.
  - Its suspensions are claims (system pressure, memory limit) on the coordinator's one
    `OOM_GUARD` reason, declared in the journal first.
  - The settings it acts under are versioned, with one writer, `MonitoringRuntime.publishOomSettings`.
    An action needs the version its observation was judged under.
  - The settings file is read once in `App.init()` and written on `srm-journal`
    ([ADR-0014](adr/0014-settings-file.md)).
- **The demo workload** lives in an isolated package, `prism.srm.demo`, with its own
  `main` (`DemoWorkload`). It uses only the JDK, `com.sun.management` for the physical
  memory size and the CPU time of its own process included, and no logger: it is a
  separate process that reports on its standard output. `ArchitectureTest` asserts both
  directions: it depends on no other `prism.srm` package and none depends on it. The
  build packages it on its own as `target/prism-<version>-demo.jar`, a
  jar without dependencies that runs with `java -jar`; the main jar contains the package
  too. The memory it holds is native memory from a confined `Arena`, so the amount does
  not depend on the heap size of the JVM, and the bound of DEMO-2 is enforced by the
  allocator itself and not only by the command line. How it is used is in
  [TESTING.md](TESTING.md).
- **Logs.** The per-user log folder is resolved by `platform` and handed to Logback
  before the first logger is used, so there is one source of truth for its location.
  Logback is a runtime-only dependency, without a shutdown hook of its own (Prism's hook
  must still be able to log; every line is flushed as it is written), and `app` never
  calls it directly. The folder rules are
  `UserFolders`, selected by the static `HostPlatform.userFolders()`, which needs
  neither OSHI nor a logger. In `App.init()`, `LoggingSetup` publishes the folder as the
  system property `srm.log.dir`, which `logback.xml` reads, and then creates the first
  logger ([ADR-0007](adr/0007-per-user-folders-and-log-files.md)). The order is fixed in
  that one method: `LoggingSetup` is the only class of `app` that may create a logger,
  and `App` has no static logger and no field initializer, because JavaFX constructs it
  before `init()`. `ArchitectureTest` checks both. That property, the default handler
  for uncaught failures, which only logs, and the bundled typeface, which
  `ui.Typefaces` registers with the font registry of the toolkit
  ([ADR-0008](adr/0008-bundled-typeface-and-icons.md)), are the three process-wide
  settings `app` makes; all are set once, in `App.init()`. `ArchitectureTest` allows
  `Font.loadFont` in `Typefaces` only.
- **Snapshots reach the UI through one kind of relay.** The system monitor's listener
  hands each snapshot to a `LatestValueRelay`, which keeps the newest and has at most one
  task waiting on the JavaFX Application Thread. The listings of the process monitor, the
  results of the detail reads and the news of what failed each have a relay of their own
  of the same kind, as do the capability report, the processes Prism holds suspended and
  the activity log, so at most one
  task waits per kind. The results of process actions are the exception: each is handed
  over with its own `Platform.runLater`, because each must be shown or logged and none
  may be replaced by a newer one; there are at most as many as accepted actions, which
  the queue of 32 and the one-per-process rule bound, plus the requests answered at once
  (busy, too many, shutting down, not monitoring), one for each user request. The view model sets a
  property only when its value changed, and a graph redraws its Canvas only for new data,
  a new size or a new color.
- **Only what is on screen is brought up to date.** The shell builds its pages once and
  puts the selected one into the page area; nothing else of the window changes. It hands
  a snapshot, or a listing of the processes, to the page on screen only and keeps the
  newest of each, which a page is given before it is put on screen, so a page never
  appears with what it held when it was last shown. The newest listing is the only one
  kept. The Performance page does the same with its four sections. Once monitoring is
  not running, the kept snapshot is dropped and every page is told, also the ones that
  are not on screen. Within `ui` the shell is at the top: a page knows neither the shell
  nor another page, and what two pages share lives in a package below both.
  `ArchitectureTest` checks this, that every sub-package of `ui` is one it knows, that the
  shared packages (`format`, `graph`, `metric`, `icon`, `control`) and the root of `ui`
  reach into no page, and that `Font.loadFont` is called by `Typefaces` only.

### Start-up order and the one-instance rule

`App.init()` takes the ownership of the user profile (`InstanceLock`, [ADR-0013](adr/0013-one-prism-per-user-profile.md),
Accepted) on the launcher thread, after logging and before anything else: a launch that finds
another Prism owning it shows one dialog and exits without building `MonitoringRuntime`. Only an
owning Prism goes on: the window, then `MonitoringRuntime` (journal, ledger, recovery of earlier
sessions, monitors, limits). The ownership is kept until the process exits.

### Controlled shutdown

Closing the window starts the sequence on a dedicated thread, never on the JavaFX
Application Thread; the application exits when it completes or when its time bound
expires. A failing step is logged and the rest still run.

1. Stop scheduling: monitors, limiter and OOM Guard take no new work, and work in
   flight is awaited, so nothing can suspend a process after step 2.
2. Through the suspension coordinator, release every suspension held by the limiter or
   by OOM Guard. A process is resumed only when no reason remains, so one the user also
   suspended stays suspended, as does any process only the user suspended.
3. Clear native limits Prism applied. On Linux that sets a scope's limit properties to
   `infinity`; no scope is removed and no process is moved (ADR-0016 decision 5).
4. Release native handles.
5. Shut down executors.

A JVM shutdown hook covers exits that bypass this sequence. It tells the coordinator to
refuse further suspensions and releases the limiter and OOM Guard reasons directly,
without an executor, under the same rule as step 2. It is idempotent, so running after
a completed controlled shutdown does nothing.

**As built so far (milestones 3, 5, 7, 9, 10 and 11).** Steps 1 to 5 exist. Milestone 11 adds:
- OOM Guard stops with the limits in step 1 and takes no new work. Its suspensions are
  released in step 2 like the limiter's.
- After the limiter thread has ended, the priorities OOM Guard lowered are raised back within
  1 s. This happens only where they can be, and the log names each one that could not be.
- The shutdown hook stops OOM Guard first and raises no priority back. Milestone 10 adds
step 3 after the suspensions are finalized: every native limit is cleared and verified by
reading it back, every job handle is closed, each verified record leaves the native-limit
ledger, and an empty ledger is deleted; a limit not verified as cleared stays in the ledger
for the next start, which lists it. The shutdown hook does the same within its bound, after
the limiter's suspensions: after a crash it is the only chance, because a job's name goes
with its last handle ([ADR-0012](adr/0012-windows-native-limits-ledger-and-recovery.md)). Milestone 9 adds, after start-up has ended: refuse new suspensions
and new limits; stop every limit (each ends at its next step without touching its
process) and the limiter thread, waiting at most a second; and, last, close the
suspension journal (an empty one is deleted, one that names suspensions the user left or
entries carried over stays), waiting for the journal's thread at most for what is left of
the bound, then stopping that thread. The shutdown hook (`srm-exit-hook`, registered by `App`)
calls `MonitoringRuntime.releaseForExit`: it stops the limits and releases the automatic
reasons through the live suspensions, then clears the native limits, all within one
deadline of 2.5 s (the suspensions at most 1.5 s of it); after a completed shutdown it finds
nothing and logs nothing. On Windows the same release also runs from a console control
handler when the console Prism runs in is closed, because the system may end the process
before the hook gets that far (`WindowsConsoleClose`). `Application.stop()` asks
`MonitoringRuntime` for the shutdown and returns; the sequence runs on `srm-shutdown`:
stop both monitors, wait for start-up to end, call off a later look at the journals of
earlier sessions (milestone 9), shut the sampling executors down, stop the
executor for process details (a request that waits is dropped, a read in flight is
interrupted), wait for a cycle and for a read in flight. Milestone 7 adds, after the
monitors have stopped: refuse new process actions and new suspensions; shut the executor
for process actions down and wait for the action in flight; release the automatic
suspension reasons (none exist yet), which resumes a process only when no reason is
left; and finalize the suspensions: an incomplete one is retried once, the ones the user
made stay, their thread handles are closed without resuming, and the log names what
stays suspended. All waits together are bounded by 5 seconds; what has not ended by
then is interrupted and the fact is logged. Each step is isolated, so a step that throws
a `RuntimeException` is logged and the rest run (LIFE-3). `srm-shutdown` is the only
non-daemon thread Prism creates, so the process ends when the sequence returns, and
nothing calls `System.exit`: a thread that kept the process alive would be a defect to
see in a thread dump, not one to hide.

## Threading

The JavaFX Application Thread only renders and handles input. It never performs process
enumeration, disk or network queries, native calls, blocking waits, or cgroup operations.

- Background work runs on executors that are created, named, and shut down by one
  owner, constructed in `app` and passed to the services that use them.
- Executors are bounded: fixed thread counts and bounded or coalescing queues. A
  scheduled executor holds a fixed set of periodic tasks, except `srm-limiter`, which holds
  at most one pending step per limit (at most 16 limits), one look at the journal, and the
  requests of the user, of which at most 32 wait. `srm-journal` takes at most 64 waiting
  tasks and refuses more; its writer coalesces, so at most one write waits, and a refusal
  fails the journal rather than writing on the caller's thread. `srm-recovery` holds at
  most one pending look. Resume and cleanup work
  that Prism owes on its own (automatic reasons, shutdown) must never be rejectable: it does
  not go through a queue that can refuse it. A Resume the user asks for is a request like
  any other: it may be answered "too many waiting" or "shutting down", and the process then
  stays suspended (LIFE-2).
- Periodic work uses `scheduleWithFixedDelay` on an injected `ScheduledExecutorService`.
- A polling job never overlaps itself. If a cycle runs long, the next one is delayed or
  skipped, not stacked.
- Results reach the UI as immutable snapshots. Updates are coalesced so that at most
  one is pending; a slow frame must not accumulate stale `Platform.runLater` tasks.
- Shutdown follows the controlled shutdown sequence above: it stops scheduling, resumes
  processes suspended by the limiter or OOM Guard (not ones the user suspended), clears
  native limits, and releases native resources. On Linux it removes no scope and writes no
  cgroup file (ADR-0016 decision 5): a released scope stays until its processes exit.

The threads Prism creates, all named:

| Thread | Daemon | Lives | Does |
|---|---|---|---|
| `srm-startup` | yes | until system monitoring runs | creates `HostPlatform` (OSHI set-up, about a second) and starts the system monitor |
| `srm-system-monitor` | yes | while monitoring runs | the system metrics cycle, every second |
| `srm-process-monitor` | yes | while monitoring runs | the process listing, every 2 seconds; its own executor, so a slow listing never delays a system sample. It starts with the first system snapshot that has the number of logical processors, against which the CPU share of a process is computed |
| `srm-process-details` | yes | from the first request for details on | reads the path and the command line of one process when the Processes page asks (PROC-5); one read at a time, and only the newest request waits |
| `srm-process-control` | yes | from the first process action or check on | runs the process actions one at a time, and the checks of whether a process Prism holds suspended is still there; during the shutdown `srm-shutdown` releases and finalizes the suspensions, nothing else touches a process |
| `srm-limiter` | yes | from the start of process monitoring on | managed processes and limits: the requests of the user, the steps of every emulated limit, the native assignments and changes (a few calls each, no periodic work), the exit of a natively limited process, and a look at the suspension journal about once a second while limits run; OOM Guard's evaluations, one queued at a time, after a sample, a listing or a change, and every intervention it makes; a task that ends with an `Error` is handed to the handler for uncaught failures |
| `srm-journal` | yes | from start-up on (the recovery of earlier sessions writes first) | writes the suspension journal, the native-limit ledger and the settings file (each only the newest of what was handed over) and runs their checks, retries and close, one at a time; nothing waits for it under a lock, and a wait for it is bounded by 2 s |
| `srm-recovery` | yes | from start-up on; it works only while a journal of an ended session waits for its lock | looks at the journals of earlier sessions again, at most seven times in about two minutes, and lists what it reads; nothing is resumed, and the shutdown calls it off within its bound |
| `srm-shutdown` | no | during shutdown | the controlled shutdown sequence |
| `srm-exit-hook` | no | when the JVM exits | the shutdown hook: stops the limits, releases what the limiter holds, clears the native limits and lets the jobs go |
| `srm-linux-release` | yes | one per `releaseAll` of the Linux scope limiter that holds a scope (test harness only under K16: the product holds no scope, so it starts one only if another limiter call holds the lock at that moment, and that one finds nothing) | clears the held scopes under the limiter's lock, checking the release's deadline before every send; the caller waits at most the bound, and the thread ends after its last bounded bus call |

Each sampling executor is a single-thread `ScheduledThreadPoolExecutor` holding one
periodic task. The executor for process details is a single-thread `ThreadPoolExecutor`
with a queue of one and a policy that drops the request waiting there for a newer one.
The executor for process actions is a single-thread `ThreadPoolExecutor` with a queue of
32 that refuses when full: an action the user asked for is never dropped silently, it is
answered with "too many actions are waiting" and nothing is done. That queue carries
requests, not cleanup: the releases of the shutdown run on `srm-shutdown` without a
queue, and a check of a missing suspended process that finds the queue full is asked
again with the next listing. Threads that are not Prism's also appear in a thread dump: those of the
JVM and of JavaFX (among them one unnamed daemon thread, `Thread-N`, that the JavaFX
toolkit attaches from native code when it starts), `JNA Cleaner`, and workers of the
JDK's common pool, which OSHI's process listing uses for its parallel streams.

`ArchitectureTest` keeps these things about the UI in place: its sub-packages have no
cycles, pages do not depend on each other or on the shell, and no class of `ui` calls
`Platform.runLater`. Every hand-over to the JavaFX
Application Thread is made by `app`, through the relay except for the results of process
actions, of limit requests and of recovery requests (each answers a request and none may
be dropped), so the "at most one pending" rule cannot be bypassed from a view.

## Performance baseline

- Long-lived OSHI objects are created once and reused.
- One process enumeration per cycle is shared by all consumers.
- Rates are computed from a monotonic clock and tolerate counter reset and wrap.
- Every history is a bounded buffer. Snapshots of exited processes are not retained.
- Graph histories are drawn on a Canvas or custom Region, never as one Node per sample.

## Resources and logging

- Stylesheets and other UI resources live under `src/main/resources/prism/srm/ui/`; the
  three faces of the typeface and their license under `fonts/` there. The notices the
  licenses of the typeface and of the icons require are in `THIRD-PARTY-NOTICES.md`.
- Logging goes through SLF4J, to the console and to a size-bounded rolling file in the
  per-user log folder ([ADR-0007](adr/0007-per-user-folders-and-log-files.md)). Tests
  use `src/test/resources/logback-test.xml`, which logs to the console only. The
  in-application activity log (`ActivityLog`) is an `application` service that keeps a
  bounded list for the UI and writes the same events through SLF4J. A log line is a
  file write: nothing logs on the JavaFX Application Thread in normal operation.
- The file-backed stores in `application` (settings, suspension journal) take their
  location as an injected `Path`; they do not resolve folders themselves.
