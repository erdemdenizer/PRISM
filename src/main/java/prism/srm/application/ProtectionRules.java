package prism.srm.application;

import java.util.Optional;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProtectionFacts;

/**
 * Which processes Prism refuses to act on (SAFE-2, SAFE-3). Applied before any action reaches the
 * platform: a refused process is never touched.
 *
 * <ul>
 *   <li>Prism's own process, by its PID. While Prism runs its PID cannot belong to another process,
 *       so the PID is enough here.
 *   <li>A process the system names as one of its own by identity (the idle and the kernel process
 *       on Windows, PID 1 elsewhere).
 *   <li>A process the system marks as critical.
 *   <li>An executable with the name of a well-known system process, as an additional defense: the
 *       facts above are the protection, the name list only adds to them.
 * </ul>
 *
 * <p>When criticality cannot be determined, that alone does not protect a process: the action
 * itself then meets the system's own access checks, which refuse what Prism may not touch.
 */
public final class ProtectionRules {

    /** Why Prism refuses a process. */
    public enum Refusal {
        OWN_PROCESS,
        SYSTEM_PROCESS,
        CRITICAL_PROCESS,
        SYSTEM_EXECUTABLE
    }

    private final long ownPid;

    public ProtectionRules(long ownPid) {
        this.ownPid = ownPid;
    }

    /** The refusal that needs no question to the system. */
    public Optional<Refusal> refusal(ProcessIdentity target) {
        return target.pid() == ownPid ? Optional.of(Refusal.OWN_PROCESS) : Optional.empty();
    }

    /** The refusal the system's facts call for. */
    public Optional<Refusal> refusal(ProtectionFacts facts) {
        if (facts.systemProcess()) {
            return Optional.of(Refusal.SYSTEM_PROCESS);
        }
        if (facts.critical().orElse(false)) {
            return Optional.of(Refusal.CRITICAL_PROCESS);
        }
        if (facts.listedSystemName()) {
            return Optional.of(Refusal.SYSTEM_EXECUTABLE);
        }
        return Optional.empty();
    }
}
