# About this checkpoint

This repository is the **milestone 5 checkpoint** of PRISM, prepared for the Operating Systems
course. It is an authentic snapshot of the team's project as it stood on **2026-10-07**, taken
from commit `caba4429bd4556becf9365fde35734e4755c33bc` ("feat: implement read-only processes
view") of the team's working repository.

## What this means

- The source under `src/`, the Maven build (`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`), the
  quality configuration (`config/`, `.editorconfig`, `.gitattributes`) and the documents under
  `docs/` are as they were at that commit, with the exceptions listed below.
- The history of this repository starts with one import commit. The working repository's own
  history for milestones 1 to 5 (nine feature and documentation commits from 2026-10-05 to
  2026-10-07, all authored by `erdemdenizer`) is not replayed here.
- Development has continued in the team's working repository since this snapshot. The
  modules that [docs/ROADMAP.md](docs/ROADMAP.md) lists as "outside this checkpoint" are not
  part of this repository and are not claimed to be unstarted; they will be published here in
  stages, at the course submissions, together with their tests and documentation.

## Changes made for the course repository

| What | Why |
|---|---|
| `README.md` rewritten | The README of that commit still described the repository as "engineering foundation only", which was out of date already then. |
| Added `CHECKPOINT.md`, `THIRD-PARTY-NOTICES.md`, `docs/ROADMAP.md`, `docs/CORE_STRUCTURES.md`, `docs/DEMO.md`, `docs/TEAM_TASKS.md` | Course deliverables: repository notes, dependency licenses, roadmap, explanation of the core structures, demo guide, task distribution. |
| Removed the `.claude/` directory and `CLAUDE.md`; removed the paragraphs that referred to them from `docs/TESTING.md` and `.gitignore` | Workspace tooling of the development environment (editor hooks, permission rules, review checklists). It is not part of the project's source or build. Removing it is a workspace clean-up, not a statement about how the project was developed. |
| `docs/MILESTONES.md`: sections 6 to 13 abridged to their objectives | The detailed plans of later milestones belong with the code that implements them. Sections 1 to 5 are complete, including what was decided and measured while building them. |

Everything else is unchanged. In particular `docs/PRODUCT_SPEC.md` is the full product
specification (it describes the whole product, not only this checkpoint), and the ADRs 0002,
0003 and 0004 are design decisions written in advance of the modules they concern; their
status lines reflect 2026-10-07.

## Verified on 2026-10-22 (Windows 11, Temurin JDK 25.0.4, this snapshot, clean directory)

| Check | Result |
|---|---|
| `.\mvnw.cmd -q compile` | success, 9 s |
| `.\mvnw.cmd clean verify` | BUILD SUCCESS in 54 s: 665 tests, 0 failures, 0 errors; Spotless and SpotBugs clean |
| `.\mvnw.cmd -q test -Dtest=HostMonitoringProbe` | passed (10 samples, 12 s) |
| `.\mvnw.cmd -q test -Dtest=HostProcessProbe` | passed (14 cycles, 29 s; the child JVM appeared and disappeared; 4 busy threads showed as 25.4 % of 16 logical processors) |
| `.\mvnw.cmd javafx:run` | window "Prism System Resource Manager" up 12 s after the command, monitoring started (system every 1 s, processes every 2 s), window closed after 30 s, controlled shutdown logged, JVM and Maven exited with code 0 |

Linux and macOS: not run. The code paths for those systems are unit-tested with injected
environments only and are reported as unverified, as the documents under `docs/` say.
