# PRISM — Process & Resource Interactive System Manager

[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net)
[![JavaFX](https://img.shields.io/badge/JavaFX-25-blue.svg)](https://openjfx.io)
[![Quality Gate](https://img.shields.io/badge/Tests-ArchUnit%20%7C%20Spotless%20%7C%20JaCoCo-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux%20%7C%20macOS-lightgrey.svg)]()

> A high-performance, cross-platform system monitor and process controller built with Java 25 and JavaFX.

---

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

# Compile with warnings treated as errors
.\mvnw.cmd -q compile

# Run full test suite and quality checks (Spotless, JaCoCo, SpotBugs, ArchUnit)
.\mvnw.cmd verify

# Launch the desktop application
.\mvnw.cmd javafx:run
```

#### Linux & macOS (Bash)

```bash
export JAVA_HOME=/path/to/jdk-25

# Compile
./mvnw -q compile

# Run full quality gate
./mvnw verify

# Launch the desktop application
./mvnw javafx:run
```

---

## Verification & Diagnostic Probes

PRISM includes standalone diagnostic harnesses to test and inspect the real system without artificial mocks:

```powershell
# Sample hardware metrics (CPU, RAM, Disk, Network)
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe

# Inspect process discovery, identity stability, and child JVM lifecycle
.\mvnw.cmd -q test -Dtest=HostProcessProbe
```

---

## Team & Responsibilities

| Contributor | Core Focus | Key Contributions |
|---|---|---|
| **[erdemdenizer](https://github.com/erdemdenizer)** | **Metrics, Architecture & Build** | Hardware telemetry backend (OSHI), rate calculation algorithms, historical performance buffer, real-time Canvas graph visualizer, and synthetic demo workload (`prism.srm.demo`). |
| **[erencatal](https://github.com/erencatal)** | **Process Control & Native Interop** | PID-reuse safe process discovery, Win32/JNA bindings (Job Objects, process/thread suspension, priority control), Linux (cgroups v2, sd-bus) & macOS native control, emulated duty-cycle limiter, and crash recovery journaling. |
| **[enessen0](https://github.com/enessen0)** | **Desktop UI & Safety Policy** | JavaFX application shell, responsive process table with in-place sorting and filtering, background-to-UI non-blocking concurrency relays, and the safety confirmation policy engine. |

For detailed breakdown, milestone deliverables, and architectural records, see [docs/TEAM_TASKS.md](docs/TEAM_TASKS.md).

---

## Architecture & Code Quality

PRISM enforces strict architectural layer boundaries validated by **ArchUnit**:

- `domain` — Pure entity records and business rules with zero external dependencies.
- `platform` — Hardware and OS abstraction (Win32 JNA, Linux, macOS, OSHI).
- `application` — Background services, rate tracking, suspension coordinator, and limits engine.
- `ui` — JavaFX presentation layer communicating strictly through thread-safe relays.

Zero circular dependencies, 100% spotless-formatted code, and comprehensive unit and integration testing.
