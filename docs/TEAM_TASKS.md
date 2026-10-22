# Team, work so far, and task distribution

Team Prism: [erdemdenizer](https://github.com/erdemdenizer),
[erencatal](https://github.com/erencatal), [enessen0](https://github.com/enessen0).

## Table 1: work completed so far (supported by evidence)

| Item | Evidence | Member |
|---|---|---|
| Engineering set-up: Maven build with the quality gate (enforcer, lint as error, Spotless, SpotBugs, JaCoCo), architecture test, documentation structure | working-repository commits `f686ab4`, `a0b442e`, `adc2b2e` (2026-10-05) | erdemdenizer |
| Product specification and milestone plan (`docs/PRODUCT_SPEC.md`, `docs/MILESTONES.md`) | commit `a0b442e` (2026-10-05) | erdemdenizer |
| Milestone 1: system metrics backend and snapshot model | commit `c935f4e` (2026-10-05) | erdemdenizer |
| Milestone 2: process snapshot backend, identity, enumeration cost measurement | commit `a747397` (2026-10-05) | erdemdenizer |
| Milestone 3: application shell and Overview | commit `ae6b777` (2026-10-05) | erdemdenizer |
| Milestone 4: Performance page | commit `096a21a` (2026-10-06) | erdemdenizer |
| Milestone 5: Processes page (read-only) | commit `caba442` (2026-10-07), the commit this checkpoint is taken from | erdemdenizer |
| Verification of this checkpoint on 2026-10-22 (full gate, probes, application run) | [CHECKPOINT.md](../CHECKPOINT.md) | erdemdenizer |

Statement on authorship: every commit of milestones 1 to 5 in the team's working repository
is authored by `erdemdenizer`. Contributions of the other members that may exist outside
Git (discussion, testing on their machines, review) are not recorded in this repository and
are **not claimed here**; the members will state them themselves at the meeting. From this
checkpoint on, every contribution is made through this repository (section "How
contributions are recorded"), so the record is visible to everyone.

## Table 2: proposed responsibilities for the next interim submission

These are **proposed future responsibilities**, not completed work. The scope follows
[ROADMAP.md](ROADMAP.md): milestone 6 (demo workload) and the first stage of milestone 7
(process actions on Windows), built on the monitoring of this checkpoint.

| Member | Area | Deliverable | GitHub issue (to open) | Acceptance criteria | Depends on | How the contribution is shown |
|---|---|---|---|---|---|---|
| erdemdenizer | Architecture, integration, repository coordination | (a) Repository set-up: branch rules, issue labels, pull-request template, review rotation. (b) Milestone 6 demo workload: a separate `prism.srm.demo` program with controllable CPU load and memory use, bounded by its arguments. (c) First stage of process control: the `ProcessController` interface in `platform`, its `OperationResult` and capability report in `domain`, and the Windows implementation of End Task / Force Kill / Suspend / Resume behind it. | `#1 Repository workflow`, `#2 Demo workload (DEMO-1..3)`, `#3 ProcessController and Windows implementation (CTL-1..5)` | `verify` green; the demo workload shows in the Processes table at the requested CPU share and exits by itself; each action returns an explicit result (`SUCCESS`, `ACCESS_DENIED`, `PROCESS_NOT_FOUND`, ...); PRISM itself and protected processes are refused before any native call; `ArchitectureTest` still passes. | — | Pull requests reviewed by a teammate; the demo in the meeting. |
| erencatal | Process-monitoring backend testing, lifecycle and edge-case coverage, process-control safety verification | (a) Test review of `ProcessSampler`, `ProcessMonitor`, `SamplingLoop` and `ProcessDetailsService` against the lifecycle: process appears, is seen once (no CPU value), PID reused by a new process (no delta across identities), process vanishes mid-enumeration, source throws, listener throws. Missing cases get tests. (b) The safety rules of process control as a unit-tested policy (protection rules, result-to-message mapping) against a fake controller, including "process exited between the check and the action". (c) A short written verification of the probes on a second Windows machine. | `#4 Lifecycle and edge-case coverage of the process monitor`, `#5 Process-control safety rules and their tests`, `#6 Probe run on a second machine` | Each new test can fail (demonstrated by a deliberate break during review); the policy tests cover every protection rule in `PRODUCT_SPEC.md` section 10; the probe report is in `docs/`. | (b) needs the `ProcessController` interface from erdemdenizer's issue #3 (the interface can be agreed first, before the Windows implementation exists). | Pull requests with tests; the review comments on #3; the probe report. |
| enessen0 | JavaFX process-action interface, error feedback, documentation, reproducible demo | (a) The actions on the Processes page: context menu and buttons for End Task, Force Kill, Suspend, Resume, enabled or disabled from the capability report, with confirmation for destructive actions. (b) Error feedback: every result, including "access denied", shown in the status bar or details pane in plain words, with the same text logged. (c) Documentation: user guide for the Processes page and the actions; keep `DEMO.md` reproducible for the next demonstration, with the demo workload as the only target. | `#7 Process actions in the UI (UI-2 actions, CAP-1..3)`, `#8 Result and error feedback`, `#9 User guide and demo script` | Snapshot tests of the page with actions enabled, disabled and after a failed action, in both window sizes; a fake capability report with different levels changes the UI without code changes; the demo runs end to end from the guide on a fresh clone. | (a) needs the view-model contract (capability report, result type) from issue #3; the UI can be built against a fake first. | Pull requests with snapshots; the user guide; running the demo in the next meeting. |

Balance: each member has one deliverable that does not depend on anyone (erdemdenizer's demo
workload, erencatal's monitor tests, enessen0's documentation and demo), and one that
integrates with the others through an interface agreed at the start.

## How contributions are recorded from now on

- One issue per deliverable, assigned to the member; the issue number in every commit message
  and pull-request title.
- Work happens on a branch named after the issue (`feat/3-process-controller`); it is merged
  through a pull request reviewed by at least one other member. Reviews are contributions too
  and are visible in the pull request.
- Commits are authored by the person who wrote the change, under their own GitHub account;
  nobody commits under another member's name.
- The lecturer's requirement is that *what each member has accomplished and will deliver* is
  clearly defined; it does not say that every member must have a commit in the first week.
  After the next interim submission, each member's issues, pull requests and reviews are the
  record.

## Open points for the team to confirm before the meeting

- Each member's contributions so far outside Git, if any, stated in their own words.
- The GitHub account through which the lecturer will access the repository (collaborator
  invitation) or the decision to make the repository public.
- Which member will speak to which part of the presentation (see the meeting script).
