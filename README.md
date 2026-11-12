# PRISM — Process & Resource Interactive System Manager

**Course release 2.** Everything the team has built up to milestone 13: monitoring, process control,
managed processes and limits, OOM Guard, settings and the Windows application image, with the
redesigned user interface. What is left for the next stage (Linux and macOS):
[docs/plans/next-stage.md](docs/plans/next-stage.md).
The first stage, the milestone 5 checkpoint, is described in [CHECKPOINT.md](CHECKPOINT.md).

## Requirements

- **JDK 25** (Eclipse Temurin 25 LTS recommended) with `JAVA_HOME` pointing at it. The build refuses any other Java version (Maven Enforcer rule in `pom.xml`).

## Compile and run

PowerShell (Windows):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'

.\mvnw.cmd -q compile          # compile with warnings as errors
.\mvnw.cmd clean verify        # full quality gate: tests, Spotless, JaCoCo, SpotBugs
.\mvnw.cmd javafx:run          # start the application
```

When the program opens successfully you can close the console that popped up.

## Mini demo (commands)

```powershell
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe   # samples CPU, memory, disk and network rates
.\mvnw.cmd -q test -Dtest=HostProcessProbe      # tests process listing, thread load, child JVM lifecycle
```

Process control and limits are demonstrated on the demo workload only, never on an application
someone uses: [docs/DEMO.md](docs/DEMO.md).

## Windows application image

```powershell
.\mvnw.cmd -Ppackage-image -DskipTests package   # target\package\image\PRISM\PRISM.exe, with prism-demo.exe
```

The image needs no Java installation. Its acceptance run is described in
[docs/TESTING.md](docs/TESTING.md) ("The Windows application image").


## Known limitations of this release

- Hard (native) limits exist on Windows only (Job Objects). On Linux and macOS the CPU limit is
  PRISM's own emulated limit; the Linux native limits are implemented but switched off in the product
  (K16) until desktop applications are validated with them.
- OOM Guard's interventions and the watched memory limit run only where the memory signals are
  validated: on Windows. Linux and macOS monitor, warn and log.
- On Windows the State column is empty for every process: Windows has states for threads,
  not for processes, and PRISM does not invent one.
- User, path and command line are absent for processes this user may not open (expected
  without elevation).
- The application image is built for Windows only, and it is not code-signed.
- The redesigned user interface is tested on Windows and on a Linux desktop, not yet on macOS.

## Team

| Member | Focus Area & Accomplished So Far |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | **System Metrics & Architecture**: Set up the main project structure, fetched CPU/RAM metrics using OSHI, built the history buffer to save performance data, and handled the chart backend.
| [erencatal](https://github.com/erencatal) | **Process Discovery & Windows API**: Handled process listing, solved PID reuse bugs, created background workers for process details, and linked Windows system calls using JNA.
| [enessen0](https://github.com/enessen0) | **UI & Thread Safety**: Designed the process table, implemented search, filtering, and sorting, and built the relay logic to safely send background data to the UI thread.

Detailed breakdown and past/future contributions: [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md).
