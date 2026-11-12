package prism.srm.domain;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The settings of one Managed Process (MGD-3): its CPU limit, its memory limit (milestone 10),
 * whether its limits are enabled, and whether the user accepted the emulated CPU limit for it after
 * a native one could not be applied, and its OOM settings (milestone 11).
 *
 * @param cpuLimitPercent the CPU limit as a whole-number percentage of the whole machine, 1 to 100
 *     (LIM-2); empty when the process has none
 * @param memoryLimitBytes the limit on the memory the process may commit, in bytes (LIM-2, LIM-5);
 *     empty when it has none
 * @param enabled whether the limits are applied; a disabled limit is kept but not in effect
 * @param emulatedCpuApproved whether the user chose the emulated CPU limit for this process after
 *     the native one failed; valid for the session (MGD-2)
 * @param oom OOM management, OOM priority and emergency action (MGD-3); off for a process just managed
 */
public record ManagedSettings(
        OptionalInt cpuLimitPercent,
        OptionalLong memoryLimitBytes,
        boolean enabled,
        boolean emulatedCpuApproved,
        OomManagement oom) {

    public static final ManagedSettings NONE =
            new ManagedSettings(OptionalInt.empty(), OptionalLong.empty(), true, false, OomManagement.OFF);

    /** The smallest memory limit Prism sets: a product bound, not a system one (plan D10). */
    public static final long LEAST_MEMORY_BYTES = 1L << 20;

    public ManagedSettings {
        Objects.requireNonNull(cpuLimitPercent, "cpuLimitPercent");
        Objects.requireNonNull(memoryLimitBytes, "memoryLimitBytes");
        Objects.requireNonNull(oom, "oom");
        if (cpuLimitPercent.isPresent() && !validCpuPercent(cpuLimitPercent.getAsInt())) {
            throw new IllegalArgumentException("A CPU limit is a whole percentage from 1 to 100: " + cpuLimitPercent);
        }
        if (memoryLimitBytes.isPresent() && !validMemoryBytes(memoryLimitBytes.getAsLong())) {
            throw new IllegalArgumentException("Not a memory limit: " + memoryLimitBytes);
        }
    }

    /** The milestone 10 settings: no OOM management. */
    public ManagedSettings(
            OptionalInt cpuLimitPercent, OptionalLong memoryLimitBytes, boolean enabled, boolean emulatedCpuApproved) {
        this(cpuLimitPercent, memoryLimitBytes, enabled, emulatedCpuApproved, OomManagement.OFF);
    }

    /** The milestone 9 settings: a CPU limit and the enabled flag. */
    public ManagedSettings(OptionalInt cpuLimitPercent, boolean enabled) {
        this(cpuLimitPercent, OptionalLong.empty(), enabled, false);
    }

    /** Whether a number may be a CPU limit (LIM-2). */
    public static boolean validCpuPercent(int percent) {
        return percent >= 1 && percent <= 100;
    }

    /** Whether a number of bytes may be a memory limit: at least 1 MiB. */
    public static boolean validMemoryBytes(long bytes) {
        return bytes >= LEAST_MEMORY_BYTES;
    }

    public ManagedSettings withCpuLimit(OptionalInt percent) {
        return new ManagedSettings(percent, memoryLimitBytes, enabled, emulatedCpuApproved, oom);
    }

    public ManagedSettings withMemoryLimit(OptionalLong bytes) {
        return new ManagedSettings(cpuLimitPercent, bytes, enabled, emulatedCpuApproved, oom);
    }

    public ManagedSettings withEnabled(boolean value) {
        return new ManagedSettings(cpuLimitPercent, memoryLimitBytes, value, emulatedCpuApproved, oom);
    }

    public ManagedSettings withEmulatedCpuApproved(boolean value) {
        return new ManagedSettings(cpuLimitPercent, memoryLimitBytes, enabled, value, oom);
    }

    public ManagedSettings withOom(OomManagement value) {
        return new ManagedSettings(cpuLimitPercent, memoryLimitBytes, enabled, emulatedCpuApproved, value);
    }

    /** The native limits these settings ask for while enabled. */
    public LimitSpec nativeSpec() {
        return new LimitSpec(cpuLimitPercent, memoryLimitBytes);
    }
}
