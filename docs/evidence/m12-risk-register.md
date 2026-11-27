# Milestone 12: risk register

Re-assessed on 2026-11-07 against the source, by the author and by an independent read-only `native-api-reviewer` that
was given the code and not this register's conclusions. Line numbers are not given here because they drift; each risk names
the class and method.

Classes:
- **BLOCKING:** reachable or harmful with K16 active, so it must be fixed before M12 can close.
- **DEFERRED:** real, but unreachable in the product. It must be fixed or accepted again before K16 is lifted.
- **DOCUMENTATION:** only a wording or document gap.
- **MITIGATED:** the risk is bounded by a mechanism in place; what remains is stated.

**Summary: no open BLOCKING risk.** Two findings were reachable with K16 active and are fixed (F1, F2). Everything else needs an
admitting limiter, which only the test harness composes.

## K16: can an ordinary product path reach the mechanism?

No. `HostPlatform.limiterFor(LINUX, ...)` builds `LinuxScopeLimiter.product(...)`, with the policy `EligibilityPolicy.none()`,
`NativeConsent.none()` and no scope manager.

| Path | What the product does |
|---|---|
| Apply | Refused by `eligibility` before any pidfd, bus or scope. `LimitService` does not reach it, because every capability is `UNAVAILABLE` |
| Change, forget | No hold can exist: holds are added only by adoption. Answers `NO_HOLD`, `NOT_HELD` |
| Clear after a crash | `clearOwned` refuses `UNSUPPORTED`; `recoveryClears` is false |
| Recovery at start-up | `mayHaveLeftLimits` is false, so `MonitoringRuntime` gives recovery no folder. No ledger is read, whatever a file on disk says. `recover` itself answers `FAILED` |
| Release at shutdown, shutdown hook | `releaseAll` finds nothing and answers `NOTHING` |
| OOM Guard | Never refers to the limiter |
| Bus | Neither `new SystemdScopeManager` nor `SystemdCapabilityProbe.onThisSystem` has a caller in the main sources, so `libsystemd` is never loaded |
| Inputs | The Linux limiter and `HostPlatform.limiterFor` read no setting, system property that selects behaviour, or environment variable. The factories and `Parts` are package-private |

What a Linux user gets:
- A process in a scope named as PRISM names them, `prism-srm-limit-<pid>-<start millis>.scope`: it can be managed, and any limit is refused with the reason (the scope-membership rule). The scope is left untouched.
- A ledger left by an earlier session: not read, no warning, not deleted.
- CPU limit: the emulated duty cycle of milestone 9, after the membership check.
- Memory limit: `UNAVAILABLE`, "memory signals are not validated" (ADR-0015 §7).

Tests that pin this:
- `HostPlatformTest.theProductsLinuxLimiterAdmitsNothing`, on the product's own composition, including recover, change, forget and release.
- `ReadOnlyNativeScanTest`: only `LinuxScopeLimiter.product` is used outside the native classes; one construction of the limiter;
  no admitting factory, constructor reference or scope manager in the main sources; `product` passes no scope manager; the
  policy, consent and scope manager fields are final.

The K16 tests were strengthened on 2026-11-07. Before that, `product`'s other paths were untested on the product's composition,
and method references and fully qualified constructions passed the scan.

**Limit of this claim.** There is no `module-info`, so a class added to the classpath could enter the package. That already
requires running code as the user, so it adds no reach. This was established by reading the source and by the tests above. The
packaged classpath does not exist yet (M13).

## Fixed on 2026-11-07

| # | Finding | Why it mattered | Fix and test | Class |
|---|---|---|---|---|
| F1 | `scopeBarrier` returned "no barrier" when the cgroup mount could not be determined (two `cgroup2` mounts, an unreadable `mountinfo`) | It contradicted ADR-0016 decision 8, "a failed read refuses". A member of a PRISM scope could get the emulated duty cycle. Reachable in the product, though it needs a PRISM scope on the machine | The rule now needs only `/proc/<pid>/cgroup`. No unified line with no mount means no such hierarchy, so no barrier. A malformed file refuses. `theScopeMembershipRuleHoldsWithoutTheCgroupMount`, seen failing before the fix | Was BLOCKING by definition, now fixed |
| F2 | The rule compared only the last path segment, and a test pinned that | A cgroup below a PRISM scope passed, although the scope's limits still apply to it. ADR-0016 says "inside". Same reach as F1 | `ScopeNames.enclosingPrismScope` matches any segment, in the barrier and in eligibility. `theScopeMembershipRuleCoversACgroupBelowAPrismScope`, seen failing before the fix; `aProcessBelowAPrismScopeIsNotEligible` | Was BLOCKING by definition, now fixed |
| F5 | `ResourceLimiter`, `RecoveredLimit` and `NativeRecovery` comments said recovery never clears | `NativeRecovery.settle` does clear, with a limiter that admits | Comments corrected | DOCUMENTATION, closed |

## Open

| # | Risk | What it can affect | Why it cannot do so today | Class |
|---|---|---|---|---|
| D1 | `sd_bus_open_user` seals the `Hello` call with libsystemd's default 25 s timeout before PRISM sets its 2 s bound | A broker that never answers `Hello` holds each open that long, and so the limiter lock during an adoption. Results stay fail-closed | The product opens no bus | DEFERRED. Fix: `sd_bus_new` with the timeout set before `sd_bus_start` |
| D2 | The target's cgroup is checked before `pidfd_open`, not after. systemd's user manager itself falls back to PID 1's `AttachProcessesToUnit` by PID when it lacks the privilege | With a privileged actor moving the target out of `user@UID.service` and a PID reuse in that window, systemd could move the newcomer by number. Whether PID 1 accepts that on a given system was not determined | No adoption in the product | DEFERRED. Fix: read the cgroup again after the open |
| D3 | sd-bus can make a standard refusal name locally from an errno after a send, which PRISM reads as "nothing was done" | A limit could be in force while reported refused. Whether this occurs in practice was not determined; `sd_bus_call` cannot tell the origin | The product opens no bus | DEFERRED |
| D4 | A hold whose process may no longer be signalled (EPERM) cannot be cleared in session: `change` is uncertain, `forget` says it still runs. Shutdown does clear it | The limit stays on PRISM's own scope until exit or shutdown | No holds in the product | DEFERRED |
| D5 | After a start refusal or a unit gone, a process that may not be signalled is reported `NATIVE_ERROR` with the emulated fallback allowed; before the start it is `ACCESS_DENIED`, no fallback | An inconsistent report; the emulated limiter would get EPERM | The native branch is unreachable | DEFERRED |
| D6 | Adoption holds the limiter lock on `srm-limiter` for seconds, and that thread also runs OOM Guard and emulated steps | Limit actions and OOM Guard evaluations queue behind an adoption | No adoption in the product | DEFERRED. Must be solved before enabling |
| D7 | `LinuxScopeLimiter` is about 1600 lines | Maintenance and review cost, no behaviour | n/a | DEFERRED |
| D8 | Group IDs are not compared. Zombies count as alive, and since they are absent from `cgroup.procs` they can be described as "moved". The wording does not say that memory charged before adoption stays with the old cgroup, or that the cpu controller does not limit real-time processes. `LimitMessages` describes Windows commit semantics for memory, wrong for a scope. `NativeRecovery`'s messages say this version does not clear | A set-group-ID program is admitted; misleading text | No adoption and no ledger in the product | DEFERRED |
| D9 | Between the proof and the send, a target can be moved out of its scope | The limit lands on PRISM's own invocation, never another unit (`NoUnitForInvocationID` seen on real systemd); the re-check reports a detected move as uncertain. An A-B-A move, or one after the re-check, goes unseen until the next operation | systemd has no conditional write, so the window cannot be closed | MITIGATED |
| D10 | `CgroupV2.validPath` rejects `..`, so a process outside PRISM's cgroup namespace root is refused an emulated limit, with "could not be read" | Availability inside a container or namespace; fails closed | Consistent with "a failed read refuses" | DEFERRED. Reachable, harmless, kept on purpose |
| D11 | Recovery's classification does not compare the record's cgroup with the live one, and `clearOwned` reads back the record's path; the unit is not bound to the identity | A crafted record could yield a false `CLEARED` | The product reads no ledger and clears nothing | DEFERRED. Must be fixed before enabling |
| D12 | "K16" appears in the text of the activity-log line from `limitations()` | An internal label shown to the user at start-up | Harmless | DOCUMENTATION. For the wording pass of M13 |
| D13 | A release that timed out keeps waiting for the lock for up to its bound. If an adoption in flight ends in that moment, the worker takes the lock past its deadline and lets go of the hold without clearing it, so a later release cannot clear it either | The limit stays on PRISM's own scope until the processes exit. It is reported correctly, as not reached. The window showed itself on hosted macOS at 70b9b6d as a test failure, and was reproduced by delaying the worker 50 ms; the test now waits for the worker | No holds in the product, and shutdown does not retry | DEFERRED |
| D14 | `LimitService` builds its native request with the two-argument `LimitSpec`, which means a hard memory limit, and no main code selects `THROTTLE`. The limiter's capability report says `NATIVE_SOFT` for memory. ADR-0016 decision 4 makes `memory.high` the default | Once enabled, a memory limit through the limit service would ask for `memory.max` and be refused without its separate consent | The product admits no process | DEFERRED. Must be settled before enabling |
| D15 | Tests that depend on timing. Hosted runners exposed three races in old tests within five runs (D13's test, and two described in the [acceptance matrix](m12-acceptance-matrix.md)), each passing elsewhere | A false red build, and time lost to it. All three were test faults; none changed the product | Each known race is fixed without weakening an assertion. Others may remain | MITIGATED |
| — | Under a stalled guest, a 2 s `GetUnit` timeout turns an adoption into `UNCERTAIN` | Availability, never correctness | Seen on the old VirtualBox VM only | MITIGATED (fail-closed) |

## Before K16 can be lifted

1. D1, D2, D6, D8, D11 and D14 closed; D3, D4, D5, D7, D10 and D13 fixed or accepted again.
2. The manual verification listed under milestone 12 in [MILESTONES.md](../MILESTONES.md), on a real Linux desktop.
3. A separate, explicit product-owner decision.

## Progress, 2026-11-27

Fixed, each with its own commit and tests, and reviewed by an independent native-API review; K16 stays in force:

| # | Status | How |
|---|---|---|
| D1 | PARTLY CLOSED | The connection is built with the steps of `sd_bus_open_user` (v255: `sd_bus_new`, the same address, `sd_bus_set_bus_client`, `sd_bus_set_trusted`) and its method-call timeout set before `sd_bus_start`, which seals the `Hello()` with it (`bus_seal_message` takes the connection's timeout for a 0 one). No event-loop function is bound, as `LibsystemdCheckTest` requires; a first version that drove the connection with `sd_bus_process` and `sd_bus_wait` broke that rule and was replaced. **Left:** a broker that hangs before SASL completes still holds an open for libsystemd's authentication timeout (`BUS_AUTH_TIMEOUT` = `DEFAULT_TIMEOUT_USEC`, 90 s upstream; Ubuntu's build value not confirmed), and so the limiter lock. Since D6 that lock is held on `srm-native-limit`, not on `srm-limiter`: such a stall delays only the native limit operations (in order, one at a time) and the release, which gives up at its own deadline; emulated steps, OOM Guard and requests for other processes go on, and the shutdown stays bounded (at most 1.5 s for the operation in flight, then the release's bound) |
| D2 | MITIGATED | The cgroup rule runs again after the pidfd is open, and the liveness and identity checks follow it, so they prove the read was the target's; a process moved out of the user's manager is not adopted. **Left:** a privileged move, an exit and a PID reuse between these checks and systemd's by-PID fallback; systemd has no conditional request, so the window cannot be closed (as D9) |
| D8 | PARTLY CLOSED | Code: a set-group-ID program whose four group IDs differ is refused (one that made them equal itself with `setresgid` is not caught); a zombie counts as exited only with a single thread left and its start time still the target's, because a thread-group leader that exits before its threads shows 'Z' while the process runs (kernel/exit.c, pidfd_poll in kernel/fork.c). Open: the wording (memory charged before adoption stays with the old cgroup; the cpu controller does not limit real-time processes; `LimitMessages` and `NativeRecovery` texts written for Windows or for the K16 product) |
| D11 | CLOSED | A record proves ownership only when its unit is the name PRISM gives the identity and the live invocation has exactly the recorded cgroup, which is also where a clearing reads back. **Residual:** the ledger has no integrity protection, so the same user can read a unit's invocation id and cgroup from the bus and write a matching record; the harm stays within a unit of PRISM's name for that identity |
| D12 | CLOSED | The user-facing reason no longer names K16 |
| D14 | CLOSED | `LimitService` asks for the memory boundary the limiter offers: `NATIVE_SOFT` (Linux `memory.high`) throttles, anything else is hard |

Fixed later the same day, with tests, and reviewed by an independent review on 2026-11-28, whose findings (S1, S2, C1 to C4, below) are addressed; run at `3c986c1` on Windows 11 (full gate, 2519 tests, 0 failures) and on the Ubuntu 24.04 desktop VM (full gate, 2508 tests, 0 failures; the native suites on real systemd, 11 of 11 and 1 of 1, no scope left); K16 stays in force:

| # | Status | How |
|---|---|---|
| D6 | CLOSED | The native part of every native limit operation (an assignment, so a Linux adoption; a change; letting go of the job of an exited process; the retry of a residue) runs on `srm-native-limit`, a single-thread executor `MonitoringRuntime` owns (a queue of 2 that refuses when full, never full: the limit service hands over one at a time). `LimitService` keeps all its state on `srm-limiter`: the checks before the system is asked (the hold, the ledger's and the journal's health, the barrier) and what the answer means stay there, and the answer is handed back to it. One operation per process at a time, and one at a time in all, in the order handed over; what else comes for that process meanwhile (a request, its exit seen by a listing or by its emulated limit, an earlier session's cap found late) waits for it in order, and its requests still count against the 32 that may wait. Requests for other processes, emulated steps and OOM Guard go on. While an operation is in flight the Limits page shows what was held before it, and the emulated limit treats the process as possibly capped. `LinuxScopeLimiter.hold` reads without the lock (one immutable view per hold, replaced only under the lock). At the shutdown an operation not yet handed over is never asked and its request is answered as refused; the one in flight is waited for at most 1.5 s before the native limits are cleared, then interrupted; one that ends after the limiter has stopped leaves its record in the ledger for the release and the next start. Tests: `LimitServiceNativeWorkTest`, `LimitServiceNativeOnWorkerTest` (every case of `LimitServiceNativeTest` again with the native part handed over), `MonitoringRuntimeNativeLimitsTest` (`nativeWorkRunsOnItsOwnThreadAndTheLimiterStaysFree`, `aShutdownDuringANativeStepEndsInItsBoundAndKeepsTheRecord`), `LinuxScopeLimiterTest.holdIsReadWithoutTheLockWhileAnAdoptionWaitsForTheManager`. Review findings, addressed: **S1** OOM Guard's watch no longer drops while an operation is in flight and comes back after it, which armed it again (a second warning and action without a new crossing): it is computed from what was held before the operation, never from the limiter's live view mid-operation, and where the operation may set a native memory limit OOM Guard pauses it (nothing crosses, clears or is acted on; it keeps what it holds and goes on as it stood); `aNativeStepThatSetsNoMemoryNeitherDropsNorArmsTheWatchAgain`, `whileInFlightTheRowShowsWhatWasInForceAndOomGuardActsOnNoMemoryThatMayBeLanding`, `OomGuardPolicyTest.aPausedWatchNeitherCrossesNorActsAndGoesOnAsItStood`. **S2** the barrier of plan D15 (OOM Guard's hold, an incomplete release) is checked again once a step that may set a CPU cap has ended: a cap set meanwhile is withdrawn to what was held before, through the worker, and the request answered with the refusal; a refused assignment then starts and offers nothing. Chosen over keeping OOM Guard off a process in flight, which would leave it unable to act for as long as an operation waits (up to D1's authentication timeout); `aCapThatLandsWhileOomGuardTookTheProcessIsWithdrawnAndRefused`, `aRefusedAssignmentWhileOomGuardTookTheProcessStartsNothing`. **C1** a first assignment checks the journal's health again on the worker, right before its record and the ask (a thread-safe read; no retry there, as a retry writes); `aJournalThatFailsWhileTheStepWaitsKeepsItFromBeingAsked`. **C2** a failure while an operation is concluded is logged, its request is still answered, what waited for it still runs and the next operation is still handed over; `aFailureWhileConcludingLosesNoWaitingWork`. **Residual:** the limiter's lock still covers each Linux operation's bus calls, so one adoption still delays the next native operation, a clearing by recovery and the release, each within its own bounds. Between the journal check on the worker and the ask (the ledger's durable record in between) the journal can still fail unseen; a change is not checked against the journal, as before D6. A cap withdrawn under S2 was in force for the time between its landing and its withdrawal, on a process OOM Guard held suspended |
| D13 | CLOSED | The release's worker waits for the lock only for what is left until the release's deadline, not the whole bound again, and lets go only of a hold whose clearing it ran: a hold it did not reach because the deadline had passed keeps its hold and its descriptor, so a later release can still clear the scope. `LinuxScopeLimiterTest.aReleaseThatTakesTheLockPastItsDeadlineLeavesTheHoldForTheNextRelease`; `pastTheBoundNothingMoreIsSentAndUnreachedHoldsAreSaid` now also checks that the hold not reached is kept and that the next release clears it. `releaseAllGivesUpWithinItsBoundWhileAnAdoptionWaitsForTheManager` still waits for the worker to end: on the test's virtual clock the deadline does not pass while the worker waits, so a worker that took the lock after the adoption would still clear the hold and the test's later release would find nothing. Review finding **C3**, addressed: a hold whose clearing sent nothing (the deadline passed after the scope was read and before the first send, or the scope could not be read) is kept with its descriptor too, as one not reached; `pastTheBoundNothingMoreIsSentAndUnreachedHoldsAreSaid` now expects both holds kept and cleared by the next release, `aReleaseThatCannotReadTheScopeSendsNothingAndKeepsTheHold`. **Residual (C4, documented):** after a completed shutdown, the exit hook releases the holds the shutdown did not reach, but the ledger's thread (`srm-journal`) has ended, so removing their records fails the ledger with an ERROR line ("The native-limit ledger failed: the ledger's thread takes no more work") and the file keeps naming them. That errs on the safe side: the next start lists them, and recovery finds their limits cleared or gone (a scope cleared, a job whose name went with its handle). The same happens on Windows after a release that timed out |

Still open before K16 can be lifted:

- **D6 and D13**: closed above, reviewed, and run on Windows and on Linux.
- **D8's wording**, above.
- **D3, D4, D5, D7, D10** to be fixed or accepted again, each with a written reason.
- **What the product does not have yet:** an eligibility policy for desktop processes (today only `none()` and the
  test harness's), and a consent in the window for the one-way move into a scope (`NativeConsent` is `none()` in the
  product, which refuses every adoption).
- The manual verification of milestone 12 on a real Linux desktop (a Hyper-V Ubuntu 24.04.5 desktop is available for
  it since 2026-11-26), and the product owner's decision.
