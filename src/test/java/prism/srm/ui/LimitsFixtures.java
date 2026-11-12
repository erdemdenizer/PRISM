package prism.srm.ui;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitReport;
import prism.srm.domain.LimitStatus;
import prism.srm.domain.LimitsSnapshot;
import prism.srm.domain.ManagedProcess;
import prism.srm.domain.ManagedProcessView;
import prism.srm.domain.ManagedSettings;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredSuspension;
import prism.srm.domain.RecoveryState;
import prism.srm.domain.SuspensionReason;

/** Managed processes, limits and recovered entries as the services hand them to the user interface, for tests. */
public final class LimitsFixtures {

    public static final LimitCapability EMULATED =
            LimitCapability.of(CapabilityLevel.EMULATED, ControlMechanism.DUTY_CYCLE);

    public static final LimitCapability NATIVE =
            LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT);

    private static final long MIB = 1L << 20;

    private LimitsFixtures() {}

    /** A process with native limits as the limit service reports them (milestone 10). */
    public static ManagedProcessView nativeLimited(
            ProcessIdentity identity,
            String name,
            java.util.OptionalInt percent,
            java.util.OptionalLong bytes,
            LimitStatus status,
            prism.srm.domain.LimitNote note,
            Optional<String> detail) {
        ManagedSettings settings = new ManagedSettings(percent, bytes, true, false);
        LimitReport cpu = percent.isPresent() || status != LimitStatus.INACTIVE
                ? new LimitReport(
                        LimitKind.CPU,
                        percent,
                        NATIVE,
                        status,
                        detail,
                        note,
                        OptionalDouble.empty(),
                        OptionalDouble.empty())
                : LimitReport.none(LimitKind.CPU, NATIVE);
        LimitReport memory = bytes.isPresent()
                ? new LimitReport(
                        LimitKind.MEMORY,
                        java.util.OptionalInt.empty(),
                        NATIVE,
                        LimitStatus.ACTIVE_ENFORCED,
                        Optional.of("enforced: at most " + prism.srm.domain.EffectiveLimits.bytes(bytes.getAsLong())
                                + " committed"),
                        prism.srm.domain.LimitNote.NONE,
                        OptionalDouble.empty(),
                        OptionalDouble.empty(),
                        bytes)
                : LimitReport.none(LimitKind.MEMORY, NATIVE);
        java.util.Map<LimitKind, LimitCapability> capabilities =
                java.util.Map.of(LimitKind.CPU, NATIVE, LimitKind.MEMORY, NATIVE);
        Optional<prism.srm.domain.FallbackOffer> offer = note == prism.srm.domain.LimitNote.FALLBACK_OFFERED
                ? Optional.of(new prism.srm.domain.FallbackOffer(detail.orElse("refused"), 4, 16, true))
                : Optional.empty();
        return new ManagedProcessView(new ManagedProcess(identity, name, settings), cpu, memory, capabilities, offer);
    }

    /** A process enforced natively at a CPU share and, when given, a commit limit. */
    public static ManagedProcessView enforced(ProcessIdentity identity, String name, int percent, long mib) {
        return nativeLimited(
                identity,
                name,
                percent > 0 ? java.util.OptionalInt.of(percent) : java.util.OptionalInt.empty(),
                mib > 0 ? java.util.OptionalLong.of(mib * MIB) : java.util.OptionalLong.empty(),
                percent > 0 ? LimitStatus.ACTIVE_ENFORCED : LimitStatus.INACTIVE,
                prism.srm.domain.LimitNote.NONE,
                percent > 0 ? Optional.of("enforced: CPU " + percent + " %") : Optional.empty());
    }

    /** A snapshot of a system with native limits, with the notices given. */
    public static LimitsSnapshot nativeSnapshot(
            List<ManagedProcessView> views, prism.srm.domain.LimitsNotice... notices) {
        return new LimitsSnapshot(views, NATIVE, NATIVE, Optional.empty(), List.of(notices));
    }

    public static LimitCapability unavailable(String reason) {
        return LimitCapability.unavailable(reason);
    }

    /** A managed process without a limit. */
    public static ManagedProcessView managed(ProcessIdentity identity, String name) {
        return new ManagedProcessView(
                new ManagedProcess(identity, name, ManagedSettings.NONE), LimitReport.none(LimitKind.CPU, EMULATED));
    }

    /** A managed process with a CPU limit in the given state. */
    public static ManagedProcessView limited(
            ProcessIdentity identity,
            String name,
            int percent,
            boolean enabled,
            LimitStatus status,
            Optional<String> detail,
            OptionalDouble measured,
            OptionalDouble lowest) {
        ManagedSettings settings = new ManagedSettings(OptionalInt.of(percent), enabled);
        return new ManagedProcessView(
                new ManagedProcess(identity, name, settings),
                new LimitReport(
                        LimitKind.CPU,
                        OptionalInt.of(percent),
                        EMULATED,
                        status,
                        detail,
                        noteOf(detail),
                        measured,
                        lowest));
    }

    public static ManagedProcessView throttling(ProcessIdentity identity, String name, int percent, double measured) {
        return limited(
                identity,
                name,
                percent,
                true,
                LimitStatus.ACTIVE_THROTTLING,
                Optional.empty(),
                OptionalDouble.of(measured),
                OptionalDouble.empty());
    }

    /** The note the services give with such a detail. */
    private static prism.srm.domain.LimitNote noteOf(Optional<String> detail) {
        String text = detail.orElse("");
        if (text.equals("disabled")) {
            return prism.srm.domain.LimitNote.DISABLED;
        }
        if (text.startsWith("floor")) {
            return prism.srm.domain.LimitNote.FLOOR;
        }
        if (text.startsWith("not measured")) {
            return prism.srm.domain.LimitNote.NOT_MEASURED;
        }
        if (text.startsWith("journal")) {
            return prism.srm.domain.LimitNote.JOURNAL;
        }
        return prism.srm.domain.LimitNote.NONE;
    }

    public static LimitsSnapshot snapshot(ManagedProcessView... views) {
        return new LimitsSnapshot(List.of(views), EMULATED, Optional.empty());
    }

    /** An entry an earlier session left, as the start-up lists it. */
    public static RecoveredSuspension recovered(
            ProcessIdentity identity,
            String name,
            Set<SuspensionReason> reasons,
            boolean resumable,
            String explanation) {
        return new RecoveredSuspension(
                identity,
                name,
                reasons,
                resumable ? RecoveryState.POSSIBLY_SUSPENDED : RecoveryState.MANUAL_INTERVENTION,
                explanation,
                resumable,
                "pid " + identity.pid() + ", written 2026-10-08T09:00:00Z by session 1-1");
    }

    public static RecoveredSuspension userLeft(ProcessIdentity identity, String name) {
        return recovered(
                identity,
                name,
                EnumSet.of(SuspensionReason.USER),
                true,
                "It is stopped. That does not prove the stop is Prism's: another program may have stopped it.");
    }

    public static ProcessIdentity identity(long pid) {
        return new ProcessIdentity(pid, Instant.parse("2026-10-08T08:00:00Z").plusSeconds(pid));
    }
}
