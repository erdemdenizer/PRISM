package prism.srm.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import prism.srm.domain.Capability;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;
import prism.srm.domain.Restorability;
import prism.srm.platform.AdoptResult;
import prism.srm.platform.ProcessController;
import prism.srm.platform.StopState;
import prism.srm.platform.SuspendResult;
import prism.srm.platform.Suspension;

/**
 * A controller that touches no process: every answer is set by the test, and every call is
 * recorded as "action pid", in order. Suspensions are {@link FakeSuspension}s that count how often
 * they were resumed and closed.
 */
public final class FakeProcessController implements ProcessController {

    /** A suspension that only counts. */
    public static final class FakeSuspension implements Suspension {
        private final ProcessIdentity identity;
        private final int threads;
        public volatile int outstanding;
        public volatile int resumes;
        public volatile int closes;
        public volatile Optional<String> memento = Optional.empty();

        public FakeSuspension(ProcessIdentity identity, int threads) {
            this.identity = identity;
            this.threads = threads;
            this.outstanding = threads;
        }

        @Override
        public ProcessIdentity identity() {
            return identity;
        }

        @Override
        public int threads() {
            return threads;
        }

        @Override
        public int outstanding() {
            return outstanding;
        }

        @Override
        public Optional<String> memento() {
            return memento;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final List<FakeSuspension> suspensions = new CopyOnWriteArrayList<>();
    public volatile Map<ControlAction, Capability> capabilities = windowsLike();
    public volatile Function<ProcessIdentity, ProtectionFacts> facts = target -> ProtectionFacts.NONE;
    public volatile Function<ProcessIdentity, ControlOutcome> liveness = target -> ControlOutcome.success();
    public volatile Function<ProcessIdentity, ControlOutcome> close = target -> ControlOutcome.success("posted");
    public volatile Function<ProcessIdentity, ControlOutcome> terminate = target -> ControlOutcome.success("started");
    public volatile BiFunction<ProcessIdentity, PriorityLevel, ControlOutcome> priority =
            (target, level) -> ControlOutcome.success("set");
    public volatile Function<ProcessIdentity, SuspendResult> suspend = target -> {
        FakeSuspension suspension = new FakeSuspension(target, 3);
        suspensions.add(suspension);
        return SuspendResult.suspended(ControlOutcome.success("3 threads"), suspension);
    };
    /** Running unless a test says otherwise. */
    public volatile Function<ProcessIdentity, StopState> stopState = target -> StopState.RUNNING;
    /** Refuses by default: a test that lets the user resume a recovered process sets it. */
    public volatile BiFunction<ProcessIdentity, Optional<String>, AdoptResult> adopt =
            (target, memento) -> AdoptResult.of(
                    AdoptResult.Verdict.REFUSED,
                    ControlOutcome.of(prism.srm.domain.OperationResult.UNSUPPORTED, "no adopt set"));
    /** Releases everything by default. */
    public volatile Function<FakeSuspension, ControlOutcome> resume = suspension -> {
        suspension.outstanding = 0;
        return ControlOutcome.success("released");
    };

    public static Map<ControlAction, Capability> windowsLike() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
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
        return Map.copyOf(map);
    }

    /** The table of Linux with pidfd signalling, and of macOS: signals and the nice value. */
    public static Map<ControlAction, Capability> unixLike() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        map.put(
                ControlAction.END_TASK,
                Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.TERMINATION_SIGNAL));
        map.put(ControlAction.FORCE_KILL, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.KILL_SIGNAL));
        map.put(ControlAction.SUSPEND, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL));
        map.put(ControlAction.RESUME, Capability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.STOP_SIGNAL));
        map.put(ControlAction.CHANGE_PRIORITY, Capability.of(CapabilityLevel.NATIVE_SOFT, ControlMechanism.NICE_VALUE));
        return Map.copyOf(map);
    }

    /** The profile the user interface gets on Linux: the table above and the levels by nice value plus 20. */
    public static prism.srm.domain.ControlProfile linuxProfile() {
        return new prism.srm.domain.ControlProfile(
                unixLike(),
                Map.of(
                        39, PriorityLevel.LOW,
                        30, PriorityLevel.BELOW_NORMAL,
                        20, PriorityLevel.NORMAL,
                        15, PriorityLevel.ABOVE_NORMAL,
                        10, PriorityLevel.HIGH));
    }

    public static Map<ControlAction, Capability> noneAvailable() {
        Map<ControlAction, Capability> map = new EnumMap<>(ControlAction.class);
        for (ControlAction action : ControlAction.values()) {
            map.put(action, Capability.UNAVAILABLE);
        }
        return Map.copyOf(map);
    }

    @Override
    public Map<ControlAction, Capability> capabilities() {
        return capabilities;
    }

    public volatile List<String> limitations = List.of();

    @Override
    public List<String> limitations() {
        return limitations;
    }

    /** The table of the Windows listing, so that the levels a test shows are the ones Prism shows. */
    public volatile Map<Integer, PriorityLevel> listedPriorities = windowsPriorities();

    public static Map<Integer, PriorityLevel> windowsPriorities() {
        return Map.of(
                4, PriorityLevel.LOW,
                6, PriorityLevel.BELOW_NORMAL,
                8, PriorityLevel.NORMAL,
                10, PriorityLevel.ABOVE_NORMAL,
                13, PriorityLevel.HIGH);
    }

    /** The profile the user interface gets from a Windows-like controller. */
    public static prism.srm.domain.ControlProfile windowsProfile() {
        return new prism.srm.domain.ControlProfile(windowsLike(), windowsPriorities());
    }

    @Override
    public Map<Integer, PriorityLevel> listedPriorities() {
        return listedPriorities;
    }

    /** Not reversible unless a test says otherwise: the answer that promises nothing. */
    public volatile Restorability restorability = Restorability.NOT_REVERSIBLE;

    @Override
    public Restorability priorityRestorability() {
        return restorability;
    }

    @Override
    public ProtectionFacts protectionFacts(ProcessIdentity target) {
        calls.add("facts " + target.pid());
        return facts.apply(target);
    }

    @Override
    public ControlOutcome liveness(ProcessIdentity target) {
        calls.add("liveness " + target.pid());
        return liveness.apply(target);
    }

    @Override
    public ControlOutcome requestClose(ProcessIdentity target) {
        calls.add("close " + target.pid());
        return close.apply(target);
    }

    @Override
    public ControlOutcome terminate(ProcessIdentity target) {
        calls.add("terminate " + target.pid());
        return terminate.apply(target);
    }

    @Override
    public SuspendResult suspend(ProcessIdentity target) {
        calls.add("suspend " + target.pid());
        return suspend.apply(target);
    }

    @Override
    public ControlOutcome resume(Suspension suspension) {
        calls.add("resume " + suspension.identity().pid());
        FakeSuspension fake = (FakeSuspension) suspension;
        fake.resumes++;
        return resume.apply(fake);
    }

    @Override
    public StopState stopState(ProcessIdentity target) {
        calls.add("stopState " + target.pid());
        return stopState.apply(target);
    }

    @Override
    public AdoptResult adopt(ProcessIdentity target, Optional<String> memento) {
        calls.add("adopt " + target.pid());
        return adopt.apply(target, memento);
    }

    /** A suspension the fake adopts, so that a test can resume it like any other. */
    public FakeSuspension adopted(ProcessIdentity target) {
        FakeSuspension suspension = new FakeSuspension(target, 1);
        suspensions.add(suspension);
        return suspension;
    }

    @Override
    public ControlOutcome changePriority(ProcessIdentity target, PriorityLevel level) {
        calls.add("priority " + target.pid() + " " + level);
        return priority.apply(target, level);
    }
}
