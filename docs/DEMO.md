# Demonstration guide

Demos A and B and the fallback are those of the milestone 5 checkpoint, with what was observed then.
Demos C to E belong to the course release 2 ([COURSE_RELEASE.md](COURSE_RELEASE.md)); they act on the
demo workload `prism-demo` only, never on an application someone uses.

Everything below was run on 2026-10-22 on Windows 11 with Temurin JDK 25.0.4 from a clean
copy of this repository. Times are from that run; a different machine will differ.

## Before the meeting

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'      # the JDK 25 installation
cd <this repository>
.\mvnw.cmd -q compile                       # about 10 s once dependencies are cached; downloads on first use
```

Open Task Manager (Performance and Details tabs) in a second window for comparison. Close
heavy applications so that the process list is calm.

## Demo A: the application (about 2 minutes)

```powershell
.\mvnw.cmd javafx:run
```

Observed: the window "Prism System Resource Manager" appears about 12 s after the command
(Maven start plus OSHI set-up on the `srm-startup` thread); the log prints "Monitoring
started: system metrics every 1 s" and "Process monitoring started: every 2 s, 16 logical
processors".

1. **Overview** (20 s): point at the CPU, memory, disk and network panels and their 60-second
   graphs; compare memory and the process count with Task Manager. Say: one immutable
   snapshot per second, produced off the UI thread.
2. **Performance → CPU** (15 s): the grid of logical processors, one small history each,
   drawn on a single Canvas.
3. **Processes** (60 s): sort by CPU (click the header) and show that the rows move while the
   table keeps its order; type part of a name in the filter; select a row and show that the
   executable path and command line appear in the details pane after a moment (read on a
   background thread). Point at the PID column and explain process identity (PID + start
   time). Optional: start Notepad, watch it appear; close it, watch the row disappear and,
   if it was selected, the pane say that the process exited.
4. **Close the window**: the log shows "Shutdown started" and "Shutdown complete", and the
   Maven command returns (observed: BUILD SUCCESS, JVM exit without `System.exit`).

If the window does not appear within a minute, check that `JAVA_HOME` points at a JDK 25 and
rerun without `-q` to see the enforcer message.

## Demo B: process lifecycle in the console (about 35 s)

```powershell
.\mvnw.cmd -q test -Dtest=HostProcessProbe
```

What it does, and what was observed on 2026-10-22 (14 cycles, 29 s, test passed):

- Cycle 1: lists the processes (340 at that moment; the first listing took 612 ms, later ones
  30 to 40 ms) and prints the top ten by CPU with PID, memory, threads and user.
- Cycle 3: starts **4 busy threads** inside the JVM and announces "expect about 25.0 % of the
  machine" (4 of 16 logical processors). Cycles 4 to 9 showed this JVM at 25.2 to 25.6 %,
  against 25.1 to 25.5 % computed independently from `ProcessHandle`.
- Cycle 4: starts a **child JVM** that only sleeps 8 s and exits. Cycle 5 printed "child pid
  4644 appeared", with the same start time as `ProcessHandle` reports; cycle 8 printed "child
  pid 4644 is gone from the snapshot (alive by Process API: false)".
- After the last cycle: the details (path, command line) of this JVM and of its parent, the
  enumeration cost statistics, and the probe's own CPU and heap.

What to say: this is the process lifecycle requested for the first meeting: a process is
created, is observed by the monitor with its identity, consumes CPU that is accounted to it,
exits, and disappears from the next listing. Nothing is touched except processes the probe
started itself.

## Fallback: system metrics in the console (about 15 s)

```powershell
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe
```

Prints processor and OS facts, the file systems, the network interfaces grouped by layer
(physical vs. logical), then ten samples one second apart: CPU total and per processor,
memory and swap, disk and network rates, uptime and process count, and how long the source
took in that cycle. Observed on 2026-10-22: passed in 12 s; the first cycle costs about 1.9 s
(device enumeration), later cycles 14 to 28 ms.

## Demo C: process control on the demo workload (Windows, about 3 minutes)

Start the demo workload in its own console, then PRISM (from the application image, or with
`.\mvnw.cmd javafx:run`):

```powershell
target\package\image\PRISM\prism-demo.exe --cpu 25 --memory 256MiB --duration 10m
```

1. **Processes**: filter for `prism-demo`, select it. The inspector shows its identity, its CPU near
   25 % of the machine and its memory.
2. **Priority**: lower it one level; the column changes on the next refresh.
3. **Suspend**, then **Resume**: its CPU falls to 0 and comes back.
4. **End task...**: the demo has no window, so PRISM says that it asked and the demo did not close,
   and does not claim it ended. **Force kill...** asks once more and ends it.
5. Point at PRISM's own row: it offers no action (CAP-2), and the Logs page records every request.

## Demo D: managed processes, the CPU limit and OOM Guard (Windows, about 3 minutes)

1. Start the demo again (`--cpu 40 --duration 10m`). In the inspector choose **Manage**, then
   **Set CPU limit...** at 10 %. On Windows the Limits page shows the limit as native: a Job Object
   holds the demo and the operating system enforces the limit. Its CPU settles near 10 %. (Where no
   native limit is available, PRISM offers its emulated limit and says so before it is used.)
2. **Remove CPU limit**: the demo goes back to 40 %.
3. **OOM Guard**: the state follows the memory available; the actions apply to managed processes only.
   Close PRISM while a limit holds: the limit is cleared and the demo goes on running (LIFE-1).

## Demo E: the application image (Windows)

`target\package\image\PRISM\PRISM.exe` starts without a Java installation. Settings: the theme
(System, Light, Dark) changes at once and is kept for the next start.

## If something fails during the meeting

| Symptom | Do |
|---|---|
| Enforcer error about the Java version | `$env:JAVA_HOME` is not a JDK 25; set it and rerun. |
| Dependency download fails (no network) | Run from the laptop where the dependencies are already cached; the demo needs no network otherwise. |
| The window opens but shows "Reading..." for long | OSHI set-up on first start can take a few seconds; wait. If it stays, the log folder named in the status bar tooltip has the reason. |
| `HostProcessProbe` reports a problem | Its assertions are about the probe's own child and its own virtual size; rerun once (a slow machine can miss the child's single 8 s window). Fall back to `HostMonitoringProbe`. |
| No display available | Use the two console probes only; they need no window. |
