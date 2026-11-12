package prism.srm.platform.linux;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import prism.srm.domain.ProcessIdentity;

/**
 * The Linux process identity (PROC-3: PID and start time), computed exactly as the process listing computes it, so that
 * identities from {@code /proc} and from the listing are equal (ADR-0010): the boot time in seconds times 1000, plus
 * field 22 of {@code /proc/<pid>/stat} (start time in clock ticks after boot) times 1000 over the clock ticks per
 * second. Shared by process control and the milestone 12 code; pure, apart from {@link #read}, which reads one stat file.
 */
final class ProcessIdentityReader {

    private ProcessIdentityReader() {}

    /**
     * The start time in milliseconds since the epoch; empty when the boot time or the clock ticks are unknown (zero or
     * negative), the ticks are negative, or the result would overflow.
     */
    static OptionalLong startMillis(long bootSeconds, long ticksPerSecond, long startTicks) {
        if (bootSeconds <= 0 || ticksPerSecond <= 0 || startTicks < 0) {
            return OptionalLong.empty();
        }
        try {
            long boot = Math.multiplyExact(bootSeconds, 1000L);
            long sinceBoot = Math.multiplyExact(startTicks, 1000L) / ticksPerSecond;
            return OptionalLong.of(Math.addExact(boot, sinceBoot));
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
    }

    /** The identity of a process whose stat file was read; empty when its start time cannot be computed. */
    static Optional<ProcessIdentity> identity(int pid, ProcStat stat, long bootSeconds, long ticksPerSecond) {
        if (pid <= 0) {
            return Optional.empty();
        }
        OptionalLong millis = startMillis(bootSeconds, ticksPerSecond, stat.startTicks());
        return millis.isPresent()
                ? Optional.of(new ProcessIdentity(pid, Instant.ofEpochMilli(millis.getAsLong())))
                : Optional.empty();
    }

    /** Reads {@code /proc/<pid>/stat} and computes the identity; a stat file whose time cannot be computed is FAILED. */
    static ProcFs.Reading<ProcessIdentity> read(ProcFs proc, int pid, long bootSeconds, long ticksPerSecond) {
        if (pid <= 0) {
            return ProcFs.Reading.not(ProcFs.Status.FAILED, "PID " + pid + " names no single process");
        }
        ProcFs.Reading<ProcStat> stat = proc.stat(pid);
        if (stat.status() != ProcFs.Status.FOUND) {
            return ProcFs.Reading.not(stat.status(), stat.detail());
        }
        return identity(pid, stat.get(), bootSeconds, ticksPerSecond)
                .map(ProcFs.Reading::found)
                .orElseGet(() -> ProcFs.Reading.not(
                        ProcFs.Status.FAILED, "the start time of PID " + pid + " cannot be computed"));
    }

    /** Whether a stat file read now shows the identity: the same PID and exactly the same start time. */
    static boolean matches(ProcessIdentity identity, ProcStat stat, long bootSeconds, long ticksPerSecond) {
        OptionalLong millis = startMillis(bootSeconds, ticksPerSecond, stat.startTicks());
        return millis.isPresent() && millis.getAsLong() == identity.startTime().toEpochMilli();
    }
}
