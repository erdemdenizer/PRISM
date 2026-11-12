package prism.srm.platform;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.application.ActivityLog;
import prism.srm.application.JournalFolder;
import prism.srm.application.LimitService;
import prism.srm.application.LimiterScheduler;
import prism.srm.application.ProtectionRules;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;

/**
 * Measures what one emulated CPU limit costs on this system (milestone 9, plan §8.3): the processor
 * time of this JVM while the limiter holds the demo workload at its limit, against the same time
 * spent asleep; and what one read of a process's processor time takes through the source. Not part
 * of the build; run by hand:
 *
 * <pre>./mvnw -q test -Dtest=LimitCostProbe -Dprobe.seconds=30 -Dprobe.limit=10</pre>
 *
 * Acts on the demo workload it starts and on nothing else (SAFE-6).
 */
class LimitCostProbe {

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    @Test
    void measure() throws Exception {
        int seconds = Integer.getInteger("probe.seconds", 30);
        int limit = Integer.getInteger("probe.limit", 10);
        int processors = Runtime.getRuntime().availableProcessors();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        HostPlatform host = HostPlatform.create();
        ProcessSource source = host.processSource();
        ScheduledThreadPoolExecutor thread = new ScheduledThreadPoolExecutor(1);
        LimiterScheduler scheduler = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                thread.execute(task);
            }

            @Override
            public Future<?> schedule(Runnable task, Duration delay) {
                return thread.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
            }
        };
        ActivityLog activity = new ActivityLog(100, InstantSource.system());
        ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1);
        try (SuspensionJournal journal =
                        SuspensionJournal.open(journalFolder.path(), "probe", SuspensionJournal.onDisk());
                DemoProcess demo = DemoProcess.start(
                        "--cpu",
                        String.format(
                                Locale.ROOT,
                                "%.3f",
                                Math.floor(100_000.0 * Math.min(4, processors) / processors) / 1000),
                        "--workers",
                        Integer.toString(Math.min(4, processors)),
                        "--duration",
                        (seconds * 3) + "s")) {
            // The time one read of the processor time takes, warm.
            long[] reads = new long[200];
            for (int i = 0; i < 50; i++) {
                source.cpuTime(demo.identity());
            }
            for (int i = 0; i < reads.length; i++) {
                long start = System.nanoTime();
                source.cpuTime(demo.identity());
                reads[i] = System.nanoTime() - start;
            }
            Arrays.sort(reads);

            // This JVM asleep, then limiting, over the same time.
            long idle = cpuOver(os, seconds);
            SuspensionCoordinator coordinator = new SuspensionCoordinator(
                    host.processController(), activity, checks, InstantSource.system(), Optional.of(journal));
            LimitService limits = new LimitService(
                    host.processController(),
                    new ProtectionRules(ProcessHandle.current().pid()),
                    coordinator,
                    source,
                    activity,
                    scheduler,
                    System::nanoTime,
                    processors,
                    result -> {},
                    snapshot -> {});
            limits.request(LimitRequest.of(LimitAction.MANAGE, demo.identity(), "demo"));
            limits.request(LimitRequest.cpuLimit(demo.identity(), "demo", limit));
            Thread.sleep(5_000);
            long limiting = cpuOver(os, seconds);
            String status = limits.snapshot()
                    .find(demo.identity())
                    .map(view -> view.cpu().status() + " " + view.cpu().measuredPercent())
                    .orElse("none");
            limits.stopForShutdown();
            thread.shutdown();
            thread.awaitTermination(2, TimeUnit.SECONDS);
            coordinator.refuseFurtherSuspensions();
            coordinator.releaseAutomaticReasons(Duration.ofSeconds(2));
            coordinator.finalizeForShutdown(Duration.ofSeconds(2));

            System.out.printf(
                    Locale.ROOT,
                    "limit-cost: os=%s processors=%d limit=%d%% seconds=%d cpuTime-read median=%.3f ms p95=%.3f ms"
                            + " jvm-idle=%.2f%%-of-a-core jvm-limiting=%.2f%%-of-a-core limiter=%.2f%%-of-a-core"
                            + " status=%s%n",
                    System.getProperty("os.name"),
                    processors,
                    limit,
                    seconds,
                    reads[reads.length / 2] / 1e6,
                    reads[reads.length * 95 / 100] / 1e6,
                    idle * 100.0 / (seconds * 1e9),
                    limiting * 100.0 / (seconds * 1e9),
                    (limiting - idle) * 100.0 / (seconds * 1e9),
                    status);
        } finally {
            thread.shutdownNow();
            checks.shutdownNow();
        }
    }

    /** The processor time of this JVM, in nanoseconds, over the seconds. */
    private static long cpuOver(OperatingSystemMXBean os, int seconds) throws InterruptedException {
        long before = os.getProcessCpuTime();
        Thread.sleep(seconds * 1000L);
        return os.getProcessCpuTime() - before;
    }
}
