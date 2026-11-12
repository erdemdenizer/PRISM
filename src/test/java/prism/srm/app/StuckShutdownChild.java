package prism.srm.app;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import prism.srm.application.ActivityLog;
import prism.srm.application.FakeProcessController;
import prism.srm.application.FakeProcessSource;
import prism.srm.application.FakeSystemMetricsSource;

/**
 * A JVM that runs the real runtime with a sampling cycle that answers no interrupt, as a stuck native
 * call does, then asks for the controlled shutdown from its main thread and leaves. Nothing calls
 * {@code System.exit}: the process has to end by itself, as the application does (LIFE-1).
 */
public final class StuckShutdownChild {

    private StuckShutdownChild() {}

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);
        FakeSystemMetricsSource system = new FakeSystemMetricsSource();
        CountDownLatch stuck = new CountDownLatch(1);
        system.onCpuReading = () -> {
            stuck.countDown();
            while (true) {
                try {
                    Thread.sleep(Duration.ofHours(1));
                } catch (InterruptedException ignored) {
                    // A native call does not answer an interrupt either.
                }
            }
        };
        FakeProcessSource processes = new FakeProcessSource();
        processes.list(List.of(FakeProcessSource.reading(FakeProcessSource.identity(4_242, 10), 100, 0, 0)));
        ActivityLog activity = new ActivityLog(100, () -> Instant.EPOCH);
        MonitoringRuntime runtime = new MonitoringRuntime(
                () -> new PlatformSources(
                        system, processes, new FakeProcessController(), 1, Optional.of(folder), "9-9"),
                System::nanoTime,
                activity,
                new MonitoringRuntime.Listeners(
                        snapshot -> {},
                        listing -> {},
                        details -> {},
                        result -> {},
                        profile -> {},
                        held -> {},
                        failures -> {},
                        limits -> {},
                        result -> {},
                        recovered -> {},
                        check -> {},
                        result -> {}),
                Duration.ofSeconds(1),
                (thread, failure) -> {},
                MonitoringRuntime.RECOVERY_RETRIES);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::releaseForExit, "srm-exit-hook"));
        runtime.start();
        stuck.await();
        System.out.println("stuck");
        System.out.flush();
        runtime.shutdown();
    }
}
