package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;

/**
 * The evidence that the resident memory of a process, as the listing reads it, follows what the
 * process holds (plan A7.6, O17b): the memory limit watched by OOM Guard (LIM-9) is judged by it.
 * The demo workload holds M of touched memory, a distinct stamp on every page so that no page is
 * combined with another; M is at most 256 MiB and never above the demo's own cap (DEMO-2).
 *
 * <p>Windows and Linux fail below the thresholds: the reading is what the resident limit relies on
 * there. macOS reports what it measured: the compressor may move pages out of physical memory, and
 * the resident metric stays unvalidated there (O17b a).
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX, OS.MAC})
class OomResidentValidationTest {

    private static final long MIB = 1L << 20;
    private static final long M = 256 * MIB;
    private static final Duration BOUND = Duration.ofSeconds(60);

    private final ProcessSource listing = HostPlatform.create().processSource();

    @Test
    void theResidentMemoryOfTheDemoIsWhatItHoldsAndItsRecordIsGoneWhenItExits() throws Exception {
        long idle;
        try (DemoProcess quiet = DemoProcess.start("--duration", "1m")) {
            idle = resident(quiet.identity())
                    .orElseThrow(() -> new AssertionError("the listing shows no resident memory"));
        }

        long first;
        long second;
        ProcessIdentity held;
        try (DemoProcess demo = DemoProcess.start("--memory", (M / MIB) + "MiB", "--duration", "2m")) {
            held = demo.identity();
            awaitAllocated(demo);
            first = listedResident(held);
            Thread.sleep(1_000);
            second = listedResident(held);
            demo.process().destroyForcibly();
            assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
        }
        boolean gone = awaitGone(held);
        String line = String.format(
                Locale.ROOT,
                "oom-resident: os=%s idle=%dMiB M=%dMiB first=%dMiB second=%dMiB gone=%s",
                System.getProperty("os.name"),
                idle / MIB,
                M / MIB,
                first / MIB,
                second / MIB,
                gone);
        System.out.println(line);

        assertThat(gone)
                .as("the record of an exited process leaves the listing: " + line)
                .isTrue();
        if (OS.MAC.isCurrentOs()) {
            if (idle >= M / 2 || first < M || second < M) {
                System.out.println("oom-resident: signal not characterised on macOS (reported, not failed; O17b)");
            }
            return;
        }
        assertThat(idle).as("the demo without memory: " + line).isLessThan(M / 2);
        assertThat(first).as("the first listing after the allocation: " + line).isGreaterThanOrEqualTo(M);
        assertThat(second).as("the next listing: " + line).isGreaterThanOrEqualTo(M);
    }

    private OptionalLong resident(ProcessIdentity identity) {
        return listing.processes().stream()
                .filter(reading -> reading.identity().equals(identity))
                .map(ProcessReading::residentBytes)
                .findFirst()
                .orElse(OptionalLong.empty());
    }

    /**
     * The resident memory of the first listing that shows the process with a value; a listing taken
     * while the system is busy may leave it out, which is not a reading of zero.
     */
    private long listedResident(ProcessIdentity identity) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            OptionalLong value = resident(identity);
            if (value.isPresent()) {
                return value.getAsLong();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the listing did not show the resident memory of the demo within 10 s");
    }

    private boolean awaitGone(ProcessIdentity identity) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            if (listing.processes().stream()
                    .noneMatch(reading -> reading.identity().equals(identity))) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static void awaitAllocated(DemoProcess demo) throws InterruptedException {
        long end = System.nanoTime() + BOUND.toNanos();
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
        throw new AssertionError("the demo did not say it allocated within " + BOUND + ": " + demo.output());
    }
}
