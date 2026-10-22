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
which jpackage will use; the plain jar from `./mvnw package` does not bundle its
dependencies and is not runnable on its own.

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
| `domain` | immutable records, enums, result types | JDK only | I/O, threads, framework types |
| `platform` | OS-neutral interfaces, selection of the implementation for the running OS, and portable implementations that need only the JDK (for example `ProcessHandle`) | `domain`; its implementation sub-packages for selection only | JNA, OSHI, or OS-specific code |
| `platform.oshi` | cross-platform implementations backed by OSHI | `platform`, `domain` | JavaFX; code only one OS can run; references to another implementation package |
| `platform.windows`, `.linux`, `.macos` | OS-specific implementations: JNA mappings, Win32, cgroups, `/proc`, POSIX | `platform`, `domain` | JavaFX; references to another implementation package |

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
| `platform` | The single entry point that detects the operating system and hands out the implementations, so nothing above names an implementation package. The interfaces: `SystemMetricsSource` and `ProcessSource` (raw readings, including a single-process read), `DiskLayerSource` and `ProcessVirtualSizeSource` (facts OSHI lacks on a system), `ProcessController` (terminate, suspend, resume, priority, and the facts that mark a process as protected), `ResourceLimiter` (native limits), the per-user folders. The portable `ProcessHandle` implementation. |
| `platform.oshi` | The metric and process sources. |
| `platform.windows` | Disk layers from the Storage Management API ([ADR-0005](adr/0005-system-metric-semantics.md)), process virtual sizes from the Process performance object ([ADR-0006](adr/0006-process-io-and-windows-virtual-size.md)), close request, per-thread suspension, priority class, critical-process detection, Job Object limits ([ADR-0002](adr/0002-windows-process-control-and-job-objects.md)), per-user folders. |
| `platform.linux` | Signals, nice values, cgroups v2 limits ([ADR-0003](adr/0003-linux-native-limits-with-cgroups-v2.md)), per-user folders. |
| `platform.macos` | Signals, nice values, per-user folders. Its `ResourceLimiter` reports `UNAVAILABLE`. |
| `application` | System and process monitors (scheduling, rates, histories); on-demand process details (`ProcessDetailsService`); control service (protection rules, messages); suspension coordinator; Managed Process registry; limit service (native first, emulated fallback); emulated CPU limiter and suspension journal ([ADR-0004](adr/0004-emulated-limiting-and-suspension-recovery.md)); OOM Guard state machine and intervention policy; activity log; settings store. |
| `ui` | Shell and the seven pages; view models; the Canvas history graphs. Sub-packages: `ui.shell` (navigation, status bar, the place of the current page), one package per page (`ui.overview`, `ui.processes` and `ui.performance` so far), `ui.metric` (what the pages that show monitoring share: the metric panel, the readouts a view model fills, a fact with its caption, what a page says when monitoring is not running), `ui.graph` (the history graph, the grid of small histories, and their arithmetic), `ui.format` (values to text, with an explicit locale and, for moments, an explicit time zone). `ui` itself holds what all of them share: the stylesheet locations and the relay that carries snapshots to the JavaFX Application Thread. |
| `app` | Wiring, executor ownership, start-up and controlled shutdown. `MonitoringRuntime` owns the sampling executors, the executor for on-demand process details, and their threads; `App` only builds the window and hands over. `MainWindow` holds the title and the sizes of the window; `MinimumWindowSize` keeps its smallest size at the same number of JavaFX units on every monitor, because a stage hands that size to the native window in pixels of the scale at that moment and not again when the scale changes. |

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
- **Emulated limiting** lives in `application` and is OS-neutral. The limiter is a
  feedback controller: it reads the target's CPU use, normalized to the whole machine,
  through the single-process read of `ProcessSource`, adapts its run/suspend split
  toward the cap, and suspends through the coordinator. It runs on its own scheduled
  executor, so a slow process enumeration can never delay a resume.
- **Protection rules** (Prism itself, protected processes, Managed-only automation) are
  enforced in `application`, before any platform call. `platform` supplies the facts
  (for example "this process is critical"), not the policy.
- **OOM Guard** receives every memory sample from the system monitor, not the coalesced
  UI feed, and hands interventions to another executor. Its state machine is a pure
  function of its inputs and an injected clock, so every transition is unit-testable
  without real memory pressure.
- **The demo workload** lives in an isolated package, `prism.srm.demo`, with its own
  `main`. It uses only the JDK (including `com.sun.management` for the physical memory
  size). `ArchitectureTest` gets a rule for it when it is added, asserting both
  directions: it depends on no other `prism.srm` package and none depends on it.
- **Logs.** The per-user log folder is resolved by `platform` and handed to Logback
  before the first logger is used, so there is one source of truth for its location.
  Logback is a runtime-only dependency: it flushes through its own shutdown hook,
  configured in `logback.xml`, and `app` never calls it directly. The folder rules are
  `UserFolders`, selected by the static `HostPlatform.userFolders()`, which needs
  neither OSHI nor a logger. In `App.init()`, `LoggingSetup` publishes the folder as the
  system property `srm.log.dir`, which `logback.xml` reads, and then creates the first
  logger ([ADR-0007](adr/0007-per-user-folders-and-log-files.md)). The order is fixed in
  that one method: `LoggingSetup` is the only class of `app` that may create a logger,
  and `App` has no static logger and no field initializer, because JavaFX constructs it
  before `init()`. `ArchitectureTest` checks both. That property and the default handler
  for uncaught failures, which only logs, are the two process-wide settings `app`
  makes; both are set once, in `App.init()`.
- **Snapshots reach the UI through one kind of relay.** The system monitor's listener
  hands each snapshot to a `LatestValueRelay`, which keeps the newest and has at most one
  task waiting on the JavaFX Application Thread. The listings of the process monitor, the
  results of the detail reads and the news of what failed each have a relay of their own
  of the same kind, so at most four tasks ever wait, one per kind. The view model sets a
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
  `ArchitectureTest` checks this, and that every sub-package of `ui` is one it knows.

### Controlled shutdown

Closing the window starts the sequence on a dedicated thread, never on the JavaFX
Application Thread; the application exits when it completes or when its time bound
expires. A failing step is logged and the rest still run.

1. Stop scheduling: monitors, limiter and OOM Guard take no new work, and work in
   flight is awaited, so nothing can suspend a process after step 2.
2. Through the suspension coordinator, release every suspension held by the limiter or
   by OOM Guard. A process is resumed only when no reason remains, so one the user also
   suspended stays suspended, as does any process only the user suspended.
3. Clear native limits Prism applied; remove cgroups Prism created.
4. Release native handles.
5. Shut down executors.

A JVM shutdown hook covers exits that bypass this sequence. It tells the coordinator to
refuse further suspensions and releases the limiter and OOM Guard reasons directly,
without an executor, under the same rule as step 2. It is idempotent, so running after
a completed controlled shutdown does nothing.

**As built so far (milestones 3 and 5).** Steps 1 and 5 exist; there is nothing yet for 2
to 4 to release, and no shutdown hook of Prism's own. `Application.stop()` asks
`MonitoringRuntime` for the shutdown and returns; the sequence runs on `srm-shutdown`:
stop both monitors, wait for start-up to end, shut the sampling executors down, stop the
executor for process details (a request that waits is dropped, a read in flight is
interrupted), wait for a cycle and for a read in flight. All waits together are bounded by 5 seconds; what has not ended by
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
  scheduled executor holds only a fixed set of periodic tasks. Resume and cleanup work
  must never be rejectable: it does not go through a queue that can refuse it.
- Periodic work uses `scheduleWithFixedDelay` on an injected `ScheduledExecutorService`.
- A polling job never overlaps itself. If a cycle runs long, the next one is delayed or
  skipped, not stacked.
- Results reach the UI as immutable snapshots. Updates are coalesced so that at most
  one is pending; a slow frame must not accumulate stale `Platform.runLater` tasks.
- Shutdown follows the controlled shutdown sequence above: it stops scheduling, resumes
  processes suspended by the limiter or OOM Guard (not ones the user suspended), clears
  native limits, removes cgroups Prism created, and releases native resources.

The threads Prism creates, all named:

| Thread | Daemon | Lives | Does |
|---|---|---|---|
| `srm-startup` | yes | until system monitoring runs | creates `HostPlatform` (OSHI set-up, about a second) and starts the system monitor |
| `srm-system-monitor` | yes | while monitoring runs | the system metrics cycle, every second |
| `srm-process-monitor` | yes | while monitoring runs | the process listing, every 2 seconds; its own executor, so a slow listing never delays a system sample. It starts with the first system snapshot that has the number of logical processors, against which the CPU share of a process is computed |
| `srm-process-details` | yes | from the first request for details on | reads the path and the command line of one process when the Processes page asks (PROC-5); one read at a time, and only the newest request waits |
| `srm-shutdown` | no | during shutdown | the controlled shutdown sequence |

Each sampling executor is a single-thread `ScheduledThreadPoolExecutor` holding one
periodic task. The executor for process details is a single-thread `ThreadPoolExecutor`
with a queue of one and a policy that drops the request waiting there for a newer one. Threads that are not Prism's also appear in a thread dump: those of the
JVM and of JavaFX (among them one unnamed daemon thread, `Thread-N`, that the JavaFX
toolkit attaches from native code when it starts), `JNA Cleaner`, and workers of the
JDK's common pool, which OSHI's process listing uses for its parallel streams.

`ArchitectureTest` keeps these things about the UI in place: its sub-packages have no
cycles, pages do not depend on each other or on the shell, and no class of `ui` calls
`Platform.runLater`. Every hand-over to the JavaFX
Application Thread is made by `app` through the relay, so the "at most one pending"
rule cannot be bypassed from a view.

## Performance baseline

- Long-lived OSHI objects are created once and reused.
- One process enumeration per cycle is shared by all consumers.
- Rates are computed from a monotonic clock and tolerate counter reset and wrap.
- Every history is a bounded buffer. Snapshots of exited processes are not retained.
- Graph histories are drawn on a Canvas or custom Region, never as one Node per sample.

## Resources and logging

- Stylesheets and other UI resources live under `src/main/resources/prism/srm/ui/`.
- Logging goes through SLF4J, to the console and to a size-bounded rolling file in the
  per-user log folder ([ADR-0007](adr/0007-per-user-folders-and-log-files.md)). Tests
  use `src/test/resources/logback-test.xml`, which logs to the console only. The
  in-application activity log (`ActivityLog`) is an `application` service that keeps a
  bounded list for the UI and writes the same events through SLF4J. A log line is a
  file write: nothing logs on the JavaFX Application Thread in normal operation.
- The file-backed stores in `application` (settings, suspension journal) take their
  location as an injected `Path`; they do not resolve folders themselves.
