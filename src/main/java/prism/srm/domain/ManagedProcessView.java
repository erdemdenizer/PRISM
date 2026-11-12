package prism.srm.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Managed Process as the Limits page shows it: what was set, and where its CPU and memory limits
 * stand.
 *
 * @param capabilities how each kind of limit could be provided for this process now (LIM-3):
 *     native, or unavailable with the reason; the page disables and explains from it (CAP-2)
 * @param offer the emulated CPU limit offered after the native one was not applied; it runs only
 *     once the user accepts it (MILESTONES section 10)
 * @param residentOffer why the native memory limit was not applied, when Prism offers to watch the
 *     resident memory of the process instead (LIM-9, plan O9); nothing is watched unless the user
 *     accepts
 */
public record ManagedProcessView(
        ManagedProcess process,
        LimitReport cpu,
        LimitReport memory,
        Map<LimitKind, LimitCapability> capabilities,
        Optional<FallbackOffer> offer,
        Optional<String> residentOffer) {

    public ManagedProcessView {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(memory, "memory");
        capabilities = Map.copyOf(capabilities);
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(residentOffer, "residentOffer");
        if (cpu.kind() != LimitKind.CPU) {
            throw new IllegalArgumentException("Not a CPU limit: " + cpu.kind());
        }
        if (memory.kind() != LimitKind.MEMORY) {
            throw new IllegalArgumentException("Not a memory limit: " + memory.kind());
        }
    }

    public ManagedProcessView(
            ManagedProcess process,
            LimitReport cpu,
            LimitReport memory,
            Map<LimitKind, LimitCapability> capabilities,
            Optional<FallbackOffer> offer) {
        this(process, cpu, memory, capabilities, offer, Optional.empty());
    }

    public ManagedProcessView(
            ManagedProcess process, LimitReport cpu, LimitReport memory, Map<LimitKind, LimitCapability> capabilities) {
        this(process, cpu, memory, capabilities, Optional.empty());
    }

    /** A process as milestone 9 shows it: its CPU limit, and no memory limit available. */
    public ManagedProcessView(ManagedProcess process, LimitReport cpu) {
        this(
                process,
                cpu,
                LimitReport.none(LimitKind.MEMORY, LimitCapability.unavailable("no native limiter on this system")),
                Map.of());
    }

    public ProcessIdentity identity() {
        return process.identity();
    }

    /** How a limit of this kind could be provided for the process; the memory report's when nothing was probed. */
    public LimitCapability capability(LimitKind kind) {
        LimitCapability probed = capabilities.get(kind);
        if (probed != null) {
            return probed;
        }
        return kind == LimitKind.CPU ? cpu.inEffect() : memory.inEffect();
    }
}
