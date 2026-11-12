# 0010. Unix process control and process identity

- Status: Accepted (run on Ubuntu x86_64 and macOS arm64 in CI; see Verification). Narrowed by [0011](0011-suspension-journal-and-recovery.md): Resume never ends a stop that existed before Prism acted, but a stop added while Prism holds the process ends with it; after a crash a stop is taken over (adopt) only on the user's request, only while it is a plain stop (`T`, never `t`)
- Date: 2026-10-08 (proposed and accepted)

## Context

Milestone 8 brings End Task, Force Kill, Suspend, Resume and Change Priority to Linux and
macOS (CTL-3, CTL-5, CTL-6). Prism names a process by its identity, PID and start time
(PROC-3), and must never act on a process that took over a PID. Checked against the
man pages, the Linux and xnu sources, the JDK sources and the OSHI 7.7.0 jar:

- `ProcessHandle.destroy()` and `destroyForcibly()` compare the start time and then call
  `kill(pid, sig)`: two steps, not one. The check is skipped when a start time is unknown,
  errno is lost (a refusal reads like an exit), and xnu's `kill` succeeds on a zombie.
- Linux: `pidfd_open` (5.3) returns a descriptor of one process; `pidfd_send_signal` (5.1)
  sends through it, and fails with ESRCH once that process has gone, "a stable reference to
  a specific process". A pidfd does not keep the PID number: it is freed when the process is
  collected. The calls exist as glibc wrappers only since 2.36.
- macOS has no documented descriptor of a process for signals; the audit-token calls are in
  libproc, which Apple calls "private interfaces ... subject to change".
- Both kernels hand out PIDs one after another and wrap (Linux at `pid_max`, at least
  32768, to 300; xnu at 99999, to 100). A freed PID is given again only after the whole
  space has been gone round.
- The nice value is per thread on Linux (`setpriority(PRIO_PROCESS, tid)` sets one
  thread), per process on macOS. Without privilege a nice value can be raised but not
  lowered, also not back to a value the process had (EACCES on both).
- SIGSTOP stops the whole process and cannot be caught; stops do not stack, and one
  SIGCONT ends them all. The system does not say who stopped a process.
- OSHI's listing shows Linux field 18 of `/proc/<pid>/stat` (nice + 20) as the priority and
  a Mach priority on macOS. Its macOS state mapping does not match xnu's `p_stat` values
  (a stopped process would show as new); not run on macOS.

## Decision

- **Linux, signals through a pidfd only.** End Task (SIGTERM), Force Kill (SIGKILL),
  Suspend (SIGSTOP) and Resume (SIGCONT): open a pidfd, then check the identity in
  `/proc/<pid>/stat`, then signal through the descriptor. The start time is computed from the
  boot time the process listing used (OSHI reads `btime` once), handed in by the platform: a
  second reading of `btime` can differ by a second after the clock was stepped, and then no
  identity would match again. The check after the open makes it
  hold for the descriptor: a process that took the PID later started later. A suspension
  holds its pidfd until it is resumed or let go. The path is probed once, on first use, on
  Prism's own process (open, signal 0, close). Where it fails, the four actions are
  `UNAVAILABLE` and the activity log says why; Prism never falls back to `kill(2)`. The
  calls are made through `syscall(2)` with the kernel's numbers 434 and 424, on x86_64 and
  aarch64 only, whose system call and signal numbers are verified; elsewhere nothing is
  tried. (The glibc wrappers were not used: looking for a symbol that is missing throws an
  `Error` in JNA, which Prism does not catch; on the two architectures the system call is
  what the wrapper makes.)
- **macOS, check then act.** The identity, the state and the flags are read with
  `sysctl(KERN_PROC_PID)` through a minimal JNA structure over the head of `kinfo_proc`;
  then `kill(2)` or `setpriority(2)`. The race between the two calls is accepted and stated:
  for it to strike, the process must exit and be collected and the kernel must hand out
  every other PID up to 99999 within microseconds. A zombie is answered as gone.
- **Priority** has no descriptor-based call on either system. Linux sets every thread in
  `/proc/<pid>/task`, reads the list a second time for threads started meanwhile, and reads
  every thread back; macOS sets the process and reads it back. The check-then-set race is
  stated, as above. `NATIVE_SOFT`: a preference, which autogrouping and cgroup weights
  limit on Linux.
- **Suspension ownership.** A process that is stopped already (T or t on Linux, SSTOP on
  macOS) is not suspended: Prism takes over no stop it did not make, so its Resume never
  continues one. A continue by another program goes unnoticed; reconciling it from the
  listing is deferred, because the listing's state is not reliable enough for it (OSHI reads
  the leading thread on Linux, and maps macOS states wrongly).
- **Capabilities.** End Task `NATIVE_SOFT` (termination signal), Force Kill `NATIVE_HARD`
  (kill signal), Suspend and Resume `NATIVE_HARD` (stop signal), Change Priority
  `NATIVE_SOFT` (nice value). The level describes the mechanism, not how firmly the
  identity is held; that is the paragraph above.
- **Words follow the mechanism** (CAP-5): a termination request, a stop of the whole
  process, the privilege a raised priority needs.

## Alternatives considered

- `ProcessHandle` for End Task and Force Kill, as CTL-3 said: the same check-then-kill on
  both systems, with errno lost. CTL-3 now prefers portable APIs only where they keep the
  identity and the system's answer.
- `kill(2)` after a check where Linux has no pidfd: rejected; Linux offers the stronger
  mechanism, and an older system gets the actions unavailable rather than weaker.
- libproc's audit-token signal calls on macOS: private API. kqueue `EVFILT_PROC` could see
  an exit between the check and the call, but cannot make them one; not used.
- Only the main thread's nice value on Linux, as `renice` does: little effect on a process
  of many threads.
- Dropping Prism's hold when the listing shows a held process running: could discard a
  valid hold on an unreliable reading; deferred.

## Consequences

- On Linux 5.3 and later, on x86_64 and aarch64, a signal reaches exactly the process the
  identity names. On older kernels, under seccomp filters that refuse pidfd and on other
  architectures, only Change Priority is offered.
- On macOS, and for priority on both systems, the identity is checked microseconds before
  the call, not with it; the report and ARCHITECTURE say so.
- A process stopped by someone else cannot be suspended by Prism; a process Prism stopped
  and someone else continued still shows as suspended by Prism until Resume. End Task on a
  process Prism has stopped is delivered and stays pending until Resume; the result says
  "asked", never that the process ended. A process killed while Prism held it stopped is a
  zombie until collected; Resume answers it as gone.
- Verified by tests against fakes that follow the documentation, on every system, and by
  tests on the running system (`@EnabledOnOs`) on Linux and macOS, in CI (below).

## Verification

The record was Proposed until the decision had run on Linux and macOS. It has, in the CI
workflow (`.github/workflows/ci.yml`): run
[37802133378](https://github.com/erdemdenizer/PRISM-internal/actions/runs/37802133378) on commit
`c44f87a`, the full gate (`./mvnw -B verify`) passing on all three jobs. Which tests ran
on which system was read from each job's surefire reports, not taken from the job's
result.

| | Ubuntu (GitHub `ubuntu-latest`) | macOS (GitHub `macos-latest`) |
|---|---|---|
| System | x86_64, kernel 6.17 (azure), 2 processors | arm64, Darwin 25.6 (xnu-12377), 5 processors |
| Native reads | `JnaLinuxTest` 15 of 15: the pidfd probe on the whole path, the start time from `/proc` equal to the JDK's and the listing's, errno of the real calls | `JnaMacosTest` 6 of 6: the identity from `sysctl` equal to the JDK's and the listing's, errno of the real calls, `getpriority` equal to `ps` |
| Actions on the demo workload | `LinuxControlIntegrationTest` 7 of 7 | `MacosControlIntegrationTest` 7 of 7 |
| Signals | SIGSTOP stops it (state T, no CPU while held), SIGCONT lets it run, SIGTERM ends it with its own `exit=signal`, SIGKILL with 137, all through a pidfd; a process stopped already is refused | the same through `kill(2)` after the check, with state SSTOP |
| Priority | nice 0 lowered to 10 on every thread; raising back to 0 refused with EACCES, nice unchanged | nice -10 (inherited, below) lowered to -5, seen by `getpriority` and by `ps`; raising back to -10 refused with EACCES |
| Identity | a start time not the workload's is refused by every action, and nothing changes | the same |
| Resources | no pidfd left open in the test's process | (no descriptor is held on macOS) |

- The listing's CPU time of another process on macOS (which the JDK does not read) lies
  between two readings of `ps`, so its units are right on arm64.
- GitHub's macOS runner starts every job at nice -10: `ps` shows it from the first process
  under launchd onwards, down to the test's JVM. The priority tests start from the value
  the process has, read by Prism and checked against `ps`; they do not assume 0.
- **Not verified**: aarch64 Linux, a kernel without pidfd or a seccomp filter that refuses
  it (covered by fakes only), the privileged paths (root, `CAP_SYS_NICE`, an
  `RLIMIT_NICE`), x86_64 macOS, and the running application on Linux or macOS: the
  actions were run by the integration tests, not through the UI. The residual races
  stated above are reasoned from the kernel sources, not measured. OSHI's macOS state
  mapping (Context) was not exercised.
