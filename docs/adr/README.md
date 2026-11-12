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
| [0002](0002-windows-process-control-and-job-objects.md) | Windows process control and Job Object limits | Accepted (decision 5 superseded by 0012 where they differ) |
| [0003](0003-linux-native-limits-with-cgroups-v2.md) | Linux native limits with cgroups v2 | Superseded by 0016 (never implemented) |
| [0004](0004-emulated-limiting-and-suspension-recovery.md) | Emulated limiting and suspension recovery | Accepted |
| [0005](0005-system-metric-semantics.md) | System metric semantics: CPU busy time and totals over physical devices | Accepted |
| [0006](0006-process-io-and-windows-virtual-size.md) | Process metric semantics: process I/O, and the Windows virtual size | Accepted |
| [0007](0007-per-user-folders-and-log-files.md) | Per-user folders and the log file | Accepted (Linux and macOS rules not yet run there; single-instance question resolved by 0013) |
| [0008](0008-bundled-typeface-and-icons.md) | Bundled typeface and icon set | Accepted |
| [0009](0009-theme-follows-platform-preferences.md) | The theme follows the platform through Scene preferences | Accepted |
| [0010](0010-unix-process-control-and-process-identity.md) | Unix process control and process identity | Accepted (run on Ubuntu x86_64 and macOS arm64 in CI) |
| [0011](0011-suspension-journal-and-recovery.md) | Suspension journal, recovery after a crash, and the emulated CPU limiter | Accepted (verified on Windows, Linux and macOS in CI; Windows performance exception) |
| [0012](0012-windows-native-limits-ledger-and-recovery.md) | Windows native limits: job lifecycle, the native-limit ledger, and recovery | Accepted (2026-10-10; run on Windows 11) |
| [0013](0013-one-prism-per-user-profile.md) | One Prism per user profile | Accepted (2026-10-10; resolves the open question of 0007) |
| [0014](0014-settings-file.md) | The settings file | Accepted (2026-10-13, with milestone 11) |
| [0015](0015-oom-guard-interventions.md) | OOM Guard interventions | Accepted (2026-10-13, with milestone 11; amends 0004 §3; Linux and macOS not validated) |
| [0016](0016-linux-native-limits-through-systemd-scopes.md) | Linux native limits through systemd transient scopes | Accepted (milestone 12; run on disposable processes in CI and on a local Ubuntu VM; not enabled in the product, K16) |
| [0017](0017-windows-application-image.md) | The Windows application image, its launchers and what guards them | Proposed (milestone 13) |
