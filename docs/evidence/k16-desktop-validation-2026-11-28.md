# K16 desktop validation (2026-11-28)

**Status: PASS.** Run on 2026-11-28, 12:26 to 13:36 (+03), on a real Ubuntu desktop. PRISM was the build of the change that
lifts K16, started from the GNOME launcher, and was driven through its own window. The product owner lifts K16 on this
result ([ADR-0016](../adr/0016-linux-native-limits-through-systemd-scopes.md), amendment of 2026-11-28;
[risk register](m12-risk-register.md), "K16 lifted, 2026-11-28").

Every result is PASS, FAIL or NOT TESTED, with what was seen. The values come from `/proc/<pid>/cgroup`, the cgroup files,
`systemctl --user show`, PRISM's log, and screenshots of the virtual machine's console. The input was given through
Hyper-V's synthetic keyboard and mouse.

## Build and system

| Item | Value |
|---|---|
| Commit and tree of the build | Branch `feat/lift-k16` at `473b589`. The full gate on this machine passed (2588 tests, 0 failures, SpotBugs 0), and so did the native suites on real systemd (11/11 and 1/1). The three display fixes made after this run are below. |
| How PRISM was started | From the GNOME app grid: a `.desktop` entry runs `./mvnw -o -q javafx:run` in the build's tree. PRISM ran in `app.slice/app-gnome-prism\x2dvalidation\x2dbuild-9594.scope`. |
| Distribution, kernel, systemd | Ubuntu 24.04.5 LTS, Linux 7.0.0-44-generic, systemd 255 (255.4-1ubuntu8.18) |
| Desktop session | GNOME on Wayland, seat0. User `ed`, UID 1000. |
| Logical processors, memory, swap | 2, 7.75 GiB, 4 GiB swap (Hyper-V virtual machine) |
| Log line | `Linux native limits: CPU NATIVE_HARD through a systemd scope; memory NATIVE_SOFT through a systemd scope; each process needs the user's consent before PRISM moves it into a scope` |
| Activity log at start-up | No native-limit warning. After the restart below, recovery cleared the limits the killed session had left. |

## Placement observed: PASS

All paths are below `/user.slice/user-1000.slice/user@1000.service/`.

| Process | Started by | cgroup | Policy |
|---|---|---|---|
| gnome-shell | session | `session.slice/org.gnome.Shell@wayland.service` | refused (session service, decision 2) |
| session bus (dbus-daemon) | session | `session.slice/dbus.service` | refused |
| a settings daemon (gsd-color) | session | `session.slice/org.gnome.SettingsDaemon.Color.service` | refused |
| gnome-session-manager | session | `app.slice/app-gnome\x2dsession\x2dmanager.slice/gnome-session-manager@ubuntu.service` | refused (a service) |
| gnome-terminal-server | D-Bus activation | `app.slice/app-org.gnome.Terminal.slice/gnome-terminal-server.service` | refused (a service) |
| `prism-demo` | clicked in the GNOME app grid | `app.slice/app-gnome-prism\x2dvalidation\x2ddemo-<pid>.scope` | admitted with consent |
| `prism-demo` | typed in GNOME Terminal | `app.slice/app-org.gnome.Terminal.slice/vte-spawn-<uuid>.scope` | admitted with consent |
| autostart helpers (accessibility bus, keyring, update notifier) | gnome-session | `app.slice/app-gnome-<name>-<pid>.scope` | admitted, with consent per process |

Finding: VTE moves a new shell into its scope asynchronously. A command passed to `gnome-terminal -- bash -c "..."` that
starts at once can stay in `gnome-terminal-server.service`, and the policy then refuses it, which is the safe side. A
command typed at the prompt runs in the `vte-spawn` scope.

## CPU cap from a launcher and from a terminal: PASS

The workload was `prism-demo --cpu 100` (2 threads, the whole machine). Its share was measured from `utime + stime` in
`/proc/<pid>/stat` over 10 s, as a share of 2 processors × 10 s.

| Workload | Limit | Scope after consent | `CPUQuotaPerSecUSec` / `cpu.max` | Measured |
|---|---|---|---|---|
| launcher demo (PID 9361) | 20 % | `app.slice/prism-srm-limit-9361-1795861314110.scope` | 400ms / `40000 100000` | 20.0 %; PRISM showed 19.9 % |
| terminal demo (PID 9339) | 30 % | `app.slice/prism-srm-limit-9339-1795861299940.scope` | 600ms / `60000 100000` | 30.0 % |
| the other demo, unlimited, meanwhile | none | unchanged | none | 77.3 % (it took the freed share) |

`OOMPolicy=continue` and `Slice=app.slice` were set on the scope.

The consent dialog appeared before anything was done:
- It listed the five statements, and Cancel was the default.
- Before the confirmation, the process was still in its own unit and no `prism-srm-limit-*` unit existed.
- **Cancel** (PID 9339, first attempt): nothing moved and no scope was created. The row stayed managed with no limit.
- **Move and limit**: the process moved and the limit was set.

## memory.high: PASS

- **Setup.** A plain shell in a new GNOME Terminal (PID 10410) was given a 256 MiB memory limit through the Limits page, with
  consent. Its scope had `MemoryHigh=268435456`, `MemoryMax=infinity`, `memory.max` `max`, and `memory.current` 0, because
  the shell's own memory stays charged to the cgroup it came from, as the consent text says.
- **Load.** From that shell, `prism-demo --memory 768MiB` (PID 10513) started inside the same scope.
- **Held.** Over 15 s, `memory.current` stayed at 255 MiB and `memory.swap.current` at 545 MiB, and `memory.events` read
  high 1218, max 0, oom 0, oom_kill 0.
- **Not killed.** The process stayed alive (`Sl+`) and ended its 300 s normally with exit 0. The desktop stayed responsive.

## memory.max: NOT TESTED in the UI, by design

- The UI does not offer a hard memory limit on Linux in this release: the limit service asks for `memory.high` (D14).
- The hard boundary is covered by `LinuxNativeLimitIntegrationTest.aHardMemoryLimitNeedsItsConsentAndBoundsTheCgroup`. On
  this machine at `473b589` it passed: without the separate consent the request answers CONSENT_REQUIRED and nothing moves;
  with it, `memory.max` is set and `OOMPolicy=continue`.

## Refusals: PASS

| Target | What was asked | Answer | Afterwards |
|---|---|---|---|
| gnome-shell (PID 1819) | CPU limit 50 % | "No limit is offered for it: it is a desktop session service (session.slice/org.gnome.Shell@wayland.service): stopping it would freeze the desktop. PRISM neither stops it nor moves it". The row reads "Not offered: desktop session service". No consent was asked. | Still in its unit, running (`Ssl`) |
| gnome-terminal-server (PID 3312) | CPU limit 50 % | Native refused: "it runs in gnome-terminal-server.service, a service of your session manager: moving it out of its unit could end it". The emulated limit was offered and not accepted. | Still in its unit, running |
| a process in a scope a killed session created (PID 9339, after the restart) | CPU limit 40 % | "it runs in prism-srm-limit-9339-….scope, a scope a PRISM session created; no second limiter is started on it" | Unchanged |
| PRISM itself, its ancestors, `init.scope`, a missing consent | — | Covered by unit tests and by `HostPlatformTest` on the real wiring, not tried by hand | — |

A known limit follows from the ownership rule (ADR-0016): after a crash, recovery clears the limits of a process left in its
scope, but that process cannot be limited again until it exits.

## PRISM window on the desktop: PASS

- PRISM ran in its own window on GNOME Wayland in the light theme: Overview, Processes, Limits, the details panel and the
  limit dialogs.
- The CPU dialog said "Native hard: the kernel caps the process, through a systemd scope PRISM moves it into …
  Real-time processes are not capped".
- The memory dialog said "Holds the process near this amount … it does not stop or terminate the process …".
- The Limits page showed "Enforced, Native hard" for the CPU caps and "Native soft, 256 MiB" for the throttle.

Three display faults were found and fixed after the run, with tests:
- The memory details said "at most 256 MiB committed", which is a Job Object's wording. A scope's throttle now reads "held
  near 256 MiB".
- A refused row named an implementation ("Native hard"). It now shows none.
- After Cancel in the consent dialog, the line kept "PRISM asks for your consent". It now says that nothing was changed
  because the consent was not given.

## Cleanup after exit: PASS

- **Limit removed in session** (PID 9361): `cpu.max` read `max 100000`. The process stayed in its scope, as decided
  (one-way), and its share rose to 68.9 %.
- **Hard kill** (`kill -9` of PRISM while PID 9339 had 30 % and the shell had 256 MiB):
  - After the kill, both limits were still in force.
  - On the next start from the launcher, the activity log read "The limits an earlier session left on java (PID 9339) were
    cleared: read back as none" and the same for bash (PID 10410).
  - `memory.high` read `max`, and the CPU quota was gone.
- **Controlled close** with the window's close button while PID 11639 had 25 % (`cpu.max` `50000 100000`): after the close,
  `CPUQuotaPerSecUSec` read `infinity`, and the state folder kept no native-limit ledger.
- **Processes ended**: once every workload and the shell had exited, no `prism-srm-limit-*` unit was left and no user unit
  had failed. gnome-shell was still the same process (PID 1819).

## Verdict: PASS

- **Native CPU limits:** desktop applications started from the launcher and from a terminal are limited only with the
  user's consent. They are held to the cap measured to 0.1 %.
- **Native memory throttle (`memory.high`):** it holds memory without killing.
- **Session services:** they are refused, both natively and for the emulated limit.
- **Clearing:** limits are cleared on removal, after a crash and at shutdown, and the scopes go when their processes end.

**Not covered here:**
- an X11 session;
- another desktop (KDE);
- a hard memory limit through the UI, which is not offered;
- a real high-DPI display;
- Ubuntu's image (`bin/PRISM`): PRISM was started through Maven.
