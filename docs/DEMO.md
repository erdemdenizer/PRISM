# Demonstration Guide

This document outlines the step-by-step demonstration scripts for course presentations, covering both **Checkpoint 1 (Telemetry Foundation)** and **Checkpoint 2 (Interactive Process Control & Safety Engine)**.

---

## Pre-Presentation Preparation

Before presenting to the course instructor:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'      # Ensure JDK 25 is active
cd <PRISM repository root>
.\mvnw.cmd -q compile                      # Pre-compile to avoid dependency download delays
```

Open Windows Task Manager side-by-side (Performance and Details tabs) for live comparison.

---

## Part 1: Interactive Process Control & Safety Demo (Checkpoint 2 — Presentation 2)

This is the primary demonstration for the second interim submission, proving interactive OS process control, Win32/JNA interop, and safety policy enforcement.

### Step 1: Start the Safe Target Workload (`prism.srm.demo`)

Open a separate PowerShell console and launch the controllable demo workload:

```powershell
# Starts a 2-thread workload targeting ~25% CPU and 100 MB memory for 3 minutes:
.\mvnw.cmd -q compile exec:java -Dexec.mainClass="prism.srm.demo.WorkloadMain" -Dexec.args="--cpu 25 --memory 100 --duration 180"
```

The workload prints its assigned PID and begins cycling CPU busy-wait loops.

### Step 2: Start PRISM

In the main console:

```powershell
.\mvnw.cmd javafx:run
```

### Step 3: Locate the Target & Inspect Metrics (30 seconds)
1. Navigate to the **Processes** tab in PRISM.
2. Type `demo` or `prism` into the filter box.
3. Observe the row: PID matches the console output, CPU % hovers around ~25%, memory matches the allocated target.
4. Compare with Windows Task Manager to confirm telemetry accuracy.

### Step 4: Demonstrate Process Suspension & Resumption (45 seconds)
1. Select the `prism-demo` process in the table.
2. Click **Suspend** in the action bar (or right-click context menu).
3. **Observe:**
   - The status bar displays the action result (`SUSPENDED`).
   - The demo workload's CPU % in PRISM (and Task Manager) drops immediately to **0.0%**.
   - The external demo console stops printing tick logs (its threads are paused via Win32 `SuspendThread`).
4. Click **Resume**.
5. **Observe:**
   - The status bar announces `RESUMED`.
   - CPU % climbs back to ~25.0%.
   - The demo console resumes printing tick logs.

### Step 5: Demonstrate Priority Adjustment (30 seconds)
1. With `prism-demo` selected, click **Priority** -> choose **High** or **Below Normal**.
2. Confirm the change.
3. In Task Manager (Details tab), right-click the process -> *Set Priority* to prove Windows kernel accepted the `SetPriorityClass` call.

### Step 6: Demonstrate the Safety Policy Engine (`ProtectionRules`) (45 seconds)
1. **Self-Protection:** Locate PRISM's own process (`java.exe` running PRISM) in the table.
2. Attempt to click **End Task** or **Force Kill**.
3. **Observe:** PRISM immediately refuses the action with an error banner: *"Cannot terminate PRISM itself."* No platform call is made.
4. **System-Critical Protection:** Search for a system-critical Windows process (e.g. `csrss.exe` or `smss.exe`).
5. **Observe:** Destructive control buttons are dynamically disabled or show a clear safety refusal dialog indicating that modifying system-critical processes would compromise OS stability.

### Step 7: Demonstrate Graceful Termination & Force Kill (30 seconds)
1. Select `prism-demo`.
2. Click **End Task** (or **Force Kill**). Confirm the dialog.
3. **Observe:** The process is cleanly terminated via Win32 API. The row vanishes from the PRISM table on the next 2-second tick, and the external demo console terminates.

### Step 8: Inspect the Activity Logs (20 seconds)
1. Switch to the **Logs** tab in PRISM.
2. Review the chronological audit log showing:
   - Every user action initiated (`SUSPEND`, `RESUME`, `CHANGE_PRIORITY`, `FORCE_KILL`).
   - The targeted PID and identity.
   - The exact result returned by the OS (`SUCCESS`, `BLOCKED_BY_SAFETY_POLICY`, etc.).

---

## Part 2: Read-Only Monitoring & Architecture Demo (Checkpoint 1 Foundation)

Use this section to review the baseline telemetry capabilities established in Milestone 1–5:

1. **Overview Page (20 s):** Point out the CPU, memory, storage read/write rates, and network throughput panels. Highlight that all graphs are generated from monotonic timestamps and bounded history buffers without allocating on the UI thread.
2. **Performance Page (20 s):** Show the logical processor grid view rendered on a single high-performance JavaFX `Canvas`.
3. **Processes Page Table Dynamics (30 s):** Show in-place sorting and filtering. Explain how `(PID, StartTime)` solves the operating system PID reuse anomaly.

---

## Part 3: Console Probes & Test Verification

For verification during technical questions or when no display environment is available:

```powershell
# 1. Process Lifecycle Probe (demonstrates child JVM spawn, load accounting, and exit):
.\mvnw.cmd -q test -Dtest=HostProcessProbe

# 2. System Monitoring Probe (samples CPU, disk, memory, and network rates):
.\mvnw.cmd -q test -Dtest=HostMonitoringProbe

# 3. Full Quality Gate Verification:
.\mvnw.cmd verify
```

---

## Troubleshooting During Live Presentation

| Symptom | Recovery Action |
|---|---|
| Java version error | Run `$env:JAVA_HOME = 'C:\path\to\jdk-25'` and verify with `java -version`. |
| Demo workload not appearing in table | Clear any text from the search filter in the Processes tab. |
| Console output pauses in terminal | Press Enter in the PowerShell console window (Windows console QuickEdit mode may pause execution if text was clicked). |
| Display fails to render | Fall back to running `HostProcessProbe` and `HostMonitoringProbe` in the console. |
