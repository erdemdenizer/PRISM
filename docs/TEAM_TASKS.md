# Team, Work So Far, and Task Distribution

Team Prism: [erdemdenizer](https://github.com/erdemdenizer), [erencatal](https://github.com/erencatal), [enessen0](https://github.com/enessen0).

PRISM is partitioned into three balanced engineering domains:
1. **System Metrics & Infrastructure** (Hardware abstraction, OSHI, rate mathematics, Canvas graphs)
2. **Process Subsystem & OS Native Interop** (Process discovery, PID lifecycle, async workers, Win32/JNA interop)
3. **Interactive UI Architecture & Safety Engine** (Table models, concurrency relays, shell navigation, safety policy)

---

## Table 1: Work Completed So Far (Milestones 1–5 Checkpoint)

| Module / Area | Focus & Implemented Components | Primary Owner |
|---|---|---|
| **System Metrics Engine & Architecture** | • Build setup, Java 25 baseline, Maven quality gate (ArchUnit architecture rules, Spotless, SpotBugs, JaCoCo).<br>• System metrics backend (`SystemMetricsSource`, `OshiSystemMetricsSource`): cumulative CPU ticks, memory, network/disk counter tracking.<br>• Rate calculator (`RateCalculator`, `DeviceRateTracker`) with monotonic timestamps and device layer separation.<br>• Overview & Performance UI graphs (`HistoryGraph`, `CanvasGraph`, multi-core CPU matrix). | **erdemdenizer** |
| **Process Subsystem & Native Interop** | • Process snapshot backend (`ProcessSource`, `OshiProcessSource`): whole-system process enumeration.<br>• Process identity model (`ProcessIdentity`): solved the OS PID reuse anomaly using `(PID, StartTime)` tuple.<br>• Asynchronous detail worker (`ProcessDetailsService`): single-thread worker with coalescing queue for on-demand path/CLI resolution.<br>• Windows Virtual Size native mapping (`WindowsProcessVirtualSizes`) via Win32 performance counters.<br>• Lifecycle verification test harness (`HostProcessProbe`). | **erencatal** |
| **Interactive UI Architecture & Shell** | • High-performance process table model (`ProcessTableModel`): in-place row reconciliation without table resets.<br>• Sorting total order (`ProcessOrder`) and case-insensitive filter (`ProcessFilter`).<br>• Concurrency bridge (`LatestValueRelay`): thread-safe rate coalescing to eliminate JavaFX Application Thread lag.<br>• Application shell (`Shell`, `Sidebar`, `StatusBar`) with dynamic diagnostic tooltips and window sizing (`MinimumWindowSize`).<br>• Verification and test fixtures for UI snapshot testing. | **enessen0** |

---

## Table 2: Deliverables for the Next Interim Submission (Milestones 6 & 7)

The next interim submission focuses on **Process Control on Windows** and the **Controllable Demo Workload**, divided into three distinct, equal modules:

| Member | Focus Module | Deliverables & Responsibilities | Acceptance Criteria |
|---|---|---|---|
| **erdemdenizer** | **Controllable Demo Workload (`prism.srm.demo`) & Integration** | • Develop `prism.srm.demo`: a standalone multi-threaded workload program capable of generating precise, user-configured CPU utilization and memory allocations via CLI arguments.<br>• Serve as the primary, safe target for destructive process control actions.<br>• Maintain build integration, ArchUnit layer boundary enforcement, and integration testing. | • Demo workload accurately reflects target CPU % across logical processors in PRISM's table.<br>• Workload cleanly exits on self-timeout or upon external control signals.<br>• `ArchitectureTest` remains 100% green with strict module isolation. |
| **erencatal** | **Windows Native Process Controller (`platform.windows`)** | • Define and implement the Win32/JNA bindings in `WindowsProcessController` implementing `ProcessController`.<br>• Implement **Graceful Close** (`WM_CLOSE`), **Force Kill** (`TerminateProcess`), **Suspend** and **Resume** (per-thread walk with `OpenThread`/`SuspendThread`/`ResumeThread` or `NtSuspendProcess`), and **Priority Class** adjustments.<br>• Map Win32 error codes (`GetLastError`) to explicit domain `OperationResult` types (`SUCCESS`, `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, `NATIVE_ERROR`). | • Controller successfully halts, resumes, and kills the target demo process.<br>• Vanished or invalid processes return explicit domain results without throwing unhandled exceptions.<br>• Full unit and probe test coverage for all process lifecycle states. |
| **enessen0** | **Safety Policy Engine & Control UI (`application` / `ui.processes`)** | • Implement safety policy engine in `application`: prevent operations on PRISM itself, system-critical processes (e.g. `csrss.exe`, `smss.exe`), and processes owned by other users without elevation.<br>• Capability reporting system: compute and report `NATIVE_HARD`, `NATIVE_SOFT`, or `UNAVAILABLE` capabilities.<br>• Interactive UI: right-click context menu, action buttons on Processes page, confirmation prompts for destructive actions, and user-friendly error banners on failure. | • Destructive actions on protected or critical processes are blocked before reaching platform calls.<br>• UI buttons dynamically enable/disable based on capability reports.<br>• Action execution produces immediate visual feedback and logs the event to `ActivityLog`. |

---

## Program Learning Guide & Meeting Responsibilities

To ensure every team member has full mastery of the system during oral reviews and meetings:

1. **erdemdenizer** should focus on:
   - System resource collection via OSHI (`OshiSystemMetricsSource`).
   - Monotonic time calculation in `RateCalculator` and handling counter wraps/resets.
   - High-efficiency JavaFX `Canvas` rendering (`CanvasGraph`) compared to standard scene-graph nodes.
   - Synthetic CPU/memory workload generation using worker threads in `prism.srm.demo`.

2. **erencatal** should focus on:
   - Why PID alone is insufficient for identifying a process (`ProcessIdentity` and OS PID recycling).
   - How asynchronous worker queues prevent UI freezes during heavy I/O (`ProcessDetailsService`).
   - Win32 API native process manipulation (`OpenProcess`, `TerminateProcess`, thread suspension) via JNA.
   - The process lifecycle demonstrated in `HostProcessProbe` (process creation, resource consumption, exit).

3. **enessen0** should focus on:
   - How `LatestValueRelay` prevents UI thread starvation by limiting pending JavaFX tasks.
   - In-place row reconciliation in `ProcessTableModel` (avoiding table reconstruction on every 2-second tick).
   - Safety rule enforcement (distinguishing user processes from critical OS processes).
   - Capability-driven UI state management and user feedback.

---

## Git Workflow & Contribution Records

- Every feature or deliverable is developed on a dedicated branch (`feat/<issue-number>-<module-name>`).
- Pull requests require a review and approval from at least one other team member before merging.
- Commits are authored under each member's verified GitHub identity.
- The repository issue tracker is used to track deliverables, acceptance criteria, and review discussions.
