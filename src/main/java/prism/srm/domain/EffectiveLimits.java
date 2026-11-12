package prism.srm.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The native limits a system holds for a process, as it reads them back, or as Prism means to set
 * them. Kept apart per kind, because each kind is set by a call of its own and either takes effect
 * as a whole or not at all (milestone 10, plan revision 4.1).
 *
 * @param cpuRate the CPU cap as a share of the whole machine in hundredths of a percent (2000 is 20
 *     %); empty when no cap is in force
 * @param memoryBytes the committed memory each process of the limit may have; empty when none
 * @param foreign what the system holds that Prism never sets, in words (a weight-based rate,
 *     another limit flag); present only for a read-back. Limits with something foreign are never
 *     Prism's.
 * @param memoryBoundary which kind of boundary the memory limit is (milestone 12): {@link MemoryBoundary#HARD}
 *     for a Job Object's commit limit or Linux {@code memory.max}, {@link MemoryBoundary#THROTTLE} for Linux
 *     {@code memory.high}. Without a memory limit it is always {@code HARD}, so that two limits without memory
 *     compare equal whatever was passed.
 */
public record EffectiveLimits(
        OptionalInt cpuRate, OptionalLong memoryBytes, Optional<String> foreign, MemoryBoundary memoryBoundary) {

    /** No limit at all. */
    public static final EffectiveLimits NONE =
            new EffectiveLimits(OptionalInt.empty(), OptionalLong.empty(), Optional.empty());

    /** The memory part of the limits, for comparing a read-back with a record per kind. */
    public record MemoryValue(OptionalLong bytes, MemoryBoundary boundary) {}

    public EffectiveLimits {
        Objects.requireNonNull(cpuRate, "cpuRate");
        Objects.requireNonNull(memoryBytes, "memoryBytes");
        Objects.requireNonNull(foreign, "foreign");
        Objects.requireNonNull(memoryBoundary, "memoryBoundary");
        if (memoryBytes.isEmpty()) {
            memoryBoundary = MemoryBoundary.HARD;
        }
        if (cpuRate.isPresent() && (cpuRate.getAsInt() < 1 || cpuRate.getAsInt() > 10_000)) {
            throw new IllegalArgumentException("A CPU rate is 1 to 10000 hundredths of a percent: " + cpuRate);
        }
        if (memoryBytes.isPresent() && memoryBytes.getAsLong() < 0) {
            throw new IllegalArgumentException("A memory limit is not negative: " + memoryBytes);
        }
    }

    /** Limits whose memory limit, if any, is a hard one: what every Windows limit is. */
    public EffectiveLimits(OptionalInt cpuRate, OptionalLong memoryBytes, Optional<String> foreign) {
        this(cpuRate, memoryBytes, foreign, MemoryBoundary.HARD);
    }

    public static EffectiveLimits of(OptionalInt cpuRate, OptionalLong memoryBytes) {
        return new EffectiveLimits(cpuRate, memoryBytes, Optional.empty());
    }

    public static EffectiveLimits of(OptionalInt cpuRate, OptionalLong memoryBytes, MemoryBoundary boundary) {
        return new EffectiveLimits(cpuRate, memoryBytes, Optional.empty(), boundary);
    }

    /** Whether no limit of any kind is held. */
    public boolean none() {
        return cpuRate.isEmpty() && memoryBytes.isEmpty() && foreign.isEmpty();
    }

    /** The CPU cap as a whole percentage, when it is one. */
    public OptionalInt cpuPercent() {
        return cpuRate.isPresent() && cpuRate.getAsInt() % 100 == 0
                ? OptionalInt.of(cpuRate.getAsInt() / 100)
                : OptionalInt.empty();
    }

    /**
     * The value of one kind, for comparing a read-back with what Prism recorded: the CPU rate, or the memory limit. A
     * hard memory limit is its bytes, as in milestone 10; a throttling one is its bytes together with the boundary, so
     * that a hard limit never stands for a throttling one of the same size.
     */
    public Object value(LimitKind kind) {
        if (kind == LimitKind.CPU) {
            return cpuRate;
        }
        return memoryBoundary == MemoryBoundary.HARD ? memoryBytes : new MemoryValue(memoryBytes, memoryBoundary);
    }

    public EffectiveLimits withCpuRate(OptionalInt rate) {
        return new EffectiveLimits(rate, memoryBytes, foreign, memoryBoundary);
    }

    public EffectiveLimits withMemoryBytes(OptionalLong bytes) {
        return new EffectiveLimits(cpuRate, bytes, foreign, memoryBoundary);
    }

    public EffectiveLimits withMemoryBoundary(MemoryBoundary boundary) {
        return new EffectiveLimits(cpuRate, memoryBytes, foreign, boundary);
    }

    /** The limits in words, for messages: "CPU 20 %, memory 512 MiB", "no limit". */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (cpuRate.isPresent()) {
            int rate = cpuRate.getAsInt();
            text.append("CPU ")
                    .append(
                            rate % 100 == 0
                                    ? String.valueOf(rate / 100)
                                    : rate / 100 + "." + String.format(Locale.ROOT, "%02d", rate % 100))
                    .append(" %");
        }
        if (memoryBytes.isPresent()) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(memoryBoundary == MemoryBoundary.THROTTLE ? "memory throttle " : "memory ")
                    .append(bytes(memoryBytes.getAsLong()));
        }
        foreign.ifPresent(other -> {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(other);
        });
        return text.isEmpty() ? "no limit" : text.toString();
    }

    /** A number of bytes in MiB or GiB, as the memory dialog takes them. */
    public static String bytes(long value) {
        long mib = 1L << 20;
        if (value % (1L << 30) == 0 && value > 0) {
            return value / (1L << 30) + " GiB";
        }
        if (value % mib == 0) {
            return value / mib + " MiB";
        }
        return value + " bytes";
    }
}
