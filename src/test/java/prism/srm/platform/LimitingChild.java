package prism.srm.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import prism.srm.application.ActivityLog;
import prism.srm.application.LimitService;
import prism.srm.application.LimiterScheduler;
import prism.srm.application.ProtectionRules;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.application.SuspensionJournal;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;

/**
 * A Prism stand-in for {@link CpuLimitIntegrationTest}: in a JVM of its own, it limits the demo
 * workload with the PID it is given, writing its journal into the folder it is given, says
 * "limiting" once its own journal holds the limit's entry as written and read back and the limit
 * is at work (or "not-limiting" and why, after 30 s), and waits to be killed. Nothing ends it gracefully: the
 * test kills it as Task Manager or {@code kill -9} would.
 *
 * <p>The journal is written as the application writes it: on a thread of its own, after the
 * coordinator's lock is let go, with the executor of {@code MonitoringRuntime} (one daemon thread
 * named {@code srm-journal}, 64 tasks waiting at most, a refusal when full), so that what a hard
 * kill leaves on disk is what the application would leave.
 *
 * <p>With {@code hold}, it stops the limit in a stop phase, for a crash there: once the
 * coordinator itself reports that it holds the workload for the CPU limiter, the limiter's thread
 * runs nothing more, so the step that would continue the workload never comes; the child says
 * "held" and waits to be killed. What the workload does is not asked; the test checks it from
 * outside.
 *
 * <p>Arguments: the journal folder, the PID of the demo workload, the limit in percent, and
 * optionally {@code hold}.
 */
public final class LimitingChild {

    private LimitingChild() {}

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        long pid = Long.parseLong(args[1]);
        int percent = Integer.parseInt(args[2]);
        boolean hold = args.length > 3 && args[3].equals("hold");
        ProcessHandle demo = ProcessHandle.of(pid).orElseThrow();
        ProcessIdentity identity =
                new ProcessIdentity(pid, demo.info().startInstant().orElseThrow());

        HostPlatform host = HostPlatform.create();
        ScheduledThreadPoolExecutor thread = new ScheduledThreadPoolExecutor(1);
        AtomicReference<SuspensionCoordinator> held = new AtomicReference<>();
        LimiterScheduler scheduler = new LimiterScheduler() {
            @Override
            public void execute(Runnable task) {
                thread.execute(gated(task));
            }

            @Override
            public Future<?> schedule(Runnable task, Duration delay) {
                return thread.schedule(gated(task), delay.toNanos(), TimeUnit.NANOSECONDS);
            }

            private Runnable gated(Runnable task) {
                return () -> {
                    SuspensionCoordinator coordinator = held.get();
                    if (coordinator != null && coordinator.heldFor(identity).contains(SuspensionReason.CPU_LIMITER)) {
                        // The workload is suspended by this limit and nothing will continue it.
                        System.out.println("held");
                        System.out.flush();
                        LockSupport.park();
                        return;
                    }
                    task.run();
                };
            }
        };
        ActivityLog activity = new ActivityLog(100, InstantSource.system());
        SuspensionJournal journal = SuspensionJournal.open(
                folder,
                SuspensionJournal.instanceName(
                        ProcessHandle.current().pid(),
                        ProcessHandle.current().info().startInstant()),
                SuspensionJournal.onDisk());
        // As MonitoringRuntime builds the journal's executor.
        ThreadPoolExecutor journalThread = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                task -> {
                    Thread writer = new Thread(task, "srm-journal");
                    writer.setDaemon(true);
                    return writer;
                },
                new ThreadPoolExecutor.AbortPolicy());
        SuspensionCoordinator coordinator = new SuspensionCoordinator(
                host.processController(),
                activity,
                new ScheduledThreadPoolExecutor(1),
                InstantSource.system(),
                Optional.of(journal),
                journalThread);
        LimitService limits = new LimitService(
                host.processController(),
                new ProtectionRules(ProcessHandle.current().pid()),
                coordinator,
                host.processSource(),
                activity,
                scheduler,
                System::nanoTime,
                Runtime.getRuntime().availableProcessors(),
                result -> System.out.println("result " + result.text()),
                snapshot -> {});
        if (hold) {
            held.set(coordinator);
        }
        limits.request(LimitRequest.of(LimitAction.MANAGE, identity, "demo"));
        limits.request(LimitRequest.cpuLimit(identity, "demo", percent));
        if (hold) {
            Thread.sleep(Long.MAX_VALUE);
        }
        String state = "";
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        boolean limiting = false;
        while (!limiting && System.nanoTime() < end) {
            // The entry as the journal last wrote and read it back: on disk before the first suspend.
            boolean recorded = journal.entries().stream()
                    .anyMatch(entry -> entry.identity().equals(identity)
                            && entry.reasons().contains(SuspensionReason.CPU_LIMITER));
            Optional<prism.srm.domain.LimitStatus> status =
                    limits.snapshot().find(identity).map(view -> view.cpu().status());
            boolean atWork = status.filter(at -> at == prism.srm.domain.LimitStatus.ADJUSTING
                            || at.name().startsWith("ACTIVE"))
                    .isPresent();
            state = "recorded=" + recorded + " status=" + status.map(Enum::name).orElse("none");
            limiting = recorded && atWork;
            if (!limiting) {
                Thread.sleep(50);
            }
        }
        System.out.println(limiting ? "limiting" : "not-limiting " + state);
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
