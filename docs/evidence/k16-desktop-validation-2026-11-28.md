# K16 desktop validation (2026-11-28)

**Status: PENDING.** Nothing below has been observed yet. This record is filled in by the manual validation on a real Linux
desktop, run with the build of the change that lifts K16. The product owner lifts K16 only if it passes
([ADR-0016](../adr/0016-linux-native-limits-through-systemd-scopes.md), amendment of 2026-11-28;
[risk register](m12-risk-register.md), "K16 lifted, 2026-11-28"). If any section ends FAIL, the change is not merged and K16
stands.

Write each result as PASS, FAIL or NOT TESTED, with what was seen. A section not carried out is NOT TESTED, never PASS.

## Build and system

| Item | Value |
|---|---|
| Commit and tree of the build | PENDING |
| How PRISM was started (image `bin/PRISM`, `./mvnw javafx:run`) | PENDING |
| Distribution, kernel, systemd version (`systemctl --user --version`) | PENDING |
| Desktop session (GNOME on Wayland or X11), user and UID | PENDING |
| Logical processors, memory, swap | PENDING |
| The log line `Linux native limits: CPU ...; memory ...` | PENDING |
| What the activity log says about native limits at start-up | PENDING |

## Placement observed

Before anything is moved: `/proc/<pid>/cgroup` of the processes below, and the unit each belongs to
(`systemctl --user status <pid>`).

| Process | Started from | cgroup | Unit kind | Result |
|---|---|---|---|---|
| `prism-demo` | a launcher (desktop file) | PENDING | PENDING | PENDING |
| `prism-demo` | a terminal (GNOME Terminal) | PENDING | PENDING | PENDING |
| an application of the desktop (for example Text Editor) | a launcher | PENDING | PENDING | PENDING |
| gnome-shell | the session | PENDING | PENDING | PENDING |
| the session bus broker | the session | PENDING | PENDING | PENDING |
| gnome-session-manager | the session | PENDING | PENDING | PENDING |
| gnome-terminal-server | the terminal | PENDING | PENDING | PENDING |
| PRISM itself, and the process it runs under | as above | PENDING | PENDING | PENDING |

Expected: what a launcher or a terminal starts is a scope in `app.slice`; gnome-shell and the bus are in `session.slice`; the
session manager and the terminal server are services.

## CPU cap from a launcher and from a terminal

For each start: the consent dialog's text as shown, the scope PRISM created (`systemctl --user status`), `cpu.max` of the
scope, the measured CPU share of a busy `prism-demo --cpu 100` against the cap, the share after the limit is removed, and
where the process stays after removal.

| Start | Cap | Consent shown | Scope and `cpu.max` | Measured share | After removal | Result |
|---|---|---|---|---|---|---|
| launcher | PENDING | PENDING | PENDING | PENDING | PENDING | PENDING |
| terminal | PENDING | PENDING | PENDING | PENDING | PENDING | PENDING |

## memory.high

`prism-demo --memory` growing past a `memory.high` set through the Limits page: `memory.events` (`high`), the process alive,
`memory.pressure`, what the Limits page and the activity log say.

Result: PENDING

## memory.max

The interface offers no hard memory limit on Linux: the limit service asks for `memory.high` (risk D14). Record whether
`memory.max` was exercised on the desktop (for example by the integration suite run as the desktop user, with its separate
consent) and what happened (`oom_kill` in `memory.events`, swap, the scope still active with `OOMPolicy=continue`), or NOT
TESTED.

Result: PENDING

## Refusals

Each with every consent the dialog could give; nothing may reach systemd (no new `prism-srm-limit-*` unit), and no consent
dialog may open.

| Target | Expected reason | Seen | Emulated limit offered? (expected: no for `session.slice`) | Result |
|---|---|---|---|---|
| gnome-shell (`session.slice`) | not a desktop application: it runs in session.slice; no emulated limit (decision 2) | PENDING | PENDING | PENDING |
| the session bus broker (`session.slice`) | as above | PENDING | PENDING | PENDING |
| the user manager (`init.scope`) | it is your systemd user manager itself | PENDING | PENDING | PENDING |
| gnome-session-manager (a service in `app.slice`) | a service of your session manager: moving it out of its unit could end it | PENDING | PENDING | PENDING |
| gnome-terminal-server (a service in `app.slice`) | as above | PENDING | PENDING | PENDING |
| PRISM itself | it is PRISM's own process | PENDING | PENDING | PENDING |
| the process PRISM runs under | PRISM runs under it | PENDING | PENDING | PENDING |
| an eligible application, the dialog cancelled (missing consent) | nothing changed, nothing moved | PENDING | PENDING | PENDING |

## PRISM window on the desktop

The window opens, draws and responds during and after the limits above; the Limits page shows each state as it is
(enforced, not offered, unavailable); light and dark theme; the window closes through the controlled shutdown.

Result: PENDING

## Cleanup after exit

After closing PRISM: the limits cleared (`cpu.max` `max`, `memory.high` `max`) on every scope PRISM held, the processes still in
their scopes and running, no `prism-srm-limit-*` unit left once its processes exit, the ledger empty or removed, nothing left
suspended. Then after a SIGKILL of PRISM with a limit in force: the next start lists the scope, proves it and clears it.

Result: PENDING

## Verdict

PENDING. Who ran it, when, and the owner's decision.
