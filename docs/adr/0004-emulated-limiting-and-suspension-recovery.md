# 0004. Emulated limiting and suspension recovery

- Status: Accepted
- Date: 2026-10-05

## Context

A native hard limit is not always available: macOS has no documented way to cap the CPU
or memory of an arbitrary running process (`setrlimit` applies to the caller and its
future children; task ports of other processes need entitlements); on Windows a process
may refuse Job Object assignment; on Linux the cgroup probe may fail. The product still
has to offer a CPU limit, honestly labelled, and must never leave a process suspended
because Prism went away.

The established technique is duty-cycle suspension, as in the `cpulimit` tool: within a
fixed time slot (100 ms there) the target runs for part of the slot and is stopped for
the rest, with the split adjusted from measured CPU use.

## Decision

1. **One owner for suspension.** A suspension coordinator in `application` is the only
   caller of the `ProcessController` suspend and resume operations. It records, per
   process identity, the reasons the process is suspended (user, limiter, OOM Guard)
   and resumes the process only when the last reason is cleared. The limiter's resume
   therefore never wakes a process the user or OOM Guard suspended, and shutdown knows
   which suspensions are Prism's to undo.
2. **One emulated CPU limiter, in `application`**, built on the coordinator, so the same
   code serves every system. It is reported as `EMULATED` everywhere and described to
   the user as approximate.
3. **The limiter is a feedback controller, not a fixed duty cycle.** The limit is a
   percentage of total machine capacity. A fixed split equal to that percentage
   throttles far too much: a single-threaded target on eight logical processors uses at
   most 12.5% of the machine, so "run 30% of the time" would hold it near 4% under a
   30% limit that it never reaches anyway. The split equals the limit only for a target
   that would otherwise saturate every processor.
   - Input: the target's CPU utilization normalized to the whole machine, measured
     about once a second through the single-process read of `ProcessSource` and
     smoothed.
   - Output: the run share of each cycle, always within a small positive floor and 1.
     It starts at 1 (never suspended) and moves toward
     `run share x limit / measured`, clamped to that range. When the measured use is
     zero or unavailable, it moves toward 1.
   - A target measured under its cap, with the run share at 1, is not suspended at all.
   - Each update changes the run share by a bounded step, and a dead band around the
     cap leaves it unchanged, so it does not oscillate. The initial bounds (step at
     most a quarter of the current share, dead band of a tenth of the limit) are
     tuned by measurement in milestone 9.
   - Each cycle is at least 100 ms unless measurement later justifies a shorter one.
   - Before every suspend and resume the process identity (PID and start time) is
     read again; on a mismatch the limiter stops and touches nothing.
   - The limiter runs on a scheduled executor dedicated to limiting, created by `app`,
     so process enumeration cannot delay a resume. It is cancellable at any point and
     always ends with its reason cleared: on cancellation, on any error, when the
     target exits, and on controlled shutdown.
4. **Suspension journal.** The coordinator keeps a small file in the per-user folder,
   written when the reasons held for a process change (per limit and per OOM
   suspension, not per duty cycle).
   - Every suspension the coordinator holds is journaled, including one held only for
     the user. Each entry stores: the process identity (PID and start time); the
     reasons Prism holds for the suspension (user, limiter, OOM Guard); diagnostic
     metadata (process name, the limit or OOM state that caused it, the identity of
     the Prism instance that wrote it); creation and last-update times.
   - At start-up, for each entry, Prism reads the live process again and requires PID
     and start time to match exactly. If they do not, the entry is stale: that process
     is never resumed or modified, and the entry is discarded and logged.
   - An entry whose writing Prism instance is still running is left alone, so a second
     instance never undoes the work of a live one.
   - Recovery releases only the limiter and OOM Guard reasons. The process is resumed
     only if no user reason remains. A user reason is taken over by the restarted
     coordinator, so the process is shown as suspended by Prism again and the user can
     resume it. Recovery and shutdown never resume a suspension the user requested.
   - Exact matching relies on the start time being stable between reads of the same
     process. Milestone 2 checks this for the OSHI source on Windows; if a source turns
     out not to be exact, the comparison rule is defined once, in the process identity
     type, and recorded here.
5. **Shutdown hook.** A JVM shutdown hook tells the coordinator to refuse further
   suspensions and releases the limiter and OOM Guard reasons directly, without an
   executor, resuming each process that has no user reason left; it is idempotent. Together with the journal this bounds the damage of a
   crash to the time until Prism is started again. It cannot make a hard kill of Prism
   harmless, and the documentation says so.
6. **Memory without a native limit** is a monitored policy, also `EMULATED`: Prism
   compares the resident memory of the Managed Process with its limit and applies that
   process's configured action under the same global switches as OOM Guard. It is never
   described as a cap. It is built together with OOM Guard, whose intervention policy
   it reuses.
7. **macOS** offers the emulated CPU limiter and the monitored memory policy only.
   `setrlimit` is not used for other processes.

## Alternatives considered

- **No fallback** (report `UNAVAILABLE`): honest but leaves macOS, and many processes on
  the other systems, without any CPU limit to demonstrate.
- **Lower priority as the "limit"**: a preference, not a bound; it is offered as its own
  action instead.
- **A watchdog helper process** that resumes targets if Prism dies: stronger than the
  journal, but a second process to ship, start and supervise. Deferred unless the
  journal proves insufficient.

## Consequences

- Emulated limiting is coarse: the target runs in bursts, its parent may observe
  stop/continue events, and time-sensitive applications can misbehave. The UI says this
  where the limit is set.
- The limiter depends on suspension, so it inherits the Windows caveat that suspension
  is per thread and not atomic (ADR-0002).
- Child processes of the target are not limited in the first version.
- A PID reused by another process is never resumed or suspended by mistake, because
  identity includes the start time.

Sources: setrlimit(2); Apple System Integrity Protection guide (task ports); the
`cpulimit` source (`TIME_SLOT`, SIGSTOP/SIGCONT loop); kill(2) and signal(7).
