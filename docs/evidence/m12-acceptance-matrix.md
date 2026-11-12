# Milestone 12: acceptance matrix

**M12 is accepted with K16 active and three documented exceptions (2026-11-07).** ADR-0016 is Accepted and ADR-0003 is Superseded. See
the [acceptance decision](m12-acceptance-decision.md). This acceptance does not enable Linux native limits for real user processes.

Unless a section says otherwise, the verdicts and evidence below are for the final candidate, commit
`f0f42799c672f6650b494d74a085e4ea5fcaa68b`, tree `b3340ad58a3f742359acd09d6b23b84cd8c65ae7`. Sections about earlier commits are
labelled and are history. This file is in a later commit that changes documents only. No test or build step reads these
documents, and that later commit has not been tested itself.

Verdicts: PASS has evidence named in the row, and is a verdict on that evidence, not an acceptance. BLOCKED cannot proceed without
something outside this work. DEFERRED is not part of this milestone's closure.

## Acceptance criteria

| Criterion | Evidence | Verdict |
|---|---|---|
| LIM-6 holds for the mechanism on real systemd and cgroup v2, on disposable, test-owned processes | Native suite 3 of 3 on the VM (native-15 to 17) and once on GitHub-hosted Ubuntu (below); details in [m12-hyperv-validation.md](m12-hyperv-validation.md). The OOM kill is not covered there, see below | PASS |
| K16: the product's limiter admits no process, opens no bus, clears nothing; nothing configures it; a second composition fails a test | [Risk register](m12-risk-register.md), `HostPlatformTest.theProductsLinuxLimiterAdmitsNothing`, `ReadOnlyNativeScanTest`, passing in the gates at this commit. Before this commit, four deliberate source changes (a constructor reference, a fully qualified construction, a scope manager in `product`, a non-final field) each failed the scan; that was not repeated here. Tests and source review, not a penetration test. K16 is an active safety restriction in the current product, see the [acceptance decision](m12-acceptance-decision.md) | PASS |
| A failed precondition reports `UNAVAILABLE` with its reason and falls back to the emulated limiter; an unanswered step stays uncertain and starts no second limiter | `LinuxNativeReadinessTest`, `LinuxScopeLimiterTest`, `LimitServiceNativeTest`, in every gate at this commit | PASS |
| Limits only on scopes Prism created and proves; a foreign or unproven scope is refused; a write reaches only the recorded invocation | `LinuxScopeLimiterTest`; on real systemd, in native-15 to 17, `aRecordlessScopeWithPrismsNameIsNeverChangedAndBlocksItsMembers` and `aLimitAddressedToAnInvocationNoUnitHasChangesNothing` | PASS |
| Removing a limit reads back as none; nothing is moved back, no scope stopped, no other unit changed, no unit left after its processes exit | `LinuxNativeLimitIntegrationTest` in native-15 to 17. After each VM run: no Prism scope 3 s later, no Java process of the run left. User units: 257 before and 257 after in each native run. In verify-9, 249 before and 257 after: a loop device and a mount for `snap-store` revision 1468 appeared, which points to a snapd refresh during the run. None of the eight is a Prism unit, and the suite's own check inside that run found 249, 249 and no change | PASS |
| The CPU limit is converted with the online logical processor count | `LinuxScopeLimiterTest.theProcessorCountIsTheKernelsOnlineListNotTheJvmsShare` and `theCpuEdgesAreExactQuotas`; measured on 2 vCPUs in native-15: 0.020 cores at 1 %, 0.401 at 20 % (quota 0.400), one busy worker at 0.991 at 100 % | PASS |
| The `memory.max` and `memory.high` text matches the kernel's documented behaviour | Not met. The text of `LimitMessages` covers Windows semantics only (register D8) and is unreachable on Linux under K16. The [acceptance decision](m12-acceptance-decision.md) states the scope and accepts the deferral as exception 1 | DEFERRED until K16 lifts |
| The full gate passes on Windows, Linux and macOS at the final commit | At this commit: Windows, Linux and the macOS test machine (local user: kerem), each `./mvnw -B clean verify`, see [Results at f0f4279](#results-at-f0f4279). Hosted `verify` passed on all three as well | PASS |

## Manual verification under K16

| Item | Evidence | Verdict |
|---|---|---|
| Integration suite as the desktop user through `user@UID.service` | `verify-9` and `native-15` to `native-17`, as the desktop user `prism` under `user@1000.service`, over SSH, not in a graphical session | PASS |
| Refusal of a login session scope and of a process outside the user manager | `aProcessOutsideTheUserManagerIsNotEligibleAndNothingIsCreated`: the process is refused with the reason and stays outside any Prism scope. On the VM it ran in a `session-*.scope` (`session-87.scope` in native-15), as its printed reason shows, because the suite started over SSH. The test does not itself pin that scope type; no Prism unit was left afterwards | PASS |
| Where processes land from GNOME Shell and a terminal, by a disposable probe, moving nothing | [m12-hyperv-validation.md](m12-hyperv-validation.md), paths A to F, 2026-10-16, not repeated at this commit. The probe is separate from Prism's code. Observations, not a validation | PASS as an observation |
| No scope or unit change left behind | The cleanup row above | PASS |

## Not part of this closure

Before K16 can be lifted, all DEFERRED: the demo workload limited from a desktop launcher and a terminal; `memory.high` and
`memory.max` driven on a desktop and recorded; Prism's own window on a Linux desktop; cleanup after exit; the register's items.

The OOM kill was not observed on the Hyper-V VM, because 4 GiB of swap took the excess. It was seen on the swapless VirtualBox
VM at c85525a and at a388cc0. Swap was not disabled to force it. It is not a written criterion of milestone 12. The acceptance decision
treats that earlier evidence as enough only for M12 with K16 active; it does not count toward lifting K16.

## Results at f0f4279

Counts are tests / failures / errors / skipped.

**Windows** (a clean export, Windows 11 Pro 10.0.26200, Temurin 25.0.4.1, the VM off):

| Check | Result |
|---|---|
| `./mvnw -B clean verify` | PASS, 6:20 min, 2298 / 0 / 0 / 37; enforcer passed, Spotless passed, SpotBugs 0, `ArchitectureTest` 28 / 0 / 0 / 0 |
| Path C native suites, `native-validation.mjs` | PASS, 20 of 20, none skipped |
| Hook tests | PASS, 326 of 326 |
| CI script tests | PASS, 8 of 8 |

**Which JDK ran the Windows gate.** `summary.txt` in the package has the line `java version "26" 2026-03-17`. That line does not describe
the JVM that built and tested. The gate script printed it with a plain `java -version`, which finds Oracle Java 26 first on that machine's
`PATH`, while the Maven wrapper uses `JAVA_HOME`, which pointed at Temurin 25.0.4.1. The evidence for that:

- All 168 Surefire reports of the gate and the four of path C record `java.version` 25.0.4.1 and `java.vendor` Eclipse Adoptium.
- `verify.log` has ArchUnit's "Detected Java version 25.0.4.1".
- The enforcer's `requireJavaVersion` rule, `[25,26)`, passed.
- The tests that start a child JVM take it from `java.home`.

Java 26 was not used to build or test, and the evidence files were not changed. The Windows summaries of 3b3475b, 70b9b6d and 963d68a have
the same line from the same script. Their Surefire reports are not in the package and were not rechecked.

**Linux** (the Hyper-V VM, Ubuntu's OpenJDK 25.0.4.1, not Temurin): `verify-9` PASS, 347 s, 2287 / 0 / 0 / 64; `native-15` to `native-17`
PASS, 12 / 0 / 0 / 0 each. Details in [m12-hyperv-validation.md](m12-hyperv-validation.md).

**macOS test machine (local user: kerem)** (macOS 26.6.2, Darwin 25.6.0, Apple M2, 16 GiB, Temurin 25.0.5+8, Maven 3.10.0, locale `tr_TR`), one run:

| Check | Result |
|---|---|
| `./mvnw -B clean verify` | PASS, exit 0, 5:31 min, 2287 / 0 / 0 / 63; enforcer passed, Spotless passed, JaCoCo report ran, SpotBugs 0 |
| `JnaMacosTest` | 6 / 0 / 0 / 0 |
| `MacosControlIntegrationTest` | 7 / 0 / 0 / 0 |
| `CpuLimitIntegrationTest` | 8 / 0 / 0 / 0 |
| `SuspensionJournalTest` | 17 / 0 / 0 / 0 |
| `ArchitectureTest` | 28 / 0 / 0 / 0 |

All 63 skips read "Disabled on operating system: Mac OS X". The run deviated from the procedure in two ways, battery power and a
locally unpacked Temurin 25; the [acceptance decision](m12-acceptance-decision.md) records both as accepted.

**Hosted:** run 42319242944, in the next section.

**Where the evidence is.** Not in this repository.

| System | Source | Identity shown there |
|---|---|---|
| Windows | `03_EVIDENCE/windows-f0f4279/` in `prism-m12-nonmacos-closure-handoff.zip` | Commit `f0f4279`, tree `b3340ad` |
| Linux | `03_EVIDENCE/linux-f0f4279/` in the same package | Commit and tree, expected equal to actual in each run; 0 uncommitted files |
| Hosted | `03_EVIDENCE/hosted-f0f4279/` in the same package | Run head `f0f4279`; merge ref `a39bf2a`, tree `b3340ad` |
| macOS | `PRISM_M12_MACOS_EVIDENCE_20261107T105813Z.tar.gz` | `git.txt`: commit `f0f4279`, tree `b3340ad`, 0 uncommitted files |

The package's SHA-256 is `2c09847a6320f82ad81302eba1b1b3e2ee1e94107a52caf3195f9a8d6090ef8d`, the macOS archive's is
`27d5e4d3e793e883a3339a83fcb0b9bddb9e49fa2163281093480ccc652069ba`. The archive was checked independently on Windows: this SHA-256, its
internal checksums (344 of 344), and the 168 Surefire reports, which add up to the counts above.

## GitHub Actions

Actions starts jobs again.

| Job | 151ab20, run 42302228938 | 70b9b6d, run 42306611864 | 3b3475b, run 42312193915 | f0f4279, run 42319242944 |
|---|---|---|---|---|
| `verify (windows-latest)` | PASS, 2295 / 0 / 0 / 47 | PASS | PASS, 2298 / 0 / 0 / 47 | PASS, 2298 / 0 / 0 / 47 |
| `verify (ubuntu-latest)` | PASS, 2284 / 0 / 0 / 64 | PASS | PASS, 2287 / 0 / 0 / 64 | PASS, 2287 / 0 / 0 / 64 |
| `verify (macos-latest)` | PASS, 2284 / 0 / 0 / 63 | **FAIL**, 2287 / 1 / 0 / 63 | PASS, 2287 / 0 / 0 / 63 | PASS, 2287 / 0 / 0 / 63 |
| `linux-native-validation` | PASS, 12 tests, 0 skipped | PASS | PASS, 12 tests, 0 skipped | PASS, 12 tests, 0 skipped |
| `native-validation` | FAIL by design | FAIL by design | FAIL by design | FAIL by design |

Counts are tests / failures / errors / skipped.

**What run 42319242944 tested.** It is a `pull_request` run, so it checked out GitHub's merge ref, not the branch head: `a39bf2a`, with
parents `321dbe5` (`main`) and `f0f4279`. The tree of `a39bf2a` is `b3340ad`, the same as the tree of `f0f4279`, so the same files were tested.
The workflow as a whole concluded `failure`, because of `native-validation`.

**The macOS failure at 70b9b6d** was a race in a test that exists since c5b39fb, not in the change under test.
`releaseAllGivesUpWithinItsBoundWhileAnAdoptionWaitsForTheManager` let the adoption finish while the timed-out release worker
could still be waiting for the lock. Delaying that worker by 50 ms reproduced the same failure at the same line; with the fix the
same delay passed 3 of 3. No assertion was weakened: the test now waits for the worker. The product behaviour behind it is
register item D13.

**`native-validation` is red by design:** the hosted Windows runner runs inside a Job Object, so the Windows native suites skip
themselves and the check refuses that. The local path C run stands in for it, the policy of milestone 10. The workflow as
a whole therefore stays red. This is an open, documented exception, not a PASS: the check was neither removed nor relaxed. See exception 3 of
the [acceptance decision](m12-acceptance-decision.md).

A hosted macOS `verify` does not replace the run on the macOS test machine (local user: kerem), which is recorded above.

## After 3b3475b

| Commit | Change since the one before | Hosted run | Local gates |
|---|---|---|---|
| 0f39e7a | Documents | 42315704145: **`verify (windows-latest)` FAIL**, 2298 / 1 / 0 / 47; the rest as usual | Not run |
| 963d68a | Documents | 42316697055: **`verify (ubuntu-latest)` FAIL**, 2287 / 1 / 0 / 64; the rest as usual | Windows 2298 / 0 / 0 / 37, path C 20 of 20; Linux 2287 / 0 / 0 / 64, native 3 of 3 |
| f0f4279 | `f1db353`: the two test fixes below. `f0f4279`: documents. `src/main` and `pom.xml` are the same as at 3b3475b | 42319242944: `verify` passed on all three; `native-validation` FAIL by design | [Results at f0f4279](#results-at-f0f4279) |

Neither failure can come from the document changes. Both are races in old tests under the load of a hosted runner:

- `MonitoringRuntimeOomTest.aJournalFailureReleasesWhatOomGuardHolds` read the last view at once, though OOM Guard publishes it
  on its own thread, after the release and the log line. The test now waits for the same condition.
- `CpuLimitIntegrationTest.aProcessStoppedByAnotherProgramIsNeitherHeldNorContinuedByTheLimiter` assumed that a stop from outside
  always lasts. A stop that coincides with the limiter's suspend ends with its continue, a residual risk ADR-0011 names. The log
  fits that: the stop succeeded and the limiter never logged it. This was not observed directly. The other program now stops the
  process again, up to five times, and prints a line when it has to. A limiter that never notices still fails the test.

**Three such races in five hosted runs: more may remain.** See register item D15.

Branch protection and rulesets cannot be read on this private repository (the API answers 403, "Upgrade to GitHub Pro or make
this repository public"), so none is enforced. Nothing was changed there.

## Earlier round: commit 3b3475b, 2026-11-07

History, not results of the final candidate. Commit `3b3475b5baa85abbdf3a81d78eb84bd945cfc360`, tree
`e3f1d27f9dbc71576a9d7670da986855189cb7b3`.

**Windows** (a clean export, Windows 11 Pro 10.0.26200, Temurin 25.0.4.1, the VM off):

| Check | Result |
|---|---|
| `./mvnw -B clean verify` | PASS, 7:22 min, 2298 / 0 / 0 / 37; enforcer passed, Spotless passed, SpotBugs 0, `ArchitectureTest` 28 / 0 / 0 / 0 |
| Path C native suites, `native-validation.mjs` | PASS, 20 of 20, none skipped |
| Hook tests | PASS, 326 of 326 |
| CI script tests | PASS, 8 of 8 |

**Linux:** see [m12-hyperv-validation.md](m12-hyperv-validation.md), round 2.

**macOS:** only hosted `verify` at this commit. The local macOS run is at f0f4279.
