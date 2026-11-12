package prism.srm.application;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import prism.srm.domain.CapabilityLevel;
import prism.srm.domain.ControlMechanism;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.ScopeProvenance;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ReleaseResult;
import prism.srm.platform.ResourceLimiter;

/**
 * A native limiter for the tests of the limit service: it keeps the jobs it "holds" the way the
 * Windows limiter does, and every answer can be replaced to drive a path. By default every limit is
 * native and every call does what it was asked.
 */
public final class FakeResourceLimiter implements ResourceLimiter {

    public static final LimitCapability NATIVE =
            LimitCapability.of(CapabilityLevel.NATIVE_HARD, ControlMechanism.JOB_OBJECT);

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final Map<ProcessIdentity, NativeHold> holds = new ConcurrentHashMap<>();
    public volatile Map<LimitKind, LimitCapability> system = Map.of(LimitKind.CPU, NATIVE, LimitKind.MEMORY, NATIVE);
    public volatile Function<ProcessIdentity, Map<LimitKind, LimitCapability>> perProcess = target -> system;
    public volatile Optional<Boolean> self = Optional.of(false);

    /** The answer to an assignment; by default enforced as asked. */
    public volatile BiFunction<ProcessIdentity, LimitSpec, ApplyResult> apply = (target, spec) -> {
        holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(spec.effective())));
        return new ApplyResult(
                ApplyResult.Phase.ENFORCED, ControlOutcome.success("enforced"), Optional.of(spec.effective()), false);
    };

    /** The answer to a change of a held job; by default applied as asked. */
    public volatile BiFunction<ProcessIdentity, LimitSpec, ChangeResult> change = (target, spec) -> {
        holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(spec.effective())));
        return new ChangeResult(
                ChangeResult.Phase.APPLIED, ControlOutcome.success("changed"), Optional.of(spec.effective()));
    };

    /** The answer to a forget; by default released. */
    public volatile Function<ProcessIdentity, ForgetResult> forget = target -> {
        holds.remove(target);
        return new ForgetResult(
                ForgetResult.Outcome.RELEASED, ControlOutcome.success(), 0, Optional.of(EffectiveLimits.NONE));
    };

    /** Whether a session on this system can have set native limits; false as on Linux and macOS. */
    public volatile boolean leftLimits = true;

    @Override
    public boolean mayHaveLeftLimits() {
        return leftLimits;
    }

    /** Whether releaseAll finds the limiter busy past its bound, as with an operation in flight. */
    public volatile boolean busy;

    /** Whether releaseAll verifies the limits of a process as cleared; by default it does. */
    public volatile java.util.function.Predicate<ProcessIdentity> clears = identity -> true;

    /** What recoveryClears answers (milestone 12); recovery must not call clearOwned whatever it is. */
    public volatile boolean clearsAfterRecovery;

    @Override
    public boolean recoveryClears() {
        return clearsAfterRecovery;
    }

    /** What clearOwned answers; by default the fake does not clear. */
    public volatile Function<LimitRecord, prism.srm.platform.ClearResult> clearOwned =
            record -> prism.srm.platform.ClearResult.unsupported("the fake does not clear");

    /** Records the call and answers {@link #clearOwned}. */
    @Override
    public prism.srm.platform.ClearResult clearOwned(LimitRecord record) {
        calls.add("clearOwned " + record.identity().pid());
        return clearOwned.apply(record);
    }

    /** What the scope-membership rule says of a process (milestone 12); by default nothing stands against it. */
    public volatile Function<ProcessIdentity, Optional<String>> scopeBarrier = target -> Optional.empty();

    @Override
    public Optional<String> scopeBarrier(ProcessIdentity target) {
        return scopeBarrier.apply(target);
    }

    public volatile Function<LimitRecord, RecoveredLimit> recover =
            record -> new RecoveredLimit(record, RecoveredLimit.Verdict.UNREACHABLE, "the job cannot be reached");

    /** An assignment that is refused and proven not to have happened, with the fallback offered or not. */
    public static BiFunction<ProcessIdentity, LimitSpec, ApplyResult> notAssigned(
            OperationResult result, String why, boolean eligible) {
        return (target, spec) -> ApplyResult.notAssigned(ControlOutcome.of(result, why), eligible);
    }

    /** An assignment whose outcome cannot be told: the job is kept, nothing known in force. */
    public BiFunction<ProcessIdentity, LimitSpec, ApplyResult> uncertain() {
        return (target, spec) -> {
            holds.put(target, new NativeHold(NativeHold.State.UNCERTAIN, Optional.empty()));
            return new ApplyResult(
                    ApplyResult.Phase.UNCERTAIN,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "could not be told"),
                    Optional.empty(),
                    false);
        };
    }

    /** A change that cannot be read back. */
    public BiFunction<ProcessIdentity, LimitSpec, ChangeResult> unverified() {
        return (target, spec) -> {
            holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.empty()));
            return new ChangeResult(
                    ChangeResult.Phase.UNVERIFIED,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "could not be read back"),
                    Optional.empty());
        };
    }

    /** A change that leaves the job holding these limits instead. */
    public BiFunction<ProcessIdentity, LimitSpec, ChangeResult> partial(EffectiveLimits observed) {
        return (target, spec) -> {
            holds.put(target, new NativeHold(NativeHold.State.ASSIGNED, Optional.of(observed)));
            return new ChangeResult(
                    ChangeResult.Phase.PARTIAL,
                    ControlOutcome.of(OperationResult.NATIVE_ERROR, "the system holds " + observed.describe()),
                    Optional.of(observed));
        };
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilities() {
        return system;
    }

    @Override
    public Optional<Boolean> selfInJob() {
        return self;
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilitiesFor(ProcessIdentity target) {
        calls.add("capabilitiesFor " + target.pid());
        return holds.containsKey(target) ? system : perProcess.apply(target);
    }

    @Override
    public Optional<NativeHold> hold(ProcessIdentity target) {
        return Optional.ofNullable(holds.get(target));
    }

    @Override
    public ApplyResult apply(ProcessIdentity target, LimitSpec spec) {
        calls.add("apply " + target.pid() + " " + spec.effective().describe());
        return apply.apply(target, spec);
    }

    /**
     * Ownership evidence the fake learns while assigning, as a scope limiter does (plan Rev 2.2 A6); empty: none, as
     * a Job Object limiter.
     */
    public volatile Optional<ScopeProvenance> provenance = Optional.empty();

    /** Runs just before the evidence is handed to the recorder. */
    public volatile Runnable beforeRecord = () -> {};

    @Override
    public ApplyResult apply(ProcessIdentity target, LimitSpec spec, OwnershipRecorder ownership) {
        Optional<ScopeProvenance> evidence = provenance;
        if (evidence.isEmpty()) {
            return apply(target, spec);
        }
        beforeRecord.run();
        boolean recorded = ownership.recordDurably(evidence.get());
        calls.add("record " + target.pid() + " " + recorded);
        if (!recorded) {
            return new ApplyResult(
                    ApplyResult.Phase.ASSIGNED_MISMATCH,
                    ControlOutcome.of(
                            OperationResult.NATIVE_ERROR, "the scope could not be recorded; no limit was set"),
                    Optional.of(EffectiveLimits.NONE),
                    false);
        }
        return apply(target, spec);
    }

    @Override
    public ChangeResult change(ProcessIdentity target, LimitSpec spec) {
        calls.add("change " + target.pid() + " " + spec.effective().describe());
        if (!holds.containsKey(target)) {
            return new ChangeResult(
                    ChangeResult.Phase.NO_HOLD,
                    ControlOutcome.of(OperationResult.INVALID_ARGUMENT, "no job"),
                    Optional.empty());
        }
        return change.apply(target, spec);
    }

    @Override
    public ForgetResult forget(ProcessIdentity target) {
        calls.add("forget " + target.pid());
        if (!holds.containsKey(target)) {
            return new ForgetResult(ForgetResult.Outcome.NOT_HELD, ControlOutcome.success(), 0, Optional.empty());
        }
        return forget.apply(target);
    }

    @Override
    public RecoveredLimit recover(LimitRecord record) {
        calls.add("recover " + record.identity().pid());
        return recover.apply(record);
    }

    /** The bound the last releaseAll was given. */
    public volatile Duration releaseWithin;

    @Override
    public ReleaseResult releaseAll(Duration within) {
        calls.add("releaseAll");
        releaseWithin = within;
        if (busy) {
            // As a limiter whose lock an operation in flight holds past the bound.
            return ReleaseResult.timedOut(Set.copyOf(holds.keySet()));
        }
        Map<ProcessIdentity, Boolean> cleared = new HashMap<>();
        holds.keySet().forEach(identity -> cleared.put(identity, clears.test(identity)));
        holds.clear();
        return new ReleaseResult(cleared, Set.of(), false);
    }
}
