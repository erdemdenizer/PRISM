# Milestone 13: functional verification of WP1, WP3 and WP6

Evidence for the functional stage that the owner ordered on 2026-11-07 (K11, K12, and the change of priority in
[m13-decisions.md](m13-decisions.md)). It is not an acceptance: the owner decides. The title bar of WP2 and visual polish
are outside this stage, and nothing here says that UI-7 is met.

Verdicts: PASS has the evidence named in its row. FAIL, BLOCKED and NOT TESTED are what they say. INFO is an observation,
not a verdict.

## What was verified, and on which code

| Item | Value |
|---|---|
| Code | Commit `65be4d3` on `feat/m13-settings-shutdown-packaging`, local, not pushed |
| Full gate | `./mvnw clean verify` on that tree, Windows 11 Pro 10.0.26200, Temurin 25.0.4.1: PASS, 08:37 min, 2351 tests, 0 failures, 0 errors, 37 skipped; javac compiled 348 and 269 source files; SpotBugs 0 |
| Image | Built right after the gate from the same tree: `prism-0.1.0-windows-x64.zip`, 49 MiB, SHA-256 `07571808ec02dc03124346c44d62e8ce916010b5648de366af71e10a9b07f7cd` |
| Clean run | `verify-image.ps1 -Environment clean` in Windows Sandbox: **IMAGE ACCEPTED**, PASS 140, INFO 17, nothing else |
| Host run | `verify-image.ps1 -Environment host` on the development machine: **HOST CHECKS PASSED**, PASS 135, INFO 18, NOT TESTED 2 (both by design, below) |
| Settings scenes and snapshots | `-Pui-snapshots`, `SettingsSnapshotTest` 40 of 40 and `SettingsPageSceneTest` 7 of 7, PASS. The 40 images were not reviewed: visual quality is not a criterion of this stage |
| Hook tests, CI script tests | 326 of 326, 8 of 8, PASS |

The raw results are not in this repository. They are in the handoff package of this stage.

## Clean environment and host, kept apart

| | Clean | Host |
|---|---|---|
| System | Windows Sandbox, Windows 11 Enterprise build 26100, 16 processors | Windows 11 Pro build 26200, 16 processors |
| Culture | `tr-TR` | `tr-TR` |
| JDK | None: 0 `java.exe` on the original PATH, no `JAVA_HOME`, no registry key, none under the program folders | Present, as expected: a host result says nothing about a machine without a JDK |
| Folder windows, system theme | Opened and changed, in a machine thrown away afterwards | Not touched: `settings/open-folders` and `system-theme/follows-the-system` are NOT TESTED there by design |
| Disk and Network on Overview | Both `Unavailable` | Both show values |

The Disk and Network cards of the Sandbox are an observation of that environment. They are not classified as a success or
a failure of the product: the same image reads both on the host. The Sandbox configuration turns networking off, and in
phase 1 its storage query (`MSFT_Disk`) failed there. They are no evidence about another clean machine either.

## K11: what the package tests prove

Every row is a result of an action, measured from outside PRISM, not the state of a button. Values are clean / host.

| Owner's demand | Check | Clean | Host |
|---|---|---|---|
| One real process | One `PRISM.exe`, no child, the window in the started process, `jvm.dll` from the image, not in a job | PASS | PASS |
| One real process, demo | `prism-demo` works in the process it started, outside any job until PRISM limits it | PASS | PASS |
| Own process cannot be suspended | Request made through the window; the log holds exactly one refusal; 0 threads suspended | PASS, 0 of 88 | PASS, 0 of 84 |
| Own process cannot be ended | End task and Force kill confirmed in their dialogs; refused, logged once each; process alive | PASS | PASS |
| Own process cannot be managed | Manage chosen in the context menu; refused and logged; no Limits row | PASS | PASS |
| The same, with no window in between | The image's runtime asked directly for suspend, end, kill, priority and manage on itself: `ACCESS_DENIED`, alive | PASS | PASS |
| Native CPU limit on the delivered demo | 20 % before, then 5 % limit measured | 19.7 %, 4.5 % | 20 %, 5.5 % |
| Limit removal | Measured after removal | 19.9 % | 20 % |
| Native memory limit on the delivered demo | Reported Native hard and logged; its removal logged | PASS | PASS |
| Native memory limit enforced | A process growing in steps of 16 MiB under a 512 MiB limit: refused at the limit, committed memory, growth after removal | 352 MiB held, 506 MiB committed, 768 MiB after | 352, 511, 768 |
| Closing clears native limits | CPU after close; a process stopped at the memory limit grows to 768 MiB once PRISM has closed | 19.8 %, PASS | 20 %, PASS |
| Closing | Exit code 0 every time, in about 0.4 s | PASS | PASS |
| Second launch | A dialog that says Prism is already running, no main window from the second process, exit 0, the first runs on | PASS | PASS |
| Second launch while the first closes | Never two main windows; a later start works | PASS | PASS |
| Modules | Probe on the image runtime: `jdk.localedata` present; `1.234.567,5`, `%25`, `15 Ocak 2026`, `İ` | PASS | PASS |

The memory enforcement row runs on `prism-probe`, a test copy of the delivered demo launcher with the same binary and
runtime and its own main class, because the delivered demo allocates at start and cannot be told when to grow
([ADR-0017](../adr/0017-windows-application-image.md)).

## WP3: shutdown and recovery on the image

| Requirement | Check | Clean | Host |
|---|---|---|---|
| LIFE-1, emulated limit released at close | Close requested while the limiter held 26 (host 27) threads; 0 suspended within 3 s; load back | PASS | PASS |
| LIFE-2 | The user's suspension, 26 of 26 threads, stays after close and after the next start | PASS | PASS |
| LIFE-6, user suspension | Next start lists it, resumes nothing; resumed only on request, then the load is back | PASS | PASS |
| LIFE-4, native limit | Hard kill: the limit stays (4.5 %, host 5.3 %); next start reports it in the log and changes nothing | PASS | PASS |
| LIFE-4 and LIFE-6, emulated | Hard kill while 26 of 26 threads held: next start resumes nothing, lists it for manual intervention with the reason, offers no resume (the entry records no threads) | PASS | PASS |
| LIFE-5 | Ctrl+C through a console test launcher while the limiter held the process: exit 130, limiter's hold released, the user's 23 (host 26) threads stay suspended | PASS | PASS |
| Closing at start-up, with a dialog open, during a save | Exit 0; the settings file whole and read by the next start | PASS | PASS |

In unit and integration tests, at `65be4d3`:

- The order of LIFE-1 is pinned in one test; a failure injected at each of the 19 steps leaves the rest running, and Prism
  ends (`MonitoringRuntimeShutdownTest`, 20 tests). The shutdown is asked for only once the limiter holds its process.
- A defect was found and fixed in this stage (`ec6f4f0`): if the step that releases the automatic suspensions failed or ran
  out of time, the last step let go of what the limiter still held, and the process stayed frozen after Prism closed. The
  last step now releases it first. A resume that throws there is tried once more and reported; if both throw, nothing is let
  go of and the exit hook still finds it (`SuspensionCoordinatorTest`).
- A sampling cycle stuck in a native call does not keep a real JVM alive: with a bound of 1 s it ended with exit 0 within
  4.5 s, under the 5 s the application uses (`ShutdownRealProcessTest`).
- `logback.xml` flushes every line, and has no hook of its own that could stop logging before Prism's own last line
  (`LoggingConfigurationTest`).

## WP1: Settings

| Requirement | Evidence | Status |
|---|---|---|
| Appearance chosen and kept (SET-1, UI-7) | Image, both environments: the file says DARK at once while PRISM runs; Dark selected after a restart | PASS |
| System follows the operating system while running (UI-7) | Clean only: page brightness 0.928 to 0.164 after the system's notification | PASS, clean only |
| Locations of logs and settings, opened in the file manager | Clean: each button opens File Explorer on its folder. A first version opened a web browser; found in the Sandbox and fixed in `d63b8a5` | PASS, Windows only |
| Damaged settings file (SET-1) | Image: moved aside once, defaults used, the page says so; units for unreadable, newer and failed-save files | PASS |
| File of a later version | Image: defaults used, not overwritten by a choice, not changed by the close | PASS |
| Capability summary | Image: each action with its level and mechanism, as the platform reports it; units for emulated, watched, unavailable and unreported | PASS |
| No switch for automatic termination; migration never enables it (SET-2) | Units | PASS |
| Keyboard | Scene test: the arrow moves the focus to the next choice, Space chooses it | PASS, scene only; real keys NOT TESTED |
| Linux and macOS | `Desktop.open`, the page and its text were not run there | NOT TESTED |

## A correction of the phase 1 report

The phase 1 report said for SAFE-2 with `win.norestart`: "one process, controls disabled, PASS". That was wrong. Its screen
capture shows that no row was selected; the buttons were disabled for that reason only. On a selected own row, Suspend, End
task and Force kill are enabled. The safety holds, because the service refuses each request, as the checks above show by
result. That the buttons are enabled for Prism's own process is a gap against CAP-2, below.

The verifier was also reviewed by an independent test review before this handoff. It found checks that could pass without
proving their claim: a dialog of any window counted for the second launch, a disabled button turned into a pass, a dead
process measured as 0 % CPU and therefore as "limited", a release counted when nothing was held, and a host run that could
report the image as accepted. All were fixed in `65be4d3`, and the runs above are with that version.

## Not run, and open

| Item | Status |
|---|---|
| CAP-2 for Prism's own row: Suspend, End task, Force kill and Manage are enabled and refused only after confirmation | Open, not fixed in this stage |
| UI-7 native title bar (WP2) | Not implemented in this stage. Windows 10 limitation open (K12); UI-7 is not reported as met |
| Logs page states, keyboard routes, real keys (WP4, WP5) | Next stage |
| Full demo script on the image, including OOM Guard | NOT TESTED |
| MON-12 budget on the image | NOT TESTED |
| Console close and log-off for LIFE-5 (only Ctrl+C run) | NOT TESTED |
| `prism-demo`'s own Ctrl+C | NOT TESTED |
| SmartScreen with a downloaded ZIP; an early start failure without a console | NOT TESTED |
| Windows 10 | NOT TESTED, no machine |
| Path C native suites at `65be4d3` ([TD-1](m13-technical-debt.md)) | NOT TESTED in this stage |
| Hosted CI | Not run, by instruction |
| Review agents other than the test review | Not run in this stage |
| Linux and macOS packages (K7) | Not built; DEL-1 not met there |
| Code signing, product icon | None |
| JavaFX and JNA extract native libraries outside the application folder; `Unsupported JavaFX configuration` warning | Reported in ADR-0017, not changed |
