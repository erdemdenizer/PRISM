# Next Stage Plan: Bringing Linux and macOS to the Level of Windows

- **Author / Module Owner:** Eren Çatal (`erencatal`)
- **Status:** Planning Document (Course Release 2)
- **Target Milestones:** Remaining ~20% of the Product Roadmap (Milestones 8, 10, 11, 12, 13 parity)

---

## 1. Executive Summary and Current State

As established in `docs/ROADMAP.md` and the Course Release 2 Scope Decision, PRISM currently delivers complete, production-grade functional capability on **Windows**:
- System resource monitoring, 60-second history rings, and Overview / Performance graphs (Milestones 1–4).
- Process enumeration by identity (`(PID, StartTime)` tuple) and inspection details (Milestone 5).
- Controllable synthetic test workload (`prism-demo`, Milestone 6).
- Windows process control: graceful termination (`WM_CLOSE`), forced termination (`TerminateProcess`), thread suspension/resume (`SuspendThread`/`ResumeThread`), priority class switching, and safety rules (Milestone 7).
- Managed processes, emulated duty-cycle CPU throttling, and crash-resilient suspension journaling (Milestone 9).
- Hard CPU and memory enforcement via Windows Job Objects (Milestone 10).
- OOM Guard state machine with hysteresis and intervention policies (Milestone 11).
- Single-instance locking, graceful shutdown, and self-contained Windows application packaging via `jpackage` (Milestone 13).

On **Linux** and **macOS**, cross-platform telemetry and emulated limiting are operational, but native operating system integration remains incomplete. Specifically, native cgroups v2 limits on Linux are intentionally disabled under safety condition **K16** (`UNAVAILABLE` in UI), and macOS relies exclusively on POSIX fallback mechanisms.

This document specifies the technical roadmap, prerequisites, testing matrices, and acceptance criteria to elevate Linux and macOS to parity with Windows.

---

## 2. Linux Parity: Native Limits and Desktop Validation

### 2.1 Lifting Safety Condition K16 (ADR-0016 & Milestone 12)
- **Current State:** The native limiter backend (`platform.linux.LinuxCgroupLimiter` / `platform.linux.LinuxProcessController`) is fully implemented using `systemd` transient scopes (`prism-srm-limit-<pid>-<startMillis>.scope`) over D-Bus via `PIDFDs`. However, safety rule **K16** marks native limits as `UNAVAILABLE` for user-facing desktop processes because transient scope migration removes processes from their parent desktop slices (`app.slice`), causing potential desktop environment tracking loss or abrupt termination upon scope stop.
- **Prerequisites for Lifting K16:**
  1. **Desktop Environment Lifecycle Validation:** Verify that moving real desktop applications (GNOME / KDE / Wayland / X11 sessions) into transient scopes preserves session integration, window manager focus, and child process inheritance without unintended OOM stops (`OOMPolicy=continue`).
  2. **Automated Recovery Suite Execution:** Execute `platform.linux.LinuxNativeLimitRecoveryTest` on bare-metal and Hyper-V/KVM Linux instances across kernel >= 6.5 and systemd >= 255.
  3. **Explicit Consent Workflow:** Ensure UI prompts require dual explicit user consent before moving third-party processes into transient scopes:
     - Warning of one-way scope migration (no move-back).
     - Separate consent for hard memory limits (`memory.max`) triggering kernel OOM-killer invocations.

### 2.2 Linux Memory Pressure and OOM Guard Signals (LIM-9 & Milestone 11)
- Validate `Pressure Stall Information` (`/proc/pressure/memory`) integration for early OOM warning.
- Verify memory threshold interventions:
  - `ELEVATED` state warning notifications.
  - `CRITICAL` state automated duty-cycle throttling of Managed Processes.
  - `EMERGENCY` state selective termination of tagged workloads.

---

## 3. macOS Parity: Signal Semantics, Memory Tracking & Packaging

### 3.1 Process Control & Priority Mechanics (Milestone 8)
- Verify `platform.macos.MacosProcessController`:
  - `SIGTERM` graceful termination with timeout fallback to `SIGKILL`.
  - `SIGSTOP` and `SIGCONT` suspension semantics and interaction with grand central dispatch (GCD) thread pools.
  - `setpriority(PRIO_PROCESS, pid, prio)` range mapping (-20 to 19) against PRISM's five-tier priority abstraction.
- Document and test behavior when operating on hardened runtime binaries and SIP-protected system binaries (`/System`, `/usr/bin`).

### 3.2 Native Resource Limits & OOM Constraints
- Since macOS kernels (XNU / Darwin) lack direct equivalents to Linux cgroups or Windows Job Objects for unprivileged users, native limits report `UNAVAILABLE`.
- Validate the emulated feedback controller (`DutyCycleController`) under macOS scheduling latency:
  - Verify that rapid `SIGSTOP`/`SIGCONT` cycles do not cause audio buffer underruns or GUI freezing on targets.
  - Test memory pressure notifications via `DISPATCH_SOURCE_TYPE_MEMORYPRESSURE` hooks.

### 3.3 Packaging and Bundling (Milestone 13)
- Author `jpackage` configuration for macOS:
  - Generate standalone `.dmg` and `.pkg` installers containing the bundled JDK 25 runtime.
  - Set bundle identifiers, application menu bindings, and dock icons.
  - Verify notarization and gatekeeper compliance prerequisites.

---

## 4. Verification and Testing Matrix

| Operating System | Test Environment | Required Test Suite / Command | Verification Criteria |
|---|---|---|---|
| **Linux (Ubuntu 24.04 LTS)** | Linux VM / Bare-metal | `mvnw test -Dtest=LinuxProcessControllerTest,LinuxCgroupIntegrationTest` | Pass all PIDFD, transient scope, and cgroup attribute assertions. |
| **Linux (Fedora / Arch)** | Systemd >= 255 host | `mvnw test -Dtest=HostMonitoringProbe,LimitRunTest` | Validate CPU quota read-back from `/sys/fs/cgroup/cpu.max`. |
| **macOS (Sonoma / Sequoia)** | Apple Silicon / Intel Mac | `mvnw test -Dtest=MacosProcessControllerTest,HostProcessProbe` | Successful `SIGSTOP`/`SIGCONT` lifecycle and process detail retrieval. |
| **Cross-Platform Gate** | All target OS | `mvnw clean verify` | Zero failures, Spotless clean, ArchUnit architecture rule compliance. |

---

## 5. Timeline and Ownership

1. **Phase 1 (Linux K16 Desktop Tests):** `erencatal` (Lead - Process & Native Interop) in coordination with `erdemdenizer`.
2. **Phase 2 (macOS Test Execution & Porting):** `erencatal` (Signals & Verification) and `enessen0` (UI verification on macOS).
3. **Phase 3 (Cross-Platform Packaging):** `erdemdenizer` (`jpackage` multi-platform targets).
