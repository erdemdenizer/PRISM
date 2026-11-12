package prism.srm.application;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.DemoProcess;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.ProcessController;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;
import prism.srm.platform.ThreadHolder;

/**
 * What the emulated CPU limiter costs on this system with the real controller and source
 * (milestone 9 external review, item 1): this JVM's processor time and heap with no limit and with
 * 1, 2 and 4 limits at once, the time of each suspend, how late the limiter's steps run, and the
 * share each target is held to; then one limit at longer cycles (a larger cost factor), and one
 * limit with thousands of extra threads in the system. Not part of the build; run by hand:
 *
 * <pre>./mvnw -q test -Djacoco.skip=true -Dtest=LimiterScalingProbe</pre>
 *
 * Acts on the demo workloads and the thread holder it starts, and on nothing else (SAFE-6).
 */
@EnabledOnOs(OS.WINDOWS)
class LimiterScalingProbe {

    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private static final Duration WARM_UP = Duration.ofSeconds(8);
    private static final Duration WINDOW = Duration.ofSeconds(Integer.getInteger("probe.seconds", 20));

    private final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final HostPlatform host = HostPlatform.create();

    @Test
    void measure() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(scenario("idle", 0, 10, 0));
        lines.add(scenario("one limit", 1, 10, 0));
        lines.add(scenario("two limits", 2, 10, 0));
        lines.add(scenario("four limits", 4, 10, 0));
        lines.add(scenario("one limit, factor 20", 1, 20, 0));
        lines.add(scenario("one limit, factor 33", 1, 33, 0));
        lines.add(scenario("one limit, +5000 threads", 1, 10, 5000));
        lines.forEach(System.out::println);
    }

    private String scenario(String name, int limits, double factor, int extraThreads) throws Exception {
        Process holder = extraThreads > 0 ? ThreadHolder.start(extraThreads, 600) : null;
        Path folder = Files.createTempDirectory("prism-probe");
        TimingController controller = new TimingController(host.processController());
        ScheduledThreadPoolExecutor thread = new ScheduledThreadPoolExecutor(1);
        ConcurrentLinkedQueue<Long> lateness = new ConcurrentLinkedQueue<>();
        LimiterScheduler scheduler = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                thread.execute(task);
            }

            @Override
            public Future<?> schedule(Runnable task, Duration delay) {
                long due = System.nanoTime() + delay.toNanos();
                return thread.schedule(
                        () -> {
                            lateness.add(System.nanoTime() - due);
                            task.run();
                        },
                        delay.toNanos(),
                        TimeUnit.NANOSECONDS);
            }
        };
        ActivityLog activity = new ActivityLog(200, InstantSource.system());
        ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1);
        List<DemoProcess> demos = new ArrayList<>();
        SuspensionJournal journal = SuspensionJournal.open(folder, "probe", SuspensionJournal.onDisk());
        DutyCycleController.Tuning defaults = DutyCycleController.Tuning.DEFAULTS;
        DutyCycleController.Tuning tuning = new DutyCycleController.Tuning(
                defaults.minCycle(),
                Duration.ofSeconds(4),
                factor,
                defaults.minRunShare(),
                defaults.smoothing(),
                defaults.deadBandShare(),
                defaults.deadBandFloor(),
                defaults.stepDown(),
                defaults.stepUp(),
                defaults.intervalsToJudge());
        SuspensionCoordinator coordinator =
                new SuspensionCoordinator(controller, activity, checks, InstantSource.system(), Optional.of(journal));
        LimitService service = new LimitService(
                controller,
                new ProtectionRules(ProcessHandle.current().pid()),
                coordinator,
                host.processSource(),
                activity,
                scheduler,
                System::nanoTime,
                PROCESSORS,
                tuning,
                result -> {},
                snapshot -> {});
        try {
            int workers = Math.min(2, PROCESSORS);
            double demand = 100.0 * workers / PROCESSORS;
            int percent = (int) Math.max(1, Math.round(demand / 2));
            for (int i = 0; i < limits; i++) {
                DemoProcess demo = DemoProcess.start(
                        "--cpu",
                        String.format(Locale.ROOT, "%.3f", Math.floor(1000 * demand) / 1000),
                        "--workers",
                        Integer.toString(workers),
                        "--duration",
                        "300s");
                demos.add(demo);
                service.request(LimitRequest.of(LimitAction.MANAGE, demo.identity(), "demo"));
                service.request(LimitRequest.cpuLimit(demo.identity(), "demo", percent));
            }
            Thread.sleep(WARM_UP.toMillis());
            System.gc();
            long heapBefore = memory.getHeapMemoryUsage().getUsed();
            controller.latencies.clear();
            lateness.clear();
            List<java.time.Duration> cpuBefore = new ArrayList<>();
            for (DemoProcess demo : demos) {
                cpuBefore.add(demo.cpu());
            }
            long jvmBefore = os.getProcessCpuTime();
            long start = System.nanoTime();
            Thread.sleep(WINDOW.toMillis());
            long elapsed = System.nanoTime() - start;
            long jvm = os.getProcessCpuTime() - jvmBefore;
            List<String> held = new ArrayList<>();
            for (int i = 0; i < demos.size(); i++) {
                double used =
                        demos.get(i).cpu().minus(cpuBefore.get(i)).toNanos() * 100.0 / ((double) elapsed * PROCESSORS);
                held.add(String.format(Locale.ROOT, "%.1f", used));
            }
            System.gc();
            long heapAfter = memory.getHeapMemoryUsage().getUsed();
            long[] suspends = controller.latencies.stream()
                    .mapToLong(Long::longValue)
                    .sorted()
                    .toArray();
            long[] late = lateness.stream().mapToLong(Long::longValue).sorted().toArray();
            return String.format(
                    Locale.ROOT,
                    "limiter-scaling: %-26s limits=%d factor=%.0f extraThreads=%d | jvm %.2f %% of a core | heap %d -> %d"
                            + " KiB | suspends %d (%.2f/s) median %.1f p95 %.1f ms | step lateness median %.1f p95 %.1f max"
                            + " %.1f ms | limit %d %% of %.1f %%, held at %s",
                    name,
                    limits,
                    factor,
                    extraThreads,
                    jvm * 100.0 / elapsed,
                    heapBefore / 1024,
                    heapAfter / 1024,
                    suspends.length,
                    suspends.length * 1e9 / elapsed,
                    percentile(suspends, 50) / 1e6,
                    percentile(suspends, 95) / 1e6,
                    percentile(late, 50) / 1e6,
                    percentile(late, 95) / 1e6,
                    late.length == 0 ? 0 : late[late.length - 1] / 1e6,
                    percent,
                    demand,
                    held);
        } finally {
            service.stopForShutdown();
            thread.shutdown();
            thread.awaitTermination(3, TimeUnit.SECONDS);
            coordinator.refuseFurtherSuspensions();
            coordinator.releaseAutomaticReasons(Duration.ofSeconds(3));
            coordinator.finalizeForShutdown(Duration.ofSeconds(3));
            journal.close();
            checks.shutdownNow();
            demos.forEach(DemoProcess::close);
            if (holder != null) {
                holder.destroyForcibly();
                holder.waitFor(10, TimeUnit.SECONDS);
            }
            try (Stream<Path> left = Files.list(folder)) {
                for (Path file : left.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(folder);
        }
    }

    private static long percentile(long[] sorted, int percent) {
        return sorted.length == 0 ? 0 : sorted[Math.min(sorted.length - 1, sorted.length * percent / 100)];
    }

    /** The real controller, timing each suspend. */
    private static final class TimingController implements ProcessController {
        private final ProcessController real;
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        TimingController(ProcessController real) {
            this.real = real;
        }

        @Override
        public Map<ControlAction, Capability> capabilities() {
            return real.capabilities();
        }

        @Override
        public List<String> limitations() {
            return real.limitations();
        }

        @Override
        public Map<Integer, PriorityLevel> listedPriorities() {
            return real.listedPriorities();
        }

        @Override
        public Restorability priorityRestorability() {
            return real.priorityRestorability();
        }

        @Override
        public ProtectionFacts protectionFacts(ProcessIdentity target) {
            return real.protectionFacts(target);
        }

        @Override
        public ControlOutcome liveness(ProcessIdentity target) {
            return real.liveness(target);
        }

        @Override
        public ControlOutcome requestClose(ProcessIdentity target) {
            return real.requestClose(target);
        }

        @Override
        public ControlOutcome terminate(ProcessIdentity target) {
            return real.terminate(target);
        }

        @Override
        public SuspendResult suspend(ProcessIdentity target) {
            long start = System.nanoTime();
            SuspendResult result = real.suspend(target);
            latencies.add(System.nanoTime() - start);
            return result;
        }

        @Override
        public ControlOutcome resume(Suspension suspension) {
            return real.resume(suspension);
        }

        @Override
        public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
            return real.changePriority(target, level);
        }

        @Override
        public StopState stopState(ProcessIdentity target) {
            return real.stopState(target);
        }

        @Override
        public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
            return real.adopt(target, memento);
        }
    }
}
