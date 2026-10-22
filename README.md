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


## Team and Task Distribution

| Member | Focus Area & Accomplished So Far | Deliverable for Next Interim Submission |
|---|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | **Architecture & System Resource Engine**: Build infrastructure, OSHI hardware metrics source (`SystemMetricsSource`), rate calculator, system history buffer, Overview & Performance charts. | **Synthetic Load Generator (`prism.srm.demo`)**: Controllable multi-threaded CPU and memory workload tool for safe, reproducible process control verification. |
| [erencatal](https://github.com/erencatal) | **Process Discovery & Windows Native Interop**: Process enumeration backend (`ProcessSource`), PID reuse prevention (`ProcessIdentity`), asynchronous details worker (`ProcessDetailsService`), Windows virtual size JNA mapping. | **Windows Native Process Controller (`platform.windows`)**: JNA/Win32 native implementations for process lifecycle actions (End Task, Force Kill, Suspend, Resume, Priority adjustment). |
| [enessen0](https://github.com/enessen0) | **Interactive UI Architecture & Safety Engine**: Process table with in-place row reconciliation (`ProcessTableModel`), sorting, search/filter, UI-to-thread relay (`LatestValueRelay`), status shell. | **Safety Policy & Action UI (`application` / `ui.processes`)**: Action menus and confirmation dialogs, safety rules (protecting PRISM and critical OS processes), capability reporting and error feedback. |

Detailed breakdown and past/future contributions: [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md)
