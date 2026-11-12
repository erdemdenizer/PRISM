package prism.srm.platform;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;
import prism.srm.domain.ScopeProvenance;

/**
 * Native limits (LIM-1, LIM-3, LIM-5; ADR-0002, milestone 10): a CPU cap on the share of the whole
 * machine and a limit on committed memory, enforced by the system. It reports a native level or
 * {@code UNAVAILABLE}, never {@code EMULATED}: the limit service derives that when it falls back.
 *
 * <p>Two operations are kept apart because what can be undone differs (plan revision 4): {@link
 * #apply} is the first assignment of a process to a new, empty job and is irreversible from the
 * moment the assignment is attempted; {@link #change} changes the limits of a job the limiter holds
 * and never assigns. The caller records its intent durably before either when the result may leave
 * a limit in force.
 *
 * <p>Every method may call the system: none may run on the JavaFX Application Thread. The limiter
 * holds the handle of every job it made while the process lives; nothing that may still enforce a
 * limit is let go silently.
 */
public interface ResourceLimiter {

    /** What each kind of limit is on this system; the same answer on every call. */
    Map<LimitKind, LimitCapability> capabilities();

    /** What the limiter found missing when it was set up, for the activity log. */
    default List<String> limitations() {
        return List.of();
    }

    /** Whether Prism itself runs inside a job, when the system says; logged once at start-up. */
    Optional<Boolean> selfInJob();

    /**
     * How each kind could be provided for this process now (LIM-3): native, or unavailable with the
     * reason (refused, in a job Prism does not hold, gone). Changes nothing.
     */
    Map<LimitKind, LimitCapability> capabilitiesFor(ProcessIdentity target);

    /** The job the limiter holds for the process, when it holds one. */
    Optional<NativeHold> hold(ProcessIdentity target);

    /** The first assignment of the process to a new job with these limits; see {@link ApplyResult}. */
    ApplyResult apply(ProcessIdentity target, LimitSpec spec);

    /**
     * Records the ownership evidence a limiter learnt while assigning (milestone 12, plan Rev 2.2 §2.5 A6, §4.5): the
     * unit, its invocation id and its cgroup, durably, before any limit is set on it. False when it could not be
     * recorded; then the limiter sets no limit.
     */
    @FunctionalInterface
    interface OwnershipRecorder {
        boolean recordDurably(ScopeProvenance provenance);
    }

    /**
     * {@link #apply(ProcessIdentity, LimitSpec)}, with a place to record ownership evidence before the limits are set.
     * A limiter without such evidence (Job Objects) ignores the recorder.
     */
    default ApplyResult apply(ProcessIdentity target, LimitSpec spec, OwnershipRecorder ownership) {
        return apply(target, spec);
    }

    /**
     * Why no second limiter, native or emulated, may start on the process because it runs inside a scope a Prism
     * session created and this limiter does not hold for it (milestone 12, plan Rev 2.2 §4.5 b: the scope-membership
     * rule, independent of any record); empty when nothing stands against it. Reads only; may be asked from the limiter
     * thread. Empty for every limiter without scopes.
     */
    default Optional<String> scopeBarrier(ProcessIdentity target) {
        return Optional.empty();
    }

    /** Changes the limits of the job the limiter holds for the process, to exactly these; see {@link ChangeResult}. */
    ChangeResult change(ProcessIdentity target, LimitSpec spec);

    /**
     * The process has exited: whatever its job still enforces on the processes it started is
     * cleared and verified, and only then is the job let go; see {@link ForgetResult}.
     */
    ForgetResult forget(ProcessIdentity target);

    /**
     * Start-up: what can be checked of a limit an earlier session recorded. Reads only: a job that can
     * still be opened has a running holder, and recovery changes nothing (ADR-0012). On Linux (milestone 12) this call
     * also changes nothing: it classifies the record's scope. A limiter whose {@link #recoveryClears} is true is then
     * asked separately, through {@link #clearOwned}.
     */
    RecoveredLimit recover(LimitRecord record);

    /**
     * Whether this limiter can clear the limits of a scope an earlier session left, through {@link #clearOwned}
     * (milestone 12, plan Rev 2.2 §4.2). False for Job Objects (ADR-0012: recovery is read-only); true only for the
     * Linux scope limiter, which clears only where ownership is proven at the moment of clearing.
     */
    default boolean recoveryClears() {
        return false;
    }

    /**
     * An explicit clearing of the limits an earlier session left on a scope whose ownership the record proves
     * (milestone 12, plan Rev 2.1 §4.2): every limit set to unlimited and read back, never a stop or a kill. Kept apart
     * from {@link #recover}, which only classifies. A limiter that does not clear answers {@link
     * ClearResult.Outcome#UNSUPPORTED} and changes nothing.
     */
    default ClearResult clearOwned(LimitRecord record) {
        return ClearResult.unsupported("this limiter does not clear the limits of earlier sessions");
    }

    /**
     * Shutdown and the exit hook: clears every limit the limiter holds and lets go of the jobs,
     * within the bound, then refuses every further apply and change. The result names each process
     * reached and whether its limits were verified as cleared; if the limiter was busy past the
     * bound, it says so and names the holds known then.
     */
    ReleaseResult releaseAll(Duration within);

    /**
     * Whether a Prism session on this system can have set native limits, today or in an earlier
     * session, whatever today's probe says: true on Windows (Job Objects), even where native CPU
     * limits are unavailable now; false where this version has no native limiter at all. Recovery of
     * earlier sessions' native limits runs only where this is true (final source audit).
     */
    default boolean mayHaveLeftLimits() {
        return true;
    }
}
