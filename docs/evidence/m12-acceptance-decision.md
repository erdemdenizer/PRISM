# Milestone 12: acceptance decision (2026-11-07)

**Decision: ACCEPTED WITH K16 ACTIVE AND THREE DOCUMENTED EXCEPTIONS.** The product owner gave the final approval on 2026-11-07,
after an independent review of the package `prism-m12-acceptance-docs-review-rev2.zip`. This acceptance does not enable Linux native
limits for real user processes.

An exception is not a pass. Each one below is stated as it is.

| Item | Value |
|---|---|
| Final candidate | Commit `f0f42799c672f6650b494d74a085e4ea5fcaa68b`, tree `b3340ad58a3f742359acd09d6b23b84cd8c65ae7` |
| Final approval | Given by the product owner |
| Date of the final approval | 2026-11-07 |
| ADR-0016 | Accepted |
| ADR-0003 | Superseded by ADR-0016. It was never implemented |
| K16 | Active |
| Merge of PR #7 | Not approved. It needs its own approval |

The owner also accepted the PASS result of the macOS run, without a rerun.

## Decisions

| # | Decision | Result |
|---|---|---|
| 1 | M12 is accepted on condition that K16 stays active | Accepted. K16 is active |
| 2 | ADR-0016 becomes Accepted and ADR-0003 Superseded at the final acceptance | Both status lines changed with this decision |
| 3 | The missing Linux memory text is deferred, with its scope documented | Exception 1 below. The criterion is not met |
| 4 | The earlier swapless evidence for the OOM kill is enough, only for M12 with K16 active | Exception 2 below. The kill was not observed at the final candidate |
| 5 | The hosted `native-validation` failure stands as an open, documented exception | Exception 3 below. The check fails and stays visible |
| 6 | PR #7 is not merged for now | Not merged. The acceptance of M12 does not approve the merge |

## K16 is an active safety restriction

In the current product K16 is in force: `HostPlatform` gives the product a limiter that admits no process, opens no bus and
clears nothing, and no setting, system property or environment variable changes that. Every process reports Linux native limits
as `UNAVAILABLE`. A Linux user gets the emulated CPU limit, and the memory limit stays `UNAVAILABLE`.

K16 can be lifted later only after separate validation and with the product owner's explicit approval. The conditions are in the
[risk register](m12-risk-register.md), "Before K16 can be lifted". Accepting M12 does not lift K16 and is not that approval.

## What the acceptance covers

- **The Linux native-limit mechanism of ADR-0016, on disposable processes the test harness starts:** CPU quota through `cpu.max`,
  memory through `memory.high` and `memory.max` with separate consent, adoption by pidfd only, limits addressed to the scope's
  invocation id, proven clearing and read-only recovery, the scope-membership rule, ledger format 2 with scope provenance.
- **K16 as above.**
- **The gates at the final candidate.** Evidence by criterion and by system is in the
  [acceptance matrix](m12-acceptance-matrix.md), the Linux runs in [m12-hyperv-validation.md](m12-hyperv-validation.md).
- **The register's summary** that no BLOCKING risk is open, with F1 and F2 fixed: [m12-risk-register.md](m12-risk-register.md).

It does not cover native limits for real user processes or applications, Prism's own window on a Linux desktop, or anything under
"Before K16 can be lifted".

## Three documented exceptions

One written criterion is deferred. The other two are not written criteria of milestone 12: they are limits of the evidence, stated
so that nobody reads more into it. None of the three is a pass.

| # | Kind | What is missing | What stands for it | Limit |
|---|---|---|---|---|
| 1 | Written criterion, deferred | "The text for `memory.max` and `memory.high` matches the kernel's documented behavior" is not met. See the scope below | K16 keeps the text unreachable on Linux | Deferred until K16 is lifted. Must be closed before that (register D8) |
| 2 | Limit of the evidence, not a written criterion | The OOM kill under `memory.max` was not observed at the final candidate: on the Hyper-V VM 4 GiB of swap took the excess (`oom_kill=0`, allocator and sibling alive, native-15), and swap was not disabled to force it | The kill, with the sibling surviving and the scope staying active, was seen on the swapless VirtualBox VM at c85525a and at a388cc0 | Enough only for M12 with K16 active. It does not count as evidence for lifting K16 |
| 3 | Open CI exception, not a written criterion | Hosted `native-validation` fails in run 42319242944, and the workflow concludes `failure`. The hosted Windows runner runs inside a Job Object, so 10 of the 11 Windows integration tests skip themselves and the check refuses that | Path C on Windows at the final candidate: 20 of 20 tests, none skipped, `native-validation.mjs` passed. The policy of milestones 10 and 11 | Not a PASS. The check stays enabled, with no `continue-on-error`. Nothing was removed or relaxed |

**Scope of exception 1.** `LimitMessages.memoryLimited` says a process "may commit at most" the limit, that a process reaching it "cannot
allocate memory and may fail or crash", and that its children each get the same limit. `memoryRemoved` says "It stays in its job
until it exits." That is Windows Job Object behaviour and is wrong for a systemd scope. Windows is unaffected. On Linux the text is
reachable only through a native memory limit, and K16 admits none. Text for `memory.max` and `memory.high` has not been written.

## macOS run: accepted procedure deviations

The run was on the macOS test machine (local user: kerem). The owner accepted these two deviations from the procedure.

| The procedure asked for | What happened | Evidence |
|---|---|---|
| Mains power | Battery, 74 %, discharging, kept awake with `caffeinate` | The run report and `machine.txt` in the evidence archive |
| Temurin JDK 25 found through `/usr/libexec/java_home -v 25` | Temurin 25.0.5+8 unpacked locally, with no system-wide install, and `JAVA_HOME` pointed at it directly | Archive: Temurin 25.0.5+8, enforcer passed |

The run report adds that `java_home -v 25` on that machine returns Java 1.8.0_491. That statement is not in the archive and was not
checked independently. The enforcer stops a build on a wrong JDK, so at most the selection would have gone unnoticed, not the build.

## Authorship and records

- The macOS evidence comes from the macOS test machine (local user: kerem). The local account name is recorded as it stands; no
  owner or contributor is inferred from it.
- A draft of this acceptance, commit `8af9e3f`, was prepared on that machine. It is not in this repository and was not applied: it
  stated an acceptance that had not been given, with a fixed date. These documents were written anew on `f0f4279` from the evidence.
- The commit that holds this decision carries the product owner's Git identity and no borrowed author.
- The Windows `summary.txt` shows a `java version "26"` line, but the Windows gate ran on Temurin 25.0.4.1. The evidence file stays as it is; the
  [acceptance matrix](m12-acceptance-matrix.md), "Which JDK ran the Windows gate", explains the difference.

## Still open after the acceptance

- **The merge of PR #7** needs its own approval. The acceptance is recorded on the branch, and `main` does not have milestone 12 yet.
- **The commit holding this decision has not been tested.** It changes documents only and no test or build step reads them.
- **The description of PR #7** still says the macOS run has not happened. It has to change before the merge.
- **D12:** the internal label "K16" appears in the activity-log line from `limitations()`. For the wording pass of milestone 13.
- **D15:** three races in old tests appeared in five hosted runs, all test faults, each fixed without weakening an assertion. More
  may remain.
- **The macOS procedure** should require that `java -version` says Temurin 25 after `JAVA_HOME` is set.
- **Packaging and the classpath** (milestone 13): the register's limit of the K16 claim depends on there being no `module-info`.
- **The `CLAUDE.md` recovery sentence** reads as though Prism resumes something by itself, which LIFE-4 denies. It is a project rule
  outside M12, left to the owner.

When this decision was recorded, no push, merge, tag or release had been made for it.

## Later note, 2026-11-07

Added after the decision. The text above stays as it was recorded, so its statements that PR #7 is not merged and that `main` does not
have milestone 12 describe that moment.

With the separate approval of the product owner, PR #7 was merged on 2026-11-07 by the merge commit
`9f4ec7b9e7634a08269e94d05b53ea22d1af81bb`. Its tree, `d35648485f3c97c8ab47f7167d7009708e020731`, is the tree of `96884a3`, the commit that
holds this decision. In run 42374199438 on `main` the three `verify` jobs and `linux-native-validation` passed and `native-validation` failed:
exception 3, tracked as [TD-1](m13-technical-debt.md). K16 stays active. No tag or release was made.
