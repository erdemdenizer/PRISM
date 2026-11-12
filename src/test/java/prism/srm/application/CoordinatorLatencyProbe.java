package prism.srm.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.DemoProcess;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.ProcessController;

/**
 * How long the coordinator's lock holds up a release on the real system (milestone 9 review,
 * item 3): one thread suspends and resumes a demo workload for the user, as fast as it can, while
 * another releases and suspends a second one for the limiter and times each release; and what one
 * durable, checked write of the journal takes on this disk. Not part of the build; run by hand:
 *
 * <pre>./mvnw -q test -Djacoco.skip=true -Dtest=CoordinatorLatencyProbe</pre>
 *
 * Acts on the demo workloads it starts and on nothing else (SAFE-6).
 */
@EnabledOnOs(OS.WINDOWS)
class CoordinatorLatencyProbe {

    @Test
    void measure() throws Exception {
        Path folder = Files.createTempDirectory("prism-latency");
        ExecutorService journalThread = Executors.newSingleThreadExecutor();
        try (DemoProcess user = DemoProcess.start("--cpu", "25", "--workers", "4", "--duration", "120s");
                DemoProcess limited = DemoProcess.start("--cpu", "25", "--workers", "4", "--duration", "120s")) {
            // One durable, checked write of a journal of a few entries, on this disk.
            long[] writes = new long[200];
            try (SuspensionJournal journal = SuspensionJournal.open(folder, "w", SuspensionJournal.onDisk())) {
                List<JournalEntry> entries = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    entries.add(new JournalEntry(
                            new prism.srm.domain.ProcessIdentity(1000 + i, Instant.EPOCH),
                            "demo",
                            EnumSet.of(SuspensionReason.CPU_LIMITER),
                            Optional.empty(),
                            Optional.of("CPU limit 30 %"),
                            "w",
                            Instant.EPOCH,
                            Instant.EPOCH,
                            false,
                            false));
                }
                for (int i = 0; i < writes.length; i++) {
                    long start = System.nanoTime();
                    journal.write(entries.subList(0, 1 + i % 4));
                    writes[i] = System.nanoTime() - start;
                }
            }

            ProcessController controller = HostPlatform.create().processController();
            SuspensionJournal journal = SuspensionJournal.open(folder, "c", SuspensionJournal.onDisk());
            SuspensionCoordinator coordinator = new SuspensionCoordinator(
                    controller,
                    new ActivityLog(100, InstantSource.system()),
                    Executors.newSingleThreadExecutor(),
                    InstantSource.system(),
                    Optional.of(journal),
                    journalThread);
            coordinator.declare(limited.identity(), "limited", SuspensionReason.CPU_LIMITER, "limit");
            AtomicBoolean running = new AtomicBoolean(!Boolean.getBoolean("probe.alone"));
            Thread userThread = new Thread(() -> {
                while (running.get()) {
                    coordinator.suspend(user.identity(), "user", SuspensionReason.USER);
                    coordinator.release(user.identity(), SuspensionReason.USER);
                }
            });
            userThread.start();
            List<Long> releases = new ArrayList<>();
            List<Long> suspends = new ArrayList<>();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < end) {
                long suspending = System.nanoTime();
                coordinator.suspend(limited.identity(), "limited", SuspensionReason.CPU_LIMITER);
                suspends.add(System.nanoTime() - suspending);
                Thread.sleep(50);
                long start = System.nanoTime();
                coordinator.release(limited.identity(), SuspensionReason.CPU_LIMITER);
                releases.add(System.nanoTime() - start);
                Thread.sleep(50);
            }
            running.set(false);
            userThread.join(5_000);
            coordinator.refuseFurtherSuspensions();
            coordinator.releaseAutomaticReasons(java.time.Duration.ofSeconds(2));
            coordinator.finalizeForShutdown(java.time.Duration.ofSeconds(2));
            coordinator.closeJournal(java.time.Duration.ofSeconds(2));

            long[] waits = releases.stream().mapToLong(Long::longValue).sorted().toArray();
            long[] taken = suspends.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf(
                    Locale.ROOT,
                    "coordinator-latency: alone=%b limiter suspend median %.1f p95 %.1f ms%n",
                    Boolean.getBoolean("probe.alone"),
                    taken[taken.length / 2] / 1e6,
                    taken[taken.length * 95 / 100] / 1e6);
            Arrays.sort(writes);
            System.out.printf(
                    Locale.ROOT,
                    "coordinator-latency: journal write median %.2f p95 %.2f max %.2f ms | limiter release while the user"
                            + " suspends another process: n=%d median %.1f p95 %.1f max %.1f ms%n",
                    writes[writes.length / 2] / 1e6,
                    writes[writes.length * 95 / 100] / 1e6,
                    writes[writes.length - 1] / 1e6,
                    waits.length,
                    waits[waits.length / 2] / 1e6,
                    waits[waits.length * 95 / 100] / 1e6,
                    waits[waits.length - 1] / 1e6);
        } finally {
            journalThread.shutdownNow();
            try (Stream<Path> left = Files.list(folder)) {
                for (Path file : left.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(folder);
        }
    }
}
