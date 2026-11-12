package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What one limit of a Managed Process is, for the Limits page (UI-4).
 *
 * @param requestedPercent the CPU limit as set, a percentage of the whole machine; empty when none
 *     is, and for a memory limit
 * @param inEffect how the limit is provided for this process: native, emulated, or unavailable with
 *     the reason (LIM-3, CAP-3)
 * @param detail what explains the status in words, such as why it failed or why the measured use
 *     stays above the limit; for the reader, not to decide by
 * @param note which explanation applies, to decide by
 * @param measuredPercent the process's CPU use over the last measurement, a percentage of the
 *     whole machine (PROC-2); empty when it was not measured
 * @param lowestPercent where the limiter could hold the process no lower: the use it measured at
 *     the smallest run share it allows; present when the status is {@link
 *     LimitStatus#ACTIVE_ABOVE_TARGET} for that reason
 * @param requestedBytes the memory limit as set; empty when none is, and for a CPU limit
 */
public record LimitReport(
        LimitKind kind,
        OptionalInt requestedPercent,
        LimitCapability inEffect,
        LimitStatus status,
        Optional<String> detail,
        LimitNote note,
        OptionalDouble measuredPercent,
        OptionalDouble lowestPercent,
        OptionalLong requestedBytes) {

    public LimitReport {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestedPercent, "requestedPercent");
        Objects.requireNonNull(inEffect, "inEffect");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(measuredPercent, "measuredPercent");
        Objects.requireNonNull(lowestPercent, "lowestPercent");
        Objects.requireNonNull(requestedBytes, "requestedBytes");
        if (kind == LimitKind.CPU && requestedBytes.isPresent()
                || kind == LimitKind.MEMORY && requestedPercent.isPresent()) {
            throw new IllegalArgumentException("A CPU limit is a percentage, a memory limit a number of bytes");
        }
    }

    /** A CPU limit, as milestone 9 reports it. */
    public LimitReport(
            LimitKind kind,
            OptionalInt requestedPercent,
            LimitCapability inEffect,
            LimitStatus status,
            Optional<String> detail,
            LimitNote note,
            OptionalDouble measuredPercent,
            OptionalDouble lowestPercent) {
        this(
                kind,
                requestedPercent,
                inEffect,
                status,
                detail,
                note,
                measuredPercent,
                lowestPercent,
                OptionalLong.empty());
    }

    /** A process with no limit of this kind, provided as given if one were set. */
    public static LimitReport none(LimitKind kind, LimitCapability capability) {
        return new LimitReport(
                kind,
                OptionalInt.empty(),
                capability,
                LimitStatus.INACTIVE,
                Optional.empty(),
                LimitNote.NONE,
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalLong.empty());
    }

    /** Whether a limit of this kind is set. */
    public boolean set() {
        return requestedPercent.isPresent() || requestedBytes.isPresent();
    }
}
