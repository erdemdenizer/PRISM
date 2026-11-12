package prism.srm.application;

import java.util.Locale;
import prism.srm.application.ControlMessages.Wording;
import prism.srm.domain.ControlOutcome;
import prism.srm.domain.RecoveryRequest;
import prism.srm.domain.RecoveryResult;
import prism.srm.platform.AdoptResult;

/**
 * The words of the results of requests about processes an earlier session of Prism may have left
 * suspended (ADR-0011). A resume is never called proof that the stop was Prism's.
 */
final class RecoveryMessages {

    private RecoveryMessages() {}

    static RecoveryResult result(RecoveryRequest request, ControlOutcome outcome, Wording wording) {
        return new RecoveryResult(request, outcome, wording.headline(), wording.explanation());
    }

    static String target(RecoveryRequest request) {
        return request.name() + " (PID " + request.identity().pid() + ")";
    }

    static String actionName(RecoveryRequest.Action action) {
        return switch (action) {
            case RESUME -> "Resume after a crash";
            case DISMISS -> "Dismiss";
        };
    }

    private static String nothingDone(RecoveryRequest request) {
        return actionName(request.action()) + " was not carried out for " + target(request) + ".";
    }

    static Wording shuttingDown(RecoveryRequest request) {
        return new Wording(nothingDone(request), "Prism is closing.");
    }

    static Wording notMonitoring(RecoveryRequest request) {
        return new Wording(nothingDone(request), "Prism is not monitoring processes.");
    }

    static Wording busy(RecoveryRequest request) {
        return new Wording(nothingDone(request), "Another action on it is in progress.");
    }

    static Wording tooManyPending(RecoveryRequest request) {
        return new Wording(nothingDone(request), "Too many actions are waiting.");
    }

    static Wording failed(RecoveryRequest request) {
        return new Wording(nothingDone(request), "Something went wrong; the Logs page has the details.");
    }

    static Wording unavailable(RecoveryRequest request) {
        return new Wording(nothingDone(request), "Resume is not available on this system.");
    }

    static Wording refused(RecoveryRequest request, ProtectionRules.Refusal refusal) {
        String headline = "Prism does not resume " + target(request) + ".";
        return switch (refusal) {
            case OWN_PROCESS -> new Wording(headline, "It is Prism's own process.");
            case SYSTEM_PROCESS, SYSTEM_EXECUTABLE ->
                new Wording(headline, "It is a system process, which Prism never suspends.");
            case CRITICAL_PROCESS -> new Wording(headline, "The system marks it as critical; Prism never suspends it.");
        };
    }

    static Wording dismissed(RecoveryRequest request) {
        return new Wording("The entry for " + target(request) + " is dismissed.", "Prism did not touch the process.");
    }

    static Wording noEntry(RecoveryRequest request) {
        return new Wording(nothingDone(request), "The entry is closed already.");
    }

    /** What came of a resume, by what the check of the controller found. */
    static Wording resume(RecoveryRequest request, SuspensionCoordinator.RecoveryOutcome outcome) {
        ControlOutcome result = outcome.outcome();
        String detail = result.detail().orElse("");
        if (outcome.verdict().isEmpty()) {
            return new Wording("Prism did not resume " + target(request) + ".", sentence(detail));
        }
        AdoptResult.Verdict verdict = outcome.verdict().get();
        return switch (verdict) {
            case ADOPTED ->
                result.succeeded()
                        ? new Wording(
                                target(request) + " is resumed.",
                                "It was stopped as recorded; that does not prove the stop was Prism's.")
                        : new Wording(
                                "Not every thread of " + target(request) + " could be resumed.",
                                "Prism keeps what is left and lists it as held by Prism.");
            case NOT_SUSPENDED ->
                new Wording(target(request) + " was not suspended any more.", "Nothing was done; the entry is closed.");
            case GONE -> new Wording(target(request) + " has exited.", "The entry is closed.");
            case REFUSED ->
                new Wording(
                        "Prism did not resume " + target(request) + ".",
                        sentence(detail.isEmpty() ? "the check did not hold" : detail) + " Prism changed nothing.");
        };
    }

    /** A detail from below as a sentence: capitalized, with a full stop. */
    private static String sentence(String detail) {
        if (detail.isBlank()) {
            return "";
        }
        String trimmed = detail.strip();
        String capitalized = trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
        return capitalized.endsWith(".") ? capitalized : capitalized + ".";
    }
}
