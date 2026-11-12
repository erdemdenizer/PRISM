# Milestone 11: cross-platform CI evidence, commit 3d6e4eb

This file records the GitHub Actions runs of the milestone 11 branch. It also records the memory-signal
evidence that ADR-0015 §7 asks for.

**Overall CI status.**
- All three `verify` jobs pass on `3d6e4eb` and on its documentation-only successors.
- The GitHub Actions workflow as a whole stays **red**: its hosted `native-validation` check
  fails, as accepted in milestone 10 (see below).
- That check is not hidden or disabled. The path C evidence (20/20, 0 skipped) stands for it.

**It changes no validation.**
- Linux and macOS stay **not validated** in `HostPlatform.oomSignalStatus`. Automatic interventions
  stay off there.
- Passing CI is evidence, not authorization. ADR-0015 §7 item 4 (acceptance by the external
  reviewer and the product owner) has not happened.

## Source identity

| Fact | Value |
|---|---|
| Branch | `feat/m11-oom-guard`, draft pull request #3 into `main` (not merged) |
| Accepted baseline | `a480e568720bf337789eeb44ab135a0decadcb83` (milestone 10) |
| First commit | `81a4a52` `feat: add OOM Guard and the watched memory limit` |
| Tested commit | `3d6e4eb` `test: check the Windows priority table only where the Windows controller can load` |
| Change between them | `src/test/java/prism/srm/platform/HostPlatformTest.java` only |

## Runs

| Run | Commit | Attempt | Windows | Linux | macOS | `native-validation` |
|---|---|---|---|---|---|---|
| 38375835210 | `81a4a52` | 1 | pass, 2084 tests | **fail**: 1 error | **fail**: 1 error | fail (hosted runner, see below) |
| 38378078944 | `3d6e4eb` | 1 | pass, 2085 tests, 0 failures, 35 skipped | pass, 2074 tests, 0 failures, 53 skipped | **fail**: 1 failure | fail (hosted runner) |
| 38378078944 | `3d6e4eb` | 2 (failed jobs only) | (attempt 1 kept) | (attempt 1 kept) | pass, 2074 tests, 0 failures, 51 skipped | fail (hosted runner) |

Runners:
- **Windows:** `windows-2025-vs2026`, Windows Server 2025, X64, 2 processors.
- **Linux:** `ubuntu-24.04` on an Azure virtual machine (kernel `6.17.0-1022-azure`), X64,
  2 processors, 7938 MiB.
- **macOS:** `macos-26-arm64` (Darwin 25.6.0), ARM64, 5 processors, 14336 MiB.
- **Java:** Temurin 25.0.4.1 on all three.

### The failure of the first run: a test defect

On Linux and macOS, `HostPlatformTest.onlyWindowsRestoresAPriorityAndOnlyWindowsHasValidatedMemorySignals`
ended in `UnsatisfiedLinkError: Unable to load library 'kernel32'`.
- **Cause.** The test made a `WindowsProcessController` on every system. That controller loads
  kernel32 when it is made.
- **Classification.** Test defect. No product code was involved.
- **Fix in `3d6e4eb`.** The Windows row is checked in `windowsRestoresAPriority`, which runs on
  Windows only. The rows of the other systems, and the whole signal table, are still checked on
  every system (`noOtherSystemRestoresAPriorityAndOnlyWindowsHasValidatedMemorySignals`). No
  assertion was removed.

### The macOS failure of run 38378078944, attempt 1: an intermittent test

`CpuLimitIntegrationTest.aProcessStoppedByAnotherProgramIsNeitherHeldNorContinuedByTheLimiter`
reported `Not reached within PT10S`: the limiter did not report `PAUSED_EXTERNAL_STOP`.
- **History.**
  - The test is from milestone 9 and runs only on Linux and macOS.
  - Milestone 11 does not change the stop detection it checks.
  - It passed in run 38375835210 on the same main code, and it passed on the rerun.
- **Likely cause.** It is consistent with the limitation PRODUCT_SPEC PROC-6 documents: "A stop
  another program adds while Prism holds the process ends with Prism's resume, and Prism cannot
  see it."
  - At a 10 % limit, the demo is held about 90 % of the time.
  - The test's own stop is a check-then-act. If the limiter stops the process between the test's
    check and its signal, the limiter's continue ends the test's stop.
  - The runner was busy: other processes took up to 28 % while that class ran.
- **What this is not.** A defect demonstrated in milestone 11.
- **What was done.** The test was not changed. It is recorded here as intermittent on the hosted
  macOS runner and left for a follow-up.

### `native-validation` stays red on hosted runners (as accepted in milestone 10)

On `windows-latest` the test JVM and its children run inside a Job Object. The native suites
therefore skip, and the check fails, as it must:
- `WindowsJobIntegrationTest` 5 of 6 skipped;
- `NativeLimitRecoveryIntegrationTest` 4 of 4 skipped;
- `ParentExitsChildSurvivesTest` 1 of 1 skipped.

The milestone 10 path C stands for it. Those suites were run again for `3d6e4eb`, on the
development machine, in a clean detached worktree. `git status --porcelain=v1 --ignored` was empty
before the run, and `git status --porcelain` was empty after it.

```
./mvnw -o -B test -Dtest=WindowsJobIntegrationTest,NativeLimitRecoveryIntegrationTest,ParentExitsChildSurvivesTest,JnaWin32Test -Dprism.native.required=true
node .github/scripts/native-validation.mjs target/surefire-reports
```

- **Result.** BUILD SUCCESS and `native validation passed`: 6 + 4 + 1 + 9 = 20 tests, 0 skipped,
  0 failures, 0 errors.
- **Job Objects.** `selfInJob=Optional[false] childInJob=Optional[false]` on all 10
  `native-validation:` lines.
- **Machine.** Windows 11 Pro build 26200.
- **Time.** 2026-10-13, 09:41:24 to 09:43:57 UTC.

## What was skipped, per system (run 38378078944, final attempts)

Every skip on Linux and macOS has the reason "Disabled on operating system": they are tests of
another system.

| System | Skipped | Suites |
|---|---|---|
| Linux | 53 | Windows suites (`JnaWin32Test` 9, `WindowsPriorityTest` 8, `WindowsControlIntegrationTest` 7, `WindowsJobIntegrationTest` 6, `NativeLimitRecoveryIntegrationTest` 4, `ParentExitsChildSurvivesTest` 1, `WindowsUserFoldersTest` 2); macOS suites (`MacosControlIntegrationTest` 7, `JnaMacosTest` 6); `HostPlatformTest` 2; `InstanceLockTest` 1 |
| macOS | 51 | Windows suites as above; Linux suites (`LinuxControlIntegrationTest` 7, `JnaLinuxTest` 3, `ListedPriorityTest` 1); `HostPlatformTest` 2; `InstanceLockTest` 1 |
| Windows | 35 | 25 disabled on Windows: the Linux and macOS suites, and two POSIX-only tests (`CpuLimitIntegrationTest` 1, `SuspensionJournalTest` 1). 10 assumption skips without a message: the native suites in the runner's Job Object (`WindowsJobIntegrationTest` 5, `NativeLimitRecoveryIntegrationTest` 4, `ParentExitsChildSurvivesTest` 1) |

On Linux, `LinuxControlIntegrationTest` (7) and `JnaLinuxTest` (3) ran and passed. On macOS,
`MacosControlIntegrationTest` (7) and `JnaMacosTest` (6) ran and passed.

## The memory-signal evidence (ADR-0015 §7 item 1)

`OomSignalValidationTest` and `OomResidentValidationTest` ran on all three systems. None was
skipped, and none failed. Their lines, from the surefire reports:

| System | Commit, run | `oom-signal:` | `oom-resident:` |
|---|---|---|---|
| Linux | `81a4a52`, 38375835210 | total 7937 MiB, baseline 5427 MiB, M 512 MiB, fall 470 MiB (0.92 M), recovery 471 MiB (0.92 M) | idle 57 MiB, M 256 MiB, first 314 MiB, second 314 MiB, gone true |
| Linux | `3d6e4eb`, 38378078944 | total 7938 MiB, baseline 5679 MiB, M 512 MiB, fall 519 MiB (1.01 M), recovery 479 MiB (0.94 M) | idle 57 MiB, M 256 MiB, first 313 MiB, second 313 MiB, gone true |
| macOS | `81a4a52`, 38375835210 | fall 119 MiB (**0.23 M**), recovery 276 MiB (0.54 M); "signal not characterised on macOS" | idle 47 MiB, first 312 MiB, second 312 MiB, gone true |
| macOS | `3d6e4eb`, 38378078944 attempt 2 | fall 142 MiB (**0.28 M**), recovery 272 MiB (0.53 M); "signal not characterised on macOS" | idle 47 MiB, first 312 MiB, second 312 MiB, gone true |
| Windows (hosted) | `3d6e4eb`, 38378078944 | fall 550 MiB (1.08 M), recovery 637 MiB (1.24 M) | idle 42 MiB, first 306 MiB, second 306 MiB, gone true |

**Linux.** Both runs met the thresholds of the tests:
- a fall and a recovery of at least half of M;
- the resident memory at or above M in two listings;
- the record gone after the exit.

The thresholds are asserted on Linux: a reading below them fails the test, never skips it.

**macOS.** The available-memory signal fell by only about a quarter of M while the demo held M.
This confirms O17: OSHI's macOS "available" (free + inactive pages) does not follow memory that a
process takes. The resident memory did follow. macOS stays not validated.

## What the Linux run exercised

- **The real path.** `OomGuardIntegrationTest` (7 tests, 0 skipped on Linux) runs OOM Guard with
  the real Linux controller, the real process listing and a journal on disk. It acts on the demo
  workload only, through real signals:
  - suspends at Emergency and resumes at Normal;
  - terminates once, only with both opt-ins;
  - never acts on an identity with another start time;
  - does nothing with the default switches.
- **Forced validation inside the test only.** The test hands OOM Guard a validated signal on
  purpose, so that the actions can be tested. The product's gate is tested apart from it:
  `HostPlatformTest` checks that Linux is UNVALIDATED, and `MonitoringRuntimeOomTest` and
  `EffectiveActionTest` check that every switch falls back to Notify on an unvalidated system.
- **Priority on Linux.** It is reported NOT_REVERSIBLE (`oom-priority: … restorability
  NOT_REVERSIBLE`), so OOM Guard never lowers a priority there.
- **No pressure, no privilege.** No real memory pressure was made (M ≤ 512 MiB), and no elevated
  privilege was used: the runner user owns the demo.

## ADR-0015 §7: what this evidence covers and what it does not

| Item | Status |
|---|---|
| 1. Both tests passed on Linux in `verify (ubuntu-latest)`, run, not skipped, thresholds met | **Met for `3d6e4eb`.** It counts for a change only if that change is made on `3d6e4eb`, or on a commit whose parent is `3d6e4eb` and which changes nothing in `platform` or the tests. |
| 2. Raw reports and output lines kept, commit and run named | This file. The raw surefire reports are in the milestone 11 handoff archive. |
| 3. A note on containers | **Not established.** The run was on a hosted Azure virtual machine, not in a container, and `MemAvailable` reported the machine's 7938 MiB. Whether the job's own cgroup v2 group had a `memory.max` was not read. The gate does not read Prism's own `memory.max` (known limit). |
| 4. External reviewer and product owner accept | **Pending.** |

Until item 4, Linux stays not validated, as ADR-0015 §7 requires.
