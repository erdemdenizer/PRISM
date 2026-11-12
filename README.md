# PRISM — Process & Resource Interactive System Manager

PRISM is a cross-platform desktop process monitor and system resource manager developed for an Operating Systems course by **Team Prism** ([erdemdenizer](https://github.com/erdemdenizer), [erencatal](https://github.com/erencatal), [enessen0](https://github.com/enessen0)).

Built in Java 25 and JavaFX 25, PRISM demonstrates core operating-system mechanisms honestly: processes, threads, scheduling priority, process identity, thread-safe concurrency, native Win32/POSIX system calls, resource limits, and safety boundaries.

---

## What PRISM Does

### 1. Real-Time Resource Telemetry (Overview & Performance)
- **Overview Page:** Total CPU load, physical/virtual memory utilization, read/write rates for all attached storage devices, receive/send throughput across network interfaces, and system uptime.
- **Performance Page:** Micro-telemetry per logical processor (grid view), comprehensive device metrics, and bounded 60-second time-series history graphs drawn directly to high-efficiency JavaFX `Canvas` surfaces.

### 2. Deep Process Discovery & Identity Model (Processes)
- **Process Table:** Lists all visible operating system processes, updated every 2 seconds without UI freeze or jitter.
- **Strict Process Identity:** Eliminates the classic OS anomaly of PID reuse by tracking each process via an immutable `(PID, StartTime)` tuple.
- **On-Demand Resolution:** Executable path, command line, user ownership, and thread counts are fetched asynchronously via a dedicated worker queue to protect the UI render loop.

### 3. Interactive Process Control & Safety Policy Engine (Checkpoint 2 — Presentation 2)
- **Interactive Control Actions:** Direct control of selected processes via toolbar buttons and context menus:
  - **End Task:** Graceful termination request (`WM_CLOSE` to top-level window on Windows, `SIGTERM` through pidfd on Linux).
  - **Force Kill:** Immediate termination (`TerminateProcess` / `ProcessHandle.destroyForcibly` / `SIGKILL`).
  - **Suspend / Resume:** Per-thread native suspension walk (`OpenThread`, `SuspendThread`, `ResumeThread` on Windows, `SIGSTOP`/`SIGCONT` on POSIX).
  - **Priority Adjustment:** Fine-grained priority class modifications across 5 supported levels (Idle, Below Normal, Normal, Above Normal, High).
- **Safety Policy Guard (`ProtectionRules`):** An application-level security and stability layer that strictly blocks:
  - Actions targeting PRISM itself (preventing accidental self-termination).
  - Actions targeting system-critical OS processes (`smss.exe`, `csrss.exe`, `wininit.exe`, `lsass.exe`, etc.).
  - Actions on processes owned by another user without elevated permissions.
- **Capability-Driven UI:** UI controls dynamically adapt to what the underlying operating system natively supports (`NATIVE_HARD`, `NATIVE_SOFT`, or `UNAVAILABLE`), providing explicit user feedback when actions cannot be performed.
- **Activity Logging (Logs Page):** Comprehensive audit trail logging every control command, caller identity, and raw operating-system response code (`SUCCESS`, `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, etc.).

### 4. Resource Limits & OOM Guard (Checkpoint 2 — Presentation 2)
- **Windows Job Object Limits:** Native job objects enforce hard CPU percentage caps and memory commit limits on managed processes.
- **Emulated Limiting Fallback:** Adaptive duty-cycle suspension loops provide best-effort CPU limiting where native limits are unsupported.
- **Crash-Resilient Journaling:** Persistent append-only journal restores suspended processes and reclaims abandoned limits after system or application crashes.
- **OOM Guard:** Real-time memory pressure monitoring and warning system.

### 5. Theme & Visual System (Checkpoint 2 — Presentation 2)
- **Automatic System Theming:** `ThemeController` dynamically synchronizes with Windows light/dark system preferences and high-contrast modes.
- **Bundled Typography:** IBM Plex Sans (Regular, Medium, SemiBold) packaged locally for pixel-perfect cross-platform consistency.
- **Scalable Iconography:** Vector and canvas-rendered icons with full support for accent tints and title bar integration.

---

## Getting Started

### Prerequisites

- **JDK 25** (Eclipse Temurin 25 LTS recommended)
- `JAVA_HOME` environment variable pointing to your JDK 25 installation (enforced by Maven Enforcer).

### Compile and Run

#### Windows (PowerShell)

```powershell
# Set Java 25 environment
$env:JAVA_HOME = 'C:\path\to\jdk-25'

# Compile with warnings as errors
.\mvnw.cmd -q compile

# Run full quality gate (665+ tests, Spotless, SpotBugs, JaCoCo)
.\mvnw.cmd verify

# Start the application
.\mvnw.cmd javafx:run
```

#### Linux & macOS (Bash)

```bash
# Set Java 25 environment
export JAVA_HOME=/path/to/jdk-25

# Compile
./mvnw -q compile

# Verify
./mvnw verify

# Start the application
./mvnw javafx:run
```

---

## Demonstrations & Probes

### Live Interactive Demo (`prism.srm.demo`)

PRISM includes a dedicated, safe target workload (`prism.srm.demo.DemoWorkload`) with zero external dependencies, designed to demonstrate process control, **CPU/Memory Limits**, and **OOM Guard** live without risking system stability.

1. **Start the demo workload in a separate terminal:**
   ```powershell
   # Windows (PowerShell) — fast direct launch after compile:
   .\mvnw.cmd -q compile
   java -cp target\classes prism.srm.demo.DemoWorkload --cpu 50 --memory 256MiB --duration 5m

   # Or via Maven exec:
   .\mvnw.cmd -q compile exec:java -Dexec.mainClass="prism.srm.demo.DemoWorkload" -Dexec.args="--cpu 50 --memory 256MiB --duration 5m"
   ```
   ```bash
   # Linux / macOS (Bash):
   ./mvnw -q compile
   java -cp target/classes prism.srm.demo.DemoWorkload --cpu 50 --memory 256MiB --duration 5m
   ```
   *(Options: `--cpu <percent>` e.g. `25` or `50`, `--memory <size>` with unit e.g. `256MiB` or `1GiB`, `--duration <time>` with unit e.g. `5m` or `180s`.)*

2. **In PRISM (`javafx:run`):**
   - **Process Control:** Search `DemoWorkload` (or the printed PID) in **Processes**. Test **Suspend** (CPU drops to `0.0%`), **Resume** (CPU returns to ~`50%`), **Priority Change**, **Safety Engine** (try killing PRISM or `csrss.exe`), and **End Task / Force Kill**.
   - **Demonstrate CPU Limit (Job Objects / Duty-Cycle):**
     1. Select the `DemoWorkload` process -> click **Manage** in the right inspector under *Limits & protection*.
     2. Click **Set...** next to *CPU limit* and set a lower cap (e.g., `15%`).
     3. **Observe:** Within seconds, CPU usage drops from ~`50%` and stays capped at `<= 15%` in both PRISM and Windows Task Manager.
   - **Demonstrate OOM Guard (Memory Policy & Pressure):**
     1. Launch a larger memory target, e.g. `java -cp target\classes prism.srm.demo.DemoWorkload --cpu 10 --memory 512MiB --duration 5m`.
     2. Select it in **Processes** -> click **Manage** -> click **Add** under *OOM Guard* (and optionally set a per-process memory limit below `512MiB` or configure OOM Guard thresholds/actions on the **OOM Guard** page).
     3. **Observe:** OOM Guard tracks the process, logs warnings/interventions on the **OOM Guard** and **Logs** pages, and applies the configured policy action (Lower Priority / Suspend / Terminate) when triggered.

### Console Probes (Historical Checkpoint 1 & Lifecycle Tests)

```powershell
# Sample hardware metrics (CPU, RAM, Disk, Network)
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe

# Inspect process discovery, identity stability, and child JVM lifecycle
.\mvnw.cmd -q test -Dtest=HostProcessProbe
```

---

## Evolution Across Checkpoints

| Checkpoint | Scope | Core Additions | Status |
|---|---|---|---|
| **Checkpoint 1 (Milestones 1–5)** | Read-Only Monitoring | Architecture setup, OSHI telemetry backend, Process identity model, rate calculators, basic table view, and console probes. | Completed & Verified |
| **Checkpoint 2 (Milestones 6–10)** | Interactive Process Control & Safety Engine | Full Win32/JNA process controller (Kill, Suspend, Resume, Priority), `ProtectionRules` safety guard, controllable demo workload (`prism.srm.demo`), Job Object limits, OOM Guard, UI redesign (223 new/refactored files, IBM Plex Sans, dark/light themes, Logs page). | **Current Presentation** |
| **Final Checkpoint (Milestones 11–13)** | Hardening & Native Packaging | Standalone `.msi` packaging via `jpackage`, Linux cgroups/systemd transient scope limits, high-load stress testing. | In Progress |

---

## Team & Domain Distribution

| Member | Focus Area & Contributions (Checkpoint 1 & 2) |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | **System Architecture, Demo Workload & Packaging**<br>• Initial project foundation, Maven quality gate (ArchUnit, Spotless, SpotBugs, JaCoCo).<br>• Hardware telemetry engine via OSHI (`SystemMetricsSource`), monotonic rate mathematics.<br>• Controllable multi-threaded demo workload (`prism.srm.demo`) for safe destruction targets.<br>• Windows Job Object resource limits, OOM Guard engine, CI pipelines, and repository coordination. |
| [erencatal](https://github.com/erencatal) | **Process Subsystem & Windows Native Interop**<br>• Full-system process enumeration backend and PID reuse resolution via `(PID, StartTime)`.<br>• Asynchronous detail worker (`ProcessDetailsService`) with coalescing worker queues.<br>• Windows Native Process Controller (`WindowsProcessController`): Win32/JNA bindings for `TerminateProcess`, `OpenThread`/`SuspendThread`/`ResumeThread`, and priority classes.<br>• Win32 error code mapping to typed domain `OperationResult` models, Linux/macOS POSIX signal handling. |
| [enessen0](https://github.com/enessen0) | **Safety Policy Engine, Interactive UI & Shell Architecture**<br>• High-performance process table model (`ProcessTableModel`) with in-place row reconciliation.<br>• Concurrency bridge (`LatestValueRelay`) eliminating JavaFX UI lag under rapid telemetry ticks.<br>• Safety Policy Engine (`ProtectionRules`, `ProcessControlService`, `ControlMessages`): self-protection and critical system process guard.<br>• Interactive Control UI (`ui/control`, `ui/processes`): dialogs, action bars, confirmation sheets, and status banners.<br>• Visual foundation & theming: `ThemeController`, `TitleBarTint`, bundled IBM Plex Sans typography, 15 CSS stylesheets, scalable icons, and extensive UI scene/snapshot tests (223 integrated files). |

Detailed historical breakdown, milestone progression, and meeting guides: [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md).