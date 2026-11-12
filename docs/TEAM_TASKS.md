# Team, Work Progression, and Task Distribution

**Team Prism:** [erdemdenizer](https://github.com/erdemdenizer), [erencatal](https://github.com/erencatal), [enessen0](https://github.com/enessen0).  
**Course:** COME 361 Operating Systems  
**Project:** PRISM (Process & Resource Interactive System Manager)

PRISM is partitioned into three balanced, equal engineering domains:
1. **System Metrics, Infrastructure & Workloads** (Hardware abstraction, OSHI telemetry, rate mathematics, Canvas graphs, demo workload).
2. **Process Subsystem & OS Native Interop** (Process discovery, PID lifecycle, async workers, Win32/JNA interop, thread-level suspension).
3. **Interactive UI Architecture & Safety Engine** (Table models, concurrency relays, shell navigation, safety policy guard, theming, UI dialogs).

---

## Evolution Across Course Milestones

The project progresses across three major course checkpoints:
- **Checkpoint 1 (Milestones 1–5):** Read-only telemetry foundation, whole-system process enumeration, immutable snapshots, and high-efficiency JavaFX rendering.
- **Checkpoint 2 (Milestones 6–10 — Current Second Presentation):** Full interactive process control on Windows, application safety policy engine, controllable demo workload, theme system, and complete UI overhaul (223 integrated files).
- **Final Checkpoint (Milestones 11–13 — Upcoming):** Standalone native packaging (`jpackage`), high-density stress testing, and advanced system limits.

---

## Table 1: Work Completed for Checkpoint 1 (Milestones 1–5 — Read-Only Foundation)

| Module / Area | Focus & Implemented Components | Primary Owner |
|---|---|---|
| **System Metrics Engine & Architecture** | • Project architecture setup, Java 25 baseline, Maven quality gate (ArchUnit rules, Spotless, SpotBugs, JaCoCo).<br>• System telemetry backend via OSHI (`SystemMetricsSource`, `OshiSystemMetricsSource`): CPU ticks, physical/swap memory, storage and network counters.<br>• Monotonic rate mathematics (`RateCalculator`, `DeviceRateTracker`) with counter wrap protection.<br>• Canvas-based graph rendering (`HistoryGraph`, `CanvasGraph`, multi-core CPU matrix). | **erdemdenizer** |
| **Process Subsystem & Identity Model** | • Process enumeration backend (`ProcessSource`, `OshiProcessSource`) capturing system-wide process tables.<br>• Process identity model (`ProcessIdentity`): solved the OS PID reuse anomaly using `(PID, StartTime)` tuples.<br>• Asynchronous detail worker (`ProcessDetailsService`): single-thread background worker with coalescing queue for on-demand path/CLI resolution.<br>• Virtual memory mapping (`WindowsProcessVirtualSizes`) via Win32 performance counters.<br>• Console lifecycle verification harness (`HostProcessProbe`). | **erencatal** |
| **Interactive UI Architecture & Shell** | • High-performance process table model (`ProcessTableModel`): in-place row reconciliation without table reconstruction.<br>• Sorting total order (`ProcessOrder`) and case-insensitive filter (`ProcessFilter`).<br>• Concurrency bridge (`LatestValueRelay`): thread-safe rate coalescing to eliminate JavaFX Application Thread starvation.<br>• Application shell foundation (`Shell`, `Sidebar`, `StatusBar`) with dynamic diagnostic tooltips and window constraints (`MinimumWindowSize`).<br>• Initial UI snapshot test harness. | **enessen0** |

---

## Table 2: Work Completed for Checkpoint 2 (Milestones 6–10 — Presentation 2)

The second interim submission transitions PRISM from a read-only monitor into a fully interactive process management utility with strict safety guarantees:

| Member | Focus Module | Deliverables & Responsibilities Completed | Acceptance Criteria Verified |
|---|---|---|---|
| **enessen0** | **Safety Policy Engine, Process Control UI & Visual System** | • **Safety Policy Guard (`ProtectionRules`, `ProcessControlService`, `ControlMessages`):** Prevents destructive operations on PRISM itself, system-critical processes (`csrss.exe`, `smss.exe`, `wininit.exe`), and unprivileged targets.<br>• **Process Control UI (`ui/control`, `ui/processes`):** Action bar, context menus, confirmation sheets (`SheetOverlay`), and dialogs for End Task, Force Kill, Suspend, Resume, CPU/Memory limits, and Priority adjustments.<br>• **Legacy Refactoring:** Removed legacy `CutTextTooltip` from `processes` package and replaced it with clean `ui/control` architecture.<br>• **Visual System & Theming:** `ThemeController` (synchronizing with Windows Light/Dark/High-Contrast preferences), `TitleBarTint`, `FolderOpener`, `InstanceLock`, bundled IBM Plex Sans typography, 15 CSS stylesheets, and scalable vector icon system (`IconView`).<br>• **Full UI Pages & Testing:** Shell navigation, Overview, Performance graph views, Limits page, OOM Guard page, Logs page, Settings page; author of over 40 scene and snapshot tests (totaling 223 integrated files). | • Destructive actions on protected processes are unconditionally blocked before reaching native APIs.<br>• Action execution produces immediate visual feedback and logs the exact OS response to `ActivityLog`.<br>• UI controls dynamically enable or disable based on capability reports (`NATIVE_HARD`, `NATIVE_SOFT`, `UNAVAILABLE`).<br>• Verified zero regression across all UI snapshot and scene tests. |
| **erencatal** | **Windows Native Process Controller (`platform.windows`) & Lifecycle** | • **Win32/JNA Native Controller (`WindowsProcessController`):** Implemented native process operations: Graceful Close (`WM_CLOSE`), Force Kill (`TerminateProcess` via `ProcessHandle.destroyForcibly`), Suspend and Resume (per-thread walk via `OpenThread`/`SuspendThread`/`ResumeThread`), and Priority Class adjustments (`SetPriorityClass`).<br>• **Error Code Translation:** Mapped Win32 error codes (`GetLastError`) to explicit domain `OperationResult` types (`SUCCESS`, `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, `NATIVE_ERROR`).<br>• **Lifecycle Edge Cases:** Test coverage for PID reuse, process vanishing during action execution, and thread enumeration race conditions. | • Controller reliably halts, resumes, adjusts priority, and kills target processes on real Windows systems.<br>• Vanished or inaccessible processes return typed domain results without crashing the JVM.<br>• Verified native controller behavior on clean Windows host environments. |
| **erdemdenizer** | **Controllable Demo Workload (`prism.srm.demo`) & System Limits** | • **Demo Workload (`prism.srm.demo`):** Standalone multi-threaded target program capable of generating precise, user-configured CPU utilization and memory allocations via CLI flags (`--cpu`, `--memory`, `--duration`).<br>• **Windows Job Object Limits:** Native job object integration for hard CPU rate limits and memory caps.<br>• **Architecture & CI Coordination:** Layer boundary enforcement (`ArchitectureTest`), POM updates, and automated GitHub Actions verification workflows. | • Demo workload cleanly isolates destructive testing from vital system processes.<br>• Workload accurately responds to suspend, resume, priority changes, and termination signals.<br>• 100% green build quality gate across all CI matrix platforms. |

---

## Table 3: Deliverables for Final Presentation (Milestones 11–13 — Upcoming)

| Member | Area | Deliverables & Responsibilities | Acceptance Criteria |
|---|---|---|---|
| **erdemdenizer** | **Packaging & Integration** | • Standalone Windows application packaging via `jpackage` producing native installers (`.msi` / `.exe`) with bundled runtime.<br>• Final CI release pipelines and multi-platform packaging automation. | Application installs, runs, and uninstalls cleanly on a clean Windows machine without requiring pre-installed JDK. |
| **erencatal** | **Platform Hardening & Stress Testing** | • High-density process stress testing (1000+ active processes).<br>• Linux native limits validation via systemd transient scopes and cgroups v2 (`platform.linux`). | System monitor retains 60 FPS responsiveness under heavy process churn; platform error boundaries remain watertight. |
| **enessen0** | **UI Polish, Keyboard Navigation & Documentation** | • Complete keyboard navigation routes and accessibility shortcuts (`Shortcuts`, `TableFocus`).<br>• Final user guide (`docs/UI_GUIDE.md`) and comprehensive oral examination demonstration script.<br>• Final visual polish and responsive layout verification across extreme window dimensions. | Seamless keyboard-only operation across all tabs; verified pixel-perfect layout on varying display DPI scaling factors. |

---

## Program Learning Guide & Presentation Responsibilities

To ensure every team member exhibits full mastery of the system during oral reviews and demonstrations:

### 1. erdemdenizer (System Metrics, Workloads & Architecture)
- **Monotonic Rate Mathematics:** Why monotonic time (`System.nanoTime()`) must be used for CPU and I/O rates instead of wall-clock time (`System.currentTimeMillis()`), and how counter rollover/reset anomalies are handled.
- **JavaFX Canvas Rendering:** Why time-series graphs are drawn directly onto an immediate-mode `Canvas` rather than scene-graph nodes (`Polyline`, `Rectangle`), avoiding memory pressure and garbage collection pauses.
- **Synthetic Workload Design:** How `prism.srm.demo` burns predictable CPU percentages across logical cores using calibrated busy-wait loops and thread pools.

### 2. erencatal (Process Subsystem & Windows API Interop)
- **Process Identity Anomaly:** Why OS PIDs alone are insufficient for tracking processes over time (PID recycling by the OS scheduler), and how the `(PID, StartTime)` composite key guarantees identity integrity.
- **Asynchronous Detail Resolution:** Why disk I/O and security descriptor lookups (executable paths, command lines, users) cannot run on the UI thread, and how worker queues coalesce requests.
- **Win32 Process Manipulation:** How `TerminateProcess`, `OpenThread`, `SuspendThread`, and `ResumeThread` interact with Windows kernel objects, and why Windows lacks an atomic process-level suspend API.

### 3. enessen0 (Safety Policy, Concurrency Bridges & UI Architecture)
- **Safety Policy Engine:** How `ProtectionRules` acts as an impenetrable gatekeeper before any platform call is made—blocking self-termination of PRISM, preventing crashes of critical Windows subsystems (`csrss.exe`, `smss.exe`), and enforcing user security boundaries.
- **Thread-Safe Concurrency Bridge:** How `LatestValueRelay` prevents UI thread starvation by coalescing background telemetry ticks, ensuring JavaFX only renders the latest state without queue backlog.
- **Capability-Driven UI:** How UI components query the domain layer to determine whether an action is `NATIVE_HARD`, `NATIVE_SOFT`, or `UNAVAILABLE`, dynamically adapting buttons, menus, and user error banners.
- **Theme & Resource Architecture:** How `ThemeController` observes Windows registry/theme events to toggle light and dark themes on the fly, and how bundled typography eliminates cross-OS font discrepancies.

---

## Git Workflow & Contribution Records

- Every deliverable is implemented on a dedicated feature branch (`feat/<member>-<module>` or `docs/<topic>`).
- Direct commits to `main` are strictly forbidden; integration occurs exclusively through reviewed Pull Requests.
- Commits are authored under each member's verified GitHub identity (`enessen0`, `erencatal`, `erdemdenizer`).
- All 223 UI, Safety, and Shell files assigned to `enessen0` were integrated via 5 modular Pull Requests with 32 atomic Conventional Commits.
