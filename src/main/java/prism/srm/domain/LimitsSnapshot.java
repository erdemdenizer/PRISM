package prism.srm.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Managed Processes and their limits at one moment, for the Limits page and the marks on the
 * Processes page.
 *
 * @param cpu what a CPU limit is on this system: native, emulated, or unavailable with the reason
 *     (LIM-3, CAP-2)
 * @param memory what a memory limit is on this system: native, or unavailable with the reason
 * @param limitsOff why Prism applies no emulated limit now, when it does not: the suspension
 *     journal that must record a limit before the limiter may suspend anything could not be written
 *     (ADR-0011); empty while limits can be set
 * @param notices further sentences the page states: a failed limit ledger, jobs of exited processes
 *     that still carry limits, processes an earlier session may still cap, Prism inside a job
 */
public record LimitsSnapshot(
        List<ManagedProcessView> processes,
        LimitCapability cpu,
        LimitCapability memory,
        Optional<String> limitsOff,
        List<LimitsNotice> notices) {

    /** Before the limits are known. */
    public static final LimitsSnapshot EMPTY = new LimitsSnapshot(
            List.of(),
            LimitCapability.unavailable("Prism is still starting"),
            LimitCapability.unavailable("Prism is still starting"),
            Optional.empty(),
            List.of());

    public LimitsSnapshot {
        processes = List.copyOf(processes);
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(limitsOff, "limitsOff");
        notices = List.copyOf(notices);
    }

    /** As milestone 9 had it: no memory limit, no further notice. */
    public LimitsSnapshot(List<ManagedProcessView> processes, LimitCapability cpu, Optional<String> limitsOff) {
        this(processes, cpu, LimitCapability.unavailable("no native limiter on this system"), limitsOff, List.of());
    }

    public boolean managed(ProcessIdentity identity) {
        return find(identity).isPresent();
    }

    public Optional<ManagedProcessView> find(ProcessIdentity identity) {
        return processes.stream()
                .filter(view -> view.identity().equals(identity))
                .findFirst();
    }
}
