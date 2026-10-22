# Roadmap

PRISM is built as a sequence of vertical slices ("milestones"), each with behavior that can
be verified. The milestone names and objectives below are those of
[MILESTONES.md](MILESTONES.md); the numbered requirements they cite are in
[PRODUCT_SPEC.md](PRODUCT_SPEC.md).

## In this checkpoint (milestones 1 to 5, snapshot of 2026-10-07)

| # | Milestone | What it delivers | Requirements |
|---|---|---|---|
| 1 | System metrics backend and snapshot model | OSHI-backed source, `SystemMonitor`, rates from monotonic time, 60 s bounded histories, immutable `SystemSnapshot` | MON-1 to MON-11 |
| 2 | Process snapshot backend and enumeration cost | `ProcessSource`, `ProcessMonitor`, process identity (PID + start time), whole-machine CPU %, I/O rates, measured enumeration cost | PROC-1 to PROC-5, PROC-7 |
| 3 | Application shell and Overview | JavaFX window with sidebar and status bar, Overview page, Canvas graphs, named threads, controlled start-up and shutdown, log file | UI-1, UI-8, MON-12, LOG-1 to LOG-3 |
| 4 | Performance page | CPU (total and per logical processor), memory and swap, file systems, network interfaces | UI-3 |
| 5 | Processes page (read-only) | Table with nine columns, sorting, filter, in-place row updates by identity, details pane with on-demand path and command line | UI-2 (table) |

All five are complete in this snapshot and verified on Windows (see
[CHECKPOINT.md](../CHECKPOINT.md) for the verification run of 2026-10-22).

## Outside this checkpoint (milestones 5.5 to 13)

These modules are **not part of this repository**. They are the remaining scope of the
product. The team has continued working on them in its working repository after the date of
this snapshot; this roadmap neither claims nor denies a state for them. Each will be
published to this repository, with its tests and documentation, at the course submission it
is assigned to (staged release plan below).

| # | Milestone | Objective | Main requirements |
|---|---|---|---|
| 5.5 | Visual foundation | One typeface and icon family, a theme that follows the operating system, page headers, panels; no change of what is shown | UI-7 (presentation) |
| 6 | Demo workload | A small separate program with controllable CPU load and memory use, bounded by its arguments: the safe target for every destructive demonstration | DEMO-1 to DEMO-3 |
| 7 | Process control on Windows | End Task, Force Kill, Suspend, Resume, Change Priority with explicit results, protection rules (never PRISM itself or protected processes), capability-driven UI, Logs page | CTL-1 to CTL-9, CAP-1 to CAP-5, SAFE-1 to SAFE-5, UI-6 |
| 8 | Process control on Linux and macOS | The same actions through POSIX mechanisms (signals, nice values) | CTL-3, CTL-5, CTL-6 |
| 9 | Managed Processes and emulated CPU limiter | Processes the user selects for management, the Limits page, a CPU limit enforced by PRISM itself through suspend and resume cycles, a journal so a crash never leaves a process suspended | MGD-1 to MGD-4, LIM-1 to LIM-4, LIM-7, LIM-8, LIFE-4 to LIFE-6, UI-4 |
| 10 | Windows native limits | Hard CPU and memory limits through Windows Job Objects (ADR-0002) | LIM-3 to LIM-5 |
| 11 | OOM Guard and memory policy | A memory-pressure state machine with hysteresis, its page, persisted settings, interventions limited to Managed Processes | OOM-1 to OOM-13, LIM-9, SET-1, SET-2, UI-5 |
| 12 | Linux native limits | CPU and memory limits through cgroups v2 for processes an unprivileged user may move (ADR-0003) | LIM-6 |
| 13 | Settings, shutdown hardening, polish, packaging | Settings page, theme switching, the full shutdown sequence, a Windows application image built with jpackage | UI-7, LIFE-1 to LIFE-6, DEL-1 |

## Staged release plan for the course repository

| Course submission | Published here | Demonstration |
|---|---|---|
| First meeting (2026-10-23) | Milestones 1 to 5: this checkpoint | Live monitoring in the application; the process-lifecycle probe |
| Next interim submission | Milestone 6 (demo workload) and the first stage of milestone 7 (process actions on Windows with explicit results and safety rules), with the tests and the process-action UI; see [TEAM_TASKS.md](TEAM_TASKS.md) for who delivers what | Control actions on the demo workload, never on a real application |
| Later submissions | Milestones 8 to 13 in order, each with its verification record | Limits, OOM Guard, packaged application |

The order follows MILESTONES.md. Dates other than the first meeting are set by the course
schedule and are not stated here.
