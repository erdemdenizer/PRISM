# PRISM — Process & Resource Interactive System Manager

## Requirements

- **JDK 25** (Eclipse Temurin 25 LTS recommended) with `JAVA_HOME` pointing at it. The build

## Compile and run

PowerShell (Windows):

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'



.\mvnw.cmd -q compile          # compile, warnings are errors (about 10 s)
.\mvnw.cmd verify              # full quality gate: enforcer, compile, 665 tests, Spotless, JaCoCo, SpotBugs (about 1 min)
.\mvnw.cmd javafx:run          # start the application
```


When the program opens successfully you can close the console that popped up.
## Mini demo (commands)

```powershell
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe   # ten system samples: CPU per processor, memory, disk and network rates (about 12 s)
.\mvnw.cmd -q test -Dtest=HostProcessProbe      # process listing every 2 s; loads 4 threads; starts a child JVM that appears and then exits (about 30 s)
```


## Known limitations of this checkpoint

- the program is read only for now.
- On Windows the State column is empty for every process: Windows has states for threads,
  not for processes, and PRISM does not invent one.
- User, path and command line are absent for processes this user may not open (expected
  without elevation).


| Member | Focus Area & Accomplished So Far |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | **System Metrics & Architecture**: Set up the main project structure, fetched CPU/RAM metrics using OSHI, built the history buffer to save performance data, and handled the chart backend.
| [erencatal](https://github.com/erencatal) | **Process Discovery & Windows API**: Handled process listing, solved PID reuse bugs, created background workers for process details, and linked Windows system calls using JNA.
| [enessen0](https://github.com/enessen0) | **UI & Thread Safety**: Designed the process table, implemented search, filtering, and sorting, and built the relay logic to safely send background data to the UI thread.
