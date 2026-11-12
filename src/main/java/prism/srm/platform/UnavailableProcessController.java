package prism.srm.platform;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.Capability;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.OperationResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;

/**
 * The controller of a system Prism cannot control processes on yet: every action is reported as
 * {@link Capability#UNAVAILABLE}, and every call answers {@link OperationResult#UNSUPPORTED}
 * without touching anything. A system Prism has no controller for uses it.
 */
final class UnavailableProcessController implements ProcessController {

    private static final Map<ControlAction, Capability> NONE = Map.copyOf(none());

    @Override
    public Map<ControlAction, Capability> capabilities() {
        return NONE;
    }

    @Override
    public Map<Integer, PriorityLevel> listedPriorities() {
        return Map.of();
    }

    @Override
    public Restorability priorityRestorability() {
        return Restorability.NOT_REVERSIBLE;
    }

    @Override
    public ProtectionFacts protectionFacts(ProcessIdentity target) {
        return ProtectionFacts.NONE;
    }

    @Override
    public ControlOutcome liveness(ProcessIdentity target) {
        return unsupported();
    }

    @Override
    public ControlOutcome requestClose(ProcessIdentity target) {
        return unsupported();
    }

    @Override
    public ControlOutcome terminate(ProcessIdentity target) {
        return unsupported();
    }

    @Override
    public SuspendResult suspend(ProcessIdentity target) {
        return SuspendResult.failed(unsupported());
    }

    @Override
    public ControlOutcome resume(Suspension suspension) {
        return unsupported();
    }

    @Override
    public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
        return unsupported();
    }

    @Override
    public StopState stopState(ProcessIdentity target) {
        return StopState.UNKNOWN;
    }

    @Override
    public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
        return AdoptResult.of(AdoptResult.Verdict.REFUSED, unsupported());
    }

    private static ControlOutcome unsupported() {
        return ControlOutcome.of(OperationResult.UNSUPPORTED, "process control is not available on this system");
    }

    private static Map<ControlAction, Capability> none() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        for (ControlAction action : ControlAction.values()) {
            map.put(action, Capability.UNAVAILABLE);
        }
        return map;
    }
}
