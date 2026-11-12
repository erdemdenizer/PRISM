package prism.srm.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.EffectiveLimits;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.ApplyResult;
import prism.srm.platform.ChangeResult;
import prism.srm.platform.ForgetResult;
import prism.srm.platform.NativeHold;
import prism.srm.platform.ReleaseResult;
import prism.srm.platform.ResourceLimiter;

/**
 * The ledger discipline around the native limiter (plan §6.4, §8.1, §8.1b): what may leave a limit
 * in force is recorded durably before the system is asked, and what the system answered is
 * recorded after, phase by phase. A record is removed only where nothing native can be left: an
 * assignment proven not to have happened, a job let go. Mechanism selection, the barrier and the
 * transitions of the Limits page stay in {@link LimitService}; this class only keeps the evidence
 * in step with the system. On the limiter thread, except {@link #releaseAll}.
 */
final class NativeLimitController {

    private final ResourceLimiter limiter;
    private final NativeLimitLedger ledger;
    private final EarlierLimits earlier;

    NativeLimitController(ResourceLimiter limiter, NativeLimitLedger ledger) {
        this(limiter, ledger, EarlierLimits.NONE);
    }

    /** @param earlier what recovery knows of other sessions' native limits (audit item 2) */
    NativeLimitController(ResourceLimiter limiter, NativeLimitLedger ledger, EarlierLimits earlier) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.earlier = Objects.requireNonNull(earlier, "earlier");
    }

    EarlierLimits earlier() {
        return earlier;
    }

    ResourceLimiter limiter() {
        return limiter;
    }

    NativeLimitLedger ledger() {
        return ledger;
    }

    Optional<NativeHold> hold(ProcessIdentity identity) {
        return limiter.hold(identity);
    }

    /** The answer when the ledger cannot record what is about to be done. */
    ControlOutcome notRecorded() {
        return ControlOutcome.of(
                OperationResult.NATIVE_ERROR,
                "native-limit ledger: " + ledger.problem().orElse("the record could not be written"));
    }

    /**
     * A first assignment: INTENDED on disk first (S0b), then the system, then what it answered (S8).
     * Empty when the ledger could not record the intent: then nothing was asked of the system.
     */
    Optional<ApplyResult> apply(ProcessIdentity identity, String name, LimitSpec spec) {
        LimitRecord intended = LimitRecord.intended(identity, name, spec.effective(), ledger.instance());
        if (!ledger.putDurably(intended)) {
            return Optional.empty();
        }
        // A Linux scope's ownership evidence goes to disk after the move and before any limit (plan Rev 2.2 A6): as a
        // change from no limit to the limits asked, so that every per-kind state while they are set is admitted.
        AtomicReference<LimitRecord> latest = new AtomicReference<>(intended);
        ApplyResult result = limiter.apply(identity, spec, provenance -> {
            LimitRecord owned = intended.applied(EffectiveLimits.NONE)
                    .withProvenance(provenance)
                    .changing(spec.effective());
            if (!ledger.putDurably(owned)) {
                return false;
            }
            latest.set(owned);
            return true;
        });
        LimitRecord base = latest.get();
        switch (result.phase()) {
            case NOT_ASSIGNED, ASSIGNED_RELEASED -> ledger.remove(identity);
            case ENFORCED -> ledger.put(base.applied(result.observed().orElse(spec.effective())));
            case ASSIGNED_MISMATCH ->
                ledger.put(
                        result.observed().isPresent()
                                ? base.applied(result.observed().get())
                                : base.flagged(LimitRecord.Flag.UNVERIFIED));
            case UNCERTAIN -> ledger.put(base.flagged(LimitRecord.Flag.UNCERTAIN));
        }
        return Optional.of(result);
    }

    /**
     * A change of a job Prism holds (§8.1b): PENDING_CHANGE{from, to} on disk first when the change
     * leaves a limit in force; a pure clearing records it without waiting. {@code from} is what the
     * job is known to hold; a change of a job whose limits are unknown is not made. Empty when the
     * ledger could not record a change that leaves a limit in force: then the system was not asked.
     */
    Optional<ChangeResult> change(ProcessIdentity identity, String name, LimitSpec spec) {
        Optional<NativeHold> hold = limiter.hold(identity);
        if (hold.isEmpty()) {
            ledger.remove(identity);
            return Optional.of(limiter.change(identity, spec));
        }
        EffectiveLimits to = spec.effective();
        Optional<LimitRecord> previous = ledger.find(identity);
        Optional<EffectiveLimits> from = hold.get().inForce();
        Optional<LimitRecord> base;
        if (from.isPresent()) {
            base = Optional.of(
                    previous.orElseGet(() -> LimitRecord.intended(identity, name, from.get(), ledger.instance()))
                            .applied(from.get()));
        } else if (hold.get().state() == NativeHold.State.UNCERTAIN && previous.isPresent()) {
            // Uncertain: the job holds the intended limits or the process is not in it; either is admitted.
            base = previous;
        } else {
            base = Optional.empty();
        }
        if (base.isPresent()) {
            LimitRecord pending = base.get().changing(to);
            if (!to.none()) {
                if (!ledger.putDurably(pending)) {
                    previous.ifPresent(ledger::put);
                    return Optional.empty();
                }
            } else {
                ledger.put(pending);
            }
        } else if (!to.none()) {
            // What is in force is unknown: a record of {from, to} could not be sound, so nothing is tightened.
            return Optional.of(new ChangeResult(
                    ChangeResult.Phase.NOT_DONE,
                    ControlOutcome.of(
                            OperationResult.INVALID_ARGUMENT,
                            "the limits in force are unknown; remove the limits first"),
                    Optional.empty()));
        }
        ChangeResult result = limiter.change(identity, spec);
        LimitRecord after =
                ledger.find(identity).orElseGet(() -> LimitRecord.intended(identity, name, to, ledger.instance()));
        switch (result.phase()) {
            case APPLIED ->
                ledger.put(
                        to.none()
                                ? after.cleared()
                                : after.applied(result.observed().orElse(to)));
            case PARTIAL -> ledger.put(after.applied(result.observed().orElseThrow()));
            case UNVERIFIED -> ledger.put(after.flagged(LimitRecord.Flag.UNVERIFIED));
            case NOT_DONE -> {
                if (previous.isPresent()) {
                    ledger.put(previous.get());
                } else if (from.isPresent()) {
                    ledger.put(after.applied(from.get()));
                }
            }
            case NO_HOLD -> ledger.remove(identity);
        }
        return Optional.of(result);
    }

    /**
     * A process an earlier session may still cap has exited: the record recovery carried into this
     * session's ledger goes, unless this session holds a job for it (native-api review, Stage 4).
     */
    void forgetCarried(ProcessIdentity identity) {
        if (limiter.hold(identity).isPresent()) {
            return;
        }
        // A record with scope provenance is ownership evidence: the scope may still hold processes the exited one
        // started, so it stays until the next start finds the scope cleared, empty or gone (plan Rev 2.2 §4.5 c).
        if (ledger.find(identity).flatMap(LimitRecord::provenance).isPresent()) {
            return;
        }
        ledger.remove(identity);
    }

    /** The process exited (§8.6): the record goes only when the job was let go. */
    ForgetResult forget(ProcessIdentity identity) {
        ForgetResult result = limiter.forget(identity);
        if (result.released()) {
            ledger.remove(identity);
        } else if (result.result() == ForgetResult.Outcome.RESIDUE
                || result.result() == ForgetResult.Outcome.UNRESOLVED) {
            // STILL_RUNS changes nothing: the process was not shown to have exited.
            ledger.find(identity).ifPresent(record -> ledger.put(record.flagged(LimitRecord.Flag.TARGET_EXITED)));
        }
        return result;
    }

    /**
     * Shutdown and the exit hook: every job cleared and let go; the records of those verified as
     * cleared say so, the others stay as they were for the next start. Then the newest model is
     * waited for within what is left of the bound.
     */
    ReleaseResult releaseAll(Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        ReleaseResult result = limiter.releaseAll(within);
        // A limit verified as cleared leaves the ledger, as at the exit of its process, so that a ledger
        // whose limits were all cleared is empty and goes; one not verified stays as it was written,
        // and the next start lists it (plan §6.5, shutdown).
        // Nothing is removed for a clearing that could not begin: every record stays (audit item 3).
        result.cleared().forEach((identity, none) -> {
            if (none) {
                ledger.remove(identity);
            }
        });
        ledger.awaitLatest(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        return result;
    }
}
