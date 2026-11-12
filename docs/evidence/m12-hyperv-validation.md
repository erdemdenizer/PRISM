# Milestone 12: Linux validation on the Hyper-V Ubuntu VM

This records what ran on the local VM `PRISM-Linux-Validation`. Full logs, Surefire reports and environment
captures of each round are in the delivery package named at the top of that round, not in this repository.

## The VM

Hyper-V Generation 2, Secure Boot (Microsoft UEFI CA), 8 GiB static memory, 2 vCPUs, a 60 GiB dynamic disk, the built-in
Default Switch. Ubuntu 24.04.5 Desktop from the GPG- and SHA-256-verified official ISO, kernel 7.0.0-38 (HWE), systemd
255.4-1ubuntu8.18, GNOME 46, Ubuntu's OpenJDK 25.0.4.1, cgroup v2 unified, a 4 GiB swap file, `systemd-oomd` active.
Everything runs as the unprivileged desktop user `prism` over a key-only SSH connection. It replaced a VirtualBox VM whose
whole guest froze for minutes under load; no freeze has been seen on this one.

## Round 1: 2026-10-16, commits a388cc0 and 151ab20

Package: `prism-m12-hyperv-linux-validation.zip`, SHA-256 `bdb1f2d534fbd26a1bd8b16d63c31815cbc215876d97d746238a79ab399a35e7`.

Every run used a fresh clone from a hash-checked Git bundle, with the commit and tree compared before Maven started.

| Run | Commit | Command | Result | Tests / failed / errors / skipped |
|---|---|---|---|---|
| stability-1 | none | idle, 2 vCPUs at 100 %, 3 GiB touched, 1 GiB fsync, user-bus latency | PASS: 0 clock gaps, host-guest drift at most 0.2 s | n/a |
| verify-1 | a388cc0 | `./mvnw -B clean verify` | PASS, 278 s | 2282 / 0 / 0 / 64 |
| native-1 | a388cc0 | native suites | FAIL: test-harness race, not the product | 12 tests, 2 failed |
| native-2 | a388cc0 | native suites | PASS, validator passed | 12 / 0 / 0 / 0 |
| native-3, 4, 5 | 151ab20 | native suites | PASS, validator passed, 3 of 3 | 12 / 0 / 0 / 0 each |
| verify-2 | 151ab20 | `./mvnw -B clean verify` | PASS, 266 s | 2284 / 0 / 0 / 64 |

The 64 skips: 53 gated to Windows or macOS with `@EnabledOnOs`, and 11 `LinuxNativeLimitIntegrationTest` tests that skip
themselves without `-Dprism.linux.native.required=true` and ran in the native runs instead.

**The race of native-1.** `LinuxHarness.Started.await` could see the process ended before the reader thread had queued the
last output line, and returned "no line". A test failed without registering its child for cleanup, so its `sleep 300`
stayed in a Prism scope, with the limits already cleared, until it exited by itself; `SystemdReadOnlySmokeTest` then failed
on that leftover scope. `LinuxHarnessAwaitTest` reproduced the race before the fix. `151ab20` changes only test code:
"ended silently" now needs the process and the reader to have ended.

**Cleanup after each passing run.** No Prism scope right after the run or 3 s later; user units unchanged apart from
Prism scopes (331 of 331, no difference); no Java process of the run left.

**On real systemd.** `cpu.max` read `2000 100000`, `40000 100000` and `200000 100000` for 1 %, 20 % and 100 % on 2 vCPUs;
`memory.high` and `memory.max` read 134217728 for 128 MiB; every scope had an invocation id; systemd answered
`NoUnitForInvocationID` and `UnixProcessIdUnknown` where the tests expected them.

**Limitation: no OOM kill here.** With 4 GiB of swap, `memory.max` of 128 MiB was reached 1061 times and the excess went to swap
(`oom_kill=0`, `peak_anon=133050368`, swap about 282 MB); the allocator and its sibling stayed alive, and the test accepts
that. The OOM kill path, with the sibling surviving and the scope staying active, was seen only on the swapless VirtualBox VM
at c85525a and at a388cc0 (ADR-0016, "Final verification"). Swap was not disabled to force it.

### GNOME lifecycle observations

**These are observations, not a validation.** The owner logged in to GNOME (Wayland, seat0). A disposable GTK 4 probe wrote its
identity, window events and a child's signals to a log. No process was moved into a scope, no unit property was changed, no real
application was touched. Prism's own window was not run. A DBusActivatable launch was not tried.

| Path | Where the probe's process landed | Its child |
|---|---|---|
| A/B: GNOME Terminal, `bash -c` | `gnome-terminal-server.service` in 3 of 3 tries; bash itself in `vte-spawn-*.scope` | Ended by SIGHUP when bash exited |
| C: terminal, child in a new session, ignoring SIGHUP | `gnome-terminal-server.service` | Lived on, reparented to `systemd --user`; stayed in that service's cgroup while the service read `inactive (dead)` |
| D: `gtk-launch` from an SSH session | The SSH logind session scope, no app scope | Lived on, reparented to PID 1 |
| E: GNOME Shell, `Exec=sh -c ...` wrapper | The Shell made `app-gnome-*.scope`, holding only `sh`; the application and child stayed in `org.gnome.Shell@wayland.service` | Lived on in the Shell's service |
| F: GNOME Shell, direct `Exec` | The application and child both in `app-gnome-*.scope` | The scope stayed active with the child after the application exited |

What follows from them:
- GNOME Shell and VTE create their scopes asynchronously, so processes forked before the move stay in the launcher's own service.
- A unit's state does not stand in for membership: a service can read `dead` with a live process in its cgroup.
- Applications inherit the Shell's `INVOCATION_ID`, so that variable proves nothing about an application.

They support K16 and the membership rules. They do not authorize adopting desktop applications.

## Round 2: 2026-11-07, commit 3b3475b

The same VM, not rebuilt. Commit `3b3475b5baa85abbdf3a81d78eb84bd945cfc360`, tree `e3f1d27f9dbc71576a9d7670da986855189cb7b3`, each run
from a fresh clone of a hash-checked bundle, as in round 1. The [acceptance matrix](m12-acceptance-matrix.md) maps these to the criteria.

| Run | Command | Result | Tests / failed / errors / skipped |
|---|---|---|---|
| verify-7 | `./mvnw -B clean verify` | PASS, 328 s; Spotless passed, SpotBugs 0, `ArchitectureTest` 28 of 28 | 2287 / 0 / 0 / 64 |
| native-9, 10, 11 | native suites, `linux-native-validation.mjs` | PASS, 3 of 3, validator passed | 12 / 0 / 0 / 0 each |

An earlier candidate, 70b9b6d, gave the same results (verify-6, native-6 to 8); the only change to code after it is one test.

**Cleanup after each run.** No Prism scope 3 s later; user units 249 before and 249 after; no Java process of the run left; no clock
gap; no kernel lockup line. The host monitor's lowest readings were 8.0 GiB free memory and 7.0 GiB free commit, above its limits.

**Differences from round 1.**
- 2287 tests, against 2284: three added for the scope-membership rule. The 64 skips and the native suite have the same size.
- The environment is unchanged: kernel 7.0.0-38, systemd 255.4-1ubuntu8.18, OpenJDK 25.0.4.1, 4 GiB of swap.
- The count of user units is 249 where it was 331. The likely reason, not checked, is that no graphical session was logged in this time. Before and after still match.
- The same swap outcome: `oom_kill=0`, `memory.max` reached 1069 times in native-9, the allocator and sibling alive. **The OOM kill is still not observed here.**
- No GNOME observation was repeated. Those of round 1 stand: the probe is separate from Prism's code.

**A fault of the runner, not the product.** In the 70b9b6d runs, the host script's `tar` read `D:/...` as a remote host, so the
reports were not unpacked and the validator said "no report". Unpacked by hand, all three passed; the script now passes `--force-local`.
The 3b3475b runs used the corrected script.

Full logs are in the delivery package of this round.

## Final candidate: 2026-11-07, commit f0f4279

Package: `prism-m12-nonmacos-closure-handoff.zip`, SHA-256 `2c09847a6320f82ad81302eba1b1b3e2ee1e94107a52caf3195f9a8d6090ef8d`, folder
`03_EVIDENCE/linux-f0f4279/`.

The same VM, not rebuilt. Commit `f0f42799c672f6650b494d74a085e4ea5fcaa68b`, tree `b3340ad58a3f742359acd09d6b23b84cd8c65ae7`, each run from
a fresh clone of a hash-checked bundle. Each run's `source-identity.txt` shows the expected commit and tree equal to the actual ones and
0 uncommitted files.

| Run | Command | Result | Tests / failed / errors / skipped |
|---|---|---|---|
| verify-9 | `./mvnw -B clean verify` | PASS, 347 s; Spotless passed, SpotBugs 0, `ArchitectureTest` 28 of 28, `CpuLimitIntegrationTest` 8 / 0 / 0 / 0 | 2287 / 0 / 0 / 64 |
| native-15, 16, 17 | native suites, `linux-native-validation.mjs` | PASS, 3 of 3, validator passed | 12 / 0 / 0 / 0 each |

Since 3b3475b only two test files and documents changed, so round 2 and the run at 963d68a (verify-8, native-12 to 14) are earlier results
of nearly the same code. They are not results of this commit, and this section does not rely on them.

**Cleanup after each run.** No Prism scope 3 s later; no Java process of the run left; no clock gap; no kernel lockup line. User units were
257 before and 257 after in each native run. In verify-9 they were 249 before and 257 after: a loop device and a mount for `snap-store`
revision 1468 appeared, which points to a snapd refresh during the run. None of the eight is a Prism unit, and the suite's own check inside
that run found 249, 249 and no change. The host monitor's lowest readings were 7.16 GiB free memory and 6.37 GiB free commit.

**Values in native-15, on 2 vCPUs.**
- CPU: 0.020 cores at 1 % (quota 0.020); 0.991 at 100 %, one busy worker; 0.401 under a quota of 0.400, throttled in 30 periods; 1.000 after release.
- Memory: `memory.max` reached 1047 times, `oom_kill=0`, `peak_anon=133349376`, swap 273178624 bytes, the allocator and sibling alive.
  `memory.events` counted `high=626` in the `memory.high` test. **The OOM kill is still not observed here.**
- The session holding a limit killed (exit 137): the process stayed at 0.400 cores, and reached 0.991 after clearing.
- The pidfd of a reaped process: `StartTransientUnit` refused with `UnixProcessIdUnknown`, and `GetUnit` answered `NoSuchUnit` afterwards.
- A process in `session-87.scope` was refused as running outside the session manager.

No GNOME observation was repeated. The environment is the one of round 2.
