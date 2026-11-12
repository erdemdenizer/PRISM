package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The result of one control operation together with what explains it to whoever reads the log:
 * the native error code, when there was one, and a short technical detail. Neither is ever the
 * message the user reads (CTL-2).
 *
 * @param result what the operation came to
 * @param nativeCode the error code the system gave, when a native call failed
 * @param detail what happened, in technical words, for the log
 */
public record ControlOutcome(OperationResult result, OptionalInt nativeCode, Optional<String> detail) {

    public ControlOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(nativeCode, "nativeCode");
        Objects.requireNonNull(detail, "detail");
    }

    public static ControlOutcome success() {
        return new ControlOutcome(OperationResult.SUCCESS, OptionalInt.empty(), Optional.empty());
    }

    public static ControlOutcome success(String detail) {
        return new ControlOutcome(OperationResult.SUCCESS, OptionalInt.empty(), Optional.of(detail));
    }

    public static ControlOutcome of(OperationResult result, String detail) {
        return new ControlOutcome(result, OptionalInt.empty(), Optional.of(detail));
    }

    public static ControlOutcome of(OperationResult result, int nativeCode, String detail) {
        return new ControlOutcome(result, OptionalInt.of(nativeCode), Optional.of(detail));
    }

    public boolean succeeded() {
        return result == OperationResult.SUCCESS;
    }
}
