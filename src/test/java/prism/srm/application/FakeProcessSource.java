package prism.srm.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import prism.srm.domain.ProcessDetails;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessReading;
import prism.srm.domain.ProcessState;
import prism.srm.platform.ProcessSource;

/**
 * A process source whose readings are set by the test. Fields are plain: a test that samples on
 * another thread sets them before starting it.
 */
public final class FakeProcessSource implements ProcessSource {

    static final Instant BOOT = Instant.parse("2026-01-01T00:00:00Z");

    List<ProcessReading> processes = List.of();

    /** Thrown by the next listing instead of returning the processes, when set. */
    Throwable failure;

    /** Runs while the processes are being listed, to let a test make the read take time. */
    public Runnable duringRead = () -> {};

    /**
     * Answers a request for the details of one process. Unless a test sets it, a request fails
     * the test: the monitor must never read details, they are read on demand only.
     */
    public volatile Function<ProcessIdentity, Optional<ProcessDetails>> onDetails = identity -> {
        throw new AssertionError("The monitor must never read details: they are read on demand only");
    };

    int listings;

    /** Sets what the following listings return. */
    public void list(List<ProcessReading> readings) {
        processes = readings;
    }

    /** The identity of a process with this PID that started this many seconds after boot. */
    public static ProcessIdentity identity(long pid, long startSecond) {
        return new ProcessIdentity(pid, BOOT.plusSeconds(startSecond));
    }

    /** A reading with the given counters and otherwise fixed, fully present values. */
    public static ProcessReading reading(
            ProcessIdentity identity, long cpuTimeMillis, long bytesRead, long bytesWritten) {
        return reading(
                identity, OptionalLong.of(cpuTimeMillis), OptionalLong.of(bytesRead), OptionalLong.of(bytesWritten));
    }

    static ProcessReading reading(
            ProcessIdentity identity, OptionalLong cpuTimeMillis, OptionalLong bytesRead, OptionalLong bytesWritten) {
        return new ProcessReading(
                identity,
                "process-" + identity.pid(),
                OptionalLong.of(1),
                Optional.of(ProcessState.RUNNING),
                cpuTimeMillis,
                OptionalLong.of(200_000_000),
                OptionalLong.of(150_000_000),
                OptionalLong.of(900_000_000),
                Optional.of("alice"),
                OptionalInt.of(12),
                OptionalInt.of(8),
                bytesRead,
                bytesWritten);
    }

    @Override
    public List<ProcessReading> processes() {
        listings++;
        duringRead.run();
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return processes;
    }

    @Override
    public Optional<ProcessDetails> details(ProcessIdentity identity) {
        return onDetails.apply(identity);
    }
}
