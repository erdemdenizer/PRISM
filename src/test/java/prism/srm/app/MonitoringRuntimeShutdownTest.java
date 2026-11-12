package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import prism.srm.application.ActivityLog;
import prism.srm.application.FakeProcessController;
import prism.srm.application.FakeProcessSource;
import prism.srm.application.FakeSystemMetricsSource;
import prism.srm.application.JournalFolder;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.CpuTimeReading;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;

/**
 * LIFE-1 and LIFE-3 step by step, with the real executors against fake sources: the whole order in
 * one place, and each step failing in turn while the limiter holds one process and the user has
 * suspended another. Injected before its action, a step's failure is the worst case: it did nothing.
 */
class MonitoringRuntimeShutdownTest {

    private static final long PATIENCE_SECONDS = 20;
    private static final ProcessIdentity LIMITED = FakeProcessSource.identity(4_242, 10);
    private static final ProcessIdentity USERS = FakeProcessSource.identity(5_555, 20);

    /** The order of LIFE-1: stop and wait, release, clear native limits, release handles, stop executors. */
    private static final List<String> STEPS = List.of(
            "stop system monitoring",
            "stop process monitoring",
            "wait for start-up to end",
            "stop looking at earlier sessions",
            "refuse new suspensions and limits",
            "stop limiting",
            "stop the sampling executors",
            "stop reading process details",
            "refuse new process actions",
            "finish process actions in flight",
            "release automatic suspensions",
            "restore priorities OOM Guard lowered",
            "finalize suspensions",
            "clear native limits",
            "close the suspension journal",
            "wait for system monitoring in flight",
            "wait for process monitoring in flight",
            "wait for the journal in flight",
            "wait for a read of process details in flight");

    @RegisterExtension
    final JournalFolder journalFolder = new JournalFolder();

    private final FakeSystemMetricsSource system = new FakeSystemMetricsSource();
    private final FakeProcessSource processes = new FakeProcessSource();
    private final FakeProcessController controller = new FakeProcessController();
    private final ActivityLog activity = new ActivityLog(500, () -> Instant.EPOCH);
    private final List<LimitResult> limitResults = new CopyOnWriteArrayList<>();
    private final List<ProcessControlResult> controlResults = new CopyOnWriteArrayList<>();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private final List<String> stepsReached = new CopyOnWriteArrayList<>();
    private MonitoringRuntime runtime;

    MonitoringRuntimeShutdownTest() {
        long started = System.nanoTime();
        processes.onCpuTime = identity -> new CpuTimeReading.Read((System.nanoTime() - started) / 1_000_000 * 8);
        processes.list(
                List.of(FakeProcessSource.reading(LIMITED, 100, 0, 0), FakeProcessSource.reading(USERS, 100, 0, 0)));
    }

    @AfterEach
    void noThreadEndedByAFailure() {
        assertThat(uncaught).isEmpty();
    }

    private void start(Consumer<String> beforeStep) throws InterruptedException {
        runtime = new MonitoringRuntime(
                () -> new PlatformSources(system, processes, controller, 1, Optional.of(journalFolder.path()), "9-9"),
                System::nanoTime,
                activity,
                new MonitoringRuntime.Listeners(
                        snapshot -> {},
                        listing -> {},
                        details -> {},
                        controlResults::add,
                        profile -> {},
                        held -> {},
                        failures -> {},
                        limits -> {},
                        limitResults::add,
                        recovered -> {},
                        check -> {},
                        result -> {}),
                Duration.ofSeconds(5),
                (thread, failure) -> uncaught.add(failure),
                MonitoringRuntime.RECOVERY_RETRIES);
        runtime.beforeShutdownSteps(name -> {
            stepsReached.add(name);
            beforeStep.accept(name);
        });
        runtime.start();
        limitTheOneAndSuspendTheOther();
    }

    private void limitTheOneAndSuspendTheOther() throws InterruptedException {
        await(() -> {
            int before = limitResults.size();
            runtime.requestLimit(LimitRequest.of(LimitAction.MANAGE, LIMITED, "demo"));
            try {
                await(() -> limitResults.size() > before);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return limitResults.get(before).outcome().succeeded();
        });
        int limits = limitResults.size();
        runtime.requestLimit(LimitRequest.cpuLimit(LIMITED, "demo", 30));
        await(() -> limitResults.size() > limits);
        int controls = controlResults.size();
        runtime.requestProcessAction(ControlRequest.of(ControlAction.SUSPEND, USERS, "demo"));
        await(() -> controlResults.size() > controls);
        assertThat(controlResults.get(controls).result().name()).isEqualTo("SUCCESS");
        await(() -> controller.calls.contains("suspend 4242"));
    }

    /** The limiter cycles between its phases: a shutdown asked for now finds a suspension to release. */
    private void untilTheLimiterHolds() throws InterruptedException {
        await(() -> outstanding(LIMITED) > 0);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("reached in time").isLessThan(end);
            Thread.sleep(20);
        }
    }

    private List<String> messages(ActivitySeverity severity) {
        return activity.entries().stream()
                .filter(entry -> entry.severity() == severity)
                .map(ActivityEntry::message)
                .toList();
    }

    private int outstanding(ProcessIdentity identity) {
        return controller.suspensions.stream()
                .filter(suspension -> suspension.identity().equals(identity))
                .mapToInt(suspension -> suspension.outstanding)
                .sum();
    }

    @Test
    void theStepsRunInTheOrderOfLife1AndTheShutdownReportsNoFailure() throws Exception {
        start(name -> {});
        untilTheLimiterHolds();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(stepsReached).containsExactlyElementsOf(STEPS);
        assertThat(messages(ActivitySeverity.ERROR)).isEmpty();
        assertThat(messages(ActivitySeverity.INFO))
                .contains("Shutdown started.", "Shutdown complete: monitoring and its executors are stopped.");
        assertThat(outstanding(LIMITED))
                .as("the limiter's suspension is released")
                .isZero();
        assertThat(outstanding(USERS)).as("LIFE-2: the user's stays").isPositive();
    }

    /** A step that fails is logged and every later step still runs; Prism still ends (LIFE-3). */
    @ParameterizedTest
    @MethodSource("steps")
    void aStepThatFailsDoesNotPreventTheRemainingSteps(String failing) throws Exception {
        start(name -> {
            if (name.equals(failing)) {
                throw new IllegalStateException("injected into: " + name);
            }
        });
        untilTheLimiterHolds();

        runtime.shutdown();

        assertThat(runtime.awaitStopped(PATIENCE_SECONDS, TimeUnit.SECONDS))
                .as("the sequence comes to its end")
                .isTrue();
        assertThat(stepsReached).containsExactlyElementsOf(STEPS);
        assertThat(messages(ActivitySeverity.ERROR)).containsExactly("Shutdown step failed: " + failing + ".");
        assertThat(messages(ActivitySeverity.WARNING))
                .contains("Shutdown complete, with failed steps: [" + failing + "].");
        assertThat(runtime.state()).isEqualTo(MonitoringRuntime.State.STOPPED);

        // What the JVM runs next: whatever the failed step left held by the limiter goes now (LIFE-5).
        runtime.releaseForExit();

        assertThat(outstanding(LIMITED)).as("nothing of the limiter stays held").isZero();
        assertThat(outstanding(USERS))
                .as("LIFE-2: the user's stays, whichever step failed")
                .isPositive();
        int calls = controller.calls.size();
        Thread.sleep(1_200);
        assertThat(controller.calls).as("nothing suspends after the exit").hasSize(calls);
    }

    private static List<String> steps() {
        return STEPS;
    }
}
