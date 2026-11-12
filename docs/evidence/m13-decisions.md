# Milestone 13: decisions of the product owner

Given on 2026-11-07, on the plan for milestone 13. Milestone 13 is not accepted, and these are decisions on scope and method, not results.

| # | Subject | Decision |
|---|---|---|
| K1 | Distribution | A Windows jpackage application image, distributed as a ZIP. No MSI in this milestone |
| K2 | Windows 10 title bar | A light title bar on Windows 10 is not accepted automatically as meeting UI-7. The documented Windows 11 solution is implemented and verified. Windows 10 compatibility is investigated separately. If no reliable documented solution exists, the limitation is recorded and the owner makes a final decision on product scope. No undocumented hacks and no fake window chrome |
| K3 | Theme tests | Windows theme changes may be tested briefly while the machine is idle. The original settings are recorded and restored, also after a failed test |
| K4 | Commits | Focused local commits per work package. No push to GitHub without separate authorization |
| K5 | Demo workload | Included in the package as a separate `prism-demo` launcher |
| K6 | Clean test environment | Windows Sandbox is preferred, and that it actually works has to be verified. If it is unavailable, alternatives are reported before any virtual machine is created or reconfigured |
| K7 | Linux and macOS packages | Outside milestone 13. What remains of DEL-1 stays documented (below) |
| K8 | Milestone 12 records | A dated note about the merge, with no rewriting of the historical decisions |
| K9 | Window title | The complete name of the specification. The existing user data folders are not renamed |
| K10 | Self-hosted runner | Outside milestone 13. The Windows native-validation policy stays and [TD-1](m13-technical-debt.md) is recorded |
| K11 | Launcher model | `win.norestart=true` may be used. That it is undocumented JDK behaviour is stated in [ADR-0017](../adr/0017-windows-application-image.md). Package tests must prove the one-process model, the self-protection of Prism, the native CPU and memory limits on the demo, and the safety of the shutdown. A package that fails them is not accepted, and a change of JDK makes the same checks mandatory again |
| K12 | Title bar | The documented DWM mechanism may be used on Windows 11. No unverified or undocumented method on Windows 10. The Windows 10 limitation stays open and is not reported as UI-7 being met on every Windows version. The final decision comes later |

Later on 2026-11-07, with K11 and K12, the owner changed the order: functional completeness, safety and real distribution first, visual improvements last. WP1, WP3 and WP6 go ahead; the title bar of WP2 and broad visual polish wait. The functional failure states and critical keyboard routes of WP4 and WP5 are not dropped; they come in the next stage.

## What remains of DEL-1

DEL-1 asks for native packages built with jpackage separately on Windows, Linux and macOS. The acceptance criterion of milestone 13 names a
Windows application image. After milestone 13 the Linux and macOS packages stay unbuilt, and DEL-1 is not met for those systems.

The Linux claim of K16 in the [risk register](m12-risk-register.md) depends on there being no `module-info`, so a Linux package needs its own
check of the classpath. K16 stays active. Nothing in milestone 13 changes it.
