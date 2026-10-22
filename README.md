# PRISM — Process & Resource Interactive System Manager

**Milestone 5 checkpoint** of a JavaFX desktop utility that monitors system resources and
processes on Windows, Linux and macOS. Built by team Prism for the Operating Systems course.

This repository is an authentic snapshot of the project as of **2026-10-07** (see
[CHECKPOINT.md](CHECKPOINT.md)). At this checkpoint PRISM is **read-only**: it observes the
system and its processes and changes nothing. Later modules (process control, resource
limits, OOM Guard, settings, packaging) are outside this checkpoint and are described in
[docs/ROADMAP.md](docs/ROADMAP.md).

## What this checkpoint does

- **Overview**: live CPU, memory, disk and network values, system facts, 60-second history
  graphs drawn on a Canvas; refreshed every second.
- **Performance**: CPU total and one small history per logical processor, memory and
  swap/pagefile, file systems with capacity and usable space, network interfaces with their
  rates.
- **Processes**: every process the user may see, refreshed every 2 seconds, with Name, PID,
  CPU %, Memory, I/O, User, Threads, Priority and State; sorting by any column, a text
  filter, rows updated in place; the executable path and command line of the selected
  process are read on demand on a background thread.
- A process is identified by **PID together with start time**, so a reused PID is never
  mistaken for an earlier process. CPU % is normalized to the whole machine (100 % = every
  logical processor busy). A value the system does not report is shown as absent, never as
  zero.
- Everything that talks to the operating system runs on named background threads; the JavaFX
  Application Thread only renders. See [docs/CORE_STRUCTURES.md](docs/CORE_STRUCTURES.md).

Verified on Windows 11. The Linux and macOS code paths of this checkpoint are unit-tested
with injected environments only and have **not been run** on those systems.

## Requirements

- **JDK 25** (Eclipse Temurin 25 LTS recommended) with `JAVA_HOME` pointing at it. The build
  refuses any other Java version (Maven Enforcer rule in `pom.xml`). Note that the `java` on
  `PATH` does not matter; the wrapper and the run goal use `JAVA_HOME`.
- Nothing else. The Maven Wrapper downloads Maven 3.10.0 and the dependencies on first use
  (internet access needed once).
- A display for `javafx:run`. Ordinary user rights are enough; PRISM never asks for
  administrator or root rights.

## Compile and run

PowerShell (Windows):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\mvnw.cmd -q compile          # compile, warnings are errors (about 10 s)
.\mvnw.cmd verify              # full quality gate: enforcer, compile, 665 tests, Spotless, JaCoCo, SpotBugs (about 1 min)
.\mvnw.cmd javafx:run          # start the application
```

Bash (macOS, Linux, Git Bash on Windows):

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw -q compile
./mvnw verify
./mvnw javafx:run
```

Closing the window stops monitoring on a dedicated shutdown thread and the JVM exits.

## Mini demo (proof of concept)

Two console probes run the real monitoring backend without the UI and print what can be
compared with Task Manager or `Get-Process`. They live in the test sources and are named so
that `verify` does not run them.

```powershell
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe   # ten system samples: CPU per processor, memory, disk and network rates (about 12 s)
.\mvnw.cmd -q test -Dtest=HostProcessProbe      # process listing every 2 s; loads 4 threads; starts a child JVM that appears and then exits (about 30 s)
```

The step-by-step demonstration, with what each step shows and a fallback, is in
[docs/DEMO.md](docs/DEMO.md).

## Architecture in one picture

Base package `prism.srm`. Dependencies point downward only; `ArchitectureTest` (ArchUnit)
enforces this on every build.

```
app            composition root: start-up, wiring, executor ownership (App, MonitoringRuntime)
 |
 v
ui  ---------> application ---------> platform (interfaces: SystemMetricsSource, ProcessSource)
 |                  |                      |
 |                  v                      v
 +-------------> domain <------------ platform.oshi | platform.windows | linux | macos
```

| Layer | Contains |
|---|---|
| `domain` | Immutable records: `SystemSnapshot`, `ProcessSnapshot`, `ProcessIdentity`, `ProcessReading`, `ProcessState`, histories. JDK only. |
| `platform` | OS-neutral interfaces and `HostPlatform`, which detects the OS and hands out the implementations. `platform.oshi` reads through OSHI; `platform.windows` adds what OSHI lacks on Windows; `platform.linux` and `platform.macos` hold per-user folder rules only at this checkpoint. |
| `application` | `SystemMonitor` and `ProcessMonitor` (periodic sampling on a `SamplingLoop`), rate calculation, bounded histories, on-demand `ProcessDetailsService`, the activity log. No JavaFX, no OSHI, no JNA. |
| `ui` | JavaFX views and view models: shell with sidebar, Overview, Performance, Processes, Canvas graphs. No OS calls. |
| `app` | `App` (JavaFX entry), `MonitoringRuntime` (owns the threads `srm-startup`, `srm-system-monitor`, `srm-process-monitor`, `srm-process-details`, `srm-shutdown`). |

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (design, including parts planned for later
milestones) and [docs/CORE_STRUCTURES.md](docs/CORE_STRUCTURES.md) (the core structures of
this checkpoint, explained).

## Technology stack

| Area | Choice |
|---|---|
| Language and runtime | Java 25 (Temurin JDK 25 LTS) |
| UI | JavaFX 25.0.4 |
| Build | Maven 3.10.0 through the Maven Wrapper |
| System information | OSHI 7.7.0 with the JNA backend (`oshi-core`), JNA 5.19.1 |
| Logging | SLF4J 2.0.20, Logback 1.5.38 |
| Tests | JUnit 5.14.4, AssertJ 3.27.7, ArchUnit 1.5.1 |
| Quality gate | Maven Enforcer, `-Xlint:all` with warnings as errors, Spotless (palantir-java-format), SpotBugs, JaCoCo |

Licenses of the dependencies: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Rationale for
the baseline: [docs/adr/0001-technology-baseline.md](docs/adr/0001-technology-baseline.md).

## Repository structure

```
pom.xml, mvnw, mvnw.cmd, .mvn/      Maven build and wrapper
config/spotbugs/exclude.xml          static-analysis exclusions (each with a reason)
src/main/java/prism/srm/             app, application, domain, platform, ui
src/main/resources/                  stylesheets (prism/srm/ui/*.css), logback.xml
src/test/java/prism/srm/             unit tests, ArchitectureTest, UI snapshot tests, the two host probes
docs/PRODUCT_SPEC.md                 numbered requirements of the whole product
docs/ARCHITECTURE.md                 layers, threading, resource rules
docs/MILESTONES.md                   the plan; milestones 1 to 5 in full, 6 to 13 abridged
docs/CORE_STRUCTURES.md              the core data structures and classes, explained
docs/ROADMAP.md                      what is in this checkpoint and what comes after
docs/DEMO.md                         demonstration guide
docs/TEAM_TASKS.md                   work so far and task distribution
docs/TESTING.md, docs/PERFORMANCE.md, docs/UI_GUIDE.md, docs/adr/
CHECKPOINT.md                        how this snapshot was made and what was changed for the course
```

## Known limitations of this checkpoint

- Read-only: no End Task, Suspend, priority change or limits yet.
- On Windows the State column is empty for every process: Windows has states for threads,
  not for processes, and PRISM does not invent one.
- User, path and command line are absent for processes this user may not open (expected
  without elevation).
- Disk and network totals exist only where the system says which devices are physical; on
  Linux and macOS that source is not written yet, so those totals are absent there.
- Linux and macOS: compiled and unit-tested, never run.
- The window opens in the light theme; theme switching belongs to a later milestone.

## Team

| GitHub | Role at the next interim submission (proposed) |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | architecture, integration, repository coordination; demo workload and the first stage of process control |
| [erencatal](https://github.com/erencatal) | process-monitoring backend tests, lifecycle and edge-case coverage, process-control safety verification |
| [enessen0](https://github.com/enessen0) | JavaFX process-action interface, error feedback, documentation, reproducible demo |

Work done so far and the full task distribution: [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md).
