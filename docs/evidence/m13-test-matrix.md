# Milestone 13: test matrix

The working list of what milestone 13 has to show. **Nothing below is accepted yet.** The functional stage (WP1, WP3, WP6) is recorded at commit `65be4d3`, with its evidence in [m13-functional-verification.md](m13-functional-verification.md). The functional completion (CAP-2, WP4, WP5, the full demo on the image, LIFE-5) is recorded at `c8c44cd` (product) and `ff9a160` (verifier), with its evidence in [m13-functional-completion.md](m13-functional-completion.md). The title bar (WP2) and the visual review are still the plan.

"Rendered" means that the snapshot profile produced an image at `9f4ec7b`: 872 tests, 725 images, on Windows, 2026-11-07. An image proves that a state can be drawn. Nobody has reviewed these images for milestone 13, so a rendered state is not a checked one. The snapshot profile does not run in `verify` or in CI.

Status words: `RENDERED`, `MISSING`, `NOT TESTED`, `PASS`, `FAIL`, `OPEN` (known and not done).

## UI-8 and UI-7: states per page

Both themes are rendered wherever a state is rendered.

| Page | Loading | Empty | Error | Unavailable | Minimum 720x480 and 704x441 | Default 1100x700 | Wide | Review of the images |
|---|---|---|---|---|---|---|---|---|
| Overview | RENDERED | n/a, it always has values | RENDERED (`error`, `system-stopped`, `process-stopped`, `all-stopped`) | RENDERED | RENDERED | RENDERED | RENDERED 1920x1080 | NOT TESTED |
| Processes | RENDERED | RENDERED (`none-reported`, `filter-no-match`) | RENDERED (`error-*`) | RENDERED | RENDERED | RENDERED | RENDERED 1600x1000 | NOT TESTED |
| Performance, four sections | RENDERED | RENDERED (`disk-no-file-system`, `network-no-interface`, `memory-no-swap`) | RENDERED (`cpu-error`, `network-system-stopped`) | RENDERED | RENDERED | RENDERED | RENDERED 1920x1080 | NOT TESTED |
| Limits | RENDERED (`reading`) | RENDERED | RENDERED (`result`) | RENDERED | RENDERED | RENDERED | RENDERED 1600x1000 | NOT TESTED |
| OOM Guard | RENDERED (`reading`) | RENDERED | RENDERED (`journal-failed`, `not-monitoring`) | RENDERED | RENDERED | RENDERED | RENDERED 1600x1000 | NOT TESTED |
| Logs | RENDERED (`logs-reading`) at `ff9a160` | RENDERED, and `logs-full` | RENDERED (`logs-folder-failed`) at `ff9a160` | n/a, the activity log is Prism's own | RENDERED 720x480 and 704x441 at `ff9a160` | RENDERED | RENDERED 1600x1000 at `ff9a160` | NOT TESTED |
| Settings | RENDERED (`reading`) at `65be4d3` | n/a | RENDERED (`startup-failed`, `file-damaged`) | RENDERED | RENDERED | RENDERED | RENDERED 1600x1000 | NOT TESTED |

The Logs page had no reading, full-log or folder-failure state in the product; they were added in `dd10e8c`. The whole snapshot profile at `ff9a160`: 965 tests, 0 failures, 801 images, none reviewed.

### Dialogs

| Dialog | Rendered | Review |
|---|---|---|
| Confirmations: end task, force kill, termination switch | Yes | NOT TESTED |
| CPU and memory limit, fallback offer, resident offer | Yes | NOT TESTED |
| OOM management, thresholds | Yes | NOT TESTED |
| Already running | Yes | NOT TESTED |

## Checks a snapshot cannot do

| Check | Scope | Status |
|---|---|---|
| Hover, tooltips, popups, transitions | Every page, both themes, in the running application | NOT TESTED |
| Tooltips in the tokens of the guide (deferred from milestone 5) | Every page | NOT TESTED |
| Selected row found without focus; contrast of muted units on Performance (deferred from milestone 5) | Performance | NOT TESTED |
| Reduced motion honored | Running application | NOT TESTED |

## Keyboard

Every page has to be operable by keyboard. The written route of each page is the one `KeyboardRoutesSceneTest` pins, forward and backward, with the name of every stop. "Real keys" is the `keyboard` scenario on the image in a clean environment, at `ff9a160`: Tab from the sidebar entry around the page and back, a UI Automation name for every stop, and a focus ring that changes at every stop. Focus visibility is measured on the image in the theme of the Sandbox (light); the dark theme is not measured with real keys.

| Page or area | Written route | Scene test with key events | Focus visible | Real keys in the running application |
|---|---|---|---|---|
| Sidebar and shell | PASS | PASS, also a mouse press giving the focus (`e9753ff`) | PASS, light | PASS |
| Overview | PASS | PASS | PASS, light | PASS |
| Processes, context menu | PASS | PASS | PASS, light | PASS with the context-menu key: opens, arrows move, one Escape closes. Shift+F10: INFO, the system's menu mode takes the next keys (JDK-8090022) |
| Performance | PASS | PASS | PASS, light | PASS |
| Limits | PASS | PASS | PASS, light | PASS |
| OOM Guard | PASS | PASS | PASS, light | PASS |
| Logs | PASS | PASS | PASS, light | PASS |
| Settings | PASS | PASS | PASS, light | PASS |
| Dialogs | Confirmations, limits, OOM | Exists: Enter and Escape on confirmations, field names (`88d5b0e`) | NOT TESTED | Enter and Escape on Force kill: PASS; others NOT TESTED |

A screen reader is not an acceptance criterion of this milestone. If one is tried, it is reported as such; otherwise the report says that it was not run.

## Native title bar

Criteria from milestone 13, on the shipped code. Feasibility experiments do not count here.

| Case | Status |
|---|---|
| `DARK`, `LIGHT`, `SYSTEM` on the main window | NOT TESTED |
| Live change of the operating system preference | NOT TESTED |
| Every decorated dialog | NOT TESTED |
| Title text and caption buttons keep contrast | NOT TESTED |
| Only windows of this process are touched | NOT TESTED |
| Clean end of the mechanism at shutdown | NOT TESTED |
| Windows 10 (decision K2 in [m13-decisions.md](m13-decisions.md)) | NOT TESTED |

## Shutdown: LIFE-1 to LIFE-7

| Requirement | Evidence required | Status |
|---|---|---|
| LIFE-1 order, off the JavaFX thread, bounded | The whole order in one test, and a real process ending within the bound with a stuck step | PASS |
| LIFE-1 logs flushed | The `immediateFlush` setting pinned by a test | PASS |
| LIFE-1 limits and holds released at close | On the image, both environments: emulated hold released, native CPU and memory limits cleared | PASS |
| LIFE-2 a user's suspension stays | On the demo workload, on the image | PASS |
| LIFE-3 a failed step is logged and the rest run | Failure injected at each step; a defect found and fixed (`ec6f4f0`) | PASS |
| LIFE-4 a killed Prism resumes nothing | Hard kill and restart, on the packaged image, native and emulated | PASS |
| LIFE-5 exit hook | Ctrl+C and console close through a console test launcher, on the image | PASS; the console close needed the fix of `2f2dc97`. Log-off NOT TESTED |
| LIFE-6 journal after a crash | Listing, resuming by request, manual intervention only without recorded threads | PASS |
| LIFE-7 one instance | Second launch while running and while closing, on the packaged launcher | PASS |
| Closing during start-up, with a dialog open, and during a save | Real process, on the image | PASS |

## Packaging: DEL-1

On the code that ships, built by what the repository will contain.

| Case | Status |
|---|---|
| Image built without a JDK-specific path in the output | PASS (`PackageImage` refuses an absolute path in the launcher configuration) |
| Starts and runs on a machine without a JDK | PASS, Windows Sandbox, `IMAGE ACCEPTED` |
| `prism-demo` launcher | PASS |
| Process model of the launchers (K11, `win.norestart=true`): one process each, no job | PASS, clean and host |
| SAFE-2 on the image, by result; CAP-2 for the own row | PASS, clean, at `ff9a160` |
| Native CPU and memory limit applied to the packaged `prism-demo` | PASS; enforcement of memory measured on a test copy of the same launcher |
| Turkish number and date formats | PASS |
| Full demo script on the image: monitoring, control, limits, OOM Guard, shutdown | PASS, clean, at `ff9a160`; MON-12 in [m13-functional-completion.md](m13-functional-completion.md) |

## Gates at the end

| Gate | Status |
|---|---|
| `./mvnw clean verify` on the final commit | PASS at `c8c44cd` for the functional completion; WP2 will add commits |
| Snapshot profile on the final commit, images reviewed | Whole profile PASS at `ff9a160`, 965 tests, 801 images; not reviewed |
| Path C on the final commit ([TD-1](m13-technical-debt.md)) | PASS at `c8c44cd`, 20 of 20, none skipped; to be run again on the milestone's final commit |
| Hook and CI script tests | PASS at the functional completion, 326 and 8 |
| All four review agents | Architecture, native API and test review at the functional completion, findings fixed; UI critic left to the visual stage |
| Linux and macOS: hosted `verify` only, the application itself not run there | NOT TESTED |
