package prism.srm.platform;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.InstantSource;
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
import prism.srm.application.NativeLimitLedger;
import prism.srm.application.ProtectionRules;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;

/**
 * Measures what one native CPU limit costs Prism on this system (milestone 10, plan §11.5): the
 * processor time of this JVM while the limit service holds the demo workload at its limit through
 * the system's limiter, against the same time spent asleep, and the share the workload got. Not part
 * of the build; run by hand where a native limiter exists:
 *
 * <pre>./mvnw -q test -Dtest=NativeLimitCostProbe -Dprobe.seconds=30 -Dprobe.limit=10</pre>
 *
 * Acts on the demo workload it starts and on nothing else (SAFE-6); clears the limit before it ends.
 */
class NativeLimitCostProbe {

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    @Test
    void measure() throws Exception {
        int seconds = Integer.getInteger("probe.seconds", 30);
        int limit = Integer.getInteger("probe.limit", 10);
        int processors = Runtime.getRuntime().availableProcessors();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        HostPlatform host = HostPlatform.create();
        ResourceLimiter limiter = host.resourceLimiter();
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
            long idle = cpuOver(os, seconds);
            SuspensionCoordinator coordinator = new SuspensionCoordinator(
                    host.processController(), activity, checks, InstantSource.system(), Optional.of(journal));
            NativeLimitLedger ledger = new NativeLimitLedger(
                    Optional.of(journalFolder.path()), "probe", SuspensionJournal.onDisk(), Runnable::run);
            LimitService limits = new LimitService(
                    host.processController(),
                    new ProtectionRules(ProcessHandle.current().pid()),
                    coordinator,
                    host.processSource(),
                    activity,
                    scheduler,
                    System::nanoTime,
                    processors,
                    limiter,
                    ledger,
                    result -> System.out.println("limit-cost: " + result.text() + " " + result.explanation()),
                    snapshot -> {});
            limits.request(LimitRequest.of(LimitAction.MANAGE, demo.identity(), "demo"));
            limits.request(LimitRequest.cpuLimit(demo.identity(), "demo", limit));
            Thread.sleep(3_000);
            long demoBefore = demo.cpu().toNanos();
            long limiting = cpuOver(os, seconds);
            double demoUse = 100.0 * (demo.cpu().toNanos() - demoBefore) / (seconds * 1e9 * processors);
            String status = limits.snapshot()
                    .find(demo.identity())
                    .map(view ->
                            view.cpu().status() + " " + view.cpu().inEffect().level())
                    .orElse("none");
            limits.stopForShutdown();
            int left = limits.releaseNativeLimits(Duration.ofSeconds(2)).notCleared();
            thread.shutdown();
            thread.awaitTermination(2, TimeUnit.SECONDS);
            coordinator.refuseFurtherSuspensions();
            coordinator.finalizeForShutdown(Duration.ofSeconds(2));

            System.out.printf(
                    Locale.ROOT,
                    "limit-cost: native os=%s processors=%d limit=%d%% seconds=%d jvm-idle=%.2f%%-of-a-core"
                            + " jvm-limiting=%.2f%%-of-a-core limiter=%.2f%%-of-a-core workload=%.1f%%-of-the-machine"
                            + " status=%s not-cleared=%d%n",
                    System.getProperty("os.name"),
                    processors,
                    limit,
                    seconds,
                    idle * 100.0 / (seconds * 1e9),
                    limiting * 100.0 / (seconds * 1e9),
                    (limiting - idle) * 100.0 / (seconds * 1e9),
                    demoUse,
                    status,
                    left);
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
