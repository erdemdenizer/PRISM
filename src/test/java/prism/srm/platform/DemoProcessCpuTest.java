package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;

/**
 * The CPU time of the demo workload, which the Linux and macOS control tests measure a stop by. On
 * macOS the JDK does not read it for a child, so it comes from the process listing there. This ties
 * the listing's reading to one taken on its own: the JDK's where it exists (Windows, Linux), and on
 * macOS the time {@code ps} reports, which converts the kernel's units by itself. Each reading of
 * the listing lies between two readings of the other, so no tolerance for the time in between is
 * needed. Reads only; the workload is the test's own (SAFE-6).
 */
class DemoProcessCpuTest {

    /** The granularity of the readings: ps prints hundredths, Windows counts in clock ticks. */
    private static final Duration STEP = Duration.ofMillis(50);

    @Test
    void theListingReadsTheCpuTimeOfTheWorkloadAsItGrows() throws Exception {
        try (DemoProcess demo =
                DemoProcess.start("--cpu", DemoProcess.oneWorkerOfCpu(), "--workers", "1", "--duration", "30s")) {
            long start = System.nanoTime();
            Duration first = bracketed(demo);
            Thread.sleep(800);
            Duration second = bracketed(demo);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(second.minus(first))
                    .as("CPU while working, by the listing")
                    .isGreaterThan(Duration.ofMillis(100))
                    .isLessThanOrEqualTo(
                            elapsed.multipliedBy(Runtime.getRuntime().availableProcessors())
                                    .plus(STEP));
        }
    }

    /** The listing's reading, checked to lie between two independent ones taken around it. */
    private static Duration bracketed(DemoProcess demo) throws Exception {
        Duration before = independent(demo);
        Duration listed = demo.cpuByListing();
        Duration after = independent(demo);
        System.out.println("demo cpu: independent " + before + " .. " + after + ", listing " + listed);

        assertThat(listed)
                .as("the listing between two independent readings")
                .isGreaterThanOrEqualTo(before.minus(STEP))
                .isLessThanOrEqualTo(after.plus(STEP));
        return listed;
    }

    private static Duration independent(DemoProcess demo) throws Exception {
        Optional<Duration> byJdk = demo.cpuByJdk();
        if (byJdk.isPresent()) {
            return byJdk.get();
        }
        assertThat(OS.MAC.isCurrentOs())
                .as("the JDK reads the CPU time of a child everywhere but macOS")
                .isTrue();
        return psTime(demo.identity().pid());
    }

    /**
     * The {@code time} column of macOS ps: minutes, seconds and hundredths ({@code m:ss.hh}, with the
     * locale's decimal mark), or hours before them.
     */
    private static Duration psTime(long pid) throws Exception {
        String[] parts = Ps.column(pid, "time").split(":");
        double seconds = Double.parseDouble(parts[parts.length - 1].replace(',', '.'));
        long minutes = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            minutes = minutes * 60 + Long.parseLong(parts[i]);
        }
        return Duration.ofMinutes(minutes).plusMillis(Math.round(seconds * 1000));
    }
}
