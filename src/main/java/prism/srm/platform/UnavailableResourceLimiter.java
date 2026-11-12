package prism.srm.platform;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.LimitCapability;
import prism.srm.domain.LimitKind;
import prism.srm.domain.LimitRecord;
import prism.srm.domain.LimitSpec;
import prism.srm.domain.OperationResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.RecoveredLimit;

/**
 * The limiter of a system without native limits Prism uses (Linux until milestone 12, macOS, LIM-7):
 * every kind is unavailable, with the reason, and nothing native is touched. The limit service then
 * runs the emulated CPU limit as in milestone 9, without asking (plan D11).
 */
public final class UnavailableResourceLimiter implements ResourceLimiter {

    private final LimitCapability unavailable;

    public UnavailableResourceLimiter(String reason) {
        this.unavailable = LimitCapability.unavailable(reason);
    }

    /** The limiter of a system with no native limit in this version. */
    public static UnavailableResourceLimiter here() {
        return new UnavailableResourceLimiter("no native limit is available on this system in this version");
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilities() {
        return Map.of(LimitKind.CPU, unavailable, LimitKind.MEMORY, unavailable);
    }

    @Override
    public Optional<Boolean> selfInJob() {
        return Optional.empty();
    }

    @Override
    public Map<LimitKind, LimitCapability> capabilitiesFor(ProcessIdentity target) {
        return capabilities();
    }

    @Override
    public Optional<NativeHold> hold(ProcessIdentity target) {
        return Optional.empty();
    }

    @Override
    public ApplyResult apply(ProcessIdentity target, LimitSpec spec) {
        return ApplyResult.notAssigned(unsupported(), false);
    }

    @Override
    public ChangeResult change(ProcessIdentity target, LimitSpec spec) {
        return new ChangeResult(ChangeResult.Phase.NO_HOLD, unsupported(), Optional.empty());
    }

    @Override
    public ForgetResult forget(ProcessIdentity target) {
        return new ForgetResult(ForgetResult.Outcome.NOT_HELD, ControlOutcome.success(), 0, Optional.empty());
    }

    @Override
    public RecoveredLimit recover(LimitRecord record) {
        return new RecoveredLimit(
                record, RecoveredLimit.Verdict.FAILED, "native limits are not available on this system");
    }

    /** No native limiter exists in this version on this system: no session here can have left one. */
    @Override
    public boolean mayHaveLeftLimits() {
        return false;
    }

    @Override
    public ReleaseResult releaseAll(Duration within) {
        return ReleaseResult.NOTHING;
    }

    private ControlOutcome unsupported() {
        return ControlOutcome.of(
                OperationResult.UNSUPPORTED, unavailable.reason().orElseThrow());
    }
}
