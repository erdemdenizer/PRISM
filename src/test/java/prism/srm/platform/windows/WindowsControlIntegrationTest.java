package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.InstantSource;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.application.ActivityLog;
import prism.srm.application.SuspensionCoordinator;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.SuspensionReason;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * Process control on the real system, against the demo workload only (SAFE-6): one worker thread
 * kept fully busy, whose CPU time the JDK reads independently of Prism. The waits are measurement
 * windows, not synchronization: CPU time is compared over a stretch of wall time.
 */
@EnabledOnOs(OS.WINDOWS)
@org.junit.jupiter.api.Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class WindowsControlIntegrationTest {

    /** A stretch of wall time over which the CPU time of the workload is compared. */
    private static final Duration WINDOW = Duration.ofMillis(800);

    /** What one fully busy thread surely uses in that time, with room for a busy machine. */
    private static final Duration RUNNING = Duration.ofMillis(250);

    /** What a suspended process may still be charged: the measurement itself is not exact. */
    private static final Duration STOPPED = Duration.ofMillis(30);

    private final WindowsProcessController controller = new WindowsProcessController();

    /** One logical processor's worth of the machine: one worker, fully busy. */
    private static String[] oneBusyWorker() {
        double percent = 100.0 / Runtime.getRuntime().availableProcessors();
        return new String[] {
            "--cpu", String.format(Locale.ROOT, "%.3f", Math.floor(percent * 1000) / 1000), "--duration", "60s"
        };
    }

    private static Duration used(DemoTarget demo) throws InterruptedException {
        Duration before = demo.cpu();
        Thread.sleep(WINDOW);
        return demo.cpu().minus(before);
    }

    @Test
    void suspendStopsEveryThreadAndResumeLetsTheProcessRunAgain() throws Exception {
        try (DemoTarget demo = DemoTarget.start(oneBusyWorker())) {
            assertThat(used(demo)).as("running before").isGreaterThan(RUNNING);

            SuspendResult suspended = controller.suspend(demo.identity());

            assertThat(suspended.outcome().result()).isEqualTo(OperationResult.SUCCESS);
            Suspension suspension = suspended.suspension().orElseThrow();
            assertThat(suspension.threads()).isGreaterThan(1);
            assertThat(used(demo)).as("suspended").isLessThanOrEqualTo(STOPPED);
            assertThat(demo.process().isAlive()).isTrue();

            ControlOutcome resumed = controller.resume(suspension);

            assertThat(resumed.result()).isEqualTo(OperationResult.SUCCESS);
            assertThat(suspension.outstanding()).isZero();
            assertThat(used(demo)).as("running again").isGreaterThan(RUNNING);
        }
    }

    /** CTL-9 with the real controller: a second suspend for the same reason adds nothing to undo. */
    @Test
    void aRepeatedSuspendDoesNotStackAndOneReleaseLetsTheProcessRun() throws Exception {
        try (DemoTarget demo = DemoTarget.start(oneBusyWorker())) {
            SuspensionCoordinator coordinator =
                    new SuspensionCoordinator(controller, new ActivityLog(10, InstantSource.system()), Runnable::run);
            ProcessIdentity identity = demo.identity();

            assertThat(coordinator
                            .suspend(identity, "java", SuspensionReason.USER)
                            .change())
                    .isEqualTo(SuspensionCoordinator.Change.SUSPENDED);
            assertThat(coordinator
                            .suspend(identity, "java", SuspensionReason.USER)
                            .change())
                    .isEqualTo(SuspensionCoordinator.Change.ALREADY_HELD);
            assertThat(used(demo)).isLessThanOrEqualTo(STOPPED);

            assertThat(coordinator.release(identity, SuspensionReason.USER).change())
                    .isEqualTo(SuspensionCoordinator.Change.RESUMED);
            assertThat(used(demo)).as("one release was enough").isGreaterThan(RUNNING);
        }
    }

    @Test
    void everyLevelIsSetAndTheSystemHoldsIt() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--duration", "60s")) {
            for (PriorityLevel level : PriorityLevel.values()) {
                ControlOutcome outcome = controller.changePriority(demo.identity(), level);

                assertThat(outcome.result()).as(level.name()).isEqualTo(OperationResult.SUCCESS);
                assertThat(priorityClassOf(demo)).as(level.name()).isEqualTo(WindowsPriority.priorityClass(level));
            }
        }
    }

    @Test
    void endTaskOnAProcessWithoutAWindowIsUnsupportedAndLeavesItRunning() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--duration", "60s")) {
            assertThat(controller.requestClose(demo.identity()).result()).isEqualTo(OperationResult.UNSUPPORTED);
            assertThat(demo.exitsWithin(Duration.ofMillis(300))).isFalse();
        }
    }

    /** PROC-3: an identity whose start time is not the process's is another process; nothing is done to this one. */
    @Test
    void anIdentityWithAnotherStartTimeIsNotFoundForEveryActionAndNothingHappens() throws Exception {
        try (DemoTarget demo = DemoTarget.start(oneBusyWorker())) {
            ProcessIdentity stale = new ProcessIdentity(
                    demo.identity().pid(), demo.identity().startTime().plusMillis(1));
            // The class the workload starts in is the runner's choice (BELOW_NORMAL on GitHub's
            // Windows runner): what is checked is that it stays as it was, not that it is Normal.
            int before = priorityClassOf(demo);
            // A level it is not in, so that acting on the stale identity would show.
            PriorityLevel other = before == WindowsPriority.priorityClass(PriorityLevel.HIGH)
                    ? PriorityLevel.LOW
                    : PriorityLevel.HIGH;

            assertThat(controller.liveness(stale).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            assertThat(controller.requestClose(stale).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            assertThat(controller.changePriority(stale, other).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            assertThat(controller.suspend(stale).outcome().result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            assertThat(controller.terminate(stale).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);

            assertThat(demo.process().isAlive()).isTrue();
            assertThat(priorityClassOf(demo)).as("the class it started in").isEqualTo(before);
            assertThat(used(demo)).as("not suspended").isGreaterThan(RUNNING);
        }
    }

    @Test
    void forceKillEndsTheProcessAndItIsNotFoundAfterwards() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--duration", "60s")) {
            ControlOutcome outcome = controller.terminate(demo.identity());

            assertThat(outcome.result()).isEqualTo(OperationResult.SUCCESS);
            assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();
            assertThat(controller.liveness(demo.identity()).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            assertThat(controller.terminate(demo.identity()).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
        }
    }

    /** A suspended process that is killed by someone else leaves nothing to undo and nothing held. */
    @Test
    void resumingAProcessThatWasKilledWhileSuspendedReleasesEverythingAndSaysItHasExited() throws Exception {
        try (DemoTarget demo = DemoTarget.start("--duration", "60s")) {
            Suspension suspension =
                    controller.suspend(demo.identity()).suspension().orElseThrow();
            demo.process().destroyForcibly();
            assertThat(demo.exitsWithin(Duration.ofSeconds(10))).isTrue();

            assertThat(controller.liveness(demo.identity()).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            // Nothing was resumed, and the result does not say so: the process is gone.
            assertThat(controller.resume(suspension).result()).isEqualTo(OperationResult.PROCESS_NOT_FOUND);
            assertThat(suspension.outstanding()).isZero();
        }
    }

    /** Read independently of the controller: a fresh handle and GetPriorityClass. */
    private static int priorityClassOf(DemoTarget demo) {
        JnaWin32 win32 = new JnaWin32();
        Win32.Opened opened = win32.openProcess(
                WindowsProcessAccess.QUERY, (int) demo.identity().pid());
        assertThat(opened.ok()).isTrue();
        try {
            return win32.priorityClass(opened.handle()).orElseThrow();
        } finally {
            win32.close(opened.handle());
        }
    }
}
