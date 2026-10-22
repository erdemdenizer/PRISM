# PRISM — Process & Resource Interactive System Manager

## Requirements

- **JDK 25** (Eclipse Temurin 25 LTS recommended) with `JAVA_HOME` pointing at it. The build refuses any other Java version (Maven Enforcer rule in `pom.xml`).

## Compile and run

PowerShell (Windows):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'

.\mvnw.cmd -q compile          # compile with warnings as errors
.\mvnw.cmd verify              # full quality gate: 665 tests, Spotless, JaCoCo, SpotBugs
.\mvnw.cmd javafx:run          # start the application
```

When the program opens successfully you can close the console that popped up.

## Mini demo (commands)

```powershell
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe   # samples CPU, memory, disk and network rates
.\mvnw.cmd -q test -Dtest=HostProcessProbe      # tests process listing, thread load, child JVM lifecycle
```


## Known limitations of this checkpoint

- the program is read only for now.
- On Windows the State column is empty for every process: Windows has states for threads,
  not for processes, and PRISM does not invent one.
- User, path and command line are absent for processes this user may not open (expected
  without elevation).

## Team

| Member | Focus Area & Accomplished So Far |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | **System Metrics & Architecture**: Set up the main project structure, fetched CPU/RAM metrics using OSHI, built the history buffer to save performance data, and handled the chart backend.
| [erencatal](https://github.com/erencatal) | **Process Discovery & Windows API**: Handled process listing, solved PID reuse bugs, created background workers for process details, and linked Windows system calls using JNA.
| [enessen0](https://github.com/enessen0) | **UI & Thread Safety**: Designed the process table, implemented search, filtering, and sorting, and built the relay logic to safely send background data to the UI thread.

Detailed breakdown and past/future contributions: [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md).
