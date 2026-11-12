# Milestone 11: acceptance decision (2026-10-13)

**Decision: ACCEPTED WITH DOCUMENTED EXCEPTIONS.** The product owner delegated the closure decision
on the conditions below, and merged it through PR #3 into `main` with a merge commit. Every condition
was checked before the merge. The checks and the merge commit are in the milestone 11 handoff archive.

An exception is not a pass. Each exception below is an accepted gap in the evidence, stated as it is.

## What is accepted

- **OOM Guard and the watched resident-memory limit**, as specified in PRODUCT_SPEC §9 and LIM-9,
  ADR-0014 and ADR-0015:
  - automatic interventions only on Windows, only on managed processes, behind switches that are
    off by default;
  - Force Kill only with its own consent, once per identity per session.
- **Linux and macOS:** OOM Guard monitors, warns and logs only. The platform table
  (`HostPlatform.oomSignalStatus`) stays fail-closed for both: UNVALIDATED.
- **Core safety properties:**
  - no action after stop;
  - no action under revoked settings, with publication under the action lock;
  - one coherent settings snapshot per sample;
  - every suspension claim released when the journal fails, with ownership kept if the release
    is incomplete;
  - protected processes and Prism's own process never acted on.

## Exceptions accepted

| Id | Plan gate (A16) | What is missing | What stands for it |
|---|---|---|---|
| E1 | 1: the gate green on three systems | The hosted `native-validation` check is red: the hosted Windows runner puts every process in a Job Object, so the native suites skip. The check stays in the workflow; it is not hidden or disabled. | The three `verify` jobs pass. Path C (the M10 policy): the native suites pass 20/20 with 0 skipped, outside any Job Object, at `3d6e4eb` and at `78e8874`. |
| E2 | 4: manual verification on Windows 11 | **No human observed the acceptance run.** | An automated GUI acceptance with UI Automation and real input, on the real application and disposable demo processes: 90 PASS, 1 NOT VERIFIED, 1 NOT EXECUTED (7B, deferred), 0 FAIL. |
| E3 | 9 (iv): a manual check of Windows "available" against Task Manager | The Task Manager window was not compared. | At the same moments, PRISM matched `GlobalMemoryStatusEx` and the PerfOS `Available Bytes` counter (Test 5). `OomSignalValidationTest` passed on Windows: on the development machine and in CI. |
| E4 | 12: every A15.9 row shown to fail under a mutation | Of the 100 applicable rows (101 rows, one struck in the plan), 98 are shown. 2 rows are not shown, and 15 are shown only by a broad mutant: see [m11-mutation-evidence.md](m11-mutation-evidence.md), "Closure addendum". There is no claim of exhaustive mutation coverage, and no mutation tool was run. | 72 hand-written mutants; 70 killed, 2 not killed (N44, H4; redundant or residual, as explained there). |
| E5 | 6: the reviewer agents | The four specialist reviews ran during implementation, and their must-fix items were resolved. They were not all run again on the final code: only `test-reviewer` ran again, on the mutation reconciliation, and its must-fix items were fixed. | External technical audits covered every later change, each ending in GO. |

## Deferred or outside M11 (not accepted as done; tracked separately)

- **Test 7B** (recovery across shutdown and restart): optional, and not executed for PID-identity
  safety reasons.
- **M9/M10 CPU and memory limit dialogs:** on invalid input the window does not grow for the
  validation text, so the buttons are clipped. This is a P2 outside M11. The recommended fix is
  `sizeToScene` on the hint, plus a scene test.
- **Windows native-memory fallback (LIM-9 via consent):** covered by deterministic tests only. It
  was not run in the GUI, because a NOT_APPLIED native limit needs a process inside another
  program's Job Object.
- **macOS:** the available-memory signal does not follow memory that a process takes (it fell by
  0.08–0.28 M in CI), so macOS stays UNVALIDATED.
- **Linux:** the signal and resident tests passed in four CI runs, but ADR-0015 §7 item 3 is open.
  `MemAvailable` is host-wide, and Prism's own cgroup v2 `memory.max` is not read. Linux stays
  UNVALIDATED until that item and the owner's acceptance of the evidence (item 4).
- **Other limits:**
  - O14 was tested by fault injection only, not on a real full disk;
  - tooltips and hover states were not checked;
  - MON-12 was measured on one machine (0.348 % of the machine);
  - one intermittent milestone 9 test on hosted macOS
    (`CpuLimitIntegrationTest.aProcessStoppedByAnotherProgram…`, consistent with PROC-6);
  - one real-thread M11 test (`MonitoringRuntimeOomTest.aJournalFailureReleasesWhatOomGuardHolds`)
    failed under load during mutation runs, but never on unmutated code.
