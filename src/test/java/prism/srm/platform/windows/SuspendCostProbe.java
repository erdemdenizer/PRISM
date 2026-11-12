package prism.srm.platform.windows;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.platform.SuspendResult;

/**
 * Measures what the emulated CPU limiter's duty cycle costs on Windows (milestone 9, plan §8.3):
 * Suspend and Resume of the demo workload through the real controller, five times a second, with
 * the time each call takes and the processor time of this JVM against a run of the same length that
 * only sleeps. Not part of the build; run by hand:
 *
 * <pre>./mvnw -q test -Dtest=SuspendCostProbe -Dprobe.seconds=30 -Dprobe.periodMillis=200</pre>
 *
 * Acts on the demo workload it starts and on nothing else (SAFE-6).
 */
@EnabledOnOs(OS.WINDOWS)
class SuspendCostProbe {

    @Test
    void measure() throws Exception {
        int seconds = Integer.getInteger("probe.seconds", 30);
        int periodMillis = Integer.getInteger("probe.periodMillis", 200);
        String workers = System.getProperty("probe.workers", "8");
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        WindowsProcessController controller = new WindowsProcessController();
        int cycles = seconds * 1000 / periodMillis;
        try (DemoTarget demo =
                DemoTarget.start("--cpu", "50", "--workers", workers, "--duration", (seconds * 3) + "s")) {
            // The JIT and the first native calls are warmed up before anything is measured.
            for (int i = 0; i < 10; i++) {
                SuspendResult warm = controller.suspend(demo.identity());
                controller.resume(warm.suspension().orElseThrow());
            }
            long idleCpu = cpuDuring(os, () -> sleep(Duration.ofSeconds(seconds)));
            long[] suspendNanos = new long[cycles];
            long[] resumeNanos = new long[cycles];
            int[] threads = new int[cycles];
            long cycleCpu = cpuDuring(os, () -> {
                for (int i = 0; i < cycles; i++) {
                    long start = System.nanoTime();
                    SuspendResult suspended = controller.suspend(demo.identity());
                    long held = System.nanoTime();
                    if (!suspended.outcome().succeeded()) {
                        throw new IllegalStateException("Suspend failed: " + suspended.outcome());
                    }
                    threads[i] = suspended.suspension().orElseThrow().threads();
                    sleep(Duration.ofMillis(periodMillis / 2));
                    long resumeStart = System.nanoTime();
                    controller.resume(suspended.suspension().get());
                    long resumed = System.nanoTime();
                    suspendNanos[i] = held - start;
                    resumeNanos[i] = resumed - resumeStart;
                    sleep(Duration.ofMillis(
                            Math.max(0, periodMillis - periodMillis / 2 - (resumed - start) / 1_000_000)));
                }
            });
            double coreShare = (cycleCpu - idleCpu) / (seconds * 1e9);
            System.out.printf(
                    Locale.ROOT,
                    "suspend-cost period=%d ms cycles=%d threads=%d..%d suspend median=%.2f ms p95=%.2f ms max=%.2f ms"
                            + " resume median=%.2f ms p95=%.2f ms max=%.2f ms probe-cpu cycling=%.3f s idle=%.3f s"
                            + " added=%.2f%% of one logical processor, %.3f%% of the machine (%d processors)%n",
                    periodMillis,
                    cycles,
                    Arrays.stream(threads).min().orElse(0),
                    Arrays.stream(threads).max().orElse(0),
                    percentile(suspendNanos, 50),
                    percentile(suspendNanos, 95),
                    percentile(suspendNanos, 100),
                    percentile(resumeNanos, 50),
                    percentile(resumeNanos, 95),
                    percentile(resumeNanos, 100),
                    cycleCpu / 1e9,
                    idleCpu / 1e9,
                    coreShare * 100,
                    coreShare * 100 / os.getAvailableProcessors(),
                    os.getAvailableProcessors());
        }
    }

    private static long cpuDuring(OperatingSystemMXBean os, Runnable work) {
        long before = os.getProcessCpuTime();
        work.run();
        return os.getProcessCpuTime() - before;
    }

    private static double percentile(long[] nanos, int percentile) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, index)] / 1e6;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
