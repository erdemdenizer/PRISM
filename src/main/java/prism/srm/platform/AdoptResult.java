package prism.srm.platform;

import java.util.Objects;
import java.util.Optional;
import prism.srm.domain.ControlOutcome;

/**
 * What came of {@link ProcessController#adopt}: whether the process is, as far as the system lets
 * Prism check, still held the way the journal of an earlier session recorded, and if so the
 * suspension that undoes exactly that.
 *
 * @param verdict what the check found
 * @param suspension present when adopted; present on a refusal too when a check Prism made could
 *     not be undone completely (on Windows, a probe increment whose ResumeThread failed), holding
 *     that remainder, which is Prism's own, so that it is not forgotten
 */
public record AdoptResult(Verdict verdict, ControlOutcome outcome, Optional<Suspension> suspension) {

    /** What a check of a process an earlier session recorded as suspended found. */
    public enum Verdict {
        /** Held as recorded: the suspension undoes it once. Not proof that the stop was Prism's. */
        ADOPTED,
        /** The process is not suspended (any more): there is nothing to resume. */
        NOT_SUSPENDED,
        /** The process has exited, or its PID belongs to another process now. */
        GONE,
        /** It cannot be told whether the hold is Prism's, or the check failed: nothing was changed. */
        REFUSED
    }

    public AdoptResult {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(suspension, "suspension");
        if ((verdict == Verdict.ADOPTED) != (outcome.succeeded())) {
            throw new IllegalArgumentException("Exactly an adoption succeeds: " + verdict + " " + outcome);
        }
        if (verdict == Verdict.ADOPTED && suspension.isEmpty()) {
            throw new IllegalArgumentException("An adoption holds its suspension");
        }
        if (verdict != Verdict.ADOPTED && verdict != Verdict.REFUSED && suspension.isPresent()) {
            throw new IllegalArgumentException("Only an adoption or a refusal may hold a suspension: " + verdict);
        }
    }

    public static AdoptResult adopted(ControlOutcome outcome, Suspension suspension) {
        return new AdoptResult(Verdict.ADOPTED, outcome, Optional.of(suspension));
    }

    public static AdoptResult of(Verdict verdict, ControlOutcome outcome) {
        return new AdoptResult(verdict, outcome, Optional.empty());
    }
}
