package prism.srm.ui.limits;

import java.util.Objects;
import prism.srm.domain.ProcessIdentity;

/**
 * One managed process on the Limits page, as text for its cells (UI-4).
 *
 * @param cpuLimit "30 %", or an en dash for no limit
 * @param status what the limit does now, in a few words
 * @param detail the whole of what the status stands for, for the tooltip; may be empty
 * @param implementation how the limit is applied: Emulated, Native hard, Unavailable, or an en dash
 * @param enabled whether the limits of the process are enabled
 * @param hasLimit whether a CPU limit is set
 * @param memoryDetail the whole of where the memory limit stands, for its tooltip; may be empty
 * @param hasMemoryLimit whether a memory limit is set, or a native one may be in force
 * @param fallbackOffered whether the emulated CPU limit is offered and waits for the user
 * @param implementationDetail the mechanism in words, for the tooltip of the implementation
 */
record LimitRow(
        ProcessIdentity identity,
        String name,
        String cpuLimit,
        String memoryLimit,
        String status,
        Tone tone,
        String detail,
        String implementation,
        boolean enabled,
        boolean hasLimit,
        String memoryDetail,
        boolean hasMemoryLimit,
        boolean fallbackOffered,
        String implementationDetail) {

    /** How the status reads: as the rest of the row, muted, or in the color of a warning or an error. */
    enum Tone {
        NORMAL,
        MUTED,
        WARNING,
        ERROR
    }

    LimitRow {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cpuLimit, "cpuLimit");
        Objects.requireNonNull(memoryLimit, "memoryLimit");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(tone, "tone");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(memoryDetail, "memoryDetail");
        Objects.requireNonNull(implementationDetail, "implementationDetail");
    }

    String pid() {
        return Long.toString(identity.pid());
    }
}
