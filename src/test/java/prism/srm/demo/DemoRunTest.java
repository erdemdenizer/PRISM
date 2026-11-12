package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * Short real runs, with assertions that do not depend on how long anything took: what was printed,
 * why it ended, that the workers are gone.
 */
class DemoRunTest {

    private static final HostFacts HOST = new HostFacts(4, 8 * Quantities.GIB);
    /** Long enough for a few status lines at the test interval, also on a loaded machine. */
    private static final Duration SHORT = Duration.ofMillis(600);

    private static final Duration STATUS = Duration.ofMillis(100);

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.US_ASCII);
    private final FakeChunkAllocator allocator = new FakeChunkAllocator();
    private final AtomicLong cpuTime = new AtomicLong();

    /** Every reading of the CPU time is 50 ms later: a plausible, positive, deterministic value. */
    private final LongSupplier cpuClock = () -> cpuTime.addAndGet(50_000_000L);

    @Test
    void runsForItsDurationAndReportsStartStatusAndEnd() {
        DemoArguments arguments = arguments(25, 0, SHORT);
        DemoRun run = new DemoRun(
                arguments, HOST, allocator.workload(0, Quantities.GIB, Quantities.MIB), cpuClock, out, STATUS);

        int exitCode = run.run();

        assertThat(exitCode).isZero();
        assertThat(run.interrupted()).isFalse();
        List<String> lines = lines();
        assertThat(lines.get(0))
                .startsWith("event=start pid=" + ProcessHandle.current().pid() + " ")
                .contains("processors=4", "physical=8.00GiB", "cap=1.00GiB", "cpu=25.0", "workers=1", "duty=1.00")
                .contains("memory=0MiB", "duration=0s", "mode=cpu");
        assertThat(lines).noneMatch(line -> line.startsWith("event=memory"));
        assertThat(lines)
                .anyMatch(line -> line.startsWith("event=status elapsed=")
                        && line.contains("remaining=")
                        && line.contains("cpu-self=")
                        && !line.contains("cpu-self=-")
                        && line.endsWith("memory=0MiB"));
        assertThat(lines.get(lines.size() - 1)).matches("event=end reason=completed elapsed=\\d+s exit=0");
        assertThat(workerThreads()).isEmpty();
        assertThat(allocator.released).as("the memory is closed at the end").isEqualTo(1);
    }

    @Test
    void anUnknownCpuTimeShowsAsADash() {
        DemoRun run = new DemoRun(
                arguments(0, 0, SHORT),
                HOST,
                allocator.workload(0, Quantities.GIB, Quantities.MIB),
                () -> -1,
                out,
                STATUS);
        run.run();
        assertThat(lines()).anyMatch(line -> line.startsWith("event=status") && line.contains("cpu-self=-"));
        assertThat(lines().get(0)).contains("mode=idle", "workers=0", "duty=0.00");
    }

    @Test
    void reportsTheMemoryItAllocated() {
        DemoRun run = new DemoRun(
                arguments(0, 2 * Quantities.MIB, SHORT),
                HOST,
                allocator.workload(2 * Quantities.MIB, Quantities.GIB, Quantities.MIB),
                cpuClock,
                out,
                STATUS);

        assertThat(run.run()).isZero();

        List<String> lines = lines();
        assertThat(lines.get(0)).contains("memory=2MiB", "mode=memory");
        assertThat(lines.get(1)).matches("event=memory status=allocated bytes=2097152 chunks=2 millis=\\d+");
        assertThat(lines).anyMatch(line -> line.startsWith("event=status") && line.endsWith("memory=2MiB"));
        assertThat(allocator.released).isEqualTo(1);
    }

    /** DEMO-3: an allocation the system refuses ends the run with the report and exit code 1. */
    @Test
    void aRefusedAllocationEndsTheRunWithAReport() {
        allocator.failingAt(2, new OutOfMemoryError("Unable to allocate 1048576 bytes"));
        DemoRun run = new DemoRun(
                arguments(25, 4 * Quantities.MIB, Duration.ofSeconds(10)),
                HOST,
                allocator.workload(4 * Quantities.MIB, Quantities.GIB, Quantities.MIB),
                cpuClock,
                out,
                STATUS);

        int exitCode = run.run();

        assertThat(exitCode).isEqualTo(1);
        assertThat(run.interrupted()).isFalse();
        List<String> lines = lines();
        assertThat(lines.get(1))
                .startsWith("event=memory status=failed requested=4MiB allocated=1MiB chunks=1 millis=")
                .endsWith("error=chunk 2 of 1MiB refused by the system: Unable to allocate 1048576 bytes");
        assertThat(lines).noneMatch(line -> line.startsWith("event=status"));
        assertThat(lines.get(2)).matches("event=end reason=allocation-failed elapsed=\\d+s exit=1");
        assertThat(lines).hasSize(3);
        assertThat(workerThreads())
                .as("no worker is started after a failed allocation")
                .isEmpty();
        assertThat(allocator.released).isEqualTo(1);
    }

    /** The stop arrives before the run has started waiting: it is noticed at the top of the loop. */
    @Test
    void aStopRequestedBeforeTheRunStartsEndsItAsInterrupted() throws InterruptedException {
        DemoRun run = new DemoRun(
                arguments(25, 0, Duration.ofSeconds(10)),
                HOST,
                allocator.workload(0, Quantities.GIB, Quantities.MIB),
                cpuClock,
                out,
                Duration.ofSeconds(1));
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread main = new Thread(() -> exitCode.set(run.run()), "test-demo-main");
        assertThat(run.awaitEnd(Duration.ofMillis(10)))
                .as("not ended before it ran")
                .isFalse();

        run.requestStop();
        main.start();
        assertThat(run.awaitEnd(Duration.ofSeconds(5))).isTrue();
        main.join(Duration.ofSeconds(5));

        assertThat(main.isAlive()).isFalse();
        assertThat(exitCode.get()).isZero();
        assertThat(run.interrupted()).isTrue();
        // The exit code after a signal is the JVM's, not the run's, so the line does not claim one.
        assertThat(lines().get(lines().size() - 1)).matches("event=end reason=interrupted elapsed=\\d+s exit=signal");
        assertThat(workerThreads()).isEmpty();
        assertThat(allocator.released).isEqualTo(1);
    }

    /**
     * What Ctrl+C is: the stop arrives while the run waits with its workers busy. The end must come
     * within the 2 s the shutdown hook waits, so the wait must be cut short, not left to the next
     * status tick.
     */
    @Test
    void aStopRequestedWhileTheRunWaitsEndsItWithinTheBoundOfTheHook() throws InterruptedException {
        DemoRun run = new DemoRun(
                arguments(10, 0, Duration.ofSeconds(20)),
                HOST,
                allocator.workload(0, Quantities.GIB, Quantities.MIB),
                cpuClock,
                out,
                Duration.ofSeconds(10));
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread main = new Thread(() -> exitCode.set(run.run()), "test-demo-main");
        main.start();
        // The only timed wait of the main thread in this phase is the one on the stop latch.
        long giveUp = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (main.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < giveUp) {
            Thread.onSpinWait();
        }
        assertThat(main.getState()).isEqualTo(Thread.State.TIMED_WAITING);
        assertThat(workerThreads())
                .as("the worker is running while the run waits")
                .hasSize(1);

        run.requestStop();

        assertThat(run.awaitEnd(Duration.ofSeconds(2)))
                .as("ended within the bound of the hook")
                .isTrue();
        main.join(Duration.ofSeconds(5));
        assertThat(main.isAlive()).isFalse();
        assertThat(exitCode.get()).isZero();
        assertThat(run.interrupted()).isTrue();
        List<String> lines = lines();
        assertThat(lines).noneMatch(line -> line.startsWith("event=status"));
        assertThat(lines.get(lines.size() - 1)).matches("event=end reason=interrupted elapsed=\\d+s exit=signal");
        assertThat(workerThreads()).isEmpty();
        assertThat(allocator.released).isEqualTo(1);
    }

    /** A worker that is busy for part of each period sleeps for the rest and still stops with the run. */
    @Test
    void aWorkerWithAFractionalDutyCycleEndsWithTheRun() {
        DemoArguments arguments = arguments(10, 0, SHORT);
        assertThat(arguments.cpuPlan()).isEqualTo(new CpuPlan(1, 0.4));
        DemoRun run = new DemoRun(
                arguments, HOST, allocator.workload(0, Quantities.GIB, Quantities.MIB), cpuClock, out, STATUS);

        assertThat(run.run()).isZero();

        assertThat(lines().get(lines().size() - 1)).matches("event=end reason=completed elapsed=\\d+s exit=0");
        assertThat(workerThreads()).isEmpty();
    }

    /** The unit of PROC-2: CPU time over wall time over the logical processors of the machine. */
    @Test
    void theShareOfTheMachineIsCpuTimeOverWallTimeOverProcessors() {
        long second = 1_000_000_000L;
        assertThat(DemoRun.machineShare(0, second, 0, second, 4)).isEqualTo("25.0");
        assertThat(DemoRun.machineShare(10 * second, 14 * second, 0, second, 16))
                .isEqualTo("25.0");
        assertThat(DemoRun.machineShare(0, 16 * second, 0, second, 16)).isEqualTo("100.0");
        assertThat(DemoRun.machineShare(0, 0, 0, second, 16)).isEqualTo("0.0");
        assertThat(DemoRun.machineShare(-1, second, 0, second, 4)).isEqualTo("-");
        assertThat(DemoRun.machineShare(0, -1, 0, second, 4)).isEqualTo("-");
        assertThat(DemoRun.machineShare(0, second, second, second, 4)).isEqualTo("-");
    }

    @Test
    void aStopRequestAfterTheEndChangesNothing() {
        DemoRun run = new DemoRun(
                arguments(0, 0, SHORT),
                HOST,
                allocator.workload(0, Quantities.GIB, Quantities.MIB),
                cpuClock,
                out,
                STATUS);
        assertThat(run.run()).isZero();
        run.requestStop();
        assertThat(run.interrupted()).isFalse();
        assertThat(run.awaitEnd(Duration.ZERO)).isTrue();
    }

    @Test
    void theStatusIntervalMustBePositive() {
        MemoryWorkload memory = allocator.workload(0, Quantities.GIB, Quantities.MIB);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DemoRun(arguments(0, 0, SHORT), HOST, memory, cpuClock, out, Duration.ZERO));
    }

    private static DemoArguments arguments(double cpuPercent, long memoryBytes, Duration duration) {
        return new DemoArguments(
                cpuPercent,
                CpuPlan.plan(cpuPercent, HOST.logicalProcessors(), OptionalInt.empty()),
                memoryBytes,
                duration);
    }

    private List<String> lines() {
        return bytes.toString(StandardCharsets.US_ASCII).lines().toList();
    }

    private static List<Thread> workerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(DemoRun.WORKER_THREAD_PREFIX))
                .toList();
    }
}
