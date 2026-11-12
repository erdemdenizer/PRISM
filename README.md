# PRISM — Process & Resource Interactive System Manager

PRISM is a cross-platform desktop process monitor and system resource manager developed for an Operating Systems course by **Team Prism** ([erdemdenizer](https://github.com/erdemdenizer), [erencatal](https://github.com/erencatal), [enessen0](https://github.com/enessen0)).

Built in Java 25 and JavaFX 25, PRISM demonstrates core operating-system mechanisms honestly: processes, threads, scheduling priority, process identity, thread-safe concurrency, native Win32/POSIX system calls, and resource boundaries.

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
  - **End Task:** Graceful termination request (`WM_CLOSE` to top-level window).
  - **Force Kill:** Immediate termination (`TerminateProcess` / `ProcessHandle.destroyForcibly`).
  - **Suspend / Resume:** Per-thread native suspension walk (`OpenThread`, `SuspendThread`, `ResumeThread`).
  - **Priority Adjustment:** Fine-grained priority class modifications across 5 supported levels (Idle, Below Normal, Normal, Above Normal, High).
- **Safety Policy Guard (`ProtectionRules`):** An application-level security and stability layer that strictly blocks:
  - Actions targeting PRISM itself (preventing accidental self-termination).
  - Actions targeting system-critical OS processes (`smss.exe`, `csrss.exe`, `wininit.exe`, `lsass.exe`, etc.).
  - Actions on processes owned by another user without elevated permissions.
- **Capability-Driven UI:** UI controls dynamically adapt to what the underlying operating system natively supports (`NATIVE_HARD`, `NATIVE_SOFT`, or `UNAVAILABLE`), providing explicit user feedback when actions cannot be performed.
- **Activity Logging (Logs Page):** Comprehensive audit trail logging every control command, caller identity, and raw operating-system response code (`SUCCESS`, `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, etc.).

### 4. Theme & Visual System (Checkpoint 2 — Presentation 2)
- **Automatic System Theming:** `ThemeController` dynamically synchronizes with Windows light/dark system preferences and high-contrast modes.
- **Bundled Typography:** IBM Plex Sans (Regular, Medium, SemiBold) packaged locally for pixel-perfect cross-platform consistency.
- **Scalable Iconography:** Vector and canvas-rendered icons with full support for accent tints and title bar integration.

---

## Requirements

- **JDK 25** (Eclipse Temurin 25 LTS recommended) with `JAVA_HOME` pointing to it. The build enforces this version via Maven Enforcer.

---

## Compile and Run

## Overview

**PRISM** combines low-latency whole-system telemetry with robust, crash-resilient process control. It features native operating system integrations (Win32 JNA, Linux cgroups v2, macOS sysctl), a responsive JavaFX desktop interface, and multi-tier safety mechanisms to prevent accidental system instability.

---

## Key Features

- **Real-Time Telemetry:** Continuous sampling of CPU, memory, disk, and network throughput with monotonic rate calculations.
- **Process Management:** Real-time process listing immune to OS PID recycling anomalies via `(PID, StartTime)` identity pairs.
- **Process Control:** Safe process termination (`WM_CLOSE` / `SIGTERM` / force kill), suspension/resumption, and priority adjustments.
- **Native & Emulated Limiting:**
  - **Windows:** Native Job Objects memory and CPU rate limits.
  - **Linux:** Cgroups v2 scope management and CPU quotas via systemd `sd-bus`.
  - **Fallback:** Adaptive emulated CPU duty-cycle limiting for unsupported environments.
- **Crash Recovery & Journaling:** Persistent append-only journaling to restore suspended processes and clean up orphaned native limits across unexpected crashes.
- **Safety Policy Engine:** Blocks destructive actions against critical system processes, protected tasks, and PRISM itself.
- **High-Performance UI:** JavaFX desktop client with non-blocking concurrency relays, real-time Canvas charts, and reactive search/filter tables.

---

## Getting Started

### Prerequisites

- **JDK 25** (Eclipse Temurin 25 LTS recommended)
- Set `JAVA_HOME` pointing to your JDK 25 installation.

### Compile and Run

#### Windows (PowerShell)

```powershell
# Set Java 25 environment
$env:JAVA_HOME = 'C:\path\to\jdk-25'

.\mvnw.cmd -q compile          # compile with warnings as errors
.\mvnw.cmd verify              # full quality gate: tests, Spotless, JaCoCo, SpotBugs
.\mvnw.cmd javafx:run          # start the application
```

---

## Demonstrations & Probes

### Live Interactive Demo (Presentation 2)

PRISM includes a dedicated, safe target workload (`prism.srm.demo`) designed for testing and demonstrating process control actions without risking system stability.

1. **Start the demo workload in a separate PowerShell terminal:**
   ```powershell
   .\mvnw.cmd -q compile exec:java -Dexec.mainClass="prism.srm.demo.WorkloadMain" -Dexec.args="--cpu 25 --memory 100 --duration 180"
   ```
2. **In PRISM (`.\mvnw.cmd javafx:run`):**
   - Locate `prism-demo` in the Processes table.
   - Observe its ~25% CPU consumption across cores.
   - Test **Suspend** -> observe CPU drop to 0.0% immediately.
   - Test **Resume** -> observe CPU recovery.
   - Test **Priority Change** -> modify priority class and verify in Task Manager.
   - Test **Safety Engine** -> attempt to kill PRISM or `csrss.exe`; observe the safety refusal dialog and log message.
   - Test **End Task / Force Kill** on `prism-demo` -> observe clean exit and removal from the process table.

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
| **Checkpoint 2 (Milestones 6–10)** | Interactive Process Control & Safety Engine | Full Win32/JNA process controller (Kill, Suspend, Resume, Priority), `ProtectionRules` safety guard, controllable demo workload (`prism.srm.demo`), UI redesign (223 new/refactored files, IBM Plex Sans, dark/light themes, Logs page). | **Current Presentation** |
| **Final Checkpoint (Milestones 11–13)** | Hardening & Native Packaging | Standalone `.msi` packaging via `jpackage`, Linux cgroups/systemd transient scope limits, high-load stress testing. | In Progress |

---

## Team & Domain Distribution

| Member | Focus Area & Contributions (Checkpoint 1 & 2) |
|---|---|
| [erdemdenizer](https://github.com/erdemdenizer) | **System Architecture, Demo Workload & Packaging**<br>• Initial project foundation, Maven quality gate (ArchUnit, Spotless, SpotBugs, JaCoCo).<br>• Hardware telemetry engine via OSHI (`SystemMetricsSource`), monotonic rate mathematics.<br>• Controllable multi-threaded demo workload (`prism.srm.demo`) for safe destruction targets.<br>• Repository coordination, CI workflows, and documentation architecture. |
| [erencatal](https://github.com/erencatal) | **Process Subsystem & Windows Native Interop**<br>• Full-system process enumeration backend and PID reuse resolution via `(PID, StartTime)`.<br>• Asynchronous detail worker (`ProcessDetailsService`) with coalescing worker queues.<br>• Windows Native Process Controller (`WindowsProcessController`): Win32/JNA bindings for `TerminateProcess`, `OpenThread`/`SuspendThread`/`ResumeThread`, and priority classes.<br>• Win32 error code mapping to typed domain `OperationResult` models. |
| [enessen0](https://github.com/enessen0) | **Safety Policy Engine, Interactive UI & Shell Architecture**<br>• High-performance process table model (`ProcessTableModel`) with in-place row reconciliation.<br>• Concurrency bridge (`LatestValueRelay`) eliminating JavaFX UI lag under rapid telemetry ticks.<br>• Safety Policy Engine (`ProtectionRules`, `ProcessControlService`, `ControlMessages`): self-protection and critical system process guard.<br>• Interactive Control UI (`ui/control`, `ui/processes`): dialogs, action bars, confirmation sheets, and status banners.<br>• Visual foundation & theming: `ThemeController`, `TitleBarTint`, bundled IBM Plex Sans typography, 15 CSS stylesheets, scalable icons, and extensive UI scene/snapshot tests (223 integrated files). |

Detailed historical breakdown, milestone progression, and meeting guides: [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md).
