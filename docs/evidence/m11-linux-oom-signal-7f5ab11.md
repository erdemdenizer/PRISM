# Linux memory signals: evidence for ADR-0015 §7, commit 7f5ab11

This file records the evidence that [ADR-0015 §7](../adr/0015-oom-guard-interventions.md) asks for
before OOM Guard's automatic interventions and the watched memory limit (LIM-9) may run on Linux.

**Outcome (2026-11-27).** The evidence was accepted (item 4 below) and the table was changed by commit
`308ecc9`: Linux is validated. Item 1 was met again on that commit itself (the last section).

## Source identity

| Fact | Value |
|---|---|
| Pull request | #37, draft, branch `feat/linux-oom-signal-evidence` |
| Tested commit | `7f5ab119aff264dd1a701f15166353a56f47a185` (`ci: record the runner's memory cgroup on Linux`) |
| Change against its parent | `.github/workflows/ci.yml` only: a read-only step that prints the runner's memory cgroup |
| Run | GitHub Actions `ci` run 45878821813, attempt 1, all jobs passed |

The commit that changes the table will be a child of this commit with no change to `platform` or
the tests in between, as item 1 requires.

## Item 1: the two tests ran and met their thresholds

Job `verify (ubuntu-latest)`: `./mvnw -B verify`, 2413 tests, 0 failures, 0 errors, 64 skipped,
BUILD SUCCESS. Runner: Linux X64, 4 processors, kernel `7.0.0-1020-azure`, Temurin 25.

Both suites ran; their surefire reports say `tests="1" errors="0" skipped="0" failures="0"`.

```
oom-signal: os=Linux total=15983MiB baseline=13393MiB M=512MiB fall=524MiB (1.02 M) recovery=542MiB (1.06 M)
oom-resident: os=Linux idle=54MiB M=256MiB first=311MiB second=311MiB gone=true
```

| Test | Threshold | Measured |
|---|---|---|
| `OomSignalValidationTest` | the available memory falls by at least half of M while M is held, and recovers by at least half of M after | fall 1.02 M; recovered past M / 2 (the test stops at the first reading beyond it, so the 1.06 M it prints is not a full recovery measured) |
| `OomResidentValidationTest` | resident below M / 2 before the allocation; at or above M in two listings after it; the record gone after the exit | 54 MiB before; 311 MiB and 311 MiB for M = 256 MiB; gone |

The same tests passed in the run of #35 (run 45870893123, fall 1.08 M, recovery 1.05 M, resident
311 MiB), and in milestone 11's runs ([m11-ci-cross-platform-3d6e4eb.md](m11-ci-cross-platform-3d6e4eb.md)).

## Item 2: the raw reports

In [m11-linux-oom-signal-7f5ab11/](m11-linux-oom-signal-7f5ab11/), copied unchanged from the
run's artifact `test-reports-ubuntu-latest`:

| File | SHA-256 |
|---|---|
| `TEST-prism.srm.platform.OomSignalValidationTest.xml` | `61cd2911a4449d9df628e8456d0713ab537dad91b2f8ac3f352aeb3598aeee9f` |
| `prism.srm.platform.OomSignalValidationTest.txt` | `06079b167da25b4958c2f93054393bf4ba8533ff38773a2373201f23a490dcdd` |
| `TEST-prism.srm.platform.OomResidentValidationTest.xml` | `525499f0c9d4ae0ae3ee8e42446035595bc048c26c96f81fce28f97f74a0ecef` |
| `prism.srm.platform.OomResidentValidationTest.txt` | `2b132da1a3aedf38217a2d29d214b387b8a7323619f97a0470c81fdbc9e05727` |

## Item 3: containers

The step `Memory cgroup of the runner (Linux)` of the same job printed:

```
cgroup: /system.slice/hosted-compute-agent.service
memory.max /system.slice/hosted-compute-agent.service: max
memory.max /system.slice: max
MemTotal:       16366628 kB
MemAvailable:   15344424 kB
SwapTotal:       3145724 kB
```

The tests ran in a cgroup with no memory limit, and none above it up to the root, so the host-wide
`MemAvailable` was the memory the tests could use. The evidence therefore says nothing about a
memory-limited container, and that stays a known limit of the gate (ADR-0015 §7, "Linux in a
container"): in a container with a cgroup `memory.max`, OOM Guard does not see that limit.

The tests' JVM ran in the same group: `SystemdReadOnlySmokeTest`, in the same job, prints
`own cgroup: /system.slice/hosted-compute-agent.service`.

## Item 4: acceptance

Given on 2026-11-27.
- **External review.** An independent read-only review of the evidence, the tests and OSHI 7.7.0's
  Linux reading (bytecode) accepted it with conditions. It hashed the copied reports against the run's
  artifact and found them equal.
- **Conditions:**
  - item 1 again, on the commit that changes the table, on top of the code it lands on (the platform
    code had changed since `7f5ab11`);
  - the code, tests and documents changed together;
  - the known limit of the kernel's and systemd-oomd's kills of PRISM itself (ADR-0015 §7);
  - the recovery worded as "past M / 2".
- **Product owner.** Accepted.

## Item 1 again, on the commit that changes the table

| Fact | Value |
|---|---|
| Commit | `308ecc9e727da4ec668670c1f9586e5e7cc2773e` (`feat(oom): validate the Linux memory signals...`), pull request #42 |
| Checked-out tree | `d8c13ce Merge 308ecc9 into 892ad54`; `308ecc9` descends from `892ad54`, so the tree is `308ecc9`'s |
| Run | GitHub Actions `ci` run 45911941676, attempt 1, job `verify (ubuntu-latest)`: 2421 tests, 0 failures, 64 skipped, BUILD SUCCESS |
| Runner | Linux X64, kernel `7.0.0-1020-azure`, Temurin 25 |

```
cgroup: /system.slice/hosted-compute-agent.service
memory.max /system.slice/hosted-compute-agent.service: max
memory.max /system.slice: max
MemTotal:       16365620 kB
MemAvailable:   15263360 kB
SwapTotal:       3145724 kB
oom-signal: os=Linux total=15982MiB baseline=13358MiB M=512MiB fall=545MiB (1.06 M) recovery=523MiB (1.02 M)
oom-resident: os=Linux idle=54MiB M=256MiB first=311MiB second=311MiB gone=true
own cgroup: /system.slice/hosted-compute-agent.service
```

Both suites ran (`tests="1" skipped="0"`) and met their thresholds: a fall of 1.06 M, a recovery past
M / 2, the resident memory at or above M in two listings, 54 MiB before. Raw reports, copied unchanged
from the run's artifact `test-reports-ubuntu-latest`, in
[m11-linux-oom-signal-308ecc9/](m11-linux-oom-signal-308ecc9/):

| File | SHA-256 |
|---|---|
| `TEST-prism.srm.platform.OomSignalValidationTest.xml` | `e9134a831cf1535dd3499885b5b7374cafaab642937d37b698bdf8a0ff9c23af` |
| `prism.srm.platform.OomSignalValidationTest.txt` | `10b85cc556fc9b61c9dc9680314f82408ded9d3da59c1f4e042953c52a812e6a` |
| `TEST-prism.srm.platform.OomResidentValidationTest.xml` | `88d1357f40a7c5516425b5a124ac70aa74eaf198ec55a5cf92a07114b6029cf8` |
| `prism.srm.platform.OomResidentValidationTest.txt` | `145ae8675efb5fd31f36d5c47548365a768c4d75fdf2cdce274ead9664973409` |

This record is committed after `308ecc9` and changes only documents, so `308ecc9` is the parent with
no change to `platform` or the tests that item 1 allows.
