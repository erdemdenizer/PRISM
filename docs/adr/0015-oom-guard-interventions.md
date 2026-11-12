# 0015. OOM Guard interventions

- Status: Accepted with milestone 11 (2026-10-13; see docs/evidence/m11-acceptance-decision.md). The
  platform table of §7 is unchanged: Linux and macOS stay not validated.
- Date: 2026-10-12
- Amends: [0004](0004-emulated-limiting-and-suspension-recovery.md) §3 ("the limiter runs on a
  scheduled executor dedicated to limiting"): `srm-limiter` also runs OOM Guard's interventions.

## Context

OOM Guard (PRODUCT_SPEC §9, LIM-9) watches memory pressure and may lower the priority of,
suspend or terminate Managed Processes, and only those, as far as the user's switches allow.
Every one of those actions is destructive or has a side effect the user must be able to trust
Prism to undo. The plan approved on 2026-10-12 (revision 3, decisions O1–O17b) settles what
the specification leaves open. This record keeps the decisions that are expensive to reverse.

## Decision

### 1. Where it runs

- The state machine (`OomStateMachine`) is pure: its inputs are the memory sample, an
  injected monotonic clock and the current settings; it holds no settings of its own. It
  runs on `srm-system-monitor` for every sample (OOM-13), in microseconds.
- Interventions (`OomGuard`) run on `srm-limiter`, the thread that already owns the managed
  registry. The guard has no reference back to `LimitService`: the service pushes managed
  changes, watched memory limits and exits to it on the same thread. The registry needs no
  lock and no copy.
- Cost accepted: an emergency action may delay a CPU limit step by at most one suspension
  (about 0.2 s on Windows, per thread) and a journal declaration waits at most 2 s, the
  bounds ADR-0011 accepted for limits; an emergency action happens at most once per settle
  interval (5 s).
- Exactly one producer publishes the OOM view: the limiter thread. The monitor thread hands
  over an immutable `Observation` and never publishes.

### 2. The gate

Every new intervention (lowering, suspending, terminating; pressure or memory policy) passes
one gate immediately before its native call, checked in this order, and the first failure
stops it with a reason the page shows as "not now: …":

0. the trigger fits the intent: a pressure action only as part of a memory sample, a
   memory-policy action only as part of a process listing; a settings change, a managed
   change, a journal failure or the end of monitoring only releases;
   0b. the observation was made under the settings version the gate reads (`VersionedSettings`):
   an observation from before a settings change never authorizes an action under the new
   switches;
1. the guard is not stopped (shutdown, exit hook);
2. monitoring is on;
3. the last observation is fresh (no older than the gap, 5 s);
4. it is not HOLD (no reading), GAP (a gap in the samples, such as after sleep), OFF, and
   system monitoring has not ended;
5. the reading itself meets the action's level now, not only the state machine's state
   (hysteresis keeps EMERGENCY for 10 s after relief; settle is 5 s), and the settle interval
   has passed; for the memory policy, the process is listed above its limit now;
6. the switch for the action is on in that same settings snapshot, the process's effective
   action is that action, and the platform's memory signal is validated;
7. the identity is in the last listing and is not protected (`ProtectionRules` against
   Prism's own PID and against fresh `protectionFacts`);
8. the incident ledger allows it.

At most one native intervention per evaluation. The controller's own identity check stays the
last defence.

**The start of the action (external code audit, 2026-10-12, P0).** The gate reads the settings
once; the user may publish new settings before the native call. The native call therefore
begins only under the action lock (§6), and only if both hold there:
- OOM Guard is not stopped;
- the settings version in force is still the one the gate authorized under.

The single writer publishes the settings under the same lock (`OomGuard.publishSettings`, called
by `MonitoringRuntime.publishOomSettings`). The two are therefore totally ordered:
- **Publication first.** The start sees the new version and refuses: "not now: settings changed;
  waiting for a sample". The next sample is judged under the new settings.
- **Start first.** The action began before the publication. It may still be in flight when the
  publication returns. It is never a new action under the replaced settings.

Any change counts, not only the switch of that action: the switches, monitoring and the
thresholds. Publishing equal settings changes no version and stops nothing.

No lock is held during a native call, and publication never waits for one. Per-process changes
(OOM management, the watched limit) reach OOM Guard on the limiter thread itself, so they
cannot interleave with an action.

Tests: `OomGuardRevocationTest`.
- 8 of its tests fail on the code before the fix, and all 11 pass after it.
- Mutation R1 removes the version check at the start; it is killed.
- The lock on publication is needed. Without it, the start could read the old version, the
  publication could complete, and only then could the action count itself in flight and begin.
  `aPublicationCannotCompleteBetweenTheVersionCheckAndTheInFlightCount` forces that interleaving.
  It fails on that mutant (R2) and passes on this code (`docs/evidence/m11-mutation-evidence.md`).

### 3. What each action means

- **Terminate is Force Kill** (O1), sent at most once per process identity per session (O15),
  whatever its result, and never retried. It needs the global switch and the per-process
  choice (OOM-9).
- **Lower priority is LOW** (O2). It is offered only where Prism can raise it back without
  privilege (`Restorability.RESTORABLE`: Windows). Where it cannot (Linux, macOS: the nice
  value), automatic priority reduction is `UNAVAILABLE` and the action falls back to Notify
  only (O16 (a)): Prism never creates a side effect it cannot undo. At NORMAL a lowered
  priority is restored to the level it had, one at a time after the resumes; a priority the
  user changed meanwhile is left as is; a restore that fails is an ERROR in the log and on
  the page, never tried twice and never reported as done.
- **OOM-8 is literal** (O8): an action whose switch is off falls back to Notify only, never to
  another automatic action.
- The CRITICAL lowering and the EMERGENCY action are separate rights (incident ledger:
  `loweredAtCritical`, `emergencyActed`, and the session-wide `terminateSent`). A settings
  change never clears the ledger: releasing a hold gives no second emergency action in the
  same incident.

### 4. Suspension: logical claims on one physical hold

A process may be held for two independent reasons: system pressure (`PRESSURE`) and its own
memory limit (`RESIDENT_POLICY`). The suspension coordinator keeps one `OOM_GUARD` reason and
one journal declaration per process, and stays the only owner of native suspensions (CTL-9).
The guard keeps the claims; the physical reason is held exactly while at least one claim
holds, released only when the last one goes. The declaration is made, durably, before the
first claim suspends (ADR-0011); every suspend that ends with no hold withdraws it.
A suspension or a release that the platform leaves incomplete is retried by the guard, one
release per evaluation, independent of the claims.

The coordinator now writes the thread memento for an `OOM_GUARD` hold on Windows, as for the
user's, so that a hold left by a crash can be resumed from the next session's list (LIFE-6).

### 5. When the journal fails (O14)

New automatic suspensions are refused. Every claim is released and the physical release is
attempted at once (as ADR-0011 does for limits); the declaration is dropped in memory and the
file stays as evidence. A release the platform leaves incomplete keeps its ownership: the
hold is reported "release incomplete" and retried, and the clean-up is not reported as done.
Lowering and termination are not affected.

### 6. Lifecycle

- A controlled shutdown stops the guard with the other limiter work ("refuse new suspensions
  and limits"), then, on `srm-shutdown` and within the shutdown's bound, restores the
  priorities the guard lowered (O7), and releases the `OOM_GUARD` reasons with the other
  automatic reasons (existing step). The restore runs on `srm-shutdown` without a queue,
  after the limiter executor has terminated, like the other "what Prism undoes itself" steps.
- The exit hook stops the guard first, then releases the automatic reasons; it does not
  restore priorities (its 1.5 s are for suspensions and native limits).
- If Prism is killed, a lowered priority stays lowered; an `OOM_GUARD` hold is listed by the
  next start and resumed only by the user.
- **Stop against an intervention (external review, 2026-10-12).** Every native intervention
  (lowering, suspension with its declaration, termination) begins under one lock. It begins only
  while OOM Guard is not stopped, and `stop()` sets the flag under the same lock without waiting.
  - **The guarantee:** once `stop()` has returned, no intervention begins. Any intervention is
    either refused (the process shows "not now: stopped") or began before `stop()` returned.
  - **What remains:** at most one intervention that began before `stop()` can still be in
    flight, because the limiter thread runs one at a time.
    - The shutdown hook waits for it at most `ACTION_BOUND` (250 ms). If it has not ended, the
      hook logs that and exits without waiting further.
    - The controlled shutdown waits for the limiter thread within its own bound (1 s) before it
      restores any priority.
    - A native call that hangs inside the operating system is not interrupted. Prism does not
      claim it was stopped; it says it was still in flight.
  - **Not an intervention:** raising back a priority Prism lowered is an undo, not an
    intervention. The shutdown's bounded restore still does it after `stop()`.
  - **Tests:** `OomGuardLifecycleTest` stops OOM Guard exactly between the gate's last check and
    the native call, and holds a native call open while it stops.
- When system monitoring ends for the session, the holds stay (O5) until the user releases
  them, turns a switch off, removes OOM management or unmanages the process; a later journal
  failure still releases them (O14).

### 7. Platform gate (O17, O17b)

`HostPlatform` reports per system whether the memory signal is validated. Validated means two
things are documented and checked: the system's available-memory signal, and the per-process
resident memory. The table fails closed.
- **Windows** is validated.
- **Linux** is not validated (external review, 2026-10-12). `MemAvailable` (proc(5)) and the
  RSS are documented.
  - *Historical (until 2026-10-12):* their behavior had not been checked on Linux.
  - *Since 2026-10-13:* the two validation tests have passed in CI on Linux; see the CI evidence
    below. Items 3 and 4 of the list below are still open, so the table is unchanged.
- **macOS** is not validated: OSHI reads `free + inactive` pages, which Apple does not define as
  available.
- **Any other system** is not validated.

On a system that is not validated:
- every automatic switch is unavailable, with that reason;
- the memory-limit policy is not offered (`setMemoryLimit` answers UNSUPPORTED);
- OOM Guard monitors, warns and logs only.

A change of this table needs new evidence and a note here.

**Evidence on Windows (milestone 11, 2026-10-12, development machine, Windows 11 build 26200).**
- `OomSignalValidationTest`: with M = 512 MiB, the signal fell by 1.06 M and recovered by 1.10 M.
- `OomResidentValidationTest`: the demo was listed at 309 MiB resident for 256 MiB held.
- Resident memory against the system counter: checked in milestone 5.

**CI evidence for Linux and macOS (2026-10-13, commit `3d6e4eb`; the table is unchanged).** Both
tests ran in CI on all three systems:
- **Linux:** they met their thresholds in two runs. In the later run the signal fell by 1.01 M and
  recovered by 0.94 M; the resident memory was 313 MiB for 256 MiB held.
- **macOS:** the signal fell by only 0.23–0.28 M, which confirms why macOS is not validated.

Items 3 and 4 below are still open. Runs, skips and lines:
[m11-ci-cross-platform-3d6e4eb.md](../evidence/m11-ci-cross-platform-3d6e4eb.md).

**What enabling Linux interventions requires.** All of the following, before the one-line change
of `HostPlatform.oomSignalStatus` and its test:
1. `OomSignalValidationTest` and `OomResidentValidationTest` passed on Linux. They count only when
   all of these hold:
   - they ran in the repository's CI job `verify (ubuntu-latest)` on the exact commit that makes
     the change, or on its parent with no change to `platform` or the tests;
   - the surefire reports show them run, not skipped;
   - their `oom-signal:` and `oom-resident:` lines meet the thresholds of the tests (a fall and a
     recovery of at least half of M; resident at or above M in two listings; the record gone
     after the exit).

   Having the tests in the repository is not evidence that they passed.
2. The raw reports and those output lines are kept as an evidence file in `docs/evidence/`, with
   the commit and the run named, as for milestone 10.
3. A note on containers is added: either the evidence ran outside a memory-limited cgroup and
   the limit below stays a known limit, or the gate reads Prism's own cgroup v2 `memory.max`.
4. The external reviewer and the product owner accept the evidence and this ADR note.

Until then the Linux tests remain in the build. They are evidence once they pass, never a
switch.

Known limits of the gate:
- **Linux in a container.** `MemAvailable` is host-wide. A cgroup v2 `memory.max` of Prism's
  own group is not read, so OOM Guard does not see a container's limit. This is one reason
  Linux stays not validated (item 3 above).
- **A reading of zero.** An available reading of exactly zero is taken as a failed read on
  every system: OSHI's macOS fallback gives zero on failure. At true exhaustion that holds the
  state, which is safe, and starts no new action.
- **A HOLD that lasts.** While readings stay missing, the incident does not end. A process
  suspended for pressure stays suspended until a reading clears the state or the user releases
  it, turns the switch off, or ends its OOM management. This is deliberate: no reading is no
  evidence that the pressure has passed.

### 8. The memory policy (LIM-9)

- Where no native memory limit is available, a memory limit is a watched resident limit, never
  called a cap (CAP-3), with mechanism `RESIDENT_POLICY`. On Windows, where the native commit
  limit could not be applied to a process, the policy is offered and runs only once the user
  accepts it (O9; ADR-0012 §8 "offered, never assumed").
- It acts only on a process with OOM management on, and only while monitoring is on (O4), with
  the process's emergency action and the same switches.
- One action per crossing of the limit. A policy hold ends when the resident memory stays at
  or below `limit − min(max(5 % of the limit, 4 MiB), limit / 2)` for two listings, or by the
  user (O3). A suspended process's resident memory rarely falls, which the limit dialog says
  before the limit is set.

## Alternatives considered

- A thread of its own (`srm-oom`): a second owner of the managed registry, with a lock or a
  copy, for no gain (the native suspension is serialized in the coordinator anyway).
- Acting through `ProcessControlService`: it is built for the user's requests (one result
  listener, an in-flight lock shared with the user, USER suspensions only).
- One "first cause wins" hold: releasing one justification could resume a process the other
  still needs suspended.
- Keeping holds on a journal failure (O14 (b)): deviates from ADR-0011 and leaves a suspension
  that the next session's list would not show correctly.
- Lowering with consent where it cannot be restored (O16 (b)), or a probe of restorability
  (O16 (c)): a permanent side effect, or a larger platform change.
- Terminating with End Task first: Windows refuses End Task for processes without windows, and
  a request frees no memory in an emergency; a two-step escalation hides a second destructive
  step.
- Restoring priorities on the limiter thread during "stop limiting": depends on that step's
  1 s bound and on its queue; the shutdown thread runs it without a queue.

## Consequences

- With the default settings nothing is ever lowered, suspended or terminated (OOM-5); the
  file never turns termination on (SET-2, ADR-0014).
- No intervention starts on stale, missing or pre-change telemetry, after a gap, on an
  unvalidated system, or after shutdown has begun.
- The cost is one extra listener per sample and one task per sample on `srm-limiter`.
- Verified by `OomStateMachineTest`, `OomGuard*Test` (the deterministic matrix of the plan,
  A15.9 M1–M11), `EffectiveActionTest`, `MonitoringRuntimeOomTest` and the integration tests on
  the demo workload; linux and macOS behaviour is verified in CI only.
