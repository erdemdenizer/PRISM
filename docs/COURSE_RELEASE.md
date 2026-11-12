# Course release 2

This is the second stage of the staged release plan in [ROADMAP.md](ROADMAP.md). The first stage was
the milestone 5 checkpoint ([CHECKPOINT.md](../CHECKPOINT.md)). This stage contains everything the team
has built since: milestones 5.5 to 13 and the redesigned user interface. It is about 80 % of the
product; what remains is to bring Linux and macOS to the level Windows has reached (scope below).

## Where it comes from

Nothing in this release was written again for the course. It is the team's existing code, taken as it
stands:

| Part | Source |
|---|---|
| Product code, tests, build, ADRs, specification | The team's working repository at commit `12c49b6d259ef3fb5bda8e0e0b529b84a405e295` (2026-11-08): milestones 1 to 13, with milestone 13's functional completion. |
| The redesigned user interface | The UI redesign branch of the working repository at commit `bd9f5508edd61a3481fe3951dae954e2fea1b1ca` (2026-11-12). It is `12c49b6` plus 159 commits that change the user interface only. Its design documents are in [ui-lab/](ui-lab/), copied unchanged. |
| The evidence of acceptance runs | `docs/evidence/` of `12c49b6`, copied unchanged. It names commits of the working repository. |
| This repository's own history | The course repository's `main` at `b8dbbb5` (import of the checkpoint, the course documents, and the team's commits of 2026-10-22). Their changes are kept: see "Integration" below. |

## What works where

| Feature | Milestone | Windows | Linux | macOS |
|---|---|---|---|---|
| System metrics with 60 s histories; Overview and Performance pages | 1, 3, 4 | yes | yes | yes |
| Process list by identity; Processes page with details and inspector | 2, 5 | yes | yes | yes |
| Themes (light, dark, follow the system) and the redesigned interface | 5.5, 13, redesign | yes | yes | yes, not yet tested on macOS |
| Demo workload `prism-demo` | 6 | yes | yes | yes |
| Process control: End task, Force kill, Suspend, Resume, Priority | 7, 7.5, 8 | yes | yes (pidfd signals) | yes |
| Protection rules, Logs page | 7 | yes | yes | yes |
| Managed processes, emulated CPU limit, suspension journal | 9 | yes | yes | yes |
| Native (hard) CPU and memory limits | 10, 12 | yes (Job Objects) | implemented, switched off in the product (K16) | no |
| OOM Guard interventions and the watched memory limit (LIM-9) | 11 | yes | monitor, warn and log only | monitor, warn and log only |
| Settings page, shutdown hardening, one Prism per user profile | 10, 11, 13 | yes | yes | yes |
| Application image (jpackage) | 13 | yes | no | no |

The rows are features the user can see and use. Counted in features per system, about 80 % are
done; the missing part is on Linux and macOS.

## Left for the next stage

- Application images for Linux and macOS.
- The Linux native limits switched on, once desktop applications are validated with them (K16,
  [ADR-0016](adr/0016-linux-native-limits-through-systemd-scopes.md)).
- Validated memory signals on Linux and macOS, so that OOM Guard's interventions and the watched
  memory limit can run there.
- The redesigned user interface tested on macOS, and its Linux test round repeated with the fix of
  finding L-1 below.
- A review of accessibility with a screen reader, and high-DPI checks beyond 125 %.

## Changes made for this release

| What | Why |
|---|---|
| The data isolation and the dark first start of the UI redesign branch taken out, and its window title, launcher name and data folder names set back to the product's | That branch kept its data apart from the product's for testing. The release uses the product's own folders (on Windows `%LOCALAPPDATA%\Prism\SystemResourceManager`, on Linux the `prism-srm` folders of the XDG state and configuration homes, on macOS `~/Library/Application Support/PrismSystemResourceManager`) and follows the system's theme at the first start (UI-7). `verify-image.ps1` and the tests that name these folders follow. |
| `ProcessDock.computeMinWidth` (finding L-1 of the redesign's Linux test, with a regression test in `ProcessTableWidthTest`) | Long user names could keep the inspector beside the table in a narrow window, and the page scrolled sideways. The test fails without the fix and passes with it. |
| References to `lab-docs/` changed to `docs/ui-lab/` | The design documents moved with the release. |
| `.claude/`, `CLAUDE.md`, `.github/` and the redesign's spikes and screenshots not included | Workspace tooling and the working repository's CI configuration, as at the first checkpoint ([CHECKPOINT.md](../CHECKPOINT.md)); the CI workflows run against the working repository, and [TESTING.md](TESTING.md) says so where it names them. |

## Integration with this repository's history

The release was integrated onto `b8dbbb5` file by file, against the checkpoint import `ce36040` as the
common base:

- A file changed only by the team since the checkpoint is kept as the team left it.
- A file changed only by the working repository takes the release version.
- A file changed on both sides is merged; the course repository's change is kept. Files: `.gitignore`,
  `ProcessSource.java`, `OshiProcessSource.java`, `HostProcessProbe.java` (no conflict), and
  `THIRD-PARTY-NOTICES.md`, `docs/TESTING.md` (resolved by hand).
- `docs/MILESTONES.md` is the working repository's full text. The checkpoint (commit `42f9da3`) had
  abridged milestones 6 to 13 because they were outside it; they are part of this release.
- The course documents (`README.md`, `CHECKPOINT.md`, `docs/ROADMAP.md`, `docs/CORE_STRUCTURES.md`,
  `docs/DEMO.md`, `docs/TEAM_TASKS.md`) are kept as the team left them and edited only where the release
  changes what they describe.
- `src/main/java/prism/srm/ui/processes/CutTextTooltip.java` is not in the release: the working
  repository had removed it with milestone 7 (commit `70ecb5d`).

No commit of this repository is changed. The release is meant to arrive as new commits on a branch,
through pull requests ([TEAM_TASKS.md](TEAM_TASKS.md)).
