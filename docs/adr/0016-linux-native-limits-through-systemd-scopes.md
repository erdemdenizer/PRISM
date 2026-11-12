# 0016. Linux native limits through systemd transient scopes

- Status: **Accepted** (2026-11-07, by the product owner, with milestone 12 and with K16 active:
  [evidence/m12-acceptance-decision.md](../evidence/m12-acceptance-decision.md)). Replaces
  [0003](0003-linux-native-limits-with-cgroups-v2.md). This does not enable Linux native limits for real user processes.
- Date: 2026-10-14 (planning Rev 2; amended in Rev 2.1 after the reviewer's conditional architecture GO; amended
  2026-10-15 in plan Rev 2.2 for review follow-up O9: decision 8 separates identity blocking, scope-membership
  blocking and ownership-provenance retention; implementation notes and CI-3 added 2026-10-15 with stages S3B to S6;
  later verification and the closure review recorded up to 2026-11-07)

## Context

ADR-0003 proposed that Prism limits processes by creating child cgroups inside a subtree delegated to Prism and
writing the target's PID into `cgroup.procs`. Planning for milestone 12 found, from the kernel and systemd
documentation (the planning evidence, doc-facts, of plan Rev 2.2):

- The kernel permits the move when the writer may write the destination's and the common ancestor's `cgroup.procs`
  (K1), and the user manager's subtree is owned by the user (K10). The permission is not the problem.
- systemd's delegation rules say a cgroup has a single writer and that cgroups systemd created are its private
  property (S1). Pulling a process out of an `app-*.scope` the user manager created violates that rule, and the
  source scope is garbage-collected once it is empty (S5), so "move it back" cannot be promised.
- Stopping a scope kills every process in it (S7). A default `OOMPolicy=stop` would stop a scope after one kernel
  OOM kill inside it (S6).
- systemd documents exactly one way to register already-running foreign processes as a unit: a transient scope
  created over D-Bus (S3), since systemd 255 by file descriptor (`PIDFDs`, S15).

## Decision

1. On Linux, Prism limits a process by asking the **systemd user manager** to create a transient scope
   `prism-srm-limit-<pid>-<startMillis>.scope` that contains the process, identified by a **pidfd** Prism opened
   and bound to the process identity (PID and start time). Prism writes no cgroup file. Where the manager does not
   accept `PIDFDs`, native limits are `UNAVAILABLE`; there is no fallback to PID-based adoption in Prism's own
   messages. (systemd's user manager itself asks the system manager to move a process by PID number when it lacks the
   privilege to move it, as from outside `user@UID.service`; Prism admits only processes inside `user@UID.service`,
   checked before the pidfd is opened, and does not check that again after it: a recorded design risk.)
   Capability discovery is read-only: files under `/proc` and `/sys/fs/cgroup`, symbol lookup in `libsystemd.so.0`,
   property reads and `GetUnit`/`GetUnitByPID`. It never calls `StartTransientUnit` or any other state-changing method,
   and never hands the pidfd of Prism or of a user process to the manager. systemd 255 is a necessary condition only;
   acceptance of `PIDFDs` is established by an authorized integration experiment on a disposable, test-owned process,
   and where it is not established native limits are `UNAVAILABLE`.
2. The scope is created without limits and with `OOMPolicy=continue`, `ManagedOOMPreference=omit`,
   `CollectMode=inactive-or-failed`, in `app.slice`. Limits are then set per kind with
   `org.freedesktop.systemd1.Unit.SetProperties` on the unit object addressed by the scope's **invocation id**, never
   by its name (`CPUQuotaPerSecUSec`, `MemoryHigh`, `MemoryMax`) and verified by reading `cpu.max`, `memory.high`, `memory.max`.
   A limit is reported as applied only after the read-back matches (LIM-4).
3. The CPU limit is Prism's whole-machine percentage P converted with the logical processor count N:
   `CPUQuotaPerSecUSec = P × N × 10 000`, which systemd writes as `cpu.max = "P·N·1000 100000"`; 100 % is set
   explicitly as N × 1 000 000 µs/s (the whole machine), not as "no quota".
4. The default memory limit is `memory.high` (`NATIVE_SOFT`, throttling, no OOM kill, may be breached). `memory.max`
   (`NATIVE_HARD`) is offered only after separate, explicit consent that the kernel may terminate the process or
   one of its children, and only where `OOMPolicy=continue` can be set (systemd ≥ 253). Both count memory charged
   after the move only (K3); `memory.swap.max` is not touched.
5. Removing a limit sets the property to `infinity`; the process stays in the scope until it exits. Prism never
   stops, kills or abandons a scope and never writes `cgroup.kill` or `memory.oom.group`.
6. Adoption is a one-way operation and is subject to explicit user consent that states: the move, the absence of a
   return to the original unit, the inheritance by child processes, the possibly changed ownership as seen by the
   desktop environment or service manager, and that an external stop of the scope terminates its processes.
7. Until desktop application ownership and lifecycle have been validated on a real desktop Linux, native limits on
   Linux are `UNAVAILABLE` for every process in the product ("not validated for desktop applications"). There is no
   experimental setting. In milestone 12 native operations run only on disposable processes the test harness creates
   and names to the limiter explicitly; per-process adoption consent and separate hard-memory consent are required by
   the limiter's contract. Extending native limits to ordinary desktop applications needs new evidence and a new
   product-owner approval (K16).
8. Recovery after a crash is read-only classification first (`recover`, unchanged contract) and a separate explicit
   clearing step (`clearOwned`) that runs only when ownership is proven: Prism's ledger record with the unit name
   and the unit's `InvocationID`, the live unit's `InvocationID` equal to it, and the recorded identity (or only its
   surviving children) in the scope's `cgroup.procs`. A scope that merely carries Prism's name prefix, or one whose
   record is absent, incomplete or mismatched, is never changed automatically; it is listed and limits on its
   members are blocked. There is no user action that overrides the ownership proof (K17: list and block only).
   Windows recovery (ADR-0012) is unchanged. Three rules are kept apart (plan Rev 2.2 §4.5):
   - **Identity blocking** (`blocksAnotherLimiter`) refuses a second limiter only for a live process whose identity
     (PID and start time) equals the record's. An exited identity, and a process that later reuses the PID, are never
     blocked. Accordingly `OWNED_CHILDREN_ONLY` (the recorded process has exited, processes it started remain) warns
     but does not block.
   - **Scope-membership blocking** refuses native and emulated limits for every process whose cgroup is inside a
     scope named as Prism names them, `prism-srm-limit-<pid>-<start millis>.scope`, proven or not, independently of any
     record; a failed read refuses. This is what keeps
     surviving descendants from overlapping limits.
   - **Provenance retention**: the ledger record is the only source of the ownership proof. While a proven scope
     exists and still holds processes, its record is not discarded — not when the recorded process exits — until the
     limits have been cleared and verified by read-back, or the scope has ceased to exist (no unit, or no members). A
     discarded record would turn a proven scope into an unproven one that can never be cleared safely.
   In the S1–S2 stage, where Prism created no scope, `OWNED_CHILDREN_ONLY` only warned and its record was not
   carried. From S3B on, a record with provenance is carried while its verdict is `OWNED_LIMITED`,
   `OWNED_CHILDREN_ONLY` or `CLEAR_FAILED`, and membership blocking is in force.
9. Ledger format 2 adds the unit name, invocation id, cgroup path and memory boundary; format 1 stays readable.

## Alternatives considered

- ADR-0003's delegated subtree with direct cgroup writes: rejected for the reasons in Context.
- PID-based adoption with post-verification: rejected; moving an unintended process is itself an unauthorized
  intervention.
- A pure-Java D-Bus client or a `busctl` subprocess: cannot pass file descriptors; rejected for adoption.
- A third-party D-Bus library (dbus-java): a new dependency; kept as the fallback if the sd-bus mapping fails its
  smoke criteria (K2).

## Consequences

- Native limits on Linux need systemd ≥ 255 for adoption and ≥ 253 for `memory.max`; a session without a reachable
  user manager, a process outside `user@UID.service`, or a controller not delegated gets `UNAVAILABLE` with the reason.
- The process is moved out of the unit its launcher created; the desktop environment may see it differently. This is
  unvalidated and is stated to the user; the feature is off by default until validated.
- After a crash the limits stay until the next start clears them where ownership is proven, or until the process exits.
- Verification so far: CI-0 (2026-10-14, GitHub-hosted ubuntu-24.04, read-only) found cgroup v2, systemd 255.4, a
  lingering user manager with `cpu memory pids` delegated and owned by the user, a reachable user bus, the Manager
  method signatures, libsystemd with every needed sd-bus symbol, `DefaultOOMPolicy=stop`, systemd-oomd inactive, and
  the `cpu` controller delegated but not yet enabled in `app.slice` (evidence/ci0/CI0_RESULTS.md). CI-0 proves neither
  adoption nor limit enforcement nor desktop behaviour. Adoption by pidfd (CI-2) and the Linux integration suite
  (CI-3) ran later, as recorded below; desktop behaviour is observed, not validated.
- CI-1 (2026-10-14, run 38542933245, PASS): the user manager started new, test-owned processes in transient scopes
  of their own (`systemd-run --user --scope`); `CPUQuota=20%` became `CPUQuotaPerSecUSec=200ms` and
  `cpu.max = 20000 100000`, held the busy loop at 0.200 of a core (1.000 without the quota), enabled `cpu` in
  `app.slice` only while that scope lived, and every scope was removed with its process. Transient scopes default to
  `OOMPolicy=stop`, which confirms decision 2. The workload's starting cgroup was a root-owned service cgroup, so the
  move into the user scope was mediated by systemd (probably the system manager's `AttachProcessesToUnit`; not
  verified): the restriction to processes under `user@UID.service` is therefore a policy, not a kernel limit.
  CI-1 proves neither adoption of an existing process nor `PIDFDs` acceptance, recovery, memory-limit safety or
  desktop behaviour.
- CI-2 (2026-10-14, run 38575902292, PASS; attempt 1, run 38565221326, BLOCKED because busctl cannot pass "h"
  arguments, so no call was sent): an already-running, test-owned process was moved into a new transient scope by a
  single `StartTransientUnit` call whose only process selector was `PIDFDs` (one descriptor sent as SCM_RIGHTS by a
  minimal standard-library research client; the bus monitor showed `PIDFDs` with a UNIX_FD and no `PIDs`). The identity
  was revalidated after the pidfd was opened; afterwards the process was in the destination scope alone, with the same
  start time; `GetUnitByPIDFD` named the destination scope and its InvocationID; the emptied source scope disappeared at
  once (decision 5: no return to the original unit), and the destination scope disappeared when the process ended.
  CI-2 does not prove protection against PID reuse (that needs the deterministic race tests), adoption from outside the
  user manager, recovery, memory-limit safety or desktop behaviour.

- Stage S3A (2026-10-15, authorized): a read-only native foundation (library and symbol checks, user-manager and unit
  property reads, strict reply-signature checks, capability correlation). It calls no state-changing manager method,
  adopts no process and leaves Linux native limits `UNAVAILABLE` in the product.

- CI-3 (2026-10-15, run 38790992106 on commit 5b52d96, job `linux-native-validation`, PASS; the first run,
  38787802162 on c5b39fb, failed and its causes were fixed, see the implementation notes): the integration suite
  `LinuxNativeLimitIntegrationTest` ran in required mode on GitHub-hosted ubuntu-24.04 (systemd 255.4, 2 CPUs,
  3 GiB swap) with 8 tests, 0 skipped, plus the read-only smoke test. On disposable processes the harness started:
  a CPU quota of 20 % of the machine (0.4 of a core on 2 CPUs) held a one-core busy loop at 0.401 cores (quota 0.400) and released it to 0.998;
  `memory.high` of 128 MiB throttled a growing allocator (130 `high` events, 133.8 MB current) without a kill;
  `memory.max` of 128 MiB with separate consent bounded resident memory, and on that runner the kernel swapped
  (about 400 MB) rather than killing (no `oom_kill`), because `memory.swap.max` is not touched (decision 4); a
  surviving child kept its scope and was cleared by `forget`; a crashed session's scope was proven and cleared only
  then; a recordless scope carrying Prism's name was never changed and blocked its members; two concurrent adoptions
  held; no `prism-srm-limit-*` unit was left after the job. CI-3 runs the test JVM outside the user manager, so it
  proves nothing about desktop applications (decision 7).

## Implementation notes (stages S3B to S6)

- **No JobRemoved subscription.** The start job is followed by polling the unit's `ActiveState` every 50 ms for at
  most 2 s, so no native code calls back into the JVM. A unit still not active after the bound is uncertain, not
  failed.
- **Unanswered calls are uncertain.** A reply carrying a D-Bus error name is a refusal (nothing was done); no reply,
  a timeout or a lost connection is `UNKNOWN`, and Prism keeps the descriptor and assumes nothing.
- **Read-back waits.** systemd answers a property write before it realizes the cgroup (it queues the
  realization), so the read-back polls for at most 1 s until the files show the request; otherwise the last reading
  is reported as a mismatch. A controller that is not enabled for the scope (absent from `cgroup.controllers`) has
  no interface files there and reads as "no limit of that kind": systemd enables `cpu` for a unit only while a CPU
  property is set. Both were found by the first CI-3 run.
- **100 % CPU** is set explicitly as N × 1 000 000 µs/s, so the read-back is an exact whole-machine rate.
- **R4 "hold re-owned after clear" is not implemented.** After `clearOwned` the process stays in an unlimited Prism
  scope, and the membership rule keeps any second limiter from it until it exits.
- **The membership rule** blocks native and emulated limits for every process in a scope named as Prism names them except
  the target this limiter holds, including survivors of this session's released scopes. The product limiter keeps
  the rule although it adopts nothing.
- **Internal audit (S7, 2026-10-15).** Independent native-API, test and architecture reviews found, and the
  following changes fix:
  - the start wait no longer takes `inactive`/`dead` without a cgroup as a failed start (systemd runs the queued
    start job at idle priority, after bus requests); only `failed` or no unit ends it, otherwise the hold is
    uncertain after the bound;
  - an error that sd-bus may have produced locally after sending (`Failed`, `IOError`, `NoMemory`, ...) is
    `UNKNOWN`, not a refusal; only systemd's own errors and the standard refusals mean nothing was done
    (caveat from the final review: sd-bus can also make a standard refusal name locally from an errno after a failed
    send, e.g. `LimitsExceeded` from ENOBUFS when its receive queue is full; not realistic on Prism's fresh private
    connection, and `sd_bus_call` gives no way to tell the two apart);
  - a value systemd accepted, or a call without an answer, that the read-back does not show is uncertain
    (adoption `UNCERTAIN`, change `UNVERIFIED`) and the next change sends every kind again;
  - an unreadable read-back after adoption is `UNCERTAIN`;
  - a scope whose ownership evidence could not be recorded never gets a limit, not through a later change either;
  - a recorded process that runs outside its scope while others remain proves nothing (`UNPROVEN_SCOPE`, list and
    block only);
  - a hold whose scope was never proven Prism's is never sent a limit write on forget or shutdown, and
    clearing sends every kind;
  - the quota uses the online processors the kernel lists in `/proc/stat`, not the JVM's share;
  - the scope-membership rule has its own status and wording (`IN_PRISM_SCOPE`), not "capped by an earlier
    session";
  - the two-argument `apply` of the scope limiter refuses (there is nowhere to record ownership);
  - systemd 255.4 answers a scope whose only descriptor names a reaped process with
    `org.freedesktop.DBus.Error.UnixProcessIdUnknown` ("Failed to set unit properties: No such process") and
    creates no unit (CI run 38799770641, T-L10). That answer is a refusal, and the limiter reports the process
    as gone.
- **CI after the audit.** Run 38799770641 on commit 2dcdd25 (the S7 fixes): `linux-native-validation` ran
  9 tests on ubuntu-24.04, 0 skipped; 8 passed (CPU 0.403 cores at a 0.400 quota, 1.000 released; `memory.high`
  82 events, no kill; `memory.max` with `OOMPolicy=continue` read on the real unit) and T-L10 failed on the
  answer above. Commit 68ff53c fixes that; its run (38802667569) was not started by GitHub: "recent account
  payments have failed or your spending limit needs to be increased" (two attempts). So 68ff53c is verified on
  Windows only (full local gate), not on Linux.
  Open, recorded for the reviewer: signal 0 counts a zombie as alive (conservative: uncertain, not released);
  memory charged before adoption stays with the old cgroup (kernel rule) and the cpu controller does not limit
  real-time processes, which the user-facing wording does not say yet.
- **Independent review closure (2026-10-16).** After the independent review of the final handoff:
  - eligibility requires the process's real, effective, saved set and file system user IDs
    (`/proc/<pid>/status`, `Uid:`) to equal Prism's own uid; an unreadable status refuses;
  - `releaseAll` is bounded end to end: the work runs on its own thread (`srm-linux-release`), the caller
    waits no longer than the bound, nothing more is sent once the bound has passed, and holds not reached are
    reported with `timedOut`; a bus call in flight at the bound ends by its own call timeout;
  - added tests: partial and unverified clearings, a pending clear left half done, a recorded process outside
    its scope, every unverified clearing outcome in recovery, a forced overlap of two adoptions, the bounded
    release with a hanging manager, and the surviving-descendant lifecycle across three sessions with the real
    scope limiter, ledger and recovery;
  - tests added to the Linux integration suite (first executed on 2026-10-16, see "Final verification"): `cpu.stat` throttling during a limit and none after it; the
    1 % and 100 % edges; `memory.high` stalls (PSI `memory.pressure`) and a slower allocation than the same
    one unlimited; a sibling in the scope survives and the scope stays active under `memory.max`; a real crash
    (the adopting JVM killed with SIGKILL) followed by proven clearing and the demo running freely again;
    exact pidfd and socket accounting after every test.
  - a second native-API review of that closure found: a dangling doc comment that javac's lint rejects (earlier
    local gates had not compiled with javac, because the IDE kept the class files current; the gate is now
    `./mvnw clean verify`); a send after the bound inside one clearing (now every limit write checks the
    bound); a race that could drop unreached holds from the result (the holds are now taken when the release
    begins); the user IDs not checked again after the pidfd is open (now checked, then signal 0, refused as
    access denied); a descriptor left open when a clearing threw (now closed in `finally`). All are fixed with
    tests. Group IDs are not compared (a set-group-ID program is not refused); recorded as open.
- **Ownership at the moment of a write (independent audit follow-up, 2026-10-16).** Root cause, reproduced with
  deterministic fake-systemd tests before any change: `change()` checked only the live pidfd and the stored
  provenance, and sent `Manager.SetUnitProperties` by unit **name**. (a) A target moved out of its scope by another
  program while its child stayed: the change set 50 % on the scope and reported APPLIED for the target. (b) The scope
  collected and a new unit made with the same name: the change set the stranger's limit. Fix:
  - every limit write (adoption, change, forget/shutdown clearing, recovery clearing) is now
    `org.freedesktop.systemd1.Unit.SetProperties` on the unit object addressed by its **invocation id**
    (`/org/freedesktop/systemd1/unit/` + the id escaped as systemd's `bus_label_escape`); systemd v255 resolves such a
    path through its invocation-id table and answers `NoUnitForInvocationID` when no unit has it
    (`manager_load_unit_from_dbus_path`, src/core/manager.c). A unit that took the name is never changed, whenever it
    took it; `SetUnitProperties` is now forbidden by the source scan;
  - `change()` proves, immediately before sending, that the unit is the recorded invocation with the recorded cgroup
    and that the target (alive by its pidfd, same identity) is in exactly that cgroup and in its `cgroup.procs`;
    otherwise nothing is sent and the hold becomes uncertain; it checks again after the sends and reports a move in
    between as unverified; adoption checks the same after its sends; clearing in the session compares the recorded
    cgroup as well as the invocation id, while recovery's `clearOwned` compares the invocation id and reads membership
    from the live unit's cgroup (one invocation has one cgroup);
  - remaining time-of-check/time-of-use limits, accepted and documented: a process can be moved between the membership
    check and a send (systemd has no conditional write); the write then lands on Prism's own invocation, never another
    unit, and the re-check reports it as uncertain/unverified; the separate reads (unit properties, `/proc`, cgroup
    files) are not one atomic snapshot; `OOMPolicy` is read by name before a hard limit (a reused name could answer
    for another unit, but the `MemoryMax` write itself would then fail on the invocation path); invocation ids are
    random 128-bit values per activation and are not reused.
  A native-API review of this fix against the v255 sources confirmed the path resolution on the user manager
  (`bus_unit_vtable` fallback, `find_unit`), that `Unit.SetProperties` runs the same handler and access checks as
  `Manager.SetUnitProperties` (`bus_unit_method_set_properties`; only the load-state validation is skipped, harmless
  for a running scope), the escaping and the error mapping. Its findings were fixed: a hold made uncertain is assigned
  again when a later change proves it again, and a failed read before a change leaves the hold as it was; an exit
  during an operation is said as an exit; a clearing whose invocation went before its sends no longer reads the files
  of a unit that took the name since (they share the cgroup path) and says the scope is gone; the membership check
  reads the files before it checks the descriptor and identity (this order holds in the change/clear proof; adoption's
  membership check and recovery's classification read the identity first, which is harmless because the check after
  the sends uses the safe order). A real-system test sends a limit to an unknown invocation id and expects
  `NoUnitForInvocationID` with nothing changed (first executed on 2026-10-16, see "Final verification").
- **Final verification (2026-10-16).** GitHub Actions still did not start jobs (billing; one probe rerun of run
  38817551574, attempt 2, no step started). The Linux suite ran instead on the local Ubuntu 24.04.5 VM
  (`PRISM-Linux-Test`, kernel 7.0.0-38, systemd 255.4-1ubuntu8.18, 4 vCPUs, 7.75 GiB, **no swap**), as the desktop
  user through `user@1000.service`, from a non-graphical login session, with the CI job's command and
  `-Dprism.linux.native.required=true`:
  - at 75af800: 9 of 11 passed. T-L2 failed at the 1 % edge (0.195 cores): the VM's virtual clock froze for about
    257 s during that test (VirtualBox log: "Giving up catch-up attempt at a 267 s lag", guest heartbeat lost), which
    also made its release time out; not a Prism fault. T-L5 failed because, without swap, anonymous memory above
    `memory.high` cannot be reclaimed and the kernel keeps the allocator in its throttling delay, so it never finished
    in 90 s (the test assumed swap; fixed in c85525a to accept that outcome only where SwapTotal is 0, with every
    throttling proof still required);
  - at c85525a (with the signal-0 fix below): **11 of 11 and the smoke test passed, 0 skipped**, the CI validator
    `linux-native-validation.mjs` passed, no `prism-srm-limit-*` unit was left. Measured: 1 % = 0.036 cores (quota
    0.040), 100 % = 1.002 cores; 40 % of a core held at 0.402 with `cpu.stat` throttling (29 periods), and after release
    1.001 cores with the `cpu` controller switched off for the scope (no `cpu.stat` to read);
    `memory.high` 128 MiB: 1757 `high` events, PSI some 71 s, the allocator alive, stalled at 144 MiB of 384 and not
    finished within 90 s (no swap);
    `memory.max` 128 MiB with consent: the kernel OOM-killed the allocator (`oom_kill` 1, no swap), its sibling in
    the scope stayed alive and the scope stayed active (`OOMPolicy=continue`); a reaped pidfd was refused with
    `UnixProcessIdUnknown` and no unit; a limit sent to an unknown invocation id was answered
    `NoUnitForInvocationID`; a SIGKILLed adopting JVM's scope was proven and cleared (0.395 → 1.002 cores);
    pidfd and socket counts unchanged after every test.
  - at f9fc7a9 (the user-ID re-check) after a fresh boot: 10 of 11 passed (1 % = 0.041, 40 % = 0.400 with 30
    throttled periods, every adoption through the new proof ENFORCED); T-L7's separate adopting JVM ended `UNCERTAIN`
    before recording ownership, coinciding with a 6 s guest stall in the VirtualBox log;
  - at dc72201 (f9fc7a9 plus a diagnostic print in the adopter): 9 of 11. T-L7 ended `UNCERTAIN` with "the process
    could not be shown to be in its scope: GetUnit failed: org.freedesktop.DBus.Error.Timeout" (the 2 s call bound),
    coinciding with 10 s stalls of the guest. T-L1 was adopted, but measured 1.005 cores against a 0.400 quota
    right after a 248 s freeze of the guest, while `cpu.stat` counted 93 throttled periods (9.2 s throttled) inside
    what the test timed as a 3 s window: the kernel was throttling, and the guest's clocks disagreed. The same test
    held the quota in runs 1 to 3 (0.411, 0.402, 0.400). Recorded as a clock artefact of the VM, not proven further. T-L7 alone then passed 2 of 3
    repeats (the third failure again with the 2 s `GetUnit` timeout during guest stalls; a further 256 s freeze
    followed). Prism reported every one of these as uncertain and claimed no limit: the fail-closed path, exercised
    for real. The VM was stopped there, as further runs on a host that freezes the guest prove nothing more.
  So the latest code ran green end to end on Linux at c85525a; f9fc7a9/dc72201 add only the user-ID comparison (and a
  test print), which ran on real Linux inside every successful adoption and change of runs 3 and 4, but no run of
  the whole suite at the final commit was free of guest freezes.
  This is the first execution on Linux of everything after 2dcdd25, including the invocation-path writes. It is not a
  desktop validation: the tests ran outside a graphical session, so decision 7 and K16 stand.
- **Final review fixes (2026-10-16).** Only ESRCH from signal 0 means the process has exited
  (`pidfd_send_signal`(2)); EPERM (by kill(2)'s rules, a process whose real and saved user IDs are no longer the
  user's, as after `setuid(0)`) had been read as an exit, so
  `forget` cleared and dropped the hold of a running process and `change` said "exited". Now adoption refuses such a
  process as access denied, `change` sends nothing and leaves the hold uncertain, `forget` says it still runs.
  `releaseAll` starts no release thread when nothing is held and the lock is free. A set-user-ID program run after the
  adoption keeps the real user ID, so signal 0 still works; the ownership proof before and after every change now also
  compares the four user IDs again, so such a process gets no further limit (the hold becomes uncertain). Recorded, not
  changed: the connection's `Hello` keeps libsystemd's default 25 s timeout, because `sd_bus_open_user` seals it
  before Prism sets its 2 s bound (a hung broker can hold an open that long; callers are bounded and fail closed);
  the target's cgroup is not checked again after the pidfd is open (decision 1); group IDs are not compared;
  `LinuxScopeLimiter` is large (1500+ lines) and adoption blocks `srm-limiter` for up to a few seconds — both to be
  resolved before any enablement.
- **Hyper-V validation (2026-10-16).** A Hyper-V VM replaced the VirtualBox one, whose whole guest froze under load, and
  has shown no freeze. The full gate and the native suite passed there at a388cc0 and at 151ab20, with one failure caused
  by a test-harness race, fixed in 151ab20. With 4 GiB of swap, `memory.max` swapped instead of killing, so the OOM kill was
  not exercised there. A disposable probe observed where GNOME and a terminal place new processes; nothing was moved. Those
  observations support decision 7 and authorize nothing. Details:
  [evidence/m12-hyperv-validation.md](../evidence/m12-hyperv-validation.md).
- **GitHub Actions again (2026-11-07).** Jobs start again. Run 42302228938 on 151ab20: `verify` passed on Windows, Ubuntu
  and macOS, and `linux-native-validation` passed with 12 tests, 0 skipped.
- **Closure review (2026-11-07).** An independent review of the product paths found no way past K16 and two faults in the
  scope-membership rule that were reachable with K16 active, against decision 8: it was off when the cgroup mount could not be
  determined, and it read only the last path segment. Both are fixed, with tests seen failing first; the rule now
  needs only `/proc/<pid>/cgroup` and covers cgroups below a Prism scope. The K16 tests were strengthened. The code does not
  yet meet decision 4: `LimitService` asks for `memory.max` on every native memory limit, and nothing requests `memory.high`
  (register D14). Open risks:
  [evidence/m12-risk-register.md](../evidence/m12-risk-register.md).
- **Round 2 (2026-11-07).** At 3b3475b: the full gate on the VM (2287 tests, 0 failures, 64 skipped) and the native suite
  3 of 3, with no scope, unit or process left; hosted `verify` on Windows, Ubuntu and macOS and `linux-native-validation`
  passed. Details: [evidence/m12-acceptance-matrix.md](../evidence/m12-acceptance-matrix.md).
- **Final candidate (2026-11-07).** At f0f4279: the full gate on Windows (2298 tests, 0 failures, 37 skipped), on the VM (2287, 0, 64) and on
  the macOS test machine (local user: kerem) (2287, 0, 63); the native suite 3 of 3 on the VM; hosted `verify` on Windows, Ubuntu and macOS and
  `linux-native-validation` passed; hosted `native-validation` failed, as it does by design. The product owner accepted this
  record on 2026-11-07.
- **K16.** `HostPlatform` builds `LinuxScopeLimiter.product(...)`, which admits no process, opens no bus and reports
  every process `UNAVAILABLE` ("Linux native limits are not validated for desktop applications"; the system-wide report
  adds "(implemented, not enabled: K16)"). There is no
  setting, property or environment variable that changes this; only the test harness composes an admitting limiter,
  for identities it started itself. K16 is an active safety restriction in the current product. It can be lifted later only
  after separate validation and with the product owner's explicit approval; accepting this ADR does not lift it.

Sources: the planning evidence (doc-facts) of plan Rev 2.2; systemd v255 `org.freedesktop.systemd1(5)`,
`systemd.resource-control(5)`, `systemd.scope(5)`, `sd_bus_message_append(3)`, `sd_bus_call(3)`; kernel
`Documentation/admin-guide/cgroup-v2.rst`.
