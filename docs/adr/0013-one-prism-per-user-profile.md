# 0013. One Prism per user profile

- Status: Accepted (product owner, 2026-10-10). Resolves the open question of
  [ADR-0007](0007-per-user-folders-and-log-files.md) ("a single-instance rule, if wanted, is a
  product decision that has not been made"), which itself is unchanged, and closes the
  concurrent-instance caveat of [ADR-0012](0012-windows-native-limits-ledger-and-recovery.md)
  for instances of one user.
- Date: 2026-10-10; accepted 2026-10-10

## Context

Nothing in PRODUCT_SPEC requires two Prism instances for one user to run at once. LIFE-6 only
says that a journal entry written by an instance that still runs is left alone, which the
journal's lock already ensures. ADR-0007 left a single-instance rule open as a product decision.

Milestone 10 showed what two instances can do. `ConcurrentInstancesTest` reproduces it with the
real recovery:

- Instance B looks at the folder before instance A has set any native limit.
- A then caps a process natively.
- B finds the process in a job B does not hold and offers the emulated limit.
- The user accepts, and B's emulated controller suspends a process that A caps.

Within one limit service nothing tells B of A's later ledger, and the limiter thread may not read
files to find out. Two independent controllers act on one process, which is what the native-limit
design exists to prevent.

## Decision

1. **One running Prism per user profile.** The ownership is an exclusive operating-system lock
   (`FileChannel.tryLock`) on `prism.instance.lock` in the user's state folder (ADR-0007).
2. **When it is taken.** `App.init()` takes it on the launcher thread, after logging is set up and
   before anything is monitored, limited or recovered. A launch that finds it held asks again for
   about three seconds, because Windows may let go of a killed owner's lock a moment late
   (ADR-0011).
3. **A refused launch.** It shows one dialog ("Prism is already running for this user, or is still
   closing") and exits. It builds no runtime, starts no thread of Prism's, and touches nothing of
   the running Prism.
4. **When it goes.** It is held until the process exits, and the system lets go of it then, also
   after a hard kill. The file that remains is no marker: only a held lock keeps a launch out. A
   launch that fails before the runtime exists lets go of it at once.
5. **When ownership cannot be proven, the launch is refused.** This covers a folder or file that
   cannot be made, and a lock that cannot be asked for. Two controllers are worse than none.
6. **One acquisition per process.** On some systems closing any channel to a file ends every lock
   the JVM holds on it.

## Alternatives considered

- **Reading other sessions' ledgers before every emulated start:** this needs file I/O on the
  limiter thread (ADR-0011 moved it off), and it is still racy. Rejected.
- **Native-only coordination between instances** (for example, refusing the fallback whenever a
  target is in any job): this breaks the approved fallback for processes in unrelated jobs.
  Rejected.
- **A lock file whose existence blocks the launch:** this leaves a stale marker after a crash.
  Rejected.

## Consequences

- **A file system without working locks** (for example a network home folder without a lock
  service) refuses every launch. This is an accepted cost.
- **The refusal is logged.** A refused launch writes one line to the shared log before it exits
  (ADR-0007's multi-writer case, for one line).
- **Older builds are not covered.** A Prism built before milestone 10 takes no instance lock, so it
  can still run beside a new one.
- **Other user profiles are not covered.** The rule does not hold across profiles. Another user's
  Prism can rarely open this user's processes with the rights an assignment needs.
- **Verification runs need a free profile.** Runs that start a second Prism must close the
  developer's own instance first, or use another user profile (docs/TESTING.md).
- **The M9 wait for late locks stays.** While this Prism owns the profile, a journal whose lock is
  held can belong only to a dead or dying session, or to an older build.
- **What was run.** Windows 11, both unit and integration tests and a real double launch. Linux and
  macOS ran no test of this rule (CI has not run).
