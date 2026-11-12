package prism.srm.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The job of a process that exited while its job still carries limits Prism could not verify as
 * cleared (milestone 10, plan §8.6): the processes the exited one started stay limited. Prism keeps
 * the job and retries; it never ends those processes.
 *
 * @param survivors how many processes the job still holds, as its accounting said; -1 when unknown
 */
public record Residue(ProcessIdentity exited, String name, EffectiveLimits inForce, int survivors, Instant since) {

    public Residue {
        Objects.requireNonNull(exited, "exited");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inForce, "inForce");
        Objects.requireNonNull(since, "since");
    }
}
