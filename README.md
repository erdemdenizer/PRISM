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


## Team

| GitHub | Role at the next interim submission (proposed) |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | architecture, integration, repository coordination; demo workload and the first stage of process control |
| [erencatal](https://github.com/erencatal) | process-monitoring backend tests, lifecycle and edge-case coverage, process-control safety verification |
| [enessen0](https://github.com/enessen0) | JavaFX process-action interface, error feedback, documentation, reproducible demo |


check : [TEAM_TASKS.md](docs/TEAM_TASKS.md)
