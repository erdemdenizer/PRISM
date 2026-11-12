# 0011. Suspension journal, recovery after a crash, and the emulated CPU limiter

- Status: Accepted (external technical review, 2026-10-09, with the Windows performance
  exception below). Run on the real system on Windows 11 by hand and in CI on Windows x64,
  Ubuntu x86_64 and macOS arm64 (run 37943563248).
- Date: 2026-10-08; accepted 2026-10-09

## Context

Milestone 9 brings the emulated CPU limiter (LIM-8) and the suspension journal (LIFE-4 to
LIFE-6). ADR-0004 was written before milestone 8 and assumed that the next start could
resume, by itself, a process a crashed Prism left suspended. Milestone 8 showed why it
cannot:

- No target system tells who stopped a process. On Linux and macOS a stop is a state, not
  a count, and has no sender; on Windows each thread has an anonymous suspend count.
- Between a crash and the next start another program may stop, continue, or stop again
  a process the journal names. A journal entry plus a matching identity plus a stopped
  state is therefore a claim, not proof that the stop is Prism's.
- Only a live token is proof: open thread handles on Windows (each one exactly one
  increment Prism made), a pidfd on Linux, the coordinator's own record on macOS while
  Prism runs.

The limiter suspends many times a second, so the journal must not be written per cycle,
and the limiter must not suspend anything a later session could not find.

## Decision

**Ownership in three layers.**

1. *In session*: a live token. Resume, a cancelled limit, an exit or an error releases
   through it, automatically.
2. *Controlled shutdown and the JVM shutdown hook* (LIFE-1, LIFE-5): the same live
   tokens, automatically. The hook (`srm-exit-hook`) stops the limits and releases the
   automatic reasons within 1.5 s; after a completed shutdown it finds nothing and says
   nothing. Logback's own shutdown hook is removed, so the hook's lines reach the log
   file; every line is flushed as it is written.
3. *After a crash*: a journal entry is a claim. Prism resumes nothing by itself.

**The journal.** One file per Prism instance, `suspensions-<pid>-<startMillis>.journal`
in `UserFolders.state()/journal`, beside a `.lock` file this instance holds an exclusive
`FileLock` on for its whole life (the journal itself is never locked, so it stays
readable). An entry holds the identity, name, reasons, cause, writing instance, creation
and update time, whether the suspension is incomplete, and on Windows a memento of the
threads Prism holds (`w1:tid@creationFiletime,…`). A write is durable and verified:
temp file in the same folder, `force(true)`, `ATOMIC_MOVE|REPLACE_EXISTING` (three tries),
folder `force` on POSIX (best effort), read back and compared byte for byte; the file ends
with a CRC line. POSIX permissions are 0700 for the folder and 0600 for the files. A
failed write or a failed check (lock lost, file changed on disk) makes the journal
FAILED until a new limit request retries it. The persistence covers a killed process,
not a power loss on Windows (a rename there is not written through). File locks are not
reliable on network file systems; the state folder is local by the folder rules of
ADR-0007.

**Written per limit, not per cycle.** The coordinator gains `declare` and `withdraw`:
a limit's entry is written before its first suspend and removed when it ends. The
limiter's suspend and release touch no file. A user suspension is written before the
process is touched and again once its memento is known. An incomplete suspension stays
in the journal.

**Written off the coordinator's lock (external review).** The first version wrote the
journal while holding the coordinator's lock, so a disk that stalled held up every
release of every process Prism holds; a deterministic test with a stalled write proved
it. The coordinator now numbers each change of what the journal should hold and hands it
to `JournalWriter`, which writes only the newest on a thread of its own (`srm-journal`),
after the lock is released. What must be on disk before Prism acts waits for its number,
outside the lock, for at most 2 s (`JOURNAL_WAIT`): a limit's declaration, the user's
suspension before the process is touched, the entries carried over at start-up. A wait
that runs out fails the journal for the session (it is not retried, since the stalled
write may still land), which stops every automatic suspension as any failure does, and
from then on no wait for the journal runs its bound out again. A release never waits for
the journal; the shutdown and the exit hook wait for what they handed over only within
what is left of their own bounds and fail nothing when that runs out. The check, retry and
close of the journal run on the same thread, bounded the same way. One delay remains
while a disk is slow but has not yet stalled: the declaration of a new limit and the
check once a second run on `srm-limiter`, so the steps of the limits running then can
start up to 2 s late (a held target stays suspended that much longer); a reported failure
is told to the listeners without the coordinator's lock. The
coordinator's lock still covers one native suspend or resume (up to about 0.2 s on
Windows), which bounds how long another release can wait; that was measured, not split.

**The journal is a precondition of every automatic suspension.** The coordinator refuses
an automatic reason (CPU limiter; OOM Guard later) that is not declared in a healthy
journal. A limit starts only after `declare` returned durable. When the journal fails
while limits run, every limit stops: no new suspension, the live tokens are released,
nothing is withdrawn, the file and the lock stay as evidence, the Limits page and the
activity log say why. The user's own suspension still goes ahead, and its result says it
was not recorded for after a crash.

**Recovery at start-up.** Before anything is suspended, Prism takes the lock of every
journal whose lock is free (a dead instance), reads it (a damaged file is read as far as
it goes and copied aside as `.corrupt-<time>`), and classifies each entry:

- process gone, or another start time: closed and logged;
- running: closed and logged ("nothing was done");
- liveness undetermined, stopped under a debugger (`t`, `P_TRACED`), Resume unavailable,
  or a Windows entry without a memento: listed for manual intervention, Dismiss only;
- otherwise: listed as possibly suspended, with Resume;
- an entry whose check throws: listed for manual intervention. One bad entry never keeps
  Prism from starting; names read from a file are shown and logged on one line.

The Processes page says how many entries there are at the right of its filter line and lists
them in a dialog (Review…), with each entry's whole explanation and evidence.

Listed entries are written into this instance's journal as carried over before the dead
file and its lock are deleted; if that write fails, or anything else goes wrong, the dead
files stay for a later start and their locks are let go. A declaration that could not be
written is forgotten at once, and one whose limit ended for the journal is forgotten too, so a
journal that is written again later carries neither.

**A journal whose lock is not free yet** (milestone 9, recovery visibility). Windows lets the
locks of a killed process go "depending upon available system resources" (LockFileEx), so the
journal of a session killed just before start-up can still be locked then. A locked journal is
never read. When its writer has ended (the PID in its instance name is gone, or runs with
another start), Prism says in the activity log that the list may be incomplete and looks again
on `srm-recovery` after 1, 2, 4, 8, 15, 30 and 60 s; what it reads is classified, listed,
carried over and deleted exactly as at start-up, and the log says when all have been read. One
still locked after about two minutes stays for the next start, and the log says so. The
Processes page says it too, beside its filter, so an empty list is never taken for a whole one:
"Checking an earlier session…" (muted) while Prism looks, nothing once all are read, and, in the warning
color, that a journal was not checked and a restart of Prism checks it again; the review dialog
repeats why the list may be incomplete. The
journal of a session that runs is left alone. No look begins once the shutdown has, and the
shutdown calls a pending one off within its bound. An identity already listed, held by this
session, or dismissed by the user is not listed again. Nothing is resumed or suspended.

**Resume on request.** Resume opens a confirmation that says the stop may not be
Prism's. Then `ProcessController.adopt(identity, memento)` checks once more and takes a
token only when the check holds:

- Linux: pidfd, state `T` (never `t`), then a pidfd-backed suspension;
- macOS: `SSTOP` without `P_TRACED`;
- Windows: no debugger (`CheckRemoteDebuggerPresent`), then every recorded thread that
  still exists with the same creation time is probed with `SuspendThread` (raising the
  count, never lowering it). All probes first, then the decision: any count ≥ 2 refused;
  0 and 1 mixed refused; all 0 closed ("no longer suspended"); all exactly 1 taken over:
  the probes are undone and each thread keeps one increment as Prism's. A probe that
  cannot be undone stays held, never forgotten. Without a memento Windows makes no
  native call.

The coordinator alone calls `adopt` (ArchitectureTest), and releases the adopted token
in the same locked call; it is never shown as held. Dismiss closes the entry and touches
nothing; the whole entry is logged.

**The emulated CPU limiter.** On `srm-limiter`, one task per limit
(`LimitRun`), steered by `DutyCycleController`: demand = smoothed use / realized run
share; target share = limit / demand within [0.05, 1]; a dead band of max(0.1·L, 0.5 pp);
steps of at most 25 % down and 50 % up; a run share of 1 suspends nothing. The cycle is
ten times the measured suspend time within [200 ms, 2 s]: on Windows a suspend lists every
thread of the system (about 110 ms on a desktop with 6,700 threads), so the cycle there is
about 1.1 s. Statuses never claim more than was measured (`ACTIVE_THROTTLING` only after
three intervals within a one-sided tolerance; `ACTIVE_ABOVE_TARGET` with "floor" or
"variable load"). A target found stopped by another program (`stoppedElsewhere`) pauses
the limit: no hold, no continue, a retry once a second. A target suspended by the user
makes the limit wait without holding a reason of its own, so the user's Resume continues
it at once. A suspend that fails is tried again in the next cycle only when it is known to
be transient and Prism holds nothing: the platform marks a failure transient only when the
thread that failed is shown to have ended (`GetExitCodeThread` on its open handle gives a
code other than `STILL_ACTIVE`), the process is shown to run (`GetExitCodeProcess` gives
`STILL_ACTIVE`), and the rollback released every increment (`SuspendResult.transientFailure`);
a refused `OpenThread`, a thread that still runs and an answer the system did not give are
not transient. The coordinator passes it on only when it holds nothing for the target
(`Outcome.retryable`).
Three in a row end the limit. Everything else ends the limit at once and is never
retried: a refusal (access, protection), a process that is gone or another one, a stop by
another program, an incomplete rollback, a failure to open or list, and a journal that
failed. *Amended 2026-10-11 (milestone 10, Windows CI):* a failure that holds nothing (a refusal
or the third transient one) is confirmed first: the processor time is read once more one cycle
later, and a target gone by then ends the limit as an exit, any other as the failure it was;
nothing is suspended or retried meanwhile. A thread of a process that is exiting can refuse a
suspend before the process reads as exited (seen on the hosted Windows runner, and in 12 of 60
provoked exits, read as gone within 16 ms). A failure that leaves anything held still ends the
limit at once. A step runs
in `try/finally`: a step that does not end properly releases whatever the coordinator holds
for the limiter, whatever the phase; an `Error` is not caught.

**The Windows performance exception (accepted 2026-10-09).** The plan's budget for the
emulated limiter is at most 3 % of one logical processor per throttled target. On Windows
it is **not met**: one throttled target costs about 10 % of one logical processor
(measured 10.8 %; 18.9 % for two, 37.9 % for four; docs/PERFORMANCE.md). The cause is the
documented suspension path itself: a suspend lists every thread of the system twice
through Toolhelp, and the cycle is ten suspend times long, so the share is about a tenth
whatever the number of threads. This is accepted as a documented exception, not as
compliance:
- The 3 % budget stays the target of the emulated path; it is recorded as unmet on Windows.
- Windows suspension is not redesigned in milestone 9. Native CPU rate control through Job
  Objects (LIM-3, LIM-5, milestone 10) is the remedy: a process Prism limits natively
  suspends nothing and costs the limiter nothing. The exception keeps applying to every
  emulated Windows limit, also after milestone 10 when the user explicitly chooses the
  emulated fallback because native assignment failed.
- **Admission.** Where suspension goes thread by thread (`PER_THREAD_SUSPENSION`, the
  Windows mechanism), at most `max(1, min(4, processors / 4))` CPU limits run at once
  (integer division over the logical processors the limits are measured against: 1 on 2
  or 4 processors, 2 on 8, 4 on 16 or more); every other mechanism keeps 16. A disabled
  limit runs nothing and takes no place; Enable and Set CPU limit on an enabled process
  are admitted by the same rule on the limiter thread, which takes requests one at a
  time, so concurrent requests cannot exceed it. A refusal says the number and why and
  changes nothing: no suspension, no journal declaration, no stored percentage, the
  process stays disabled. The rule bounds how many limits run, not what they cost: the
  cost per limit depends on the machine (on one whose suspend takes over 200 ms the cycle
  is held at 2 s and the share is higher), and four limits were measured on one desktop
  only.
- Rejected for milestone 9: one listing per suspend (about 3 %, a new suspend path), thread
  handles kept between cycles (a redesign), cycles of 3 to 6 s (a coarser limit), an
  adaptive cycle under a global budget (unmeasured), counting only throttling limits (a
  limit that starts throttling later cannot be refused).

**Shutdown order.** Stop the monitors; wait for start-up; refuse new suspensions and
limits; stop the limits and the limiter thread (≤ 1 s); then the existing steps; release
the automatic reasons; finalize; close the journal (an empty one is deleted, one that
names the user's suspensions or carried entries stays).

## Alternatives considered

- Resuming the limiter's entries automatically at the next start: rejected; a stop found
  after a crash can be another program's, and continuing it is the one harm Prism must
  not do.
- On Windows, resuming an entry without a memento when every thread shows one
  suspension: rejected; Process Explorer suspending the whole process gives the same
  picture.
- Writing the journal on every cycle: rejected; five writes a second per limit, each a
  forced write and a rename.
- One shared journal file: rejected; two instances would race, and a dead instance could
  not be told from a live one.
- A fixed 200 ms cycle on Windows: rejected after measurement; a suspend took 110–130 ms,
  most of a core at 5 Hz.

## Consequences

- After a hard kill during a limit, the target stays stopped until the user acts on the
  list at the next start; on Windows a limiter entry (no memento) must be resumed outside
  Prism, for example in Resource Monitor. LIFE-4, LIFE-6, LIM-8 and PROC-6 say so.
- Residual risks the confirmation names: on Unix, a stop another program made after the
  crash ends with the user's Resume; on Windows the one increment taken over may not be
  Prism's. In session, on Linux and macOS, a SIGSTOP another program sends while Prism
  holds the process ends with Prism's SIGCONT, and Prism cannot see it; the limiter does
  this often. SIGCONT also runs the target's handler and gives its parent a SIGCHLD.
- Without a writable state folder there are no CPU limits, and Prism says why.
- On Windows one limit costs about 10 % of one core (measured 10.8 %; 18.9 % for two
  limits, 37.9 % for four), whatever the number of threads of the system: the cost is the
  system-wide thread listing of each suspend (83 % of the limiter's samples in
  `Thread32Next`), and the cycle is ten suspend times long, so the share is about a tenth
  per limit. The budget of 3 % of one core per limit is not met on Windows; this is the
  accepted performance exception above, bounded by admission and ended by native limits in
  milestone 10 for every process that can be limited natively. The one limiter thread
  serializes the limits: at four limits a step starts up to 0.2 s late.
- The lowest share a limit can hold is about 5 % of what the process would use, more on
  Windows where threads run while a suspend lists them; the Limits page shows the lowest
  reached instead of calling the limit met.
- Verified on Windows by deterministic tests (virtual clock, fault-injecting file
  operations) and `CpuLimitIntegrationTest` on the real system; the Linux and macOS
  integration tests run in CI only, and passed there with the Windows ones (run 37943563248).
