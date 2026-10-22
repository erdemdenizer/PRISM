# Architecture decision records

One file per significant decision: a choice that is expensive to reverse, constrains
later work, or that someone will later ask "why?" about. Examples: a new dependency, a
change to the technology baseline, the threading model, how a resource limit is
enforced on an operating system, a coverage threshold.

- Copy [0000-template.md](0000-template.md) to `NNNN-short-title.md` with the next number.
- Keep it short: the context, the decision, the consequences.
- Records are not edited after acceptance except to mark them superseded; a changed
  decision gets a new record that links back.

| No. | Title | Status |
|---|---|---|
| [0001](0001-technology-baseline.md) | Technology baseline | Accepted |
| [0002](0002-windows-process-control-and-job-objects.md) | Windows process control and Job Object limits | Accepted |
| [0003](0003-linux-native-limits-with-cgroups-v2.md) | Linux native limits with cgroups v2 | Proposed (not yet run on Linux) |
| [0004](0004-emulated-limiting-and-suspension-recovery.md) | Emulated limiting and suspension recovery | Accepted |
| [0005](0005-system-metric-semantics.md) | System metric semantics: CPU busy time and totals over physical devices | Accepted |
| [0006](0006-process-io-and-windows-virtual-size.md) | Process metric semantics: process I/O, and the Windows virtual size | Accepted |
| [0007](0007-per-user-folders-and-log-files.md) | Per-user folders and the log file | Accepted (Linux and macOS rules not yet run there) |
