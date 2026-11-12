# Milestone 13: functional completion (CAP-2, WP4, WP5, the full demo on the image, LIFE-5)

Evidence for the stage the owner ordered after the functional handoff of WP1, WP3 and WP6: the remaining functional and
safety work of milestone 13, without the title bar of WP2 and without visual polish. It is not an acceptance: the owner
decides. Nothing here says that UI-7 is met.

Verdicts: PASS has the evidence named in its row. FAIL, BLOCKED and NOT TESTED are what they say. INFO is an observation,
not a verdict.

## What was verified, and on which code

| Item | Value |
|---|---|
| Product code | Commit `c8c44cd` on `feat/m13-settings-shutdown-packaging`, local, not pushed. Later commits change only the image verifier and documents |
| Full gate | `./mvnw clean verify` at `c8c44cd`, Windows 11 Pro 10.0.26200, Temurin 25.0.4.1: PASS, 07:30 min, 2364 tests, 0 failures, 0 errors, 37 skipped; javac compiled 349 and 271 source files; SpotBugs 0 |
| The gate before it | At `8065ade` the same gate failed on one SpotBugs finding, a public static synchronized method in the new `WindowsConsoleClose` (`USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION`). Fixed with a private lock in `c8c44cd`; the check was not weakened |
| Image | Built right after the gate from the same tree: `prism-0.1.0-windows-x64.zip`, SHA-256 `b289da8b693dc23fab44e4b4ca78b6fa5500fafe19f8ec6d443d03867d121c8f` |
| Clean run | `verify-image.ps1` at `ff9a160`, `-Environment clean`, in Windows Sandbox: **IMAGE ACCEPTED**, PASS 177, INFO 21, nothing else |
| Host run | Same verifier, `-Environment host`: **HOST CHECKS PASSED**, PASS 172, INFO 22, NOT TESTED 2 (by design, below) |
| Path C ([TD-1](m13-technical-debt.md)) | At `c8c44cd`, started through WMI outside any job (`IsProcessInJob` false), no VM or Sandbox running: `native validation passed`, 20 tests (WindowsJobIntegrationTest 6, NativeLimitRecoveryIntegrationTest 4, ParentExitsChildSurvivesTest 1, JnaWin32Test 9), none skipped |
| Snapshot profile | `-Pui-snapshots` at `ff9a160`: 965 tests, 0 failures, 801 images. The images were not reviewed: visual quality is the next stage |
| MON-12 | On the image: 0.302 % of the machine (budget 2 %), no heap growth found; [below](#mon-12-on-the-image) |

The raw results are not in this repository. They are in the handoff package of this stage.

## CAP-2: Prism's own process

The window offers nothing for Prism's own row, and says why; the service still refuses every request (SAFE-2), so a
request that reached it by another way would change nothing.

| Check | Where | Result |
|---|---|---|
| Suspend, Resume, Priority, End task and Force kill disabled on the own row | Image, clean | PASS, 5 of 5 |
| The reason on the page and on every control when pointed at | Image, clean | PASS: "This is Prism's own process. Prism does not suspend, end, change, limit or manage itself." on 5 of 5 |
| Context menu of the own row: Suspend, Force kill, Manage, Set CPU limit, Add to OOM management disabled, and the click was on the own row | Image, clean | PASS |
| No request reached the service, the process runs untouched | Image, clean | PASS: 0 of 89 threads suspended, 0 requests in the log |
| SAFE-2 refusals as results of the real runtime, without a window | Image, clean, `image-probe.jar` | PASS for change priority, suspend, end task, force kill and manage: `ACCESS_DENIED`, alive afterwards |
| The same in unit and scene tests (`ProcessActions`, `ManagedActions`, the scene tests of both pages) | `verify` | PASS |

## WP4: the Logs page and states of the same kind

| State | Before | Now | Evidence |
|---|---|---|---|
| Reading, before the first entries arrive | The page said "no entries" | "Reading the activity log…" until the log is shown | `LogsViewModelTest`, `ShellNavigationTest`, snapshot `logs-reading` |
| Full log | Nothing said that older entries are not kept here | A line says that only the newest entries are kept and that the log file has the rest | `LogsViewModelTest`, snapshot `logs-full` with 1000 entries |
| The log folder did not open | The outcome went to the Settings page even when the Logs page asked | It is said on the page that asked | `ShellNavigationTest`, snapshot `logs-folder-failed` |
| Small window | 720x480 only | 720x480 and 704x441, also 1100x700 and 1600x1000 | `LogsSnapshotTest`, 48 images |
| Unavailable | n/a: the activity log is Prism's own and is always there | | |

No other page had a state of this kind missing: the states of the other pages were already rendered (test matrix).

## WP5: the keyboard

Scene tests pin the Tab route of every page forward and backward, at 1100x700 and 704x441, with the name of every stop,
and Space, Enter, Escape and the arrows where they act (`KeyboardRoutesSceneTest`). On the image, with real keys
(`keyboard` scenario):

| Check | Result |
|---|---|
| From each sidebar entry Tab walks the page and comes back, on all seven pages | PASS, 30 stops |
| Every stop has a UI Automation name | PASS |
| The ring around every stop changes when it gets the focus, with the ring of the previous owner left out and no change without a key | PASS |
| Context menu of a row with the context-menu key, 13 items | PASS |
| The arrows move in the menu | PASS |
| One Escape closes the menu | PASS |
| Shift+F10 | INFO: it opens the menu, but on Windows the window also enters the system's menu mode, which takes the next keys; two Escapes close it. JDK-8090022 describes the same. Not a product defect that Prism can fix without an undocumented workaround (K2); the context-menu key works fully |

Defects found and fixed on the way:

- A sidebar entry pressed with the mouse did not take the keyboard focus, so the arrow keys acted from the entry before
  it (`e9753ff`).
- Text fields were named for screen readers with accessible text. The Windows bridge of JavaFX 25 takes the name of a
  text field only from its label, and reads its accessible text as its value: the CPU limit, memory limit, threshold and
  folder fields were read as their description instead of the number or path, and the two folder fields had no name.
  Confirmed from the JavaFX 25.0.4 classes (`WinAccessible`, `TextInputControl`) and with UI Automation on the image; fixed
  with labels in `88d5b0e`.

A screen reader was not run. That is not an acceptance criterion of this milestone.

## The full demo on the image

All on the packaged `prism-demo`, never on a user's process.

| Area | Checks | Result |
|---|---|---|
| Process control from the window | Priority Below normal (read as the priority class), Suspend 26 of 26 threads, Resume, the load back, End task says that the demo has no window to ask and does not claim it ended, Enter and Escape both decline Force kill, Force kill ends the demo and is logged, close | PASS |
| Native limits | CPU 5 % measured, removed, memory limit refusing an allocation, cleared at close | PASS (the scenarios of the functional stage, run again) |
| OOM Guard | Thresholds above the available memory bring Emergency without using memory; the demo, under OOM management with Suspend, is suspended 26 of 26 and the log says so; close releases the hold and the load is back | PASS |
| OOM Guard and a hard kill of Prism | Killed while it held 26 of 26 threads; the next start resumes nothing, lists it, offers Resume after a check because OOM Guard recorded the threads, and resumes | PASS |
| Closing | Closed during start-up, with a dialog open and during a save; one instance | PASS |

## LIFE-5

| Case | Result |
|---|---|
| Ctrl+C to PRISM in a console while the limiter holds a process and the user holds another | PASS: the hook released the limiter's hold, the user's suspension stayed |
| The console of PRISM closed, same situation | PASS after a fix. The first run on the image failed: the system ends the process as soon as the JVM's own console handler returns, before the shutdown hooks have run, and the limiter's hold stayed. `WindowsConsoleClose` (`2f2dc97`) registers a console control handler that runs the same release first and then lets the JVM's handler run. The release has one deadline of 2.5 s, half the 5 s the system waits by default (`db3a4a8`) |
| Ctrl+C to the demo in its own console while a native limit holds it | PASS: the demo ends, PRISM goes on, says that it has exited and is no longer managed, and closes cleanly |
| Log-off | NOT TESTED. The development machine was not logged off, by instruction. HandlerRoutine says that `CTRL_LOGOFF_EVENT` is received only by services; an interactive application is ended at log-off, so the shutdown hook may not run. Windows Sandbox was not used for it, because logging off ends the Sandbox and its results with it |

The native review of `WindowsConsoleClose` found the mapping, the constant and the return value right and asked for one
deadline over the whole release; that was done. Not verified: whether `SetConsoleCtrlHandler` succeeds in the GUI
launcher, which has no console (there it is never called either way), and behaviour under Windows Terminal.

## MON-12 on the image

The protocol of [PERFORMANCE.md](../PERFORMANCE.md), now on the packaged application: the image of `c8c44cd`, started from
`PRISM.exe` with an isolated `LOCALAPPDATA`, the window 1280x800 and visible on the Processes page (the page with the
most work: the process table), 90 s of warm-up, then 600 s idle with nobody at the machine (the run started only after
more than four minutes without input and below 15 % machine load, and with no Sandbox running). UI Automation was let go
before the measurement. The heap was read with `jcmd GC.run` and `GC.heap_info` of the same JDK version.

| Measure | Value |
|---|---|
| Duration | 600.3 s |
| Processor time used by Prism | 29.05 s |
| Of one logical processor | 4.84 % |
| **Of the machine** | **0.302 %** (budget 2 %, **met**) |
| Heap in use after GC: start, middle, end | 34 788, 34 380, 36 066 K |
| Handles | 1 173 to 1 186 |
| Threads | 76 to 81 |
| Working set | 276 to 305 MiB |
| The machine meanwhile | 5.0 % on average over 20 samples |
| Close request to the end of the process | within the 15 s waited, `closed: True` |

**CPU: PASS.** **Heap: no growth found by this protocol.** The middle reading is below the start and the end is 1.3 MiB
(3.7 %) above it, within the spread between single readings. Three readings after a forced collection can show a trend
but cannot prove that there is none; a longer run was not made.

## Host run

`verify-image.ps1` at `ff9a160`, `-Environment host`, on the development machine with the same image, started only after
more than four minutes without input: **HOST CHECKS PASSED**, PASS 172, INFO 22, NOT TESTED 2. Both NOT TESTED are by
design: on the host no folder window is opened and the Windows theme is not changed; both are PASS in the clean run. A host
run cannot accept an image, because a JDK is beside it.

## Reviews

| Review | Scope | Findings | What was done |
|---|---|---|---|
| Architecture | The changes of this stage | No blocker; the log capacity read in two places, a native registration on the JavaFX thread, the own-process check written twice | Fixed in `6e6fddb`; two nits left as they were, as correct |
| Native API | `WindowsConsoleClose` and the release it runs | The release was bounded per step, not as a whole; the callback thread had no name | One deadline (`db3a4a8`), a named daemon callback thread |
| Test quality | Tests of this stage and the new image scenarios | Checks that proved less than their names: the focus ring could be another control's, End task was not held to its wording, a partial resume passed, a check was only INFO, the pin left out checks; no test of the single deadline | All fixed in `8065ade`; the new tests fail on the old code |
| UI critic | Left to the visual stage, as the owner allowed | | |

## Open

| Item | Status |
|---|---|
| UI-7 native title bar (WP2) | Not in this stage. Windows 10 limitation open (K12) |
| Visual review of the 801 snapshot images and the running window | Visual stage |
| The reason line of the own row is shortened in the middle at 1100x700 ("Prism doe…, end") | Open, layout; the whole text is on every action's tooltip |
| Shift+F10 on Windows enters the system's menu mode (JDK-8090022) | Open, outside Prism; the context-menu key is the keyboard route |
| LIFE-5 at log-off | NOT TESTED |
| Windows 10, SmartScreen with a downloaded ZIP | NOT TESTED |
| Hosted CI | Not run, by instruction |
| Linux and macOS packages (K7) | Not built |
