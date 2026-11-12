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
- A process in a scope named as Prism names them, `prism-srm-limit-<pid>-<start millis>.scope`: it can be managed, and any limit is refused with the reason (the scope-membership rule). The scope is left untouched.
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
| F1 | `scopeBarrier` returned "no barrier" when the cgroup mount could not be determined (two `cgroup2` mounts, an unreadable `mountinfo`) | It contradicted ADR-0016 decision 8, "a failed read refuses". A member of a Prism scope could get the emulated duty cycle. Reachable in the product, though it needs a Prism scope on the machine | The rule now needs only `/proc/<pid>/cgroup`. No unified line with no mount means no such hierarchy, so no barrier. A malformed file refuses. `theScopeMembershipRuleHoldsWithoutTheCgroupMount`, seen failing before the fix | Was BLOCKING by definition, now fixed |
| F2 | The rule compared only the last path segment, and a test pinned that | A cgroup below a Prism scope passed, although the scope's limits still apply to it. ADR-0016 says "inside". Same reach as F1 | `ScopeNames.enclosingPrismScope` matches any segment, in the barrier and in eligibility. `theScopeMembershipRuleCoversACgroupBelowAPrismScope`, seen failing before the fix; `aProcessBelowAPrismScopeIsNotEligible` | Was BLOCKING by definition, now fixed |
| F5 | `ResourceLimiter`, `RecoveredLimit` and `NativeRecovery` comments said recovery never clears | `NativeRecovery.settle` does clear, with a limiter that admits | Comments corrected | DOCUMENTATION, closed |

## Open

| # | Risk | What it can affect | Why it cannot do so today | Class |
|---|---|---|---|---|
| D1 | `sd_bus_open_user` seals the `Hello` call with libsystemd's default 25 s timeout before Prism sets its 2 s bound | A broker that never answers `Hello` holds each open that long, and so the limiter lock during an adoption. Results stay fail-closed | The product opens no bus | DEFERRED. Fix: `sd_bus_new` with the timeout set before `sd_bus_start` |
| D2 | The target's cgroup is checked before `pidfd_open`, not after. systemd's user manager itself falls back to PID 1's `AttachProcessesToUnit` by PID when it lacks the privilege | With a privileged actor moving the target out of `user@UID.service` and a PID reuse in that window, systemd could move the newcomer by number. Whether PID 1 accepts that on a given system was not determined | No adoption in the product | DEFERRED. Fix: read the cgroup again after the open |
| D3 | sd-bus can make a standard refusal name locally from an errno after a send, which Prism reads as "nothing was done" | A limit could be in force while reported refused. Whether this occurs in practice was not determined; `sd_bus_call` cannot tell the origin | The product opens no bus | DEFERRED |
| D4 | A hold whose process may no longer be signalled (EPERM) cannot be cleared in session: `change` is uncertain, `forget` says it still runs. Shutdown does clear it | The limit stays on Prism's own scope until exit or shutdown | No holds in the product | DEFERRED |
| D5 | After a start refusal or a unit gone, a process that may not be signalled is reported `NATIVE_ERROR` with the emulated fallback allowed; before the start it is `ACCESS_DENIED`, no fallback | An inconsistent report; the emulated limiter would get EPERM | The native branch is unreachable | DEFERRED |
| D6 | Adoption holds the limiter lock on `srm-limiter` for seconds, and that thread also runs OOM Guard and emulated steps | Limit actions and OOM Guard evaluations queue behind an adoption | No adoption in the product | DEFERRED. Must be solved before enabling |
| D7 | `LinuxScopeLimiter` is about 1600 lines | Maintenance and review cost, no behaviour | n/a | DEFERRED |
| D8 | Group IDs are not compared. Zombies count as alive, and since they are absent from `cgroup.procs` they can be described as "moved". The wording does not say that memory charged before adoption stays with the old cgroup, or that the cpu controller does not limit real-time processes. `LimitMessages` describes Windows commit semantics for memory, wrong for a scope. `NativeRecovery`'s messages say this version does not clear | A set-group-ID program is admitted; misleading text | No adoption and no ledger in the product | DEFERRED |
| D9 | Between the proof and the send, a target can be moved out of its scope | The limit lands on Prism's own invocation, never another unit (`NoUnitForInvocationID` seen on real systemd); the re-check reports a detected move as uncertain. An A-B-A move, or one after the re-check, goes unseen until the next operation | systemd has no conditional write, so the window cannot be closed | MITIGATED |
| D10 | `CgroupV2.validPath` rejects `..`, so a process outside Prism's cgroup namespace root is refused an emulated limit, with "could not be read" | Availability inside a container or namespace; fails closed | Consistent with "a failed read refuses" | DEFERRED. Reachable, harmless, kept on purpose |
| D11 | Recovery's classification does not compare the record's cgroup with the live one, and `clearOwned` reads back the record's path; the unit is not bound to the identity | A crafted record could yield a false `CLEARED` | The product reads no ledger and clears nothing | DEFERRED. Must be fixed before enabling |
| D12 | "K16" appears in the text of the activity-log line from `limitations()` | An internal label shown to the user at start-up | Harmless | DOCUMENTATION. For the wording pass of M13 |
| D13 | A release that timed out keeps waiting for the lock for up to its bound. If an adoption in flight ends in that moment, the worker takes the lock past its deadline and lets go of the hold without clearing it, so a later release cannot clear it either | The limit stays on Prism's own scope until the processes exit. It is reported correctly, as not reached. The window showed itself on hosted macOS at 70b9b6d as a test failure, and was reproduced by delaying the worker 50 ms; the test now waits for the worker | No holds in the product, and shutdown does not retry | DEFERRED |
| D14 | `LimitService` builds its native request with the two-argument `LimitSpec`, which means a hard memory limit, and no main code selects `THROTTLE`. The limiter's capability report says `NATIVE_SOFT` for memory. ADR-0016 decision 4 makes `memory.high` the default | Once enabled, a memory limit through the limit service would ask for `memory.max` and be refused without its separate consent | The product admits no process | DEFERRED. Must be settled before enabling |
| D15 | Tests that depend on timing. Hosted runners exposed three races in old tests within five runs (D13's test, and two described in the [acceptance matrix](m12-acceptance-matrix.md)), each passing elsewhere | A false red build, and time lost to it. All three were test faults; none changed the product | Each known race is fixed without weakening an assertion. Others may remain | MITIGATED |
| — | Under a stalled guest, a 2 s `GetUnit` timeout turns an adoption into `UNCERTAIN` | Availability, never correctness | Seen on the old VirtualBox VM only | MITIGATED (fail-closed) |

## Before K16 can be lifted

1. D1, D2, D6, D8, D11 and D14 closed; D3, D4, D5, D7, D10 and D13 fixed or accepted again.
2. The manual verification listed under milestone 12 in [MILESTONES.md](../MILESTONES.md), on a real Linux desktop.
3. A separate, explicit product-owner decision.
