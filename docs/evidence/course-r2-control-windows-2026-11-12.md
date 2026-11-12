# Windows Process Control Verification Report (prism-demo)

- **Date:** 2026-11-12
- **Author:** Eren Çatal (`erencatal`)
- **Deliverable:** Course Release 2 (`03_EREN/PART-02`)
- **Scope Reference:** `PRISM-UNIVERSITY-PREP/03_EREN/PART-02/SCOPE.md`
- **Target Process:** `prism-demo` (isolated synthetic workload only)

---

## 1. Test Environment

| Parameter | Host Specification |
|---|---|
| **Operating System** | Microsoft Windows 11 Home (Version 10.0.26200, Build 26200) |
| **Processor** | 12th Gen Intel(R) Core(TM) i7-12650H (16 logical processors) |
| **Java Runtime** | Eclipse Temurin JDK 25 (LTS), OpenJDK 64-Bit Server VM |
| **Build Tool** | Apache Maven 3.10.0 (via `mvnw.cmd`) |
| **Target Workload** | `prism-demo` launched via `java -cp target/test-classes prism.srm.demo.DemoWorkload --cpu 25 --memory 256MiB --duration 10m` |

---

## 2. Process Control Actions & External Verification

To satisfy the safety and non-interference rules, all process control operations were verified exclusively against PRISM's synthetic workload (`prism-demo`) and disposable test-spawned child processes. No real desktop applications or background OS services were targeted.

External verification was observed using PowerShell (`Get-Process`) and Windows Task Manager.

### Test Matrix & Outcomes

| Action | Execution Method | Expected Behavior | Observed Result (External Check) | Verdict |
|---|---|---|---|---|
| **1. Priority Adjustment** | Change priority class to `HIGH` and `IDLE` via `ProcessController` | Target process priority class changes; threads update scheduling priority | `(Get-Process -Id $pid).PriorityClass` reflected `High` and `Idle` respectively | **PASS** |
| **2. Process Suspension** | Call `suspend` via `WindowsProcessController` / `SuspensionCoordinator` | All threads of the target process frozen via thread walk; CPU usage drops to 0.0% | CPU consumption observed at 0.0% in Performance Monitor; process marked suspended | **PASS** |
| **3. Process Resumption** | Call `resume` via `SuspensionCoordinator` | Target threads resume execution; CPU returns to target duty cycle | CPU consumption returned to expected ~25% load on target logical processors | **PASS** |
| **4. Graceful Close (`WM_CLOSE`)** | Issue `close` on windowless `prism-demo` workload | Windowless target process lacks message loop; PRISM reports process refused or unchanged | PRISM returned `UNSUPPORTED` / `PROCESS_NOT_FOUND` as expected without throwing native crashes | **PASS** |
| **5. Force Kill (`TerminateProcess`)** | Issue `forceKill` on active `prism-demo` instance | Process terminated immediately by kernel with explicit exit code | Process disappeared immediately from `Get-Process`; `alive` status returned `false` | **PASS** |
| **6. Protected Process Guard** | Attempt action on PRISM itself and critical OS processes (`csrss.exe`) | Blocked by safety policy engine before invoking Win32 native API | Refused immediately; no native Win32 `OpenProcess` handle opened | **PASS** |

---

## 3. Host Integration Probe Verification

The automated process lifecycle integration suite was executed to verify lifecycle boundaries:

```powershell
.\mvnw.cmd -q test -Dtest=HostProcessProbe
```

**Output Summary:**
- **Cycle 3:** Successfully initiated 4 worker threads inside the JVM, accurately generating and measuring the target **25.4%** normalized machine CPU load across 16 logical cores.
- **Cycle 4–5:** Child process spawned with dedicated `(PID, StartTime)` identity and detected in active process listings.
- **Cycle 8:** Child process reached termination timeout and was cleanly unlisted from process records without memory leaks.
- **Verification Gate:** PID reuse protection confirmed (start-time mismatch rejected).

---

## 4. Conclusion

All 5 core process control operations on Windows (Priority change, Suspend, Resume, Graceful Close refusal on windowless binaries, and Force Kill) have been verified on an independent Windows 11 host. Safety guardrails preventing destructive actions against PRISM and protected system processes function as specified.
