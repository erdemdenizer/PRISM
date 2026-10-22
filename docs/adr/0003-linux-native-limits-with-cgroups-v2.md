# 0003. Linux native limits with cgroups v2

- Status: Proposed
- Date: 2026-10-05

This record stays Proposed until the mechanism has been run on a real Linux system. The
team has no Linux machine yet; nothing below has been executed.

## Context

Prism runs as an ordinary user in a desktop session and must limit the CPU and memory of
other running processes with cgroups v2, without root. From the kernel cgroup-v2
documentation, cgroups(7) and the systemd documentation:

- Moving a process needs write access to `cgroup.procs` of the destination and of "the
  common ancestor of the source and destination cgroups".
- A non-root cgroup can distribute resources to children "only when they don't have any
  processes of their own" (no internal processes).
- systemd delegates `pids`, `memory` and `cpu` to the user manager (`user@UID.service`),
  not `io`. A user's desktop applications live under `user@UID.service`; processes of a
  TTY or SSH login live in `session-N.scope`, whose common ancestor with anything under
  `user@UID.service` is owned by root.
- systemd's delegation rules say a cgroup has a single writer and that a program must
  not write to attributes of cgroups systemd manages, unless the unit is delegated to it.
- `cpu.max` takes `$MAX $PERIOD`: "the group may consume up to $MAX in each $PERIOD
  duration"; the default is `max 100000`.
- `memory.max` is the "memory usage hard limit". "If a cgroup's memory usage reaches
  this limit and can't be reduced, the OOM killer is invoked in the cgroup. Under
  certain circumstances, the usage may go over the limit temporarily." "Some kinds of
  allocations don't invoke the OOM killer" and may instead fail with `-ENOMEM`. Which
  task the OOM killer selects inside the cgroup is the kernel's choice.
- `memory.high` is the "memory usage throttle limit": above it "the processes of the
  cgroup are throttled and put under heavy reclaim pressure. Going over the high limit
  never invokes the OOM killer and under extreme conditions the limit may be breached."

## Decision (proposed)

1. Prism limits processes through **child cgroups it creates inside a subtree delegated
   to Prism itself**. The Linux launcher starts Prism in a transient user scope with
   delegation (`systemd-run --user --scope -p Delegate=yes`), which is the arrangement
   systemd sanctions for a program that manages its own cgroups. Inside that scope Prism
   moves itself to a leaf, enables the controllers for its children, and creates one
   child cgroup per limited process.
2. A native limit is claimed only after a **runtime probe** passes for the specific
   target: cgroup2 is mounted; Prism's own cgroup is delegated and writable; the needed
   controller is listed for the child; the target's current cgroup has a common ancestor
   with Prism's subtree that Prism can write to (in practice: the target runs under
   `user@UID.service`).
3. If any check fails, the Linux limiter reports `UNAVAILABLE` for that process and the
   limit service falls back to the emulated limiter, so the level in effect is
   `EMULATED`. This includes Prism started without the delegating launcher, processes
   in a login session scope, other users' processes and system services.
4. Removing a limit writes `max`; the process is moved back to its original cgroup if it
   still exists, which is the only write outside cgroups Prism created. Prism removes
   only cgroups it created, and only when they are empty.
5. **CPU conversion.** Prism's CPU limit is a whole-number percentage P of the whole
   machine. With N logical processors and the default period of 100000 microseconds,
   Prism writes quota = P / 100 x N x period as `$MAX $PERIOD`, which is exactly
   P x N x 1000 microseconds. Example: 25% on 8 logical processors is `200000 100000`. A limit of 100% is written
   as `max`. N is the machine's logical processor count, the same divisor used for
   process CPU % in the UI, so the limit and the displayed value share one unit.
6. **Memory wording.** `memory.max` is offered as the hard boundary (`NATIVE_HARD`) and
   `memory.high` as the throttling boundary (`NATIVE_SOFT`). Prism describes
   `memory.max` as a boundary at which the cgroup may enter OOM and the kernel may
   invoke the OOM killer inside it; it never states that the target process is
   guaranteed to be killed, that every allocation beyond the limit fails, or that usage
   can never exceed the limit momentarily.
7. All of this is file I/O on the cgroup file system; no shell command and no extra
   dependency.

## Alternatives considered

- **Transient scopes through the systemd user manager over D-Bus** (`StartTransientUnit`
  with `PIDs=`): the mechanism systemd documents for adopting existing processes, and it
  cleans up after itself. It needs a D-Bus client, which the baseline does not have
  (a new dependency, a `busctl` subprocess, or a JNA binding to `sd-bus`). It is the
  fallback design if the delegated-subtree approach fails validation.
- **Writing into systemd-managed cgroups directly** without delegation: works by file
  permissions but violates systemd's single-writer rule. Rejected.
- **Running Prism as root**: rejected by the product requirements.

## Consequences

- Native limits on Linux depend on how Prism was launched; the packaged launcher must
  set up the delegated scope, and the UI must explain when it is missing.
- Moving a process out of the scope its desktop environment created is visible to that
  environment. Whether this has side effects, and what to do when the original cgroup
  has been removed, must be established on a real system.
- If Prism's scope is stopped while foreign processes are still inside it, systemd may
  terminate them. Shutdown must move every process back first; the crash case needs a
  tested answer before acceptance.
- Open questions to settle in milestone 12: controller availability at each level on
  current distributions; behavior under GNOME and KDE; whether `memory.high` alone is
  the safer default offer.

Sources: kernel "Control Group v2" documentation; cgroups(7); systemd "Control Group
APIs and Delegation"; systemd.resource-control(5); systemd.scope(5); systemd v252
release notes.
