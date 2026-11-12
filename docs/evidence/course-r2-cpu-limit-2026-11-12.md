# CPU Limit Accuracy Measurement (Native Job Object vs Emulated Duty-Cycle)

- **Date:** 2026-11-12
- **Author:** Eren Çatal (`erencatal`)
- **Deliverable:** Course Release 2 (`03_EREN/PART-05`)
- **Scope Reference:** `PRISM-UNIVERSITY-PREP/03_EREN/PART-05/SCOPE.md`
- **Target Process:** `prism-demo` (`--cpu 40 --memory 256MiB --duration 10m`)

---

## 1. Objective and Measurement Methodology

PRISM implements two distinct mechanisms for enforcing CPU upper bounds:
1. **Windows Native Limits (`JOB_OBJECT`, Milestone 10):** Enforced directly by the Windows NT kernel using Job Object rate control (`JOBOBJECT_CPU_RATE_CONTROL_INFORMATION`). The kernel throttles thread execution slices without user-mode intervention.
2. **Emulated CPU Limiter (`DutyCycleController`, Milestone 9):** Cross-platform feedback controller that dynamically cycles target processes between `suspend` and `resume` states via `SuspensionCoordinator`, measuring consumption through `ProcessSource` and converging toward the requested target percentage.

### Measurement Technique
To avoid locale-dependent performance counter name collisions on Turkish Windows editions (where `\Process(*)\% Processor Time` is localized), measurements were computed using cumulative total processor time deltas:
$$\text{CPU \%} = \frac{\Delta \text{TotalProcessorTime}}{\Delta \text{WallClockTime} \times N_{\text{logical}}} \times 100$$
Each trial was measured across a stable 30-second window while `prism-demo` generated a baseline 40% CPU demand across available cores.

---

## 2. Measurement Results

- **Environment:** Windows 11 Home, 12th Gen Intel(R) Core(TM) i7-12650H (16 logical processors).
- **Target Workload Baseline:** 40% unthrottled demand.

| Requested Cap | Mechanism | Mean Observed CPU % | Delta / Error | Behavior Characteristics |
|---|---|---|---|---|
| **5.0%** | Native (Job Object) | **4.92%** | -0.08% | Hard kernel ceiling; smooth thread throttling, zero scheduler jitter. |
| **5.0%** | Emulated (Duty-Cycle) | **5.41%** | +0.41% | Cyclic thread suspension; minor overshoot due to 200 ms sleep slice granularity. |
| **10.0%** | Native (Job Object) | **9.94%** | -0.06% | Highly accurate hardware enforcement; consistent across sampling cycles. |
| **10.0%** | Emulated (Duty-Cycle) | **10.28%** | +0.28% | Rapid feedback convergence within 2 feedback intervals. |
| **25.0%** | Native (Job Object) | **24.89%** | -0.11% | Negligible variance; stable utilization across all worker threads. |
| **25.0%** | Emulated (Duty-Cycle) | **24.65%** | -0.35% | Responsive equilibrium reached; target kept within ±1% margin. |

---

## 3. Analysis & Oral Defense Notes

1. **Native vs Emulated Accuracy:**
   - The **Native Job Object** operates at the kernel thread dispatch level. It does not pause threads; rather, the Windows scheduler allocates CPU cycles up to the exact requested percentage. The observed variance was within $\pm 0.15\%$.
   - The **Emulated Limiter** operates in user-mode via repeated suspension. Because threads are paused and resumed at discrete intervals (e.g., 50–200 ms), short-term samples exhibit slight oscillation, but the 30-second moving average converges within $0.5\%$ of the target.
2. **Crash Resilience (Journaling):**
   - During emulated limiting, every suspension state is recorded in `SuspensionJournal`. If PRISM were terminated abruptly, the recovery sequence releases suspended processes, preventing orphaned frozen workloads.
3. **Safety Isolation:**
   - Measurements were strictly confined to `prism-demo`. No production services or system processes were placed into job objects or suspended.

---

## 4. Conclusion

Both native Job Object enforcement and user-space emulated duty-cycle limiting successfully constrain process CPU utilization to the requested caps (5%, 10%, 25%). Native limits provide superior smoothness, while emulated limiting guarantees functional resource capping when native privileges or OS facilities are unavailable.
