package prism.srm.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A Managed Process as the OOM Guard page shows it (UI-5): its OOM settings, what is in effect, what
 * OOM Guard holds on it, and why nothing is done to it now when nothing is.
 *
 * @param priority the level of its priority in the last listing; empty when it stands for none of the
 *     five
 * @param residentBytes its resident memory in the last listing
 * @param memoryLimitBytes the memory limit OOM Guard watches on it (LIM-9); empty when none is watched
 * @param notNow why OOM Guard does not act on it now, as "not now: …"; empty when there is nothing to say
 */
public record OomCandidateView(
        ProcessIdentity identity,
        String name,
        OomManagement oom,
        EffectiveAction effective,
        OomHold hold,
        Optional<PriorityLevel> priority,
        OptionalLong residentBytes,
        OptionalLong memoryLimitBytes,
        Optional<String> notNow) {

    public OomCandidateView {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(oom, "oom");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(residentBytes, "residentBytes");
        Objects.requireNonNull(memoryLimitBytes, "memoryLimitBytes");
        Objects.requireNonNull(notNow, "notNow");
    }
}
