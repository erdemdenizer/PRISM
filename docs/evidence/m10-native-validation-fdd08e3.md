# Milestone 10 native validation, plan §11.6 path C: commit fdd08e3

This is external evidence for the `native-validation` requirement of milestone 10. It is **not a
green CI result**. The GitHub-hosted `native-validation` check of PR #2 stays red for this commit
(see "Why path C"), and this file does not change that.

**Acceptance (2026-10-10).** An independent external reviewer verified this evidence and
**accepted** it as the alternative native verification that plan revision 4.1 §11.6 permits
(path C). The review covered:

- the 18 hashes of the review package and the 15 published artifact hashes;
- the four Surefire XML suites: 20 tests, 0 skipped, 0 failures, 0 errors;
- the Maven and validation outputs, which agree with the XML;
- an independent pass of `native-validation.mjs` on these XML copies.

The acceptance covers native validation only. It is not a green hosted check, and it does not by
itself complete milestone 10 (see docs/MILESTONES.md §10).

## Source identity

| Fact | Value |
|---|---|
| Tested commit | `fdd08e314aab24d82b12db6da6626c86c578702e` (`docs: record Windows native limits and accept ADR-0012 and ADR-0013`) |
| Branch / remote | `feat/m10-windows-native-limits`; `origin/feat/m10-windows-native-limits` and `git ls-remote` gave the same SHA |
| Checkout | a new detached `git worktree` at that SHA, outside the developer's checkout. `git status --porcelain=v1 --ignored` printed nothing before the run, and no `target/` directory existed. |
| After the run | `git status --porcelain` in the worktree still printed nothing (`target/` is ignored) |

This evidence holds only for the code of `fdd08e3`. A later commit that changes anything outside
`docs/` makes it void. To check a later commit `<C>`, run
`git diff --name-only fdd08e314aab24d82b12db6da6626c86c578702e <C>`: it must list only files under `docs/`.

## Environment

| Fact | Value |
|---|---|
| Windows | Windows 11 Pro 25H2, version 10.0.26200, build 26200.9457, 64-bit (`os.arch` amd64) |
| Processor | AMD Ryzen 7 5800H, 16 logical processors |
| Java | Eclipse Temurin 25.0.4.1+1-LTS, OpenJDK 64-Bit Server VM |
| Maven | 3.10.0 (c43a36b8d67be7e0805a411bc0898af1a51f5472), through the wrapper |
| Locale | tr_TR, platform encoding UTF-8, native encoding Cp1254, time zone Europe/Istanbul |
| Test JVM in a Job Object | no: `selfInJob=Optional[false]` on all 10 `native-validation:` lines |
| Test children in a Job Object | no: `childInJob=Optional[false]` for every child (10 children) |
| Native capability probe | CPU `NATIVE_HARD` (`JOB_OBJECT`), memory `NATIVE_HARD` (`JOB_OBJECT`). The CPU rate-control probe was accepted, so Dynamic Fair Share Scheduling did not refuse it (error 50 not seen). |
| Run time (UTC) | 2026-10-10T19:16:11Z to 2026-10-10T19:18:35Z |

## Command

Run in the clean worktree:

```
./mvnw -o -B test -Dtest=WindowsJobIntegrationTest,NativeLimitRecoveryIntegrationTest,ParentExitsChildSurvivesTest,JnaWin32Test -Dprism.native.required=true
```

- Exit code 0, `BUILD SUCCESS`.
- With `-Dprism.native.required=true`, a test whose child is in a Job Object fails instead of
  being skipped (`NativeValidation.decide`).

Then, on the reports of that run:

```
node .github/scripts/native-validation.mjs target/surefire-reports
```

Exit code 0:

```
native validation passed
validated: prism.srm.platform.windows.WindowsJobIntegrationTest: tests=6
validated: prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest: tests=4
validated: prism.srm.platform.windows.ParentExitsChildSurvivesTest: tests=1
validated: prism.srm.platform.windows.JnaWin32Test: tests=9
```

The script also passes on the sanitized copies in this folder.

## Results

| Suite | Tests | Skipped | Failures | Errors | Time |
|---|---|---|---|---|---|
| `WindowsJobIntegrationTest` | 6 | 0 | 0 | 0 | 32.1 s |
| `NativeLimitRecoveryIntegrationTest` | 4 | 0 | 0 | 0 | 64.2 s |
| `ParentExitsChildSurvivesTest` | 1 | 0 | 0 | 0 | 20.4 s |
| `JnaWin32Test` | 9 | 0 | 0 | 0 | 1.7 s |
| **Total** | **20** | **0** | **0** | **0** | |

This matches the expected 20 tests.

## What each real Windows test established

Every target is a disposable demo process started and ended by the test. CPU figures are
whole-machine percentages, measured from outside over 10 s windows.

| Test | Established (measured values from this run) |
|---|---|
| `aTwentyPercentCapHoldsTheWorkloadNearItAndClearingItLetsTheWorkloadRunAgain` | A 20 % hard cap held the workload near 20 %, and clearing the cap freed it again. Measured: `baseline=57.4 capped=20.8 cleared=57.9`. |
| `aCommitLimitRefusesAnAllocationBeyondItAndClearingItAllowsTheAllocation` | Under a 256 MiB commit limit, a 512 MiB allocation failed after 80 MiB (`allocated=80 failed=true`). After clearing (`APPLIED`), 512 MiB was allocated. |
| `aProcessInAJobOfAnotherProgramIsRefusedAndNoJobIsMade` | A process in a job Prism does not hold was refused before assignment (`NOT_ASSIGNED`, fallback eligible), and no hold was kept. |
| `aProcessThatHasExitedIsNotFoundAndNothingIsLeft` | Applying to an exited process returns `NOT_ASSIGNED` / `PROCESS_NOT_FOUND`, and no hold is kept. |
| `anExitedProcessIsForgottenAndItsJobLetGo` | When the target exits, `forget` clears the limits and lets the job go. |
| `bothNativeLimitsAreAvailableHere` | The system probe reports both kinds `NATIVE_HARD` on this machine. |
| `afterAHardKillTheLimitStaysTheNameIsGoneAndTheNextStartListsIt` | Prism was hard-killed. The cap stayed in force (`limited=20.9 afterKill=20.1`), and the job name was gone (`OpenJobObjectW` error 2) while the target was still in a job. The next start listed the target as `UNREACHABLE` and changed nothing (`afterRecovery=20.5`). After the target exited, the verdict was `GONE_CHILDREN_UNKNOWN`. |
| `recoveryNeverChangesTheJobOfAPrismSessionThatStillRuns` | The job of a Prism session that still runs is reported `HELD_ELSEWHERE` and left unchanged (`use=20.6`). |
| `aProcessTheTargetStartedStaysLimitedAndTheNextStartSaysItMayBe` | A child of a killed session's exited target stayed limited (`childUse=21.7`), and the next start warned `GONE_CHILDREN_UNKNOWN`. |
| `aCrashInTheMiddleOfAChangeIsReadAsEitherValuePerKind` | Prism was killed after a CPU change from 20 % to 30 % (`afterKill=31.7`). Recovery accepted the value per kind and reported `UNREACHABLE`. |
| `whenTheTargetExitsPrismClearsItsLimitsForTheChildAndLetsTheJobGo` | A child stayed limited after its parent target exited (`limited=20.9`). `forget` cleared the limits (`RELEASED`, `survivors=2`: the child and its console host), and the child ran free afterwards (`free=58.9`). |
| `JnaWin32Test` (9 tests) | The JNA mappings and structure sizes read the real system. The System process refuses every action right with access denied. Error codes reach the caller with their system text. |

## Observed during the run

`WindowsJobLimiter` logged one warning:

```
Clearing a job's limits: CPU 87, memory 0
```

- **Where it came from.** The `releaseAll` of the `@AfterEach` clean-up, after the commit-limit
  test. That job never had CPU rate control and had already been cleared.
- **What the code means.** Setting CPU rate control to 0/0 on such a job returned error 87
  (`ERROR_INVALID_PARAMETER`).
- **Why it does not decide the outcome.** The limiter never takes a set call's return value as
  the result. It reads the job back, and the read-back showed no limits. No test failed.
- **What was changed.** Nothing: this task authorizes no source change. The behavior is recorded
  here for the reviewer.

## Not run, or not supported

- 32-bit targets, and systems with Dynamic Fair Share Scheduling enabled.
- Recovery across users or across elevation.
- Linux and macOS: no native limiter there. Their CI runs are recorded on PR #2.
- `-Pui-snapshots` UI tests and manual checks (Task Manager comparison): not part of this suite.
- One machine, one run. This is not a statistical study of the cap's accuracy.

## Why path C

On the GitHub-hosted runner (`windows-latest`, run 38069100036, job 114262681024), the test JVM
runs inside a Job Object, and so does every child:

```
native-validation: selfInJob=Optional[true] childInJob=Optional[true]
```

By design (plan D1), Prism refuses a process that is in a job it does not hold. In the main CI the
native suites are therefore skipped, and the separate `native-validation` check (job
114263827531) correctly failed:

```
not validated: prism.srm.platform.windows.WindowsJobIntegrationTest: tests=6 skipped=5 failures=0 errors=0
not validated: prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest: tests=4 skipped=4 failures=0 errors=0
not validated: prism.srm.platform.windows.ParentExitsChildSurvivesTest: tests=1 skipped=1 failures=0 errors=0
validated: prism.srm.platform.windows.JnaWin32Test: tests=9
```

- **What was not done:** path A tier 2 (breakaway in test sources), and path B (a self-hosted
  `windows-native` runner, which needs a runner registration). Neither is available in this task.
- **What stays as it is:** the hosted check stays red, and no threshold, skip rule or workflow was
  changed.
- **The exception:** this file is an external-evidence exception for independent review, not a
  substitute for a green check.

## Artifacts

The folder `m10-native-validation-fdd08e3/` holds sanitized copies of the original files.

**Sanitizing changed only machine-local data:**

- In each XML, the `<properties>` block keeps only these properties:
  - `java.version`, `java.vendor`, `java.vm.name`, `java.vm.version`, `java.runtime.version`
  - `os.name`, `os.version`, `os.arch`, `sun.arch.data.model`
  - `file.encoding`, `native.encoding`, `user.language`, `user.country`
  - `prism.native.required`
- In every file, the user's home path became `%USERPROFILE%`, and the host name became `<host>`.
- **Correction after acceptance.** The first pass missed one line of `native-suite-output.txt`.
  - JaCoCo's `argLine` (line 19) prints paths with doubled backslashes, so the home path stayed
    there, together with the path of the temporary worktree.
  - The copy was made again from the unchanged original: the doubled-backslash home path became
    `%USERPROFILE%`, and the worktree path became `<worktree>`.
  - Only line 19 changed. Its committed-copy hash below is new; the original and its hash are
    unchanged.
- `native-suite.log` is stored as `native-suite-output.txt`, because `*.log` is ignored.
- A `.gitattributes` in the folder turns off Git's whitespace checks there, because the
  trailing spaces of Maven's output are part of the record.

Test elements, counts, times and outputs are unchanged. The original files are kept outside the
repository by the developer, and their hashes are listed below so that they can be compared.

| File | SHA-256 of the original | SHA-256 of the committed copy |
|---|---|---|
| `TEST-prism.srm.platform.windows.WindowsJobIntegrationTest.xml` | `1ffacc9594072c5813d3b50fe51935ba49c678ddea2ee16d2ed4a9bed006d144` | `d283e9cacf7316662f9cc7d7ebb7a8b94f4f43dbbeb323e24e13b6aa019c024d` |
| `TEST-prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest.xml` | `b8a51afb0e04076270607880f50fabcfa727006560ae57f4814ee97cbcc46f7e` | `fbc09cf43321fc636b5e0d27e8f1ff0e1362a44908e4d94227cd8aec6f5072e7` |
| `TEST-prism.srm.platform.windows.ParentExitsChildSurvivesTest.xml` | `5a14f24cf8d25e5808dc39edbd1ae80d9e2a55799fb0440746f1fb2723ae2d0e` | `0712eeee1cc2793afae571f19d31876a3a433ef9fc369043a1665d11be443000` |
| `TEST-prism.srm.platform.windows.JnaWin32Test.xml` | `c45b02a2130c4057bcc1682d73a04fe315fe4bb9bbb76b176548abc66a1ee797` | `22beb0f2fa7dc3a145bca3f6e36bf4b67e6956d05096015742671a47b376dd84` |
| `prism.srm.platform.windows.WindowsJobIntegrationTest.txt` | `352409095ec16f1f25ada863d24c287a5d17251939c24450b131496e8046a0e6` | `6263c4a2fe8f8ddf2fef2a7843d7ab054584bfc398273fa7665288b290262b47` |
| `prism.srm.platform.windows.NativeLimitRecoveryIntegrationTest.txt` | `41d0956d1267e5d8eb408ada10e3a4e2affc4061aa14d9fe518ad9e02592ccf6` | `2d7cfd6172a0c25793da2e6bdbf3f5333dbb58c298bbdc2b9898973833490abc` |
| `prism.srm.platform.windows.ParentExitsChildSurvivesTest.txt` | `1807a4dd31622308fd1069a52a7f30c272a57b1e49cf3a2f39588be49791a8f4` | `8a35ed10f98acfa3e06a652159dfb224b496a7ef324ece22cffdd2b89d6b00a6` |
| `prism.srm.platform.windows.JnaWin32Test.txt` | `0975a50a0249d07ab1d6bce79e3fa3ae2b800ce43559231a325b7bc3dc7ecbe3` | `291e7db2923732788b004e53ec1b3ff291d72d1fb90fdafb3a02a5fb39131d33` |
| `native-suite.log` → `native-suite-output.txt` | `0b8489685e3718e1d97f56e9746f10f0b4b2f6bd44cc7d9967d8615ce876d4fd` | `b8c29c7ac42b8da44ce0defa09edf968ce89670766f3bb00a7b729616dd4b272` |
| `native-suite.exit` | `19eaf43821a7660ec323a87c8457bf74823beb296c39f5e01aa8a683aa50f061` | same |
| `validation.out` | `b023d46b0faf09e92a4994a8a964910c01eeac96ed8e8c114b4373fefea2eda6` | same |
| `validation.exit` | `19eaf43821a7660ec323a87c8457bf74823beb296c39f5e01aa8a683aa50f061` | same |
| `maven-version.txt` | `76e9ed8054bcc07bde355c73e41f9ce6bea6ff2088ab78a72e6913249e17fd9a` | `08316f687d088bfbc7a1069f0c48b441998ab4b528646890dfa6de50a8fd453f` |
| `start.txt` | `a4bb8253bd313e3db742dcb7a77b29b38bbc7877169bca3f43449fbc5ca2ff4b` | same |
| `end.txt` | `4ac66406d446d46947c14f4e4eb9c51a6f350b997252d01a8821a844ceea911d` | same |

**Freshness of the reports.** The worktree had no `target/` before the run. Each XML's file time
falls between the run's start and end:

- 22:16:38 for `JnaWin32Test`
- 22:17:42 for `NativeLimitRecoveryIntegrationTest`
- 22:18:02 for `ParentExitsChildSurvivesTest`
- 22:18:34 for `WindowsJobIntegrationTest`

These are local times (UTC+3); the run went from 19:16:11Z to 19:18:35Z.
