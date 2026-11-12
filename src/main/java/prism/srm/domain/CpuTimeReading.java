package prism.srm.domain;

import java.util.Objects;

/**
 * What one read of a single process's processor time came to. Three answers, because the emulated
 * CPU limiter must tell a target that has exited (it ends the limit at once) from one that could
 * not be read this time (it waits, and lets the target run when it cannot measure it).
 */
public sealed interface CpuTimeReading {

    /** The processor time the process has used since it started, kernel and user together, in milliseconds. */
    record Read(long cpuMillis) implements CpuTimeReading {
        public Read {
            if (cpuMillis < 0) {
                throw new IllegalArgumentException("cpuMillis must not be negative: " + cpuMillis);
            }
        }
    }

    /** The process has exited, or its PID belongs to another process now. */
    record Gone() implements CpuTimeReading {}

    /** The process may still run, but its time could not be read; the reason is for the log. */
    record Unreadable(String reason) implements CpuTimeReading {
        public Unreadable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    CpuTimeReading GONE = new Gone();
}
