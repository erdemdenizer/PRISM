package prism.srm.ui;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.Claim;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlProfile;
import prism.srm.domain.EffectiveAction;
import prism.srm.domain.EmergencyAction;
import prism.srm.domain.OomActionRecord;
import prism.srm.domain.OomCandidateView;
import prism.srm.domain.OomHold;
import prism.srm.domain.OomManagement;
import prism.srm.domain.OomPriority;
import prism.srm.domain.OomReading;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSignalStatus;
import prism.srm.domain.OomState;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.OomView;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.Restorability;

/** Views of OOM Guard for the tests of the user interface: built by hand, as OOM Guard would make them. */
public final class OomFixtures {

    public static final Instant AT = Instant.parse("2026-10-12T14:02:11Z");
    public static final long TOTAL = 32L << 30;

    private OomFixtures() {}

    public static ProcessIdentity identity(long pid) {
        return new ProcessIdentity(pid, Instant.parse("2026-10-12T09:00:00Z").plusSeconds(pid % 1000));
    }

    public static OomSettings settings(boolean priority, boolean suspend, boolean terminate) {
        return OomSettings.DEFAULTS.withSwitches(new OomSwitches(true, priority, suspend, terminate));
    }

    public static OomReading reading(double percent) {
        return new OomReading(Math.round(TOTAL * percent / 100), TOTAL, 0);
    }

    public static OomCandidateView candidate(
            long pid,
            String name,
            OomManagement oom,
            OomSettings settings,
            OomHold hold,
            Optional<PriorityLevel> priority,
            long residentMiB,
            Optional<String> notNow) {
        return candidate(
                pid, name, oom, settings, hold, priority, residentMiB, notNow, true, OomSignalStatus.VALIDATED);
    }

    /** A candidate on a system whose journal may be failing or whose memory signals may not be validated. */
    public static OomCandidateView candidate(
            long pid,
            String name,
            OomManagement oom,
            OomSettings settings,
            OomHold hold,
            Optional<PriorityLevel> priority,
            long residentMiB,
            Optional<String> notNow,
            boolean journalHealthy,
            OomSignalStatus signal) {
        EffectiveAction effective = EffectiveAction.of(
                oom,
                settings.switches(),
                windows().capabilities(),
                journalHealthy,
                signal == OomSignalStatus.VALIDATED ? Restorability.RESTORABLE : Restorability.NOT_REVERSIBLE,
                signal);
        return new OomCandidateView(
                identity(pid),
                name,
                oom,
                effective,
                hold,
                priority,
                OptionalLong.of(residentMiB << 20),
                OptionalLong.empty(),
                notNow);
    }

    public static OomHold suspended(Set<Claim> claims) {
        return new OomHold(
                claims, OomHold.Physical.SUSPENDED, false, Set.of(), Optional.empty(), false, Optional.empty());
    }

    public static OomHold lowered(PriorityLevel was) {
        return new OomHold(
                Set.of(),
                OomHold.Physical.NONE,
                false,
                Set.of(Claim.PRESSURE),
                Optional.of(was),
                false,
                Optional.empty());
    }

    public static OomHold failed(String why) {
        return new OomHold(Set.of(), OomHold.Physical.NONE, false, Set.of(), Optional.empty(), false, Optional.of(why));
    }

    public static OomHold incomplete() {
        return new OomHold(
                Set.of(),
                OomHold.Physical.RELEASE_INCOMPLETE,
                false,
                Set.of(),
                Optional.empty(),
                false,
                Optional.of("release incomplete: 1 thread(s) still held"));
    }

    public static OomManagement oom(EmergencyAction action) {
        return new OomManagement(true, OomPriority.NORMAL, action);
    }

    public static OomActionRecord record(
            OomState state, long pid, String name, OomActionRecord.Kind kind, OperationResult result, String text) {
        return new OomActionRecord(AT, state, Claim.PRESSURE, identity(pid), name, kind, result, text);
    }

    public static OomView view(
            OomSettings settings,
            OomState state,
            Optional<OomReading> reading,
            List<OomCandidateView> candidates,
            List<OomActionRecord> actions) {
        return view(
                settings,
                state,
                reading,
                candidates,
                actions,
                Optional.empty(),
                false,
                OomSignalStatus.VALIDATED,
                OomView.Availability.ALL);
    }

    public static OomView view(
            OomSettings settings,
            OomState state,
            Optional<OomReading> reading,
            List<OomCandidateView> candidates,
            List<OomActionRecord> actions,
            Optional<String> notice,
            boolean monitorEnded,
            OomSignalStatus signal,
            OomView.Availability availability) {
        boolean monitoring = settings.switches().monitoring();
        return new OomView(
                monitoring,
                monitoring ? state : OomState.NORMAL,
                monitoring ? reading : Optional.empty(),
                monitoring ? reading.map(found -> AT) : Optional.empty(),
                settings,
                monitoring && state != OomState.NORMAL ? Optional.of(AT) : Optional.empty(),
                candidates,
                actions,
                notice,
                monitorEnded,
                signal == OomSignalStatus.VALIDATED ? Restorability.RESTORABLE : Restorability.NOT_REVERSIBLE,
                signal,
                availability);
    }

    /** The availability of a system whose memory signals are not validated (macOS in milestone 11). */
    public static OomView.Availability unvalidated() {
        String why = "not available on this system: memory signals are not validated";
        return new OomView.Availability(Optional.of(why), Optional.of(why), Optional.of(why), Optional.empty());
    }

    public static ControlProfile windows() {
        Map<ControlAction, Capability> map = new java.util.EnumMap<>(ControlAction.class);
        map.put(ControlAction.END_TASK, Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.CLOSE_REQUEST));
        map.put(
                ControlAction.FORCE_KILL,
                Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.PROCESS_HANDLE_TERMINATE));
        map.put(
                ControlAction.SUSPEND,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION));
        map.put(
                ControlAction.RESUME,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PER_THREAD_SUSPENSION));
        map.put(
                ControlAction.CHANGE_PRIORITY,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.PRIORITY_CLASS));
        return new ControlProfile(map, Map.of(4, PriorityLevel.LOW, 8, PriorityLevel.NORMAL));
    }
}
