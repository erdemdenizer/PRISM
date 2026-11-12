package prism.srm.ui.control;

import prism.srm.application.ControlMessages;
import prism.srm.domain.OperationResult;

/**
 * How a line about an action reads: a result in one of three tones, or text that stands in for a
 * result (a reason, an action on its way), which is muted. The tones are the severities the
 * activity log gives the same results, so a line and its log entry never disagree.
 */
public enum ResultTone {
    /** A reason or an action on its way: muted. */
    NONE,
    SUCCESS,
    /** Not done, for a reason that is no failure of the system: refused, gone, not supported. */
    WARNING,
    /** The system failed. */
    FAILURE;

    public static ResultTone of(OperationResult result) {
        return switch (ControlMessages.severity(result)) {
            case INFO -> SUCCESS;
            case WARNING -> WARNING;
            case ERROR -> FAILURE;
        };
    }
}
