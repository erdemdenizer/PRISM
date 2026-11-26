# Linux memory signals: evidence for ADR-0015 §7, commit 7f5ab11

This file records the evidence that [ADR-0015 §7](../adr/0015-oom-guard-interventions.md) asks for
before OOM Guard's automatic interventions and the watched memory limit (LIM-9) may run on Linux.

**It changes no validation by itself.** `HostPlatform.oomSignalStatus` still reports Linux as not
validated. Item 4 of the ADR, acceptance by the external reviewer and the product owner, is open;
the one-line change of the table follows only after it.

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
| `OomSignalValidationTest` | the available memory falls by at least half of M while M is held, and recovers by at least half of M after | fall 1.02 M, recovery 1.06 M |
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

## Item 4: acceptance

Open. Needed from the external reviewer and the product owner before the change of
`HostPlatform.oomSignalStatus` and its test.
