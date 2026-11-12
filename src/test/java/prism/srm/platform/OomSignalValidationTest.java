package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The evidence that the memory still available, as Prism reads it, follows what processes take
 * (plan A15.4, P1-7, ADR-0015 §7): the reading falls when the demo workload holds M of touched
 * memory and rises again when it exits. M is small (at most 512 MiB, at most a quarter of what is
 * available, never above the demo's own cap), so no memory pressure is made (DEMO-2).
 *
 * <p>On Windows and Linux the signal is documented and this test is the evidence that Prism reads
 * it: below the thresholds the test fails, it never skips. On macOS it only reports what it
 * measured: the compressor may keep pages that are nearly all zero out of physical memory, so a
 * smaller fall there is no defect, and the signal stays unvalidated (O17).
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX, OS.MAC})
class OomSignalValidationTest {

    private static final long MIB = 1L << 20;
    private static final long MOST = 512 * MIB;
    private static final Duration ALLOCATION_BOUND = Duration.ofSeconds(60);
    private static final Duration LOWEST_WINDOW = Duration.ofSeconds(3);
    private static final Duration RECOVERY_BOUND = Duration.ofSeconds(10);

    private final SystemMetricsSource source = HostPlatform.create().systemMetricsSource();

    @Test
    void theAvailableMemoryFallsWhileTheDemoHoldsMemoryAndRisesWhenItExits() throws Exception {
        long baseline = stableBaseline();
        long total = source.memory().totalBytes();
        long cap = Math.min(1L << 30, physicalMemory() / 4);
        long m = Math.min(Math.min(MOST, cap), baseline / 4) / MIB * MIB;
        if (m < 64 * MIB || baseline < 2 * m) {
            fail("not enough free memory to validate: available " + baseline / MIB + " MiB of " + total / MIB
                    + " MiB, M " + m / MIB + " MiB");
        }

        long lowest;
        long highest;
        try (DemoProcess demo = DemoProcess.start("--memory", (m / MIB) + "MiB", "--duration", "2m")) {
            awaitAllocated(demo);
            lowest = lowestOver(LOWEST_WINDOW);
            demo.process().destroyForcibly();
            assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
            highest = highestUntil(lowest + m / 2, RECOVERY_BOUND);
        }
        long fall = baseline - lowest;
        long recovery = highest - lowest;
        String line = String.format(
                Locale.ROOT,
                "oom-signal: os=%s total=%dMiB baseline=%dMiB M=%dMiB fall=%dMiB (%.2f M) recovery=%dMiB (%.2f M)",
                System.getProperty("os.name"),
                total / MIB,
                baseline / MIB,
                m / MIB,
                fall / MIB,
                (double) fall / m,
                recovery / MIB,
                (double) recovery / m);
        System.out.println(line);

        if (OS.MAC.isCurrentOs()) {
            if (fall < m / 2 || recovery < m / 2) {
                System.out.println("oom-signal: signal not characterised on macOS (reported, not failed; O17)");
            }
            return;
        }
        String causes = " (possible causes: other processes changing their memory meanwhile, a parallel test, a"
                + " container that reads the memory of the host)";
        assertThat(fall).as("the fall while the demo holds M: " + line + causes).isGreaterThanOrEqualTo(m / 2);
        assertThat(recovery)
                .as("the rise after the demo exits: " + line + causes)
                .isGreaterThanOrEqualTo(m / 2);
    }

    /** The median of three readings a second apart, when they spread by no more than a quarter of 512 MiB. */
    private long stableBaseline() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Long> readings = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (i > 0) {
                    Thread.sleep(1_000);
                }
                readings.add(source.memory().availableBytes());
            }
            readings.sort(null);
            long spread = readings.get(2) - readings.get(0);
            long m = Math.min(MOST, readings.get(1) / 4);
            if (spread <= m / 4) {
                return readings.get(1);
            }
            System.out.println("oom-signal: baseline unstable, spread " + spread / MIB + " MiB; measuring again");
        }
        throw new AssertionError("baseline unstable: the available memory moved too much while nothing was started");
    }

    private static void awaitAllocated(DemoProcess demo) throws InterruptedException {
        long end = System.nanoTime() + ALLOCATION_BOUND.toNanos();
        while (System.nanoTime() < end) {
            for (String line : demo.output()) {
                if (line.startsWith("event=memory status=allocated")) {
                    return;
                }
                if (line.startsWith("event=memory status=failed")) {
                    throw new AssertionError("the demo could not allocate: " + line);
                }
            }
            if (!demo.process().isAlive()) {
                throw new AssertionError("the demo exited: " + demo.output());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the demo did not say it allocated within " + ALLOCATION_BOUND + ": " + demo.output());
    }

    /** The lowest reading over the window; one reading may be older than the last change (OSHI memoizes it). */
    private long lowestOver(Duration window) throws InterruptedException {
        long lowest = Long.MAX_VALUE;
        long end = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < end) {
            lowest = Math.min(lowest, source.memory().availableBytes());
            Thread.sleep(100);
        }
        return lowest;
    }

    /** The highest reading until it reaches the target, within the bound. */
    private long highestUntil(long target, Duration bound) throws InterruptedException {
        long highest = Long.MIN_VALUE;
        long end = System.nanoTime() + bound.toNanos();
        while (System.nanoTime() < end && highest < target) {
            highest = Math.max(highest, source.memory().availableBytes());
            Thread.sleep(100);
        }
        return highest;
    }

    /** The physical memory as the demo's cap reads it (the JDK's operating system bean). */
    private static long physicalMemory() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean
                ? bean.getTotalMemorySize()
                : 0;
    }
}
